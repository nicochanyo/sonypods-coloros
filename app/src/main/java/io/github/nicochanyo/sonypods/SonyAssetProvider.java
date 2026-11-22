package io.github.nicochanyo.sonypods;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Exported read-only ContentProvider serving cached official Sony PNG artwork
 * and optional user-picked custom PNG overrides across SELinux boundaries to
 * {@code com.heytap.mydevices} without filesystem permission issues.
 */
public final class SonyAssetProvider extends ContentProvider {
    public static final String AUTHORITY = ConfigStore.PKG_NAME + ".assets";
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final ExecutorService DIRECT_FETCH_EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SonyPods-DirectArt");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Boolean> DIRECT_IN_FLIGHT = new ConcurrentHashMap<>();

    public static Uri buildArtworkUri(String modelName, int colorCode) {
        String safe = (modelName == null || modelName.trim().isEmpty())
                ? "Sony"
                : SonyDeviceDetector.normalizeModelName(modelName);
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("artwork")
                .appendPath(safe)
                .appendQueryParameter("color", String.valueOf(colorCode))
                .build();
    }

    public static Uri buildCustomUri() {
        return new Uri.Builder()
                .scheme("content")
                .authority(AUTHORITY)
                .appendPath("custom")
                .build();
    }

    /**
     * Loads official cached PNG or custom PNG via ContentProvider IPC / caller cache / CDN,
     * falling back to transparent {@link SonyArtworkFallback} if unavailable synchronously.
     */
    public static Bitmap loadBitmap(
            Context ctx,
            String modelName,
            int colorCode,
            String customUriStr,
            int targetSizePx
    ) {
        return loadBitmap(ctx, modelName, colorCode, customUriStr, targetSizePx, null);
    }

    /**
     * Official-first artwork loader:
     * 1) Custom PNG override (if configured),
     * 2) Caller-local cached official PNG,
     * 3) SonyAssetProvider Binder IPC -> persist locally,
     * 4) Direct Sony CDN fetch via bundled catalog (sync off main thread, or async + callback on main thread),
     * 5) Vector fallback placeholder only when offline / fetch pending.
     */
    public static Bitmap loadBitmap(
            Context ctx,
            String modelName,
            int colorCode,
            String customUriStr,
            int targetSizePx,
            Runnable onOfficialCached
    ) {
        if (ctx != null) {
            boolean wantCustom = customUriStr != null && !customUriStr.trim().isEmpty();
            if (wantCustom) {
                File customCache = getCallerCacheFile(ctx, modelName, colorCode, true);
                Bitmap custom = decodeFromProviderUri(ctx, buildCustomUri(), targetSizePx);
                if (custom != null) {
                    writeCallerCacheBitmap(customCache, custom);
                    return custom;
                }
                Bitmap cachedCustom = decodeCallerCacheFile(customCache);
                if (cachedCustom != null) {
                    return cachedCustom;
                }
            }

            File officialCache = getCallerCacheFile(ctx, modelName, colorCode, false);
            Bitmap cachedOfficial = decodeCallerCacheFile(officialCache);
            if (cachedOfficial != null) {
                return cachedOfficial;
            }

            Bitmap fromIpc = decodeFromProviderUri(ctx, buildArtworkUri(modelName, colorCode), targetSizePx);
            if (fromIpc != null) {
                writeCallerCacheBitmap(officialCache, fromIpc);
                return fromIpc;
            }

            if (Looper.myLooper() != Looper.getMainLooper()) {
                Bitmap direct = SonyModelImageManager.downloadOfficialBitmapDirect(
                        ctx, modelName, colorCode, targetSizePx);
                if (direct != null) {
                    writeCallerCacheBitmap(officialCache, direct);
                    return direct;
                }
            } else {
                scheduleDirectOfficialFetch(ctx, modelName, colorCode, targetSizePx, officialCache, onOfficialCached);
            }
        }
        return SonyArtworkFallback.createFallbackBitmap(modelName, colorCode, targetSizePx);
    }

