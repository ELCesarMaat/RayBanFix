package com.elcesarmaat.raybanfix;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

// Serves fixed videos that aren't kept in the gallery ("Guardar archivos arreglados" off) to the app they're shared
// with. The files live in the cache and are swept after a day, which leaves the receiving app time to copy them.
public class SharedFileProvider extends ContentProvider {

    static final String AUTHORITY = "com.elcesarmaat.raybanfix.files";
    private static final String DIR = "shared";
    private static final long MAX_AGE_MS = 24L * 60 * 60 * 1000;

    static File newFile(Context context, String name) throws IOException {
        File dir = new File(context.getCacheDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("No pude crear la carpeta temporal.");
        }
        long now = System.currentTimeMillis();
        File[] existing = dir.listFiles();
        if (existing != null) {
            for (File f : existing) {
                if (now - f.lastModified() > MAX_AGE_MS) {
                    f.delete();
                }
            }
        }
        return new File(dir, safeName(name));
    }

    static Uri uriFor(File file) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(file.getName()).build();
    }

    private static String safeName(String name) {
        return name.replaceAll("[^A-Za-z0-9._() -]", "_");
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || !name.equals(safeName(name))) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        try {
            File dir = new File(getContext().getCacheDir(), DIR).getCanonicalFile();
            File file = new File(dir, name).getCanonicalFile();
            if (!dir.equals(file.getParentFile()) || !file.isFile()) {
                throw new FileNotFoundException(String.valueOf(uri));
            }
            return file;
        } catch (IOException e) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new FileNotFoundException("Solo lectura: " + uri);
        }
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = fileFor(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] requested = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        List<String> columns = new ArrayList<>();
        List<Object> values = new ArrayList<>();
        for (String column : requested) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                columns.add(column);
                values.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                columns.add(column);
                values.add(file.length());
            }
        }
        MatrixCursor cursor = new MatrixCursor(columns.toArray(new String[0]), 1);
        cursor.addRow(values.toArray());
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "video/mp4";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        return null;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        return 0;
    }
}
