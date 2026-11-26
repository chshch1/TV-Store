package com.example.tvwebdavbox;

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

/**
 * 把下载到本机的文件以 content:// 形式交给播放器 / 系统安装器。
 * 只允许访问本应用的私有目录，避免越权读取。
 */
public class LocalFileProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".files";

    public static Uri uriFor(Context context, File file) {
        return Uri.parse("content://" + context.getPackageName() + AUTHORITY_SUFFIX
                + "/" + Uri.encode(file.getAbsolutePath().substring(1), "/"));
    }

    private File resolve(Uri uri) throws FileNotFoundException {
        String path = uri.getPath();
        if (path == null) {
            throw new FileNotFoundException("空路径");
        }
        File file = new File("/" + Uri.decode(path.substring(1)));
        if (!isAllowed(file)) {
            throw new FileNotFoundException("不允许访问该路径");
        }
        if (!file.exists()) {
            throw new FileNotFoundException(file.getAbsolutePath());
        }
        return file;
    }

    private boolean isAllowed(File file) {
        String absolute = file.getAbsolutePath();
        Context context = getContext();
        if (context == null) {
            return false;
        }
        File[] roots = new File[]{
                context.getExternalFilesDir(null),
                context.getFilesDir(),
                context.getCacheDir()
        };
        for (File root : roots) {
            if (root != null && absolute.startsWith(root.getAbsolutePath())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = resolve(uri);
        int flags = "r".equals(mode) ? ParcelFileDescriptor.MODE_READ_ONLY
                : ParcelFileDescriptor.MODE_READ_WRITE;
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public String getType(Uri uri) {
        try {
            return FileUtils.mimeTypeOf(resolve(uri).getName());
        } catch (FileNotFoundException e) {
            return "application/octet-stream";
        }
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File file;
        try {
            file = resolve(uri);
        } catch (FileNotFoundException e) {
            return null;
        }
        String[] columns = projection != null ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        Object[] row = new Object[columns.length];
        for (int i = 0; i < columns.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) {
                row[i] = file.getName();
            } else if (OpenableColumns.SIZE.equals(columns[i])) {
                row[i] = file.length();
            } else {
                row[i] = null;
            }
        }
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("不支持写入");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("不支持删除");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("不支持更新");
    }
}
