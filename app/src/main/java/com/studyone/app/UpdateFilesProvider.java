package com.studyone.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/** Read-only URI for the single APK verified by AppUpdates. */
public final class UpdateFilesProvider extends ContentProvider {
    private File validatedFile(Uri uri) throws FileNotFoundException {
        if (uri == null || !"/apk".equals(uri.getPath()) || getContext() == null)
            throw new FileNotFoundException("Unrecognized update path");
        File file = new File(new File(getContext().getFilesDir(), "studyone-updates"), "studyone.apk");
        if (!file.isFile()) throw new FileNotFoundException("Update not ready");
        return file;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        return "/apk".equals(uri.getPath()) ? "application/vnd.android.package-archive" : null;
    }

    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only");
        return ParcelFileDescriptor.open(validatedFile(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override public Cursor query(Uri uri, String[] projection,
                                  String selection, String[] selectionArgs, String sortOrder) {
        try {
            File file = validatedFile(uri);
            String[] columns = projection == null
                    ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                    : projection;
            MatrixCursor result = new MatrixCursor(columns, 1);
            Object[] values = new Object[columns.length];
            for (int i=0; i<columns.length; i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) values[i] = "StudyOne-update.apk";
                else if (OpenableColumns.SIZE.equals(columns[i])) values[i] = file.length();
            }
            result.addRow(values);
            return result;
        } catch (FileNotFoundException e) {
            return null;
        }
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException();
    }
    @Override public int delete(Uri uri, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
        throw new UnsupportedOperationException();
    }
}
