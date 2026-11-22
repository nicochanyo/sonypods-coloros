package io.github.nicochanyo.sonypods;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Downloads, caches, and resolves Sony's official cloud headphone catalog artwork
 * (from Sony's public GraphQL endpoint) and optional custom transparent PNG overrides.
 */
public final class SonyModelImageManager {
    private static final String GRAPHQL_URL = "https://v1.api.data-gateway.seeds.services/graphql";
    private static final String API_KEY = "37ksfjEuDRYXYtgus4rEDw6QC2NqhTfr";
    private static final String IMAGE_HOST = "hpc-image.data-gateway.seeds.services";
    private static final String CATALOG_QUERY =
            "query { HPC { getAllCloudModelInfos { "
                    + "model_id model_number is_official_model_name model_name "
                    + "model_color_id sca_image_image_url sca_source_color model_category "
                    + "} } }";

    private static final String BUNDLED_CATALOG_RESOURCE = "/sony_official_catalog.json";
    private static final int CONNECT_TIMEOUT_MS = 12_000;
    private static final int READ_TIMEOUT_MS = 25_000;
    private static final int MAX_BYTES = 6 * 1024 * 1024;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "SonyPods-ArtworkSync");
        t.setDaemon(true);
        return t;
    });
    private static final Map<String, Boolean> IN_FLIGHT = new ConcurrentHashMap<>();
    private static volatile JSONArray sBundledCatalog;

    private SonyModelImageManager() {
    }

    public static final class SyncResult {
        public final boolean ok;
        public final int count;
        public final String message;

        public SyncResult(boolean ok, int count, String message) {
            this.ok = ok;
            this.count = count;
            this.message = message;
        }
    }

    public static File getArtworkDir(Context ctx) {
        File dir = new File(ctx.getFilesDir(), "artwork");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    public static File getCatalogFile(Context ctx) {
        return new File(getArtworkDir(ctx), "catalog.json");
    }

    public static File getCustomOverrideFile(Context ctx) {
        return new File(getArtworkDir(ctx), "custom_override.png");
    }

    public static boolean hasCustomOverride(Context ctx) {
        File f = getCustomOverrideFile(ctx);
        return f.exists() && f.length() > 0;
    }

    public static int getCatalogCount(Context ctx) {
        JSONArray arr = readCatalogArray(ctx);
        return arr != null ? arr.length() : 0;
    }

    public static File getCachedModelFile(Context ctx, String modelName, int colorCode) {
        String key = buildModelCacheKey(modelName, colorCode);
        File exact = new File(getArtworkDir(ctx), key + ".png");
        if (exact.exists() && exact.length() > 0) {
            return exact;
        }
        String basePrefix = buildModelCacheKey(modelName, -1);
        File generic = new File(getArtworkDir(ctx), basePrefix + ".png");
        if (generic.exists() && generic.length() > 0) {
            return generic;
        }
        File[] files = getArtworkDir(ctx).listFiles();
        if (files != null) {
            String variantPrefix = basePrefix + "_c";
            for (File f : files) {
                if (f.getName().startsWith(variantPrefix) && f.getName().endsWith(".png") && f.length() > 0) {
                    return f;
                }
            }
        }
        return exact;
    }

    public static SyncResult syncCatalogBlocking(Context ctx, String activeModelName, int activeColorCode) {
        HttpURLConnection conn = null;
        try {
            byte[] body = new JSONObject().put("query", CATALOG_QUERY)
                    .toString().getBytes(StandardCharsets.UTF_8);
            conn = (HttpURLConnection) new URL(GRAPHQL_URL).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", API_KEY);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body);
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            String raw = readStreamString(is);
            if (code < 200 || code >= 300) {
                return new SyncResult(false, 0, "HTTP " + code);
            }
            JSONObject root = new JSONObject(raw);
            JSONObject data = root.optJSONObject("data");
            JSONObject hpc = data != null ? data.optJSONObject("HPC") : null;
            JSONArray records = hpc != null ? hpc.optJSONArray("getAllCloudModelInfos") : null;
            if (records == null || records.length() == 0) {
                return new SyncResult(false, 0, "Empty catalog");
            }

            JSONArray filtered = new JSONArray();
            for (int i = 0; i < records.length(); i++) {
                JSONObject item = records.optJSONObject(i);
                if (item == null) continue;
                String model = item.optString("model_name", "").trim();
                String url = item.optString("sca_image_image_url", "").trim();
                if (!model.isEmpty() && isAllowedImageUrl(url)) {
                    filtered.put(item);
                }
            }
            if (filtered.length() == 0) {
                return new SyncResult(false, 0, "No valid entries");
            }
            writeStringAtomically(getCatalogFile(ctx), filtered.toString());

            if (activeModelName != null && !activeModelName.trim().isEmpty()) {
                downloadOfficialModelBlocking(ctx, activeModelName, activeColorCode);
            }
            return new SyncResult(true, filtered.length(), "OK");
        } catch (Throwable t) {
            return new SyncResult(false, 0, t.getMessage() != null ? t.getMessage() : "Error");
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static boolean downloadOfficialModelBlocking(Context ctx, String modelName, int colorCode) {
        String url = resolveImageUrl(ctx, modelName, colorCode);
        if (url == null || !isAllowedImageUrl(url)) {
            return false;
        }
        try {
            byte[] bytes = fetchOfficialImageBytes(url);
            if (bytes == null || bytes.length == 0) {
                return false;
            }
            Bitmap check = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (check == null) {
                return false;
            }
            check.recycle();

            File outExact = new File(getArtworkDir(ctx), buildModelCacheKey(modelName, colorCode) + ".png");
            writeBytesAtomically(outExact, bytes);
            return true;
        } catch (Throwable t) {
            XposedCompat.debug("Artwork download failed: " + t.getMessage());
            return false;
        }
    }

    /**
     * Resolves an official Sony CDN artwork URL from the synced or APK-bundled catalog
     * and returns a decoded, scaled Bitmap directly (for cross-process callers).
     */
    public static Bitmap downloadOfficialBitmapDirect(Context ctx, String modelName, int colorCode, int targetSizePx) {
        String url = resolveImageUrl(ctx, modelName, colorCode);
        if (url == null || !isAllowedImageUrl(url)) {
            return null;
        }
        try {
            byte[] bytes = fetchOfficialImageBytes(url);
            if (bytes == null || bytes.length == 0) {
                return null;
            }
            Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (decoded == null) {
                return null;
            }
            return scaleBitmapToTarget(decoded, targetSizePx);
        } catch (Throwable t) {
            XposedCompat.debug("Direct official artwork download failed: " + t.getMessage());
            return null;
        }
    }

    static Bitmap scaleBitmapToTarget(Bitmap decoded, int targetSizePx) {
        if (decoded == null) return null;
        int cap = Math.max(256, Math.min(768, targetSizePx));
        int maxDim = Math.max(decoded.getWidth(), decoded.getHeight());
        if (maxDim <= cap) {
            return decoded;
        }
        float scale = (float) cap / (float) maxDim;
        int w = Math.max(1, Math.round(decoded.getWidth() * scale));
        int h = Math.max(1, Math.round(decoded.getHeight() * scale));
        Bitmap scaled = Bitmap.createScaledBitmap(decoded, w, h, true);
        if (scaled != decoded) {
            decoded.recycle();
        }
        return scaled;
    }

    private static byte[] fetchOfficialImageBytes(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            byte[] bytes = readStreamBytes(conn.getInputStream());
            return bytes.length > 0 ? bytes : null;
        } catch (Throwable t) {
            XposedCompat.debug("HTTP image fetch failed: " + t.getMessage());
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static void ensureImageAsync(Context ctx, String modelName, int colorCode) {
        if (ctx == null || modelName == null || modelName.trim().isEmpty()) {
            return;
        }
        File target = new File(getArtworkDir(ctx), buildModelCacheKey(modelName, colorCode) + ".png");
        if (target.exists() && target.length() > 0) {
            return;
        }
        String flightKey = target.getName();
        if (IN_FLIGHT.putIfAbsent(flightKey, Boolean.TRUE) != null) {
            return;
        }
        final Context appCtx = ctx.getApplicationContext();
        EXECUTOR.execute(() -> {
            try {
                if (getCatalogCount(appCtx) == 0) {
                    syncCatalogBlocking(appCtx, modelName, colorCode);
                } else {
                    downloadOfficialModelBlocking(appCtx, modelName, colorCode);
                }
            } finally {
                IN_FLIGHT.remove(flightKey);
            }
        });
    }

    public static String resolveImageUrl(Context ctx, String modelName, int colorCode) {
        JSONArray arr = readCatalogArray(ctx);
        if (arr == null || arr.length() == 0 || modelName == null || modelName.trim().isEmpty()) {
            return null;
        }
        String targetNorm = compactModel(stripSeriesSuffix(modelName));
        if (targetNorm.isEmpty()) {
            return null;
        }

        // Pass 1: exact compacted model name or model number match
        String url = matchCatalogUrl(arr, targetNorm, colorCode, true);
        if (url != null) {
            return url;
        }

        // Pass 2: fuzzy/substring match after stripping LE_/Sony prefixes (for renamed BT devices)
        String alt = modelName.trim()
                .replaceAll("(?i)^(LE[-_]|SONY\\s+)+", "")
                .trim();
        String altNorm = compactModel(stripSeriesSuffix(alt));
        if (!altNorm.isEmpty()) {
            return matchCatalogUrl(arr, altNorm, colorCode, false);
        }
        return null;
    }

    private static String matchCatalogUrl(JSONArray arr, String targetNorm, int colorCode, boolean exactOnly) {
        String bestFuzzyModel = null;
        int bestFuzzyLen = 0;
        if (!exactOnly) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject item = arr.optJSONObject(i);
                if (item == null) continue;
                String recModel = compactModel(stripSeriesSuffix(item.optString("model_name", "")));
                if (recModel.length() < 4) continue;
                String url = item.optString("sca_image_image_url", "").trim();
                if (!isAllowedImageUrl(url)) continue;
                if ((targetNorm.contains(recModel) || recModel.startsWith(targetNorm))
                        && recModel.length() > bestFuzzyLen) {
                    bestFuzzyLen = recModel.length();
                    bestFuzzyModel = recModel;
                }
            }
            if (bestFuzzyModel == null) {
                return null;
            }
        }

        String exactColorUrl = null;
        String lowNibbleUrl = null;
        String blackOrDefaultUrl = null;
        String firstModelUrl = null;
        int lowNibble = colorCode >= 0 ? (colorCode & 0x0F) : -1;

        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.optJSONObject(i);
            if (item == null) continue;
            String recModel = compactModel(stripSeriesSuffix(item.optString("model_name", "")));
            String recNum = compactModel(item.optString("model_number", ""));
            boolean matches = exactOnly
                    ? (targetNorm.equals(recModel) || targetNorm.equals(recNum))
                    : bestFuzzyModel.equals(recModel);
            if (!matches) {
                continue;
            }
            String url = item.optString("sca_image_image_url", "").trim();
            if (!isAllowedImageUrl(url)) {
                continue;
            }
            if (firstModelUrl == null) {
                firstModelUrl = url;
            }
            Integer recColor = parseColorId(item.optString("model_color_id", ""));
            if (colorCode >= 0 && recColor != null) {
                if (recColor == colorCode) {
                    exactColorUrl = url;
                    break;
                }
                if (recColor == lowNibble && lowNibbleUrl == null) {
                    lowNibbleUrl = url;
                }
            }
            if (recColor != null && (recColor == 0x01 || recColor == 0x00) && blackOrDefaultUrl == null) {
                blackOrDefaultUrl = url;
            }
        }
        if (exactColorUrl != null) return exactColorUrl;
        if (lowNibbleUrl != null) return lowNibbleUrl;
        if (blackOrDefaultUrl != null) return blackOrDefaultUrl;
        return firstModelUrl;
    }

    private static String stripSeriesSuffix(String raw) {
        if (raw == null) return "";
        return raw.trim().replaceAll("(?i)\\s+series$", "").trim();
    }

    public static boolean importCustomImage(Context ctx, Uri sourceUri) {
        if (ctx == null || sourceUri == null) {
            return false;
        }
        try (InputStream is = ctx.getContentResolver().openInputStream(sourceUri)) {
            if (is == null) return false;
            byte[] raw = readStreamBytes(is);
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length);
            if (bmp == null) return false;
            File out = getCustomOverrideFile(ctx);
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 96, fos);
            }
            bmp.recycle();
            return out.exists() && out.length() > 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void clearCustomImage(Context ctx) {
        if (ctx == null) return;
        File f = getCustomOverrideFile(ctx);
        if (f.exists()) {
            f.delete();
        }
    }

    private static JSONArray readCatalogArray(Context ctx) {
        if (ctx != null) {
            File cat = getCatalogFile(ctx);
            if (cat.exists() && cat.length() > 0) {
                try (InputStream is = new java.io.FileInputStream(cat)) {
                    String raw = readStreamString(is);
                    JSONArray parsed = new JSONArray(raw);
                    if (parsed.length() > 0) {
                        return parsed;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return getBundledCatalog();
    }

    private static JSONArray getBundledCatalog() {
        JSONArray cached = sBundledCatalog;
        if (cached != null) {
            return cached;
        }
        synchronized (SonyModelImageManager.class) {
            if (sBundledCatalog != null) {
                return sBundledCatalog;
            }
            try (InputStream is = SonyModelImageManager.class.getResourceAsStream(BUNDLED_CATALOG_RESOURCE)) {
                if (is != null) {
                    String raw = readStreamString(is);
                    JSONArray parsed = new JSONArray(raw);
                    if (parsed.length() > 0) {
                        sBundledCatalog = parsed;
                        return parsed;
                    }
                }
            } catch (Throwable t) {
                XposedCompat.debug("Failed loading bundled Sony catalog: " + t.getMessage());
            }
        }
        return null;
    }

    private static String buildModelCacheKey(String modelName, int colorCode) {
        String norm = SonyDeviceDetector.normalizeModelName(modelName)
                .toLowerCase(Locale.US)
                .replaceAll("[^a-z0-9]+", "_");
        if (norm.isEmpty()) norm = "sony_audio";
        return colorCode >= 0 ? (norm + "_c" + colorCode) : norm;
    }

    private static String compactModel(String raw) {
        if (raw == null) return "";
        String norm = SonyDeviceDetector.normalizeModelName(raw);
        return norm.toLowerCase(Locale.US).replaceAll("[^a-z0-9]", "");
    }

    private static Integer parseColorId(String raw) {
        if (raw == null) return null;
        String v = raw.trim();
        if (v.isEmpty()) return null;
        try {
            if (v.startsWith("0x") || v.startsWith("0X")) {
                return Integer.parseInt(v.substring(2), 16);
            }
            if (v.matches("[0-9A-Fa-f]{2}")) {
                return Integer.parseInt(v, 16);
            }
            return Integer.parseInt(v);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    public static boolean isAllowedImageUrl(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return false;
        }
        try {
            URL url = new URL(raw.trim());
            return "https".equalsIgnoreCase(url.getProtocol())
                    && IMAGE_HOST.equalsIgnoreCase(url.getHost())
                    && url.getPath() != null && !url.getPath().isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String readStreamString(InputStream is) throws Exception {
        byte[] bytes = readStreamBytes(is);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static byte[] readStreamBytes(InputStream is) throws Exception {
        if (is == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int total = 0;
        int n;
        while ((n = is.read(buf)) >= 0) {
            total += n;
            if (total > MAX_BYTES) {
                throw new IllegalStateException("Response exceeded size cap");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    private static void writeStringAtomically(File target, String content) throws Exception {
        writeBytesAtomically(target, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void writeBytesAtomically(File target, byte[] bytes) throws Exception {
        File tmp = new File(target.getParentFile(), target.getName() + ".tmp");
        try (FileOutputStream fos = new FileOutputStream(tmp)) {
            fos.write(bytes);
        }
        if (target.exists()) {
            target.delete();
        }
        if (!tmp.renameTo(target)) {
            try (FileOutputStream fos = new FileOutputStream(target)) {
                fos.write(bytes);
            }
            tmp.delete();
        }
    }
}
