package com.elcesarmaat.raybanfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentValues;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashSet;
import java.util.Set;

// Tags a video that lost the glasses' metadata on its way through another app. Unlike FixActivity, the model can't
// be read from the file, so the user confirms it. The result is only kept in Movies/RayBanFix when the
// "Guardar también una copia" option is on; otherwise it's shared from a temporary file.
public class OtherAppActivity extends Activity {

    private static final String PREFS = "other_app";
    private static final String KEY_LAST_DEVICE = "last_device";

    private interface Step {
        void run() throws Exception;
    }

    private AlertDialog progress;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri src = getIntent() == null ? null : getIntent().getData();
        if (src == null) {
            showError("No recibi ningun video.");
            return;
        }
        showProgress(R.string.checking);
        inBackground(() -> {
            StrippedVideoPatcher.Scan scan = StrippedVideoPatcher.scan(() -> open(src));
            runOnUiThread(() -> {
                if (scan.alreadyTagged) {
                    Toast.makeText(this, R.string.already_tagged, Toast.LENGTH_LONG).show();
                    share(src);
                } else {
                    askForDevice(src, scan);
                }
            });
        });
    }

    private InputStream open(Uri src) throws IOException {
        InputStream raw = getContentResolver().openInputStream(src);
        if (raw == null) {
            throw new IOException("No pude abrir el video.");
        }
        return new BufferedInputStream(raw, 1 << 16);
    }

    private void askForDevice(Uri src, StrippedVideoPatcher.Scan scan) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        dismissProgress();
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_model, null);
        TextView message = view.findViewById(R.id.message);
        RadioGroup group = view.findViewById(R.id.models);
        EditText custom = view.findViewById(R.id.custom_model);

        String preferred = scan.detectedDevice != null ? scan.detectedDevice : lastDevice();
        message.setText(scan.detectedDevice != null
                ? getString(R.string.model_detected, scan.detectedDevice)
                : getString(R.string.model_missing));

        Set<String> models = new LinkedHashSet<>();
        models.add(preferred);
        models.add(StrippedVideoPatcher.DEFAULT_DEVICE);
        for (String model : models) {
            RadioButton button = new RadioButton(builder.getContext());
            button.setId(View.generateViewId());
            button.setText(model);
            button.setTag(model);
            group.addView(button);
            if (model.equals(preferred)) {
                group.check(button.getId());
            }
        }
        RadioButton other = new RadioButton(builder.getContext());
        other.setId(View.generateViewId());
        other.setText(R.string.model_other);
        group.addView(other);
        group.setOnCheckedChangeListener((g, checkedId) ->
                custom.setVisibility(checkedId == other.getId() ? View.VISIBLE : View.GONE));

        AlertDialog dialog = builder
                .setTitle(R.string.model_title)
                .setView(view)
                .setPositiveButton(R.string.add_tag, null)
                .setNegativeButton(android.R.string.cancel, (d, which) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
        // Set after show() so a bad custom model keeps the dialog open instead of dismissing it.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            RadioButton checked = group.findViewById(group.getCheckedRadioButtonId());
            if (checked == null) {
                return;
            }
            String device;
            if (checked == other) {
                device = custom.getText().toString().trim();
                String problem = StrippedVideoPatcher.checkDevice(device);
                if (problem != null) {
                    custom.setError(problem);
                    return;
                }
            } else {
                device = (String) checked.getTag();
            }
            dialog.dismiss();
            rememberDevice(device);
            showProgress(R.string.working);
            inBackground(() -> tagAndShare(src, scan, device));
        });
    }

    private void tagAndShare(Uri src, StrippedVideoPatcher.Scan scan, String device) throws Exception {
        StrippedVideoPatcher.Patch patch = scan.patch(device);
        if (!Settings.saveFixed(this)) {
            File temp = SharedFileProvider.newFile(this, outputName(src));
            try (OutputStream os = new FileOutputStream(temp)) {
                patch.writeTo(os);
            } catch (Exception e) {
                temp.delete();
                throw e;
            }
            Uri shared = SharedFileProvider.uriFor(temp);
            runOnUiThread(() -> share(shared));
            return;
        }
        Uri out = null;
        try {
            out = createOutput(outputName(src));
            try (OutputStream os = getContentResolver().openOutputStream(out)) {
                if (os == null) {
                    throw new IOException("No pude crear el archivo de salida.");
                }
                patch.writeTo(os);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(out, done, null, null);
        } catch (Exception e) {
            deleteQuietly(out);
            throw e;
        }
        Uri result = out;
        runOnUiThread(() -> share(result));
    }

    private void inBackground(Step step) {
        new Thread(() -> {
            try {
                step.run();
            } catch (Mp4Patcher.PatchException e) {
                runOnUiThread(() -> showError(e.getMessage()));
            } catch (Exception e) {
                runOnUiThread(() -> showError("Error al procesar el video: " + e.getMessage()));
            }
        }, "raybanfix-other").start();
    }

    private String lastDevice() {
        return prefs().getString(KEY_LAST_DEVICE, StrippedVideoPatcher.DEFAULT_DEVICE);
    }

    private void rememberDevice(String device) {
        prefs().edit().putString(KEY_LAST_DEVICE, device).apply();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    private Uri createOutput(String name) throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/RayBanFix");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        if (uri == null) {
            throw new IOException("MediaStore no pudo crear el archivo en Movies/RayBanFix.");
        }
        return uri;
    }

    private String outputName(Uri src) {
        String name = null;
        try (Cursor c = getContentResolver().query(src, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Exception ignored) {
            // Some providers don't expose a display name; fall back to a generic one.
        }
        if (name == null || name.isEmpty()) {
            name = "video_" + System.currentTimeMillis() + ".mp4";
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "_rbfix.mp4";
    }

    private void deleteQuietly(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().delete(uri, null, null);
        } catch (Exception ignored) {
            // Best effort: a leftover pending entry is cleaned up by MediaStore after a while.
        }
    }

    private void share(Uri uri) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        dismissProgress();
        startActivity(Shares.chooser(this, uri));
        finish();
    }

    private void showError(String message) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        dismissProgress();
        new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setOnDismissListener(d -> finish())
                .show();
    }

    private void showProgress(int text) {
        dismissProgress();
        progress = new AlertDialog.Builder(this).setView(progressView(text)).setCancelable(false).show();
    }

    private void dismissProgress() {
        if (progress != null && progress.isShowing()) {
            progress.dismiss();
        }
        progress = null;
    }

    private LinearLayout progressView(int text) {
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminate(true);
        layout.addView(bar);
        TextView label = new TextView(this);
        label.setText(text);
        label.setPadding(pad, 0, 0, 0);
        layout.addView(label);
        return layout;
    }
}
