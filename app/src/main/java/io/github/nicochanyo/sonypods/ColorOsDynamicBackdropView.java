package io.github.nicochanyo.sonypods;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.os.ParcelFileDescriptor;
import android.util.AttributeSet;
import android.view.Choreographer;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.io.File;

/**
 * ColorOS 17 official dynamic backdrop view matching {@code AboutDeviceOtaUpdatePreference}
 * ({@code Settings.apk}) and {@code OTA.apk} ({@code FragmentIdle}).
 * <p>
 * Plays the device's built-in ColorOS video asset
 * ({@code /my_product/decouping_wallpaper/common/wallpaper_group/00_ColorOS15/ColorOS1506.mp4})
 * silently with zero audio focus request, backed by a 120Hz Choreographer-driven cosmic
 * aurora sphere shader fallback and initial last-frame placeholder so it is never blank.
 */
public class ColorOsDynamicBackdropView extends FrameLayout implements TextureView.SurfaceTextureListener {
    public static final int MODE_ABOUT_CARD = 0;
    public static final int MODE_FULLSCREEN_OTA = 1;

    private static final String[] VIDEO_CANDIDATES = new String[]{
            "/my_product/decouping_wallpaper/common/wallpaper_group/00_ColorOS15/ColorOS15006_with_mask.mp4",
            "/my_product/decouping_wallpaper/common/wallpaper_group/00_ColorOS15/ColorOS1506.mp4",
            "/my_product/decouping_wallpaper/common/wallpaper_group/wallpaper_group_sub/00_ColorOS15/ColorOS15006_with_mask.mp4",
            "/my_product/decouping_wallpaper/common/wallpaper_group/wallpaper_group_sub/00_ColorOS15/ColorOS1506.mp4",
            "/my_product/decouping_wallpaper/common/wallpaper_group/00_ColorOS16/ColorOS1606.mp4",
            "/my_product/decouping_wallpaper/common/wallpaper_group/wallpaper_group_sub/00_ColorOS16/ColorOS1606.mp4"
    };

    private final int mode;
    private final float density;
    private final AnimatedAuroraDrawable auroraDrawable;
    private ImageView placeholderView;
    private TextureView textureView;

    private MediaPlayer mediaPlayer;
    private Surface surface;
    private boolean surfaceReady;
    private boolean playerPrepared;
    private boolean playingEasterEgg;
    private boolean videoRendering;
    private boolean topVideoIsWithMask;
    private String resolvedVideoPath;
    private int videoWidth;
    private int videoHeight;

    public ColorOsDynamicBackdropView(Context context) {
        this(context, MODE_ABOUT_CARD);
    }

    public ColorOsDynamicBackdropView(Context context, int mode) {
        super(context);
        this.mode = mode;
        this.density = context.getResources().getDisplayMetrics().density;
        this.auroraDrawable = new AnimatedAuroraDrawable(density);
        initViews(context);
    }

