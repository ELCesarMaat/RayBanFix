package com.elcesarmaat.raybanfix;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Intent;
import android.content.IntentSender;
import android.content.res.ColorStateList;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.text.format.Formatter;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {

    private static final int REQUEST_PICK_VIDEO = 1;
    private static final int REQUEST_PICK_OTHER_APP_VIDEO = 2;
    private static final int REQUEST_DELETE = 3;
    private static final int REQUEST_MEDIA_ACCESS = 4;

    // Survives activity recreation so the list doesn't re-read every copy's header on each resume.
    private static final Map<String, OriginInspector.Origin> ORIGINS = new ConcurrentHashMap<>();

    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private final Map<String, View> rows = new HashMap<>();
    private final Map<String, Boolean> available = new ConcurrentHashMap<>();
    private List<Conversion> conversions = Collections.emptyList();
    private int generation;

    private LinearLayout videoList;
    private View emptyState;
    private View accessBanner;
    private View convertBanner;
    private View barNormal;
    private View barSelection;
    private TextView convertBannerText;
    private TextView videoCount;
    private TextView selectionCount;
    private Object backCallback;

    // A list row: either a shortcut to an original glasses video or an old full copy in Movies/RayBanFix.
    private static final class Entry {
        final String key;
        final boolean shortcut;
        final long refId;
        final Uri uri;
        final String name;
        final long addedMs;
        final long sizeBytes;
        final long durationMs;

        private Entry(String key, boolean shortcut, long refId, Uri uri, String name, long addedMs, long sizeBytes,
                      long durationMs) {
            this.key = key;
            this.shortcut = shortcut;
            this.refId = refId;
            this.uri = uri;
            this.name = name;
            this.addedMs = addedMs;
            this.sizeBytes = sizeBytes;
            this.durationMs = durationMs;
        }

        static Entry of(ShortcutStore.Shortcut s) {
            return new Entry("s" + s.id, true, s.id, s.uri, s.name, s.addedMs, s.sizeBytes, s.durationMs);
        }

        static Entry copy(long id, Uri uri, String name, long addedSeconds, long sizeBytes, long durationMs) {
            return new Entry("c" + id, false, id, uri, name, addedSeconds * 1000L, sizeBytes, durationMs);
        }

        String originKey() {
            return key + ":" + sizeBytes;
        }
    }

    private static final class Conversion {
        final Entry copy;
        final Uri original;

        Conversion(Entry copy, Uri original) {
            this.copy = copy;
            this.original = original;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Ui.applySystemBarInsets(findViewById(R.id.root));
        videoList = findViewById(R.id.video_list);
        emptyState = findViewById(R.id.empty_state);
        accessBanner = findViewById(R.id.access_banner);
        convertBanner = findViewById(R.id.convert_banner);
        convertBannerText = findViewById(R.id.convert_banner_text);
        videoCount = findViewById(R.id.video_count);
        barNormal = findViewById(R.id.bar_normal);
        barSelection = findViewById(R.id.bar_selection);
        selectionCount = findViewById(R.id.selection_count);

        findViewById(R.id.pick_button).setOnClickListener(v -> pickVideo(REQUEST_PICK_VIDEO));
        findViewById(R.id.other_app_button).setOnClickListener(v -> pickVideo(REQUEST_PICK_OTHER_APP_VIDEO));
        findViewById(R.id.settings_button).setOnClickListener(
                v -> startActivity(new Intent(this, OptionsActivity.class)));
        findViewById(R.id.access_banner_action).setOnClickListener(
                v -> MediaAccess.request(this, REQUEST_MEDIA_ACCESS));
        findViewById(R.id.convert_banner_action).setOnClickListener(v -> confirmConversion());
        findViewById(R.id.close_selection).setOnClickListener(v -> exitSelection());
        findViewById(R.id.select_all).setOnClickListener(v -> selectAll());
        findViewById(R.id.delete_selected).setOnClickListener(v -> confirmDelete(selectedEntries()));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        // Only reached below API 33; newer versions go through the OnBackInvokedCallback registered for selection.
        if (!selected.isEmpty()) {
            exitSelection();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA_ACCESS) {
            refresh();
        }
    }

    private void pickVideo(int requestCode) {
        Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        pick.addCategory(Intent.CATEGORY_OPENABLE);
        pick.setType("video/*");
        startActivityForResult(pick, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_DELETE) {
            refresh();
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        if (requestCode == REQUEST_PICK_OTHER_APP_VIDEO) {
            Intent tag = new Intent(this, OtherAppActivity.class);
            tag.setData(data.getData());
            tag.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(tag);
            return;
        }
        if (requestCode != REQUEST_PICK_VIDEO) {
            return;
        }
        try {
            // Keeps the picked video readable after this session, so its shortcut works without media access.
            getContentResolver().takePersistableUriPermission(data.getData(), Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException ignored) {
            // The provider doesn't offer persistable grants; the shortcut then relies on media access.
        }
        fix(data.getData());
    }

    private void fix(Uri source) {
        Intent fix = new Intent(this, FixActivity.class);
        fix.setAction(Intent.ACTION_SEND);
        fix.setType("video/*");
        fix.putExtra(Intent.EXTRA_STREAM, source);
        fix.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(fix);
    }

    private void refresh() {
        int current = ++generation;
        io.execute(() -> {
            List<Entry> list = load();
            runOnUiThread(() -> {
                if (current == generation) {
                    render(list);
                }
            });
            List<Conversion> found = findConversions(list);
            runOnUiThread(() -> {
                if (current == generation) {
                    showConversions(found);
                }
            });
        });
    }

    private List<Entry> load() {
        List<Entry> out = new ArrayList<>();
        for (ShortcutStore.Shortcut s : ShortcutStore.list(this)) {
            out.add(Entry.of(s));
        }
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        String[] projection = {
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.SIZE,
                MediaStore.Video.VideoColumns.DURATION,
        };
        String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
        String[] args = {Environment.DIRECTORY_MOVIES + "/RayBanFix/%"};
        try (Cursor c = getContentResolver().query(collection, projection, selection, args, null)) {
            while (c != null && c.moveToNext()) {
                long id = c.getLong(0);
                out.add(Entry.copy(id, ContentUris.withAppendedId(collection, id), c.getString(1), c.getLong(2),
                        c.getLong(3), c.getLong(4)));
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.addedMs, a.addedMs));
        return out;
    }

    // Old copies made by the glasses fix whose original is still on the phone can become shortcuts.
    private List<Conversion> findConversions(List<Entry> list) {
        List<Conversion> out = new ArrayList<>();
        if (MediaAccess.level(this) == MediaAccess.Level.NONE) {
            return out;
        }
        for (Entry e : list) {
            if (e.shortcut || originOf(e) != OriginInspector.Origin.GLASSES) {
                continue;
            }
            Uri original = SourceResolver.findOriginalOfCopy(getContentResolver(), e.name, e.sizeBytes);
            if (original != null) {
                out.add(new Conversion(e, original));
            }
        }
        return out;
    }

    private void render(List<Entry> list) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        entries.clear();
        entries.addAll(list);
        Set<String> present = new HashSet<>();
        for (Entry e : list) {
            present.add(e.key);
        }
        selected.retainAll(present);

        accessBanner.setVisibility(MediaAccess.level(this) == MediaAccess.Level.NONE ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.access_banner_text)).setText(R.string.access_banner_none);
        emptyState.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        videoCount.setText(list.isEmpty() ? ""
                : getResources().getQuantityString(R.plurals.video_count, list.size(), list.size()));

        videoList.removeAllViews();
        rows.clear();
        LayoutInflater inflater = LayoutInflater.from(this);
        String currentDay = null;
        for (Entry entry : list) {
            String day = dayLabel(entry.addedMs);
            if (!day.equals(currentDay)) {
                TextView header = (TextView) inflater.inflate(R.layout.item_date_header, videoList, false);
                header.setText(day);
                videoList.addView(header);
                currentDay = day;
            }
            View row = buildRow(inflater, entry);
            rows.put(entry.key, row);
            videoList.addView(row);
        }
        updateSelectionUi();
    }

    private void showConversions(List<Conversion> found) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        conversions = found;
        convertBanner.setVisibility(found.isEmpty() ? View.GONE : View.VISIBLE);
        if (!found.isEmpty()) {
            convertBannerText.setText(getResources().getQuantityString(R.plurals.convert_banner, found.size(),
                    found.size(), Formatter.formatShortFileSize(this, bytesOf(found))));
        }
    }

    private View buildRow(LayoutInflater inflater, Entry entry) {
        View row = inflater.inflate(R.layout.item_video, videoList, false);
        ((TextView) row.findViewById(R.id.name)).setText(entry.name);
        String details = DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date(entry.addedMs))
                + " · " + Formatter.formatShortFileSize(this, entry.sizeBytes);
        ((TextView) row.findViewById(R.id.details)).setText(details);
        row.findViewById(R.id.copy_chip).setVisibility(entry.shortcut ? View.GONE : View.VISIBLE);
        TextView duration = row.findViewById(R.id.duration);
        duration.setText(formatDuration(entry.durationMs));
        duration.setVisibility(entry.durationMs > 0 ? View.VISIBLE : View.GONE);

        ImageView thumb = row.findViewById(R.id.thumb);
        thumb.setClipToOutline(true);
        io.execute(() -> loadThumbnail(entry.uri, thumb));

        TextView chip = row.findViewById(R.id.origin);
        bindChecking(chip);
        if (entry.shortcut) {
            io.execute(() -> {
                boolean ok = SourceResolver.canOpen(getContentResolver(), entry.uri);
                available.put(entry.key, ok);
                runOnUiThread(() -> {
                    if (ok) {
                        bindOrigin(chip, OriginInspector.Origin.GLASSES);
                    } else {
                        bindMissing(chip);
                    }
                });
            });
        } else {
            OriginInspector.Origin cached = ORIGINS.get(entry.originKey());
            if (cached != null) {
                bindOrigin(chip, cached);
            } else {
                io.execute(() -> {
                    OriginInspector.Origin origin = originOf(entry);
                    runOnUiThread(() -> bindOrigin(chip, origin));
                });
            }
        }

        row.setOnClickListener(v -> {
            if (selected.isEmpty()) {
                showActions(entry);
            } else {
                toggle(entry.key);
            }
        });
        row.setOnLongClickListener(v -> {
            toggle(entry.key);
            return true;
        });
        return row;
    }

    private OriginInspector.Origin originOf(Entry entry) {
        OriginInspector.Origin cached = ORIGINS.get(entry.originKey());
        if (cached != null) {
            return cached;
        }
        try (InputStream raw = getContentResolver().openInputStream(entry.uri)) {
            if (raw == null) {
                return null;
            }
            OriginInspector.Origin origin = OriginInspector.inspect(new BufferedInputStream(raw, 1 << 16));
            ORIGINS.put(entry.originKey(), origin);
            return origin;
        } catch (Exception e) {
            return null;
        }
    }

    private void loadThumbnail(Uri uri, ImageView target) {
        try {
            Bitmap bitmap = getContentResolver().loadThumbnail(uri, new Size(240, 320), null);
            runOnUiThread(() -> target.setImageBitmap(bitmap));
        } catch (Exception ignored) {
            // No thumbnail (original gone, no access, still scanning); the placeholder background stays.
        }
    }

    private void bindChecking(TextView chip) {
        chip.setVisibility(View.VISIBLE);
        chip.setText(R.string.origin_checking);
        chip.setBackgroundResource(R.drawable.bg_chip_neutral);
        chip.setTextColor(getColor(R.color.rb_on_surface_variant));
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
    }

    private void bindMissing(TextView chip) {
        int color = getColor(R.color.rb_on_warn_container);
        chip.setVisibility(View.VISIBLE);
        chip.setText(R.string.origin_missing);
        chip.setBackgroundResource(R.drawable.bg_chip_warn);
        chip.setTextColor(color);
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_warning_small, 0, 0, 0);
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(color));
    }

    private void bindOrigin(TextView chip, OriginInspector.Origin origin) {
        if (origin == null) {
            chip.setVisibility(View.GONE);
            return;
        }
        int text;
        int background;
        int color;
        int icon;
        switch (origin) {
            case GLASSES:
                text = R.string.origin_glasses;
                background = R.drawable.bg_chip_ok;
                color = R.color.rb_on_ok_container;
                icon = R.drawable.ic_check_small;
                break;
            case TAG_ONLY:
                text = R.string.origin_tag_only;
                background = R.drawable.bg_chip_warn;
                color = R.color.rb_on_warn_container;
                icon = R.drawable.ic_warning_small;
                break;
            default:
                text = R.string.origin_untagged;
                background = R.drawable.bg_chip_neutral;
                color = R.color.rb_on_surface_variant;
                icon = 0;
                break;
        }
        int resolved = getColor(color);
        chip.setVisibility(View.VISIBLE);
        chip.setText(text);
        chip.setBackgroundResource(background);
        chip.setTextColor(resolved);
        chip.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0);
        chip.setCompoundDrawableTintList(ColorStateList.valueOf(resolved));
    }

    private void showActions(Entry entry) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle(entry.name);
        if (entry.shortcut && Boolean.FALSE.equals(available.get(entry.key))) {
            dialog.setMessage(R.string.source_unavailable)
                    .setPositiveButton(R.string.action_remove,
                            (d, which) -> removeEntries(Collections.singletonList(entry), false))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        CharSequence[] actions = {getString(R.string.action_share), getString(R.string.action_play),
                getString(entry.shortcut ? R.string.action_remove : R.string.action_delete)};
        dialog.setItems(actions, (d, which) -> {
            if (which == 0) {
                if (entry.shortcut) {
                    fix(entry.uri);
                } else {
                    startActivity(Shares.chooser(this, entry.uri));
                }
            } else if (which == 1) {
                try {
                    startActivity(Shares.player(entry.uri));
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(this, R.string.no_player, Toast.LENGTH_SHORT).show();
                }
            } else {
                confirmDelete(Collections.singletonList(entry));
            }
        }).show();
    }

    private void toggle(String key) {
        if (!selected.remove(key)) {
            selected.add(key);
        }
        updateSelectionUi();
    }

    private void selectAll() {
        for (Entry e : entries) {
            selected.add(e.key);
        }
        updateSelectionUi();
    }

    private void exitSelection() {
        selected.clear();
        updateSelectionUi();
    }

    private List<Entry> selectedEntries() {
        List<Entry> out = new ArrayList<>();
        for (Entry e : entries) {
            if (selected.contains(e.key)) {
                out.add(e);
            }
        }
        return out;
    }

    private void updateSelectionUi() {
        boolean selecting = !selected.isEmpty();
        barNormal.setVisibility(selecting ? View.GONE : View.VISIBLE);
        barSelection.setVisibility(selecting ? View.VISIBLE : View.GONE);
        if (selecting) {
            selectionCount.setText(getResources().getQuantityString(R.plurals.selected_count, selected.size(),
                    selected.size()));
        }
        for (Map.Entry<String, View> row : rows.entrySet()) {
            boolean on = selected.contains(row.getKey());
            row.getValue().setActivated(on);
            CheckBox check = row.getValue().findViewById(R.id.check);
            check.setVisibility(selecting ? View.VISIBLE : View.GONE);
            check.setChecked(on);
        }
        updateBackCallback(selecting);
    }

    private void updateBackCallback(boolean selecting) {
        if (Build.VERSION.SDK_INT < 33) {
            return;
        }
        OnBackInvokedDispatcher dispatcher = getOnBackInvokedDispatcher();
        if (selecting && backCallback == null) {
            OnBackInvokedCallback callback = this::exitSelection;
            dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
            backCallback = callback;
        } else if (!selecting && backCallback != null) {
            dispatcher.unregisterOnBackInvokedCallback((OnBackInvokedCallback) backCallback);
            backCallback = null;
        }
    }

    private void confirmDelete(List<Entry> targets) {
        if (targets.isEmpty()) {
            return;
        }
        int shortcuts = 0;
        for (Entry e : targets) {
            if (e.shortcut) {
                shortcuts++;
            }
        }
        int n = targets.size();
        boolean onlyShortcuts = shortcuts == n;
        int message = onlyShortcuts ? R.string.remove_message
                : shortcuts == 0 ? R.string.delete_message : R.string.mixed_message;
        new AlertDialog.Builder(this)
                .setTitle(getResources().getQuantityString(onlyShortcuts ? R.plurals.remove_title
                        : R.plurals.delete_title, n, n))
                .setMessage(message)
                .setPositiveButton(onlyShortcuts ? R.string.action_remove : R.string.action_delete,
                        (d, which) -> removeEntries(targets, false))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmConversion() {
        List<Conversion> pending = new ArrayList<>(conversions);
        if (pending.isEmpty()) {
            return;
        }
        int n = pending.size();
        new AlertDialog.Builder(this)
                .setTitle(R.string.convert_confirm_title)
                .setMessage(getResources().getQuantityString(R.plurals.convert_confirm_message, n, n,
                        Formatter.formatShortFileSize(this, bytesOf(pending))))
                .setPositiveButton(R.string.convert_action, (d, which) -> convert(pending))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void convert(List<Conversion> pending) {
        io.execute(() -> {
            List<Conversion> oldestFirst = new ArrayList<>(pending);
            // Oldest first, so each shortcut keeps the date of the first copy made from its original.
            Collections.sort(oldestFirst, (a, b) -> Long.compare(a.copy.addedMs, b.copy.addedMs));
            List<Entry> copies = new ArrayList<>();
            for (Conversion c : oldestFirst) {
                SourceResolver.Info info = SourceResolver.info(getContentResolver(), c.original);
                if (info == null) {
                    continue;
                }
                ShortcutStore.upsert(this, c.original, info.name, info.sizeBytes, c.copy.durationMs, c.copy.addedMs);
                copies.add(c.copy);
            }
            runOnUiThread(() -> removeEntries(copies, true));
        });
    }

    private void removeEntries(List<Entry> targets, boolean conversion) {
        io.execute(() -> {
            List<Long> shortcutIds = new ArrayList<>();
            List<Uri> needConsent = new ArrayList<>();
            int deleted = 0;
            long freed = 0;
            for (Entry e : targets) {
                if (e.shortcut) {
                    shortcutIds.add(e.refId);
                    continue;
                }
                try {
                    if (getContentResolver().delete(e.uri, null, null) > 0) {
                        deleted++;
                        freed += e.sizeBytes;
                    }
                } catch (SecurityException ex) {
                    // Not owned by this app (e.g. after a reinstall): the system has to ask the user.
                    needConsent.add(e.uri);
                }
            }
            int removed = 0;
            if (!shortcutIds.isEmpty()) {
                List<ShortcutStore.Shortcut> gone = ShortcutStore.remove(this, shortcutIds);
                removed = gone.size();
                releaseUnusedGrants(gone);
            }
            String message = conversion
                    ? (freed > 0 ? getString(R.string.converted_toast, Formatter.formatShortFileSize(this, freed)) : null)
                    : summary(removed, deleted);
            runOnUiThread(() -> {
                exitSelection();
                if (message != null) {
                    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
                }
                if (!needConsent.isEmpty()) {
                    requestSystemDelete(needConsent);
                }
                refresh();
            });
        });
    }

    // Picked videos hold a persisted read grant; drop it once no shortcut points at that video anymore.
    private void releaseUnusedGrants(List<ShortcutStore.Shortcut> gone) {
        Set<Uri> stillUsed = new HashSet<>();
        for (ShortcutStore.Shortcut s : ShortcutStore.list(this)) {
            stillUsed.add(s.uri);
        }
        for (ShortcutStore.Shortcut s : gone) {
            if (!stillUsed.contains(s.uri) && SourceResolver.hasPersistedRead(getContentResolver(), s.uri)) {
                try {
                    getContentResolver().releasePersistableUriPermission(s.uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (SecurityException ignored) {
                    // Already released.
                }
            }
        }
    }

    private String summary(int removed, int deleted) {
        List<String> parts = new ArrayList<>();
        if (removed > 0) {
            parts.add(getResources().getQuantityString(R.plurals.removed_toast, removed, removed));
        }
        if (deleted > 0) {
            parts.add(getResources().getQuantityString(R.plurals.deleted_toast, deleted, deleted));
        }
        return parts.isEmpty() ? null : String.join(" · ", parts);
    }

    private void requestSystemDelete(List<Uri> uris) {
        if (Build.VERSION.SDK_INT < 30) {
            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show();
            return;
        }
        try {
            PendingIntent request = MediaStore.createDeleteRequest(getContentResolver(), uris);
            startIntentSenderForResult(request.getIntentSender(), REQUEST_DELETE, null, 0, 0, 0);
        } catch (IntentSender.SendIntentException | RuntimeException e) {
            Toast.makeText(this, R.string.delete_failed, Toast.LENGTH_LONG).show();
        }
    }

    private static long bytesOf(List<Conversion> list) {
        long total = 0;
        for (Conversion c : list) {
            total += c.copy.sizeBytes;
        }
        return total;
    }

    private String dayLabel(long millis) {
        Calendar item = Calendar.getInstance();
        item.setTimeInMillis(millis);
        Calendar today = Calendar.getInstance();
        if (sameDay(item, today)) {
            return getString(R.string.today);
        }
        Calendar yesterday = Calendar.getInstance();
        yesterday.add(Calendar.DAY_OF_YEAR, -1);
        if (sameDay(item, yesterday)) {
            return getString(R.string.yesterday);
        }
        String pattern = item.get(Calendar.YEAR) == today.get(Calendar.YEAR)
                ? "EEEE d 'de' MMMM" : "EEEE d 'de' MMMM 'de' yyyy";
        String label = new SimpleDateFormat(pattern, Locale.getDefault()).format(item.getTime());
        return label.substring(0, 1).toUpperCase(Locale.getDefault()) + label.substring(1);
    }

    private static boolean sameDay(Calendar a, Calendar b) {
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR);
    }

    private static String formatDuration(long ms) {
        long seconds = Math.max(0, ms / 1000);
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }
}
