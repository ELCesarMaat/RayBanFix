package com.elcesarmaat.raybanfix;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Turns the temporary URI another app shares with us into one we can still open later, for shortcuts.
final class SourceResolver {

    static final class Info {
        final String name;
        final long sizeBytes;

        Info(String name, long sizeBytes) {
            this.name = name;
            this.sizeBytes = sizeBytes;
        }
    }

    // "20260923_203527_e8c6064d_rbfix (1).mp4" -> "20260923_203527_e8c6064d"
    private static final Pattern COPY_NAME = Pattern.compile("^(.*)_rbfix(?: \\(\\d+\\))?\\.[^.]+$");

    private SourceResolver() {
    }

    static Info info(ContentResolver resolver, Uri uri) {
        String[] columns = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        try (Cursor c = resolver.query(uri, columns, null, null, null)) {
            if (c != null && c.moveToFirst() && !c.isNull(0) && !c.isNull(1)) {
                return new Info(c.getString(0), c.getLong(1));
            }
        } catch (Exception ignored) {
            // Provider gone or access revoked.
        }
        return null;
    }

    static boolean hasPersistedRead(ContentResolver resolver, Uri uri) {
        for (UriPermission p : resolver.getPersistedUriPermissions()) {
            if (p.isReadPermission() && p.getUri().equals(uri)) {
                return true;
            }
        }
        return false;
    }

    /** A URI for the same video that will still open later, or null (no media access, cloud-only item...). */
    static Uri durableUri(Context context, Uri shared, Info info) {
        ContentResolver resolver = context.getContentResolver();
        if (hasPersistedRead(resolver, shared)) {
            return shared;
        }
        if (MediaAccess.level(context) == MediaAccess.Level.NONE) {
            return null;
        }
        Uri media = null;
        if (MediaStore.AUTHORITY.equals(shared.getAuthority())) {
            media = shared;
        } else {
            try {
                if (DocumentsContract.isDocumentUri(context, shared)) {
                    media = MediaStore.getMediaUri(context, shared);
                }
            } catch (Exception ignored) {
                // Not a media document; try the other ways below.
            }
            if (media == null) {
                // Google Photos embeds the MediaStore URI as one of its path segments.
                for (String segment : shared.getPathSegments()) {
                    if (segment.startsWith("content://media/")) {
                        media = Uri.parse(segment);
                        break;
                    }
                }
            }
            if (media == null && info != null) {
                media = findVideo(resolver, info.name, info.sizeBytes, false);
            }
        }
        return media != null && canOpen(resolver, media) ? media : null;
    }

    /** Original of an old full copy made by the glasses fix; the fix never changes the file size. */
    static Uri findOriginalOfCopy(ContentResolver resolver, String copyName, long sizeBytes) {
        Matcher m = COPY_NAME.matcher(copyName);
        if (!m.matches()) {
            return null;
        }
        Uri uri = findVideo(resolver, m.group(1) + ".%", sizeBytes, true);
        return uri != null && canOpen(resolver, uri) ? uri : null;
    }

    static boolean canOpen(ContentResolver resolver, Uri uri) {
        try (ParcelFileDescriptor fd = resolver.openFileDescriptor(uri, "r")) {
            return fd != null;
        } catch (Exception e) {
            return false;
        }
    }

    static long durationMs(Context context, Uri uri) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(context, uri);
            String value = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            return 0;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
                // Nothing left to do with it.
            }
        }
    }

    private static Uri findVideo(ContentResolver resolver, String name, long sizeBytes, boolean nameIsPattern) {
        Uri collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        String selection = MediaStore.MediaColumns.DISPLAY_NAME + (nameIsPattern ? " LIKE ?" : " = ?")
                + " AND " + MediaStore.MediaColumns.SIZE + " = ?"
                + " AND (" + MediaStore.MediaColumns.RELATIVE_PATH + " IS NULL OR "
                + MediaStore.MediaColumns.RELATIVE_PATH + " NOT LIKE ?)";
        String[] args = {name, String.valueOf(sizeBytes), Environment.DIRECTORY_MOVIES + "/RayBanFix/%"};
        try (Cursor c = resolver.query(collection, new String[]{MediaStore.MediaColumns._ID}, selection, args,
                MediaStore.MediaColumns.DATE_ADDED + " ASC")) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(collection, c.getLong(0));
            }
        } catch (Exception ignored) {
            // No read access to other apps' media.
        }
        return null;
    }
}