    public ColorOsDynamicBackdropView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ColorOsDynamicBackdropView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        this.mode = MODE_ABOUT_CARD;
        this.density = context.getResources().getDisplayMetrics().density;
        this.auroraDrawable = new AnimatedAuroraDrawable(density);
        initViews(context);
    }

    private void initViews(Context context) {
        setBackground(auroraDrawable);
        resolveVideoSource();

        if (mode == MODE_ABOUT_CARD && resolvedVideoPath != null) {
            Drawable lastFrame = loadSystemSettingsLastFrame(context);
            if (lastFrame != null) {
                placeholderView = new ImageView(context);
                placeholderView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                placeholderView.setImageDrawable(lastFrame);
                addView(placeholderView, new LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }

        if (resolvedVideoPath != null) {
            textureView = new TextureView(context);
            textureView.setOpaque(false);
            textureView.setSurfaceTextureListener(this);
            addView(textureView, new LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private void resolveVideoSource() {
        for (String path : VIDEO_CANDIDATES) {
            try {
                File f = new File(path);
                if (f.exists() && f.isFile() && f.canRead()) {
                    resolvedVideoPath = path;
                    topVideoIsWithMask = path.contains("_with_mask");
                    return;
                }
            } catch (Throwable ignored) {
            }
        }
        resolvedVideoPath = null;
    }

    private static Drawable loadSystemSettingsLastFrame(Context context) {
        try {
            Context settingsCtx = context.createPackageContext("com.android.settings", Context.CONTEXT_IGNORE_SECURITY);
            Resources res = settingsCtx.getResources();
            String[] candidates = {
                    "about_device_top_video_last_frame_17",
                    "about_device_top_video_last_frame_16",
                    "about_device_top_video_last_frame_15",
                    "about_device_top_video_last_frame"
            };
            for (String name : candidates) {
                int id = res.getIdentifier(name, "drawable", "com.android.settings");
                if (id != 0) {
                    return settingsCtx.getDrawable(id);
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public void replayIfIdle() {
        if (mediaPlayer != null && playerPrepared) {
            try {
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.seekTo(0);
                    mediaPlayer.start();
                }
            } catch (Throwable ignored) {
            }
        } else if (surfaceReady && textureView != null && mediaPlayer == null && resolvedVideoPath != null) {
            startMediaPlayer(textureView.getSurfaceTexture(), false);
        }
        auroraDrawable.pulse();
    }

    public void playEasterEgg() {
        if (!surfaceReady || textureView == null) {
            auroraDrawable.pulse();
            return;
        }
        if (resolvedVideoPath != null && tryPlaySettingsEasterEgg(textureView.getSurfaceTexture())) {
            return;
        }
        if (mediaPlayer != null && playerPrepared) {
            try {
                mediaPlayer.seekTo(0);
                mediaPlayer.start();
            } catch (Throwable ignored) {
            }
        } else {
            auroraDrawable.pulse();
        }
    }

    public void pausePlayback() {
        auroraDrawable.setActive(false);
        if (mediaPlayer != null) {
            try {
                if (mediaPlayer.isPlaying()) {
                    mediaPlayer.pause();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public void resumePlayback() {
        if (!videoRendering) {
            auroraDrawable.setActive(isAttachedToWindow());
        }
        if (mediaPlayer != null && playerPrepared && mode == MODE_FULLSCREEN_OTA) {
            try {
                if (!mediaPlayer.isPlaying()) {
                    mediaPlayer.start();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private boolean tryPlaySettingsEasterEgg(SurfaceTexture st) {
        if (st == null || playingEasterEgg) return false;
        try {
            Context settingsCtx = getContext().createPackageContext(
                    "com.android.settings", Context.CONTEXT_IGNORE_SECURITY);
            int rawId = settingsCtx.getResources().getIdentifier(
                    "about_device_easter_egg_video", "raw", "com.android.settings");
            if (rawId == 0) return false;
            AssetFileDescriptor afd = settingsCtx.getResources().openRawResourceFd(rawId);
            if (afd == null) return false;

            releaseMediaPlayer();
            playingEasterEgg = true;
            surface = new Surface(st);
            MediaPlayer mp = new MediaPlayer();
            configureSilentAudio(mp);
            mp.setSurface(surface);
            mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            mp.setLooping(false);
            mp.setOnVideoSizeChangedListener((p, vw, vh) -> {
                videoWidth = vw;
                videoHeight = vh;
                applyDisplayTransform();
            });
            mp.setOnPreparedListener(p -> {
                playerPrepared = true;
                videoWidth = p.getVideoWidth();
                videoHeight = p.getVideoHeight();
                applyDisplayTransform();
                p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    onFirstFrameRendered();
                    return true;
                }
                return false;
            });
            mp.setOnCompletionListener(p -> {
                playingEasterEgg = false;
                if (surfaceReady && textureView != null && resolvedVideoPath != null) {
                    startMediaPlayer(textureView.getSurfaceTexture(), true);
                }
            });
            mp.setOnErrorListener((p, w, e) -> {
                playingEasterEgg = false;
                fallbackToAurora();
                return true;
            });
            mediaPlayer = mp;
            mp.prepareAsync();
            return true;
        } catch (Throwable ignored) {
            playingEasterEgg = false;
            return false;
        }
    }

    private void startMediaPlayer(SurfaceTexture st, boolean seekToEnd) {
        if (st == null || resolvedVideoPath == null) {
            fallbackToAurora();
            return;
        }
        releaseMediaPlayer();
        playerPrepared = false;
        playingEasterEgg = false;

        try {
            surface = new Surface(st);
            MediaPlayer mp = new MediaPlayer();
            configureSilentAudio(mp);
            mp.setSurface(surface);

            File f = new File(resolvedVideoPath);
            try (ParcelFileDescriptor pfd = ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)) {
                mp.setDataSource(pfd.getFileDescriptor());
            } catch (Throwable openErr) {
                mp.setDataSource(resolvedVideoPath);
            }

            mp.setLooping(mode == MODE_FULLSCREEN_OTA);
            mp.setOnVideoSizeChangedListener((p, vw, vh) -> {
                videoWidth = vw;
                videoHeight = vh;
                applyDisplayTransform();
            });
            mp.setOnPreparedListener(p -> {
                playerPrepared = true;
                videoWidth = p.getVideoWidth();
                videoHeight = p.getVideoHeight();
                applyDisplayTransform();
                if (seekToEnd) {
                    int dur = Math.max(0, p.getDuration() - 60);
                    p.seekTo(dur);
                }
                p.start();
            });
            mp.setOnInfoListener((p, what, extra) -> {
                if (what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START) {
                    onFirstFrameRendered();
                    return true;
                }
                return false;
            });
            mp.setOnCompletionListener(p -> {
                // Keep final rendered frame crisp on TextureView in MODE_ABOUT_CARD (matches Settings)
                if (mode == MODE_FULLSCREEN_OTA) {
                    try {
                        p.seekTo(0);
                        p.start();
                    } catch (Throwable ignored) {
                    }
                }
            });
            mp.setOnErrorListener((p, what, extra) -> {
                fallbackToAurora();
                return true;
            });
            mediaPlayer = mp;
            mp.prepareAsync();
        } catch (Throwable err) {
            fallbackToAurora();
        }
    }

    private static void configureSilentAudio(MediaPlayer mp) {
        try {
            mp.setVolume(0f, 0f);
            mp.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build());
        } catch (Throwable ignored) {
        }
    }

    private void onFirstFrameRendered() {
        videoRendering = true;
        auroraDrawable.setActive(false);
        if (textureView != null) {
            textureView.setAlpha(1f);
            textureView.setVisibility(View.VISIBLE);
        }
        if (placeholderView != null && placeholderView.getVisibility() == View.VISIBLE) {
            placeholderView.animate().alpha(0f).setDuration(180L).withEndAction(() -> {
                if (placeholderView != null && videoRendering) {
                    placeholderView.setVisibility(View.GONE);
                }
            }).start();
        }
    }

    private void fallbackToAurora() {
        videoRendering = false;
        releaseMediaPlayer();
        if (textureView != null) {
            textureView.setVisibility(View.GONE);
        }
        if (placeholderView != null) {
            placeholderView.setVisibility(View.GONE);
        }
        auroraDrawable.setActive(isAttachedToWindow());
    }

    private void applyDisplayTransform() {
        if (textureView == null) return;
        int viewW = textureView.getWidth();
        int viewH = textureView.getHeight();
        if (viewW <= 0 || viewH <= 0 || videoWidth <= 0 || videoHeight <= 0) return;

        Matrix m = new Matrix();
        float cx = viewW * 0.5f;
        float cy = viewH * 0.5f;

        if (mode == MODE_ABOUT_CARD && !playingEasterEgg && videoHeight > videoWidth * 1.35f) {
            // Exact AboutDeviceOtaUpdatePreference portrait wallpaper crop transform
            float targetW = (topVideoIsWithMask ? 901.3f : 450.65f) * density;
            float targetH = 1001.44f * density;
            float tx = (topVideoIsWithMask ? 161.79f : -19.7f) * density;
            float ty = (topVideoIsWithMask ? -58.33f : 75.83f) * density;
            m.postScale(targetW / viewW, targetH / viewH, cx, cy);
            m.postScale(-1.0f, 1.0f, cx, cy);
            m.postRotate(143.5f, cx, cy);
            m.postTranslate(tx, ty);
        } else {
            // Upright center-crop keeping circular geometry distortion-free
            float sx = viewW / (float) videoWidth;
            float sy = viewH / (float) videoHeight;
            float scale = Math.max(sx, sy);
            float scaleX = (videoWidth * scale) / viewW;
            float scaleY = (videoHeight * scale) / viewH;
            m.setScale(scaleX, scaleY, cx, cy);
        }
        textureView.setTransform(m);
    }

    private void releaseMediaPlayer() {
        playerPrepared = false;
        if (mediaPlayer != null) {
            try {
                mediaPlayer.reset();
            } catch (Throwable ignored) {
            }
            try {
                mediaPlayer.release();
            } catch (Throwable ignored) {
            }
            mediaPlayer = null;
        }
        if (surface != null) {
            try {
                surface.release();
            } catch (Throwable ignored) {
            }
            surface = null;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!videoRendering) {
            auroraDrawable.setActive(true);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        auroraDrawable.setActive(false);
        videoRendering = false;
        surfaceReady = false;
        releaseMediaPlayer();
        super.onDetachedFromWindow();
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture st, int width, int height) {
        surfaceReady = true;
        startMediaPlayer(st, false);
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture st, int width, int height) {
        applyDisplayTransform();
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture st) {
        surfaceReady = false;
        videoRendering = false;
        releaseMediaPlayer();
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture st) {
    }

    /**
     * 120Hz Choreographer-driven ColorOS 17 cosmic glass-sphere aurora backdrop used both as
     * a smooth zero-flash underlay before video renders and as the fallback on non-ColorOS devices.
     */
    private static final class AnimatedAuroraDrawable extends Drawable {
        private final float d;
        private final Paint skyPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rightSpherePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rightGlowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint rightRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint diagPlanePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint leftSpherePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint leftRimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path rightSpherePath = new Path();
        private final Path diagPlanePath = new Path();
        private final Path leftSpherePath = new Path();
        private final RectF ovalRect = new RectF();

        private boolean active;
        private long startNs;
        private float phase;
        private int lastW;
        private int lastH;

        private final Choreographer.FrameCallback ticker = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (!active) return;
                if (startNs == 0L) startNs = frameTimeNanos;
                phase = (frameTimeNanos - startNs) / 1_000_000_000f;
                invalidateSelf();
                Choreographer.getInstance().postFrameCallback(this);
            }
        };

        AnimatedAuroraDrawable(float d) {
            this.d = d;
            rightGlowPaint.setStyle(Paint.Style.FILL);
            rightRimPaint.setStyle(Paint.Style.STROKE);
            rightRimPaint.setStrokeWidth(2.2f * d);
            leftRimPaint.setStyle(Paint.Style.STROKE);
            leftRimPaint.setStrokeWidth(2.0f * d);
        }

        void setActive(boolean next) {
            if (this.active == next) return;
            this.active = next;
            if (next) {
                Choreographer.getInstance().postFrameCallback(ticker);
            } else {
                Choreographer.getInstance().removeFrameCallback(ticker);
            }
        }

        void pulse() {
            startNs = 0L;
            phase = 0f;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect b) {
            super.onBoundsChange(b);
            lastW = -1;
            lastH = -1;
        }

        private void rebuildShaders(int w, int h) {
            lastW = w;
            lastH = h;

            skyPaint.setShader(new LinearGradient(
                    0f, 0f, w * 0.25f, h,
                    new int[]{0xFF0C0B2D, 0xFF151340, 0xFF28205F, 0xFF392B76, 0xFF221A4C},
                    new float[]{0f, 0.28f, 0.56f, 0.80f, 1f},
                    Shader.TileMode.CLAMP));

            float rx = w * 0.78f;
            float ry = h * 0.68f;
            float rRad = Math.max(w, h) * 0.48f;
            rightSpherePaint.setShader(new RadialGradient(
                    rx - rRad * 0.08f, ry + rRad * 0.14f, rRad * 1.15f,
                    new int[]{0xFFFFB240, 0xFFFF6830, 0xFFE86EA5, 0xFF8C63D6, 0xFF5140A3},
                    new float[]{0f, 0.34f, 0.62f, 0.84f, 1f},
                    Shader.TileMode.CLAMP));
            rightGlowPaint.setShader(new RadialGradient(
                    rx - rRad * 0.12f, ry + rRad * 0.22f, rRad * 0.85f,
                    new int[]{0x66FFC44D, 0x26FF682B, 0x00FF682B},
                    new float[]{0f, 0.52f, 1f},
                    Shader.TileMode.CLAMP));
            rightRimPaint.setShader(new LinearGradient(
                    rx - rRad, ry - rRad * 0.8f, rx + rRad * 0.3f, ry + rRad * 0.4f,
                    new int[]{0x99F3D8FF, 0x45FFD2B8, 0x10FFFFFF},
                    new float[]{0f, 0.55f, 1f},
                    Shader.TileMode.CLAMP));

            diagPlanePaint.setShader(new LinearGradient(
                    0f, h * 0.38f, w * 0.72f, h * 0.76f,
                    new int[]{0xD8FF784E, 0xDCE292BA, 0xC85F48B5, 0x99362B7A},
                    new float[]{0f, 0.34f, 0.70f, 1f},
                    Shader.TileMode.CLAMP));

            float lx = w * 0.20f;
            float ly = h * 0.80f;
            float lRad = Math.max(w, h) * 0.44f;
            leftSpherePaint.setShader(new RadialGradient(
                    lx - lRad * 0.06f, ly + lRad * 0.10f, lRad * 1.05f,
                    new int[]{0xFFFFC14B, 0xFFFF662E, 0xFFD9649D, 0xFF5C42A3},
                    new float[]{0f, 0.38f, 0.72f, 1f},
                    Shader.TileMode.CLAMP));
            leftRimPaint.setShader(new LinearGradient(
                    lx - lRad * 0.7f, ly - lRad * 0.85f, lx + lRad * 0.6f, ly + lRad * 0.3f,
                    new int[]{0xAAFFE88E, 0x55FF945E, 0x10FFFFFF},
                    new float[]{0f, 0.50f, 1f},
                    Shader.TileMode.CLAMP));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            int w = b.width();
            int h = b.height();
            if (w <= 0 || h <= 0) return;
            if (w != lastW || h != lastH) {
                rebuildShaders(w, h);
            }

            float dx1 = (float) Math.sin(phase * 0.55f) * 14f * d;
            float dy1 = (float) Math.cos(phase * 0.42f) * 12f * d;
            float dx2 = (float) Math.cos(phase * 0.48f) * 12f * d;
            float dy2 = (float) Math.sin(phase * 0.60f) * 10f * d;
            float breath = 1.0f + (float) Math.sin(phase * 0.75f) * 0.025f;

            canvas.drawRect(b, skyPaint);

            diagPlanePath.reset();
            diagPlanePath.moveTo(0f, h * 0.48f + dy2 * 0.4f);
            diagPlanePath.lineTo(w * 1.1f, h * 0.78f + dy1 * 0.5f);
            diagPlanePath.lineTo(w * 1.1f, h * 1.1f);
            diagPlanePath.lineTo(0f, h * 1.1f);
            diagPlanePath.close();
            canvas.save();
            canvas.clipPath(diagPlanePath);
            canvas.drawRect(b, diagPlanePaint);
            canvas.restore();

            float rx = w * 0.78f + dx1;
            float ry = h * 0.68f + dy1;
            float rRad = Math.max(w, h) * 0.46f * breath;
            rightSpherePath.reset();
            ovalRect.set(rx - rRad, ry - rRad, rx + rRad, ry + rRad);
            rightSpherePath.addOval(ovalRect, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(rightSpherePath);
            canvas.drawCircle(rx, ry, rRad, rightSpherePaint);
            canvas.drawCircle(rx - rRad * 0.1f, ry + rRad * 0.15f, rRad * 0.9f, rightGlowPaint);
            canvas.restore();
            canvas.drawOval(ovalRect, rightRimPaint);

            float lx = w * 0.20f + dx2;
            float ly = h * 0.80f + dy2;
            float lRad = Math.max(w, h) * 0.42f * (2f - breath);
            leftSpherePath.reset();
            ovalRect.set(lx - lRad, ly - lRad, lx + lRad, ly + lRad);
            leftSpherePath.addOval(ovalRect, Path.Direction.CW);
            canvas.save();
            canvas.clipPath(leftSpherePath);
            canvas.drawCircle(lx, ly, lRad, leftSpherePaint);
            canvas.restore();
            canvas.drawOval(ovalRect, leftRimPaint);
        }

        @Override
        public void setAlpha(int alpha) {
            skyPaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            skyPaint.setColorFilter(colorFilter);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.OPAQUE;
        }
    }
}
