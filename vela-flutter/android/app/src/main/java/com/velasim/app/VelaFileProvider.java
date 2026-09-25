package com.velasim.app;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.List;

/**
 * 把构建产物用 {@code content://} 暴露给别的应用 —— 工程页「用其他应用打开」的后端。
 *
 * <pre>
 *   content://com.velasim.app.fileprovider/files/vela/projects/x/dist/a.rpk
 *   content://com.velasim.app.fileprovider/sdcard/Vortex/rpk/a.rpk
 * </pre>
 *
 * <p>为什么需要它：Android 7 起把 {@code file://} 交给别的应用会直接抛
 * {@code FileUriExposedException}，所以分享/打开私有目录里的文件必须走内容提供者。
 * 这里不去引 androidx 的 FileProvider（多一个依赖、离线构建时还得联网拉包），
 * 自己实现最小的一份：只读、只在白名单根目录里、只按 URI 授权（
 * {@code Intent.FLAG_GRANT_READ_URI_PERMISSION}），provider 本身 {@code exported=false}。</p>
 */
public class VelaFileProvider extends ContentProvider {

    /** 与 AndroidManifest 里的 {@code ${applicationId}.fileprovider} 对齐。 */
    public static final String AUTHORITY_SUFFIX = ".fileprovider";

    private static final String ROOT_FILES = "files";
    private static final String ROOT_CACHE = "cache";
    private static final String ROOT_SDCARD = "sdcard";

    /** 给一个真实的文件算出可分享的 content URI；落在白名单根之外就返回 null。 */
    public static Uri uriFor(Context ctx, File f) {
        if (ctx == null || f == null) {
            return null;
        }
        String auth = ctx.getPackageName() + AUTHORITY_SUFFIX;
        try {
            String abs = f.getCanonicalPath();
            String files = ctx.getFilesDir().getCanonicalPath();
            if (abs.startsWith(files + File.separator)) {
                return build(auth, ROOT_FILES, abs.substring(files.length()));
            }
            String cache = ctx.getCacheDir().getCanonicalPath();
            if (abs.startsWith(cache + File.separator)) {
                return build(auth, ROOT_CACHE, abs.substring(cache.length()));
            }
            String ext = Environment.getExternalStorageDirectory().getCanonicalPath();
            if (abs.startsWith(ext + File.separator)) {
                return build(auth, ROOT_SDCARD, abs.substring(ext.length()));
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** 路径分段要编码（空格、中文），但保留分隔符，方便在 logcat 里一眼看懂。 */
    private static Uri build(String auth, String root, String rel) {
        String tail = rel.startsWith(File.separator) ? rel.substring(1) : rel;
        return Uri.parse("content://" + auth + "/" + root + "/" + Uri.encode(tail, "/"));
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    /** URI → 文件；只允许白名单根，且必须落在根内部（挡 `../`）。 */
    private File resolve(Uri uri) {
        Context ctx = getContext();
        if (ctx == null || uri == null) {
            return null;
        }
        List<String> segments = uri.getPathSegments();
        if (segments.isEmpty()) {
            return null;
        }
        String root = segments.get(0);
        File base;
        if (ROOT_FILES.equals(root)) {
            base = ctx.getFilesDir();
        } else if (ROOT_CACHE.equals(root)) {
            base = ctx.getCacheDir();
        } else if (ROOT_SDCARD.equals(root)) {
            base = Environment.getExternalStorageDirectory();
        } else {
            return null;
        }
        StringBuilder rel = new StringBuilder();
        for (int i = 1; i < segments.size(); i++) {
            if (i > 1) {
                rel.append('/');
            }
            rel.append(segments.get(i));
        }
        File f = new File(base, rel.toString());
        try {
            String c = f.getCanonicalPath();
            String b = base.getCanonicalPath();
            if (!c.equals(b) && !c.startsWith(b + File.separator)) {
                return null;
            }
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File f = resolve(uri);
        if (f == null || !f.isFile()) {
            throw new FileNotFoundException(String.valueOf(uri));
        }
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    /** 有些应用（文件管理器）先问名字和大小，答不上来会显示成一串乱码。 */
    @Override
    public Cursor query(Uri uri, String[] projection, String selection,
                        String[] selectionArgs, String sortOrder) {
        File f = resolve(uri);
        if (f == null || !f.isFile()) {
            return null;
        }
        String[] cols = projection == null
                ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor c = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) {
                row[i] = f.getName();
            } else if (OpenableColumns.SIZE.equals(cols[i])) {
                row[i] = f.length();
            } else {
                row[i] = null;
            }
        }
        c.addRow(row);
        return c;
    }

    @Override
    public String getType(Uri uri) {
        File f = resolve(uri);
        if (f == null) {
            return null;
        }
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String ext = name.substring(dot + 1).toLowerCase();
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
            if (mime != null && !mime.isEmpty()) {
                return mime;
            }
        }
        return "application/octet-stream";
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("VelaFileProvider 是只读的");
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("VelaFileProvider 是只读的");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("VelaFileProvider 是只读的");
    }
}
