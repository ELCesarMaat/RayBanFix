package com.elcesarmaat.raybanfix;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

// Shortcuts to original glasses videos, kept in app-private storage so re-sharing never needs a stored copy.
final class ShortcutStore {

    static final class Shortcut {
        final long id;
        final Uri uri;
        final String name;
        final long sizeBytes;
        final long durationMs;
        final long addedMs;

        Shortcut(long id, Uri uri, String name, long sizeBytes, long durationMs, long addedMs) {
            this.id = id;
            this.uri = uri;
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.durationMs = durationMs;
            this.addedMs = addedMs;
        }
    }

    private static final String FILE = "shortcuts.json";
    private static final Object LOCK = new Object();

    private ShortcutStore() {
    }

    static List<Shortcut> list(Context context) {
        synchronized (LOCK) {
            return read(context);
        }
    }

    // The same video (same name and size) keeps a single entry and its original date; only the source is refreshed.
    static void upsert(Context context, Uri uri, String name, long sizeBytes, long durationMs, long addedMs) {
        synchronized (LOCK) {
            List<Shortcut> all = read(context);
            long nextId = 1;
            for (int i = 0; i < all.size(); i++) {
                Shortcut s = all.get(i);
                nextId = Math.max(nextId, s.id + 1);
                if (s.name.equals(name) && s.sizeBytes == sizeBytes) {
                    long duration = durationMs > 0 ? durationMs : s.durationMs;
                    all.set(i, new Shortcut(s.id, uri, name, sizeBytes, duration, Math.min(s.addedMs, addedMs)));
                    write(context, all);
                    return;
                }
            }
            all.add(new Shortcut(nextId, uri, name, sizeBytes, durationMs, addedMs));
            write(context, all);
        }
    }

    static List<Shortcut> remove(Context context, Collection<Long> ids) {
        synchronized (LOCK) {
            List<Shortcut> kept = new ArrayList<>();
            List<Shortcut> removed = new ArrayList<>();
            for (Shortcut s : read(context)) {
                (ids.contains(s.id) ? removed : kept).add(s);
            }
            write(context, kept);
            return removed;
        }
    }

    private static List<Shortcut> read(Context context) {
        List<Shortcut> out = new ArrayList<>();
        AtomicFile file = file(context);
        if (!file.getBaseFile().exists()) {
            return out;
        }
        try {
            JSONArray array = new JSONArray(new String(file.readFully(), StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                out.add(new Shortcut(o.getLong("id"), Uri.parse(o.getString("uri")), o.getString("name"),
                        o.getLong("size"), o.optLong("duration"), o.getLong("added")));
            }
        } catch (IOException | JSONException e) {
            // A corrupt store only loses the shortcuts; the original videos are untouched.
            out.clear();
        }
        return out;
    }

    private static void write(Context context, List<Shortcut> all) {
        AtomicFile file = file(context);
        FileOutputStream out = null;
        try {
            JSONArray array = new JSONArray();
            for (Shortcut s : all) {
                array.put(new JSONObject()
                        .put("id", s.id)
                        .put("uri", s.uri.toString())
                        .put("name", s.name)
                        .put("size", s.sizeBytes)
                        .put("duration", s.durationMs)
                        .put("added", s.addedMs));
            }
            out = file.startWrite();
            out.write(array.toString().getBytes(StandardCharsets.UTF_8));
            file.finishWrite(out);
        } catch (IOException | JSONException e) {
            if (out != null) {
                file.failWrite(out);
            }
        }
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), FILE));
    }
}