    private static void scheduleDirectOfficialFetch(
            Context ctx,
            String modelName,
            int colorCode,
            int targetSizePx,
            File cacheFile,
            Runnable onOfficialCached
    ) {
        if (ctx == null || modelName == null || modelName.trim().isEmpty() || cacheFile == null) {
            return;
        }
        String key = cacheFile.getAbsolutePath();
        if (DIRECT_IN_FLIGHT.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        final Context appCtx = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        DIRECT_FETCH_EXEC.execute(() -> {
            try {
                Bitmap bmp = SonyModelImageManager.downloadOfficialBitmapDirect(
                        appCtx, modelName, colorCode, targetSizePx);
                if (bmp != null) {
                    writeCallerCacheBitmap(cacheFile, bmp);
                    bmp.recycle();
                    if (onOfficialCached != null) {
                        MAIN_HANDLER.post(onOfficialCached);
                    }
                }
            } finally {
                DIRECT_IN_FLIGHT.remove(key);
            }
        });
    }

    private static File getCallerCacheFile(Context ctx, String modelName, int colorCode, boolean isCustom) {
        try {
            File dir = new File(ctx.getCacheDir(), "sonypods_art");
            if (isCustom) {
                return new File(dir, "custom.png");
            }
            String norm = (modelName == null || modelName.trim().isEmpty())
                    ? "sony"
                    : SonyDeviceDetector.normalizeModelName(modelName);
            String safe = norm.toLowerCase(Locale.US).replaceAll("[^a-z0-9]+", "_");
            if (safe.isEmpty()) safe = "sony";
            return new File(dir, "art_" + safe + "_" + colorCode + ".png");
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static void writeCallerCacheBitmap(File file, Bitmap bmp) {
        if (file == null || bmp == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            File tmp = new File(parent, file.getName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                fos.flush();
            }
            if (!tmp.renameTo(file)) {
                tmp.delete();
            }
        } catch (Throwable ignored) {
        }
    }

    private static Bitmap decodeCallerCacheFile(File file) {
        if (file == null || !file.exists() || file.length() <= 0) {
            return null;
        }
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap decodeFromProviderUri(Context ctx, Uri uri, int targetSizePx) {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            Bitmap decoded = BitmapFactory.decodeStream(is);
            if (decoded == null) return null;
            int maxDim = Math.max(decoded.getWidth(), decoded.getHeight());
            int cap = Math.max(256, Math.min(768, targetSizePx));
            if (maxDim > cap) {
                float scale = (float) cap / (float) maxDim;
                int w = Math.max(1, Math.round(decoded.getWidth() * scale));
                int h = Math.max(1, Math.round(decoded.getHeight() * scale));
                Bitmap scaled = Bitmap.createScaledBitmap(decoded, w, h, true);
                if (scaled != decoded) {
                    decoded.recycle();
                }
                return scaled;
            }
            return decoded;
        } catch (Throwable ignored) {
            return null;
        }
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (uri == null || (mode != null && !"r".equals(mode))) {
            throw new FileNotFoundException("Read-only provider");
        }
        Context ctx = getContext();
        if (ctx == null) {
            throw new FileNotFoundException("No context");
        }
        List<String> segs = uri.getPathSegments();
        if (segs.isEmpty()) {
            throw new FileNotFoundException("Empty path");
        }
        String kind = segs.get(0);
        if ("custom".equals(kind)) {
            File f = SonyModelImageManager.getCustomOverrideFile(ctx);
            if (f.exists() && f.length() > 0) {
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            throw new FileNotFoundException("No custom image");
        }
        if ("artwork".equals(kind) && segs.size() >= 2) {
            String model = segs.get(1);
            int color = -1;
            String rawColor = uri.getQueryParameter("color");
            if (rawColor != null) {
                try {
                    color = Integer.parseInt(rawColor);
                } catch (NumberFormatException ignored) {
                }
            }
            File f = SonyModelImageManager.getCachedModelFile(ctx, model, color);
            if (f.exists() && f.length() > 0) {
                return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
            }
            if (SonyModelImageManager.downloadOfficialModelBlocking(ctx, model, color)) {
                f = SonyModelImageManager.getCachedModelFile(ctx, model, color);
                if (f.exists() && f.length() > 0) {
                    return ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY);
                }
            }
            throw new FileNotFoundException("Artwork unavailable");
        }
        throw new FileNotFoundException("Unsupported path: " + uri);
    }

    @Override
    public String getType(Uri uri) {
        return "image/png";
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        return null;
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
