package com.elcesarmaat.raybanfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class FixActivity extends Activity {

    private static final int REQUEST_MEDIA_ACCESS = 1;

    private AlertDialog progress;
    private Uri pendingSrc;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Uri src = sharedUri(getIntent());
        if (src == null) {
            showError("No recibi ningun video.");
            return;
        }
        if (shouldAskForMediaAccess(src)) {
            // Asked once, before the work starts, so the shortcut can be saved; later changes go through Options.
            pendingSrc = src;
            MediaAccess.request(this, REQUEST_MEDIA_ACCESS);
            return;
        }
        start(src);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_ACCESS && pendingSrc != null) {
            Uri src = pendingSrc;
            pendingSrc = null;
            start(src);
        }
    }

    private boolean shouldAskForMediaAccess(Uri src) {
        return !Settings.askedMediaAccess(this)
                && MediaAccess.level(this) == MediaAccess.Level.NONE
                && !SourceResolver.hasPersistedRead(getContentResolver(), src);
    }

    private void start(Uri src) {
        progress = new AlertDialog.Builder(this).setView(progressView()).setCancelable(false).show();
        new Thread(() -> process(src), "raybanfix").start();
    }

    @SuppressWarnings("deprecation")
    private static Uri sharedUri(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            return intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        }
        return intent.getParcelableExtra(Intent.EXTRA_STREAM);
    }

    private void process(Uri src) {
        ContentResolver resolver = getContentResolver();
        Uri out = null;
        try (InputStream raw = resolver.openInputStream(src)) {
            if (raw == null) {
                throw new IOException("No pude abrir el video.");
            }
            Mp4Patcher.Plan plan = Mp4Patcher.prepare(new BufferedInputStream(raw, 1 << 16));
            if (plan.alreadyPatched) {
                runOnUiThread(() -> {
                    Toast.makeText(this, "Este video ya tenia la etiqueta; se comparte tal cual.", Toast.LENGTH_LONG).show();
                    share(src);
                });
                return;
            }
            String name = outputName(src);
            Uri shareUri;
            String notice = null;
            if (!Settings.saveFixed(this)) {
                File temp = SharedFileProvider.newFile(this, name);
                try (OutputStream os = new FileOutputStream(temp)) {
                    plan.writeTo(os);
                } catch (Exception e) {
                    temp.delete();
                    throw e;
                }
                shareUri = SharedFileProvider.uriFor(temp);
            } else {
                Uri existing = findExistingOutput(name);
                if (existing != null) {
                    shareUri = existing;
                    notice = "Ya estaba arreglado; se comparte la copia de Movies/RayBanFix.";
                } else {
                    out = createOutput(name);
                    try (OutputStream os = resolver.openOutputStream(out)) {
                        if (os == null) {
                            throw new IOException("No pude crear el archivo de salida.");
                        }
                        plan.writeTo(os);
                    }
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(out, done, null, null);
                    shareUri = out;
                }
            }
            // The shortcut is kept whether or not a full copy was also saved.
            if (!rememberShortcut(src)) {
                notice = notice == null ? getString(R.string.shortcut_not_saved)
                        : notice + "\n" + getString(R.string.shortcut_not_saved);
            }
            Uri result = shareUri;
            String message = notice;
            runOnUiThread(() -> {
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                }
                share(result);
            });
        } catch (Mp4Patcher.PatchException e) {
            deleteQuietly(out);
            runOnUiThread(() -> showError(e.getMessage()));
        } catch (Exception e) {
            deleteQuietly(out);
            runOnUiThread(() -> showError("Error al procesar el video: " + e.getMessage()));
        }
    }

    private boolean rememberShortcut(Uri src) {
        SourceResolver.Info info = SourceResolver.info(getContentResolver(), src);
        if (info == null) {
            return false;
        }
        Uri durable = SourceResolver.durableUri(this, src, info);
        if (durable == null) {
            return false;
        }
        ShortcutStore.upsert(this, durable, info.name, info.sizeBytes, SourceResolver.durationMs(this, durable),
                System.currentTimeMillis());
        return true;
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
            // Unique fallback so an unnamed source can never be mistaken for an earlier output.
            name = "video_" + System.currentTimeMillis() + ".mp4";
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "_rbfix.mp4";
    }

    private Uri findExistingOutput(String name) {
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + " = ? AND "
                + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = {name, Environment.DIRECTORY_MOVIES + "/RayBanFix/%"};
        try (Cursor c = getContentResolver().query(collection, new String[]{MediaStore.MediaColumns._ID},
                selection, args, null)) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Exception ignored) {
            // If the lookup fails we just create a new copy.
        }
        return null;
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

    private void dismissProgress() {
        if (progress != null && progress.isShowing()) {
            progress.dismiss();
        }
        progress = null;
    }

    private LinearLayout progressView() {
        int pad = Math.round(24 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        layout.setGravity(Gravity.CENTER_VERTICAL);
        layout.setPadding(pad, pad, pad, pad);
        ProgressBar bar = new ProgressBar(this);
        bar.setIndeterminate(true);
        layout.addView(bar);
        TextView text = new TextView(this);
        text.setText(R.string.working);
        text.setPadding(pad, 0, 0, 0);
        layout.addView(text);
        return layout;
    }
}
