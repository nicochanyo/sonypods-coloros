package io.github.nicochanyo.sonypods;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;

/**
 * ColorOS 17 Living Flow visual, physics, and tactile feedback primitives:
 * <ul>
 *   <li>{@link SpotLightDrawable}: official ColorOS 17 AGSL RuntimeShader point-light halo with radial fallback.</li>
 *   <li>{@link FluidSpring}: 120Hz Choreographer-driven damped harmonic spring with velocity handoff.</li>
 *   <li>{@link TouchFeedback}: COUITouchMotionAnimator-inspired deformable scale/drag + synchronized SpotLight & haptics.</li>
 * </ul>
 */
public final class LivingFlowEffects {
    private static final int TAG_TOUCH_POINT = 0x53504354;
    private static final int TAG_TOUCH_FEEDBACK = 0x53504C54;

    public enum EdgeStyle {
        NONE,
        CAPSULE,
        CIRCLE,
        CARD
    }

    private LivingFlowEffects() {
    }

    public static SpotLightDrawable attachEdgeGlow(View view, boolean dark, float cornerRadiusDp,
                                                   boolean isCircle, boolean isCapsule, EdgeStyle edgeStyle) {
        if (view == null) return null;
        float d = view.getResources().getDisplayMetrics().density;
        SpotLightDrawable spot = new SpotLightDrawable(
                dark, d, cornerRadiusDp, isCircle, isCapsule, 0.22f, 80f, edgeStyle);
        view.setForeground(spot);
        return spot;
    }

    // -------------------------------------------------------------------------
    // 1. Official ColorOS 17 AGSL RuntimeShader Edge Micro-Glow & SpotLight
    // -------------------------------------------------------------------------
    public static final class SpotLightDrawable extends Drawable {
        // ColorOS 17 Clock.apk ShadowEdge AGSL perimeter catchlight + subtle bevel
        private static final String AGSL_EDGE_GLOW =
                "uniform float4 u_sizeRadius;\n"
                        + "uniform float4 u_nearFar;\n"
                        + "uniform float4 u_fade;\n"
                        + "uniform float4 u_alphaPower;\n"
                        + "uniform float4 u_angleBoost;\n"
                        + "float rBoxSdf(float2 p, float2 b, float r) {\n"
                        + "    float rr = min(r, min(b.x, b.y));\n"
                        + "    float2 q = abs(p) - b + float2(rr);\n"
                        + "    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rr;\n"
                        + "}\n"
                        + "half4 main(float2 fragCoord) {\n"
                        + "    float w = u_sizeRadius.x;\n"
                        + "    float h = u_sizeRadius.y;\n"
                        + "    float2 halfSize = float2(w, h) * 0.5;\n"
                        + "    if (halfSize.x <= 1.0 || halfSize.y <= 1.0) { return half4(0.0); }\n"
                        + "    float2 pos = float2(fragCoord.x, h - fragCoord.y) - halfSize;\n"
                        + "    float d = rBoxSdf(pos, halfSize, u_sizeRadius.z);\n"
                        + "    float shape = 1.0 - smoothstep(-1.0, 1.0, d);\n"
                        + "    if (shape < 0.001) { return half4(0.0); }\n"
                        + "    float nMax = max(0.5, u_nearFar.y);\n"
                        + "    float fMax = max(0.5, u_nearFar.w);\n"
                        + "    float maxReach = max(nMax, fMax) + 1.5;\n"
                        + "    if (d < -maxReach) { return half4(0.0); }\n"
                        + "    float nMin = min(nMax - 0.1, u_nearFar.x);\n"
                        + "    float fMin = min(fMax - 0.1, u_nearFar.z);\n"
                        + "    float distanceAll = (halfSize.x + halfSize.y) * 2.0;\n"
                        + "    float ratio = mod(u_angleBoost.x + halfSize.x / distanceAll * 0.5 + 0.5, 1.0);\n"
                        + "    float2 safePos = (abs(pos.x) + abs(pos.y) < 0.001) ? float2(1.0, 0.0) : pos;\n"
                        + "    float2 dir_pos = normalize(safePos);\n"
                        + "    float2 dirAbs_pos = abs(dir_pos);\n"
                        + "    float2 pos_n = min(dirAbs_pos * halfSize.yx / max(float2(0.001), dirAbs_pos.yx), halfSize) * sign(dir_pos);\n"
                        + "    float2 halfSize_sym = float2(-halfSize.y, halfSize.x);\n"
                        + "    float flag = mix(-1.0, 1.0, step(ratio, 0.5)) * dot(halfSize_sym, pos_n);\n"
                        + "    float dis_point = distanceAll * min(ratio, 1.0 - ratio) * 2.0;\n"
                        + "    float dis_pos = abs(pos_n.x - halfSize.x) + abs(pos_n.y - halfSize.y);\n"
                        + "    float distance_near = mix(dis_point + dis_pos, abs(dis_point - dis_pos), step(0.0, flag));\n"
                        + "    distance_near = min(2.0 * distanceAll - distance_near, distance_near);\n"
                        + "    float nLo = u_fade.x * distanceAll;\n"
                        + "    float nHi = max(nLo + 1.0, u_fade.y * distanceAll);\n"
                        + "    float fLo = u_fade.z * distanceAll;\n"
                        + "    float fHi = max(fLo + 1.0, u_fade.w * distanceAll);\n"
                        + "    float lineNearAlpha = u_alphaPower.x * (1.0 - smoothstep(nLo, nHi, distance_near));\n"
                        + "    float lineFarAlpha = u_alphaPower.y * (1.0 - smoothstep(fLo, fHi, distanceAll - distance_near));\n"
                        + "    float nBand = smoothstep(-nMax, -nMin, d);\n"
                        + "    float fBand = smoothstep(-fMax, -fMin, d);\n"
                        + "    float lineNearSdf = pow(nBand, 2.8) * 0.78 + 0.22 * nBand;\n"
                        + "    float lineFarSdf = pow(fBand, 2.8) * 0.78 + 0.22 * fBand;\n"
                        + "    float continuousRim = u_alphaPower.z * lineFarSdf;\n"
                        + "    float whiteAlpha = clamp((lineNearAlpha * lineNearSdf + lineFarAlpha * lineFarSdf + continuousRim) * u_angleBoost.y, 0.0, 1.0);\n"
                        + "    float rimOut = whiteAlpha * shape;\n"
                        + "    float bevelOut = 0.0;\n"
                        + "    if (u_angleBoost.z > 0.001) {\n"
                        + "        float outerBand = smoothstep(-0.85 * u_sizeRadius.w, 0.0, d);\n"
                        + "        bevelOut = u_angleBoost.z * outerBand * (1.0 - rimOut) * shape;\n"
                        + "    }\n"
                        + "    return half4(rimOut, rimOut, rimOut, clamp(rimOut + bevelOut, 0.0, 1.0));\n"
                        + "}\n";

        // ColorOS 17 additive luminous SpotLight AGSL kernel (bright white glow only, never darkens)
        private static final String AGSL_SPOTLIGHT =
                "uniform vec4 u_lightPosAndHeight;\n"
                        + "uniform vec4 u_lightColorAndAmbient;\n"
                        + "uniform vec4 u_lightingParams;\n"
                        + "uniform vec2 resolution;\n"
                        + "float dither(vec2 coord, float ditherN) {\n"
                        + "    float n1 = fract(sin(dot(coord, vec2(12.9898, 78.233))) * 43758.5453);\n"
                        + "    float n2 = fract(sin(dot(coord + vec2(1.0), vec2(12.9898, 78.233))) * 43758.5453);\n"
                        + "    return (n1 + n2 - 1.0) * ditherN / 255.0;\n"
                        + "}\n"
                        + "half4 main(float2 fragCoord) {\n"
                        + "    float2 lightP = u_lightPosAndHeight.xy;\n"
                        + "    float radius = max(1.0, u_lightPosAndHeight.z);\n"
                        + "    float intensity = u_lightPosAndHeight.w;\n"
                        + "    if (intensity <= 0.002) { return half4(0.0); }\n"
                        + "    vec2 delta = fragCoord - lightP;\n"
                        + "    float dist2d = length(delta);\n"
                        + "    if (dist2d >= radius) { return half4(0.0); }\n"
                        + "    vec3 lightCol = u_lightColorAndAmbient.rgb;\n"
                        + "    float ambientStr = u_lightColorAndAmbient.w;\n"
                        + "    float diffuseStr = u_lightingParams.x;\n"
                        + "    float lightH = u_lightingParams.y;\n"
                        + "    float ditherN = u_lightingParams.w;\n"
                        + "    vec3 L = normalize(vec3(delta, lightH));\n"
                        + "    float diff = max(L.z, 0.0);\n"
                        + "    float att = smoothstep(1.0, 0.0, dist2d / radius);\n"
                        + "    float glow = clamp((ambientStr + diffuseStr * diff) * att * att * 1.45 * intensity, 0.0, 1.0);\n"
                        + "    glow = clamp(glow + dither(fragCoord, ditherN), 0.0, 1.0);\n"
                        + "    return half4(lightCol * glow, glow);\n"
                        + "}\n";

        private final Paint edgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clipPath = new Path();
        private final RectF clipRect = new RectF();
        private final boolean dark;
        private final float density;
        private final float cornerRadiusPx;
        private final boolean isCircle;
        private final boolean isCapsule;
        private final EdgeStyle edgeStyle;
        private final float maxIntensity;
        private final float maxRadiusPx;
        private final float lightHeightPx;
        private final float ambientStr;
        private final float diffuseStr;

        private float lightR = 1f;
        private float lightG = 1f;
        private float lightB = 1f;
        private float lightX;
        private float lightY;
        private float progress;
        private boolean shaderFailed;
        private boolean edgeShaderFailed;
        private RuntimeShader runtimeShader;
        private RuntimeShader edgeShader;

        private final FluidSpring glowSpring = new FluidSpring(460f, 0.84f).setThresholds(0.002f, 0.02f);

        public SpotLightDrawable(boolean dark, float density, float cornerRadiusDp,
                                 boolean isCircle, boolean isCapsule, float maxIntensity, float maxRadiusDp) {
            this(dark, density, cornerRadiusDp, isCircle, isCapsule, maxIntensity, maxRadiusDp,
                    ColorOsLivingFlowEngine.isHardwareMaterialSupported()
                            ? EdgeStyle.NONE
                            : (isCircle ? EdgeStyle.CIRCLE : (isCapsule ? EdgeStyle.CAPSULE : EdgeStyle.CARD)));
        }

        public SpotLightDrawable(boolean dark, float density, float cornerRadiusDp,
                                 boolean isCircle, boolean isCapsule, float maxIntensity, float maxRadiusDp,
                                 EdgeStyle edgeStyle) {
            this.dark = dark;
            this.density = density;
            this.cornerRadiusPx = cornerRadiusDp * density;
            this.isCircle = isCircle;
            this.isCapsule = isCapsule;
            this.edgeStyle = edgeStyle != null ? edgeStyle : EdgeStyle.NONE;
            this.maxIntensity = maxIntensity;
            this.maxRadiusPx = Math.max(80f, maxRadiusDp * density);
            this.lightHeightPx = 90f * density;
            this.ambientStr = 0.25f;
            this.diffuseStr = 0.65f;

            if (Build.VERSION.SDK_INT >= 29) {
                paint.setBlendMode(android.graphics.BlendMode.PLUS);
            } else {
                paint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD));
            }
            setLightColor(0xFFFFFFFF);
            glowSpring.setListener(v -> {
                progress = Math.max(0f, Math.min(1f, v));
                invalidateSelf();
            }, null);
        }

        public void setLightColor(int color) {
            this.lightR = Color.red(color) / 255f;
            this.lightG = Color.green(color) / 255f;
            this.lightB = Color.blue(color) / 255f;
        }

        public void onPress(float x, float y) {
            this.lightX = x;
            this.lightY = y;
            glowSpring.animateTo(1f);
            invalidateSelf();
        }

        public void onMove(float x, float y) {
            this.lightX = x;
            this.lightY = y;
            if (progress > 0.01f) {
                invalidateSelf();
            }
        }

        public void onRelease() {
            glowSpring.animateTo(0f);
        }

        public void cancel() {
            glowSpring.cancel();
            progress = 0f;
            invalidateSelf();
        }

        @Override
        protected void onBoundsChange(Rect bounds) {
            super.onBoundsChange(bounds);
            clipPath.reset();
            if (!bounds.isEmpty()) {
                clipRect.set(bounds);
                if (isCircle) {
                    float size = Math.min(clipRect.width(), clipRect.height());
                    float cx = clipRect.centerX();
                    float cy = clipRect.centerY();
                    clipPath.addCircle(cx, cy, size * 0.5f, Path.Direction.CW);
                } else {
                    float r = isCapsule ? (clipRect.height() * 0.5f) : cornerRadiusPx;
                    clipPath.addRoundRect(clipRect, r, r, Path.Direction.CW);
                }
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float w = b.width();
            float h = b.height();

            if (edgeStyle != EdgeStyle.NONE
                    && !ColorOsLivingFlowEngine.isHardwareMaterialSupported()) {
                drawEdgeMicroGlow(canvas, b, w, h);
            }

            if (progress <= 0.003f) return;

            float curRadius = maxRadiusPx * (0.45f + 0.55f * progress);
            float curIntensity = maxIntensity * progress;

            int save = canvas.save();
            if (!clipPath.isEmpty()) {
                canvas.clipPath(clipPath);
            }

            if (Build.VERSION.SDK_INT >= 33 && !shaderFailed) {
                try {
                    if (runtimeShader == null) {
                        runtimeShader = new RuntimeShader(AGSL_SPOTLIGHT);
                    }
                    runtimeShader.setFloatUniform("u_lightPosAndHeight", lightX, lightY, curRadius, curIntensity);
                    runtimeShader.setFloatUniform("u_lightColorAndAmbient", lightR, lightG, lightB, ambientStr);
                    runtimeShader.setFloatUniform("u_lightingParams", diffuseStr, lightHeightPx, 0.10f, 1.0f);
                    runtimeShader.setFloatUniform("resolution", w, h);
                    paint.setShader(runtimeShader);
                    canvas.drawRect(b, paint);
                    canvas.restoreToCount(save);
                    return;
                } catch (Throwable t) {
                    shaderFailed = true;
                }
            }

            // Fallback soft radial highlight for API 26-32
            int centerCol = Color.argb(Math.round(Math.min(1f, curIntensity * 0.65f) * 255f),
                    Math.round(lightR * 255f), Math.round(lightG * 255f), Math.round(lightB * 255f));
            paint.setShader(new RadialGradient(lightX, lightY, Math.max(1f, curRadius),
                    new int[]{centerCol, (centerCol & 0x00FFFFFF)},
                    new float[]{0f, 1f}, Shader.TileMode.CLAMP));
            canvas.drawRect(b, paint);
            canvas.restoreToCount(save);
        }

        private void drawEdgeMicroGlow(Canvas canvas, Rect b, float w, float h) {
            float effectiveR;
            if (isCircle) {
                effectiveR = Math.min(w, h) * 0.5f;
            } else if (isCapsule) {
                effectiveR = h * 0.5f;
            } else {
                effectiveR = cornerRadiusPx;
            }

            if (Build.VERSION.SDK_INT >= 33 && !edgeShaderFailed) {
                try {
                    if (edgeShader == null) {
                        edgeShader = new RuntimeShader(AGSL_EDGE_GLOW);
                    }
                    float nMin, nMax, fMin, fMax;
                    float f0, f1, f2, f3;
                    float nAlpha, fAlpha, baseRim, bevelShadow;
                    if (edgeStyle == EdgeStyle.CIRCLE) {
                        nMin = 0.55f * density;
                        nMax = 2.20f * density;
                        fMin = 0.50f * density;
                        fMax = 1.30f * density;
                        f0 = 0.06f;
                        f1 = 0.40f;
                        f2 = 0.01f;
                        f3 = 0.28f;
                        nAlpha = dark ? 0.50f : 0.85f;
                        fAlpha = dark ? 0.62f : 1.00f;
                        baseRim = dark ? 0.16f : 0.46f;
                        bevelShadow = dark ? 0f : 0.055f;
                    } else if (edgeStyle == EdgeStyle.CARD) {
                        nMin = 0.60f * density;
                        nMax = 2.40f * density;
                        fMin = 0.50f * density;
                        fMax = 1.15f * density;
                        f0 = 0.18f;
                        f1 = 0.35f;
                        f2 = 0.25f;
                        f3 = 0.25f;
                        nAlpha = dark ? 0.44f : 0.80f;
                        fAlpha = dark ? 0.38f : 0.72f;
                        baseRim = dark ? 0.14f : 0.40f;
                        bevelShadow = dark ? 0f : 0.045f;
                    } else { // CAPSULE
                        nMin = 0.55f * density;
                        nMax = 2.20f * density;
                        fMin = 0.50f * density;
                        fMax = 1.30f * density;
                        f0 = 0.20f;
                        f1 = 0.40f;
                        f2 = 0.15f;
                        f3 = 0.35f;
                        nAlpha = dark ? 0.48f : 0.82f;
                        fAlpha = dark ? 0.58f : 1.00f;
                        baseRim = dark ? 0.14f : 0.42f;
                        bevelShadow = dark ? 0f : 0.055f;
                    }
                    float pressBoost = 1f + 0.18f * progress;
                    edgeShader.setFloatUniform("u_sizeRadius", w, h, effectiveR, density);
                    edgeShader.setFloatUniform("u_nearFar", nMin, nMax, fMin, fMax);
                    edgeShader.setFloatUniform("u_fade", f0, f1, f2, f3);
                    edgeShader.setFloatUniform("u_alphaPower", nAlpha, fAlpha, baseRim, dark ? 1f : 0f);
                    edgeShader.setFloatUniform("u_angleBoost", 0f, pressBoost, bevelShadow, 0f);
                    edgePaint.setStyle(Paint.Style.FILL);
                    edgePaint.setShader(edgeShader);
                    canvas.drawRect(b, edgePaint);
                    return;
                } catch (Throwable t) {
                    edgeShaderFailed = true;
                }
            }

            // Diagonal gradient stroke fallback for API 26-32
            int cTopLeft = dark ? 0x80FFFFFF : 0xE6FFFFFF;
            int cMid1 = dark ? 0x20FFFFFF : 0x26FFFFFF;
            int cMid = dark ? 0x10FFFFFF : 0x0CFFFFFF;
            int cMid2 = dark ? 0x28FFFFFF : 0x33FFFFFF;
            int cBotRight = dark ? 0x70FFFFFF : 0xCCFFFFFF;
            edgePaint.setStyle(Paint.Style.STROKE);
            edgePaint.setStrokeWidth(1.3f * density);
            edgePaint.setShader(new android.graphics.LinearGradient(
                    0f, 0f, w, h,
                    new int[]{cTopLeft, cMid1, cMid, cMid2, cBotRight},
                    new float[]{0f, 0.26f, 0.50f, 0.74f, 1f},
                    Shader.TileMode.CLAMP));
            float inset = 0.65f * density;
            if (isCircle) {
                float size = Math.min(w, h) - inset * 2f;
                canvas.drawCircle(w * 0.5f, h * 0.5f, Math.max(1f, size * 0.5f), edgePaint);
            } else {
                clipRect.set(inset, inset, w - inset, h - inset);
                float rr = Math.max(0f, effectiveR - inset);
                canvas.drawRoundRect(clipRect, rr, rr, edgePaint);
            }
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            edgePaint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    // -------------------------------------------------------------------------
    // 2. 120Hz Choreographer Damped Harmonic Spring
    // -------------------------------------------------------------------------
    public static final class FluidSpring {
        public interface OnUpdateListener {
            void onUpdate(float value);
        }

        public interface OnEndListener {
            void onEnd(float finalValue);
        }

        private float stiffness;
        private float dampingRatio;
        private float value;
        private float velocity;
        private float target;
        private float settleThreshold = 0.05f;
        private float velocityThreshold = 0.4f;
        private boolean running;
        private long lastFrameNs;

        private OnUpdateListener updateListener;
        private OnEndListener endListener;

        private final Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
            @Override
            public void doFrame(long frameTimeNanos) {
                if (!running) return;
                float dtSec;
                if (lastFrameNs == 0L) {
                    dtSec = 1f / 120f;
                } else {
                    dtSec = (frameTimeNanos - lastFrameNs) / 1_000_000_000f;
                    if (dtSec < 0.001f) dtSec = 0.001f;
                    if (dtSec > 0.032f) dtSec = 0.032f;
                }
                lastFrameNs = frameTimeNanos;

                int steps = (stiffness > 1100f || dtSec > 0.012f) ? 3 : 1;
                float subDt = dtSec / steps;
                float c = 2f * dampingRatio * (float) Math.sqrt(stiffness);

                for (int i = 0; i < steps; i++) {
                    float disp = value - target;
                    float accel = -stiffness * disp - c * velocity;
                    velocity += accel * subDt;
                    value += velocity * subDt;
                }

                if (updateListener != null) {
                    updateListener.onUpdate(value);
                }

                if (Math.abs(value - target) <= settleThreshold && Math.abs(velocity) <= velocityThreshold) {
                    value = target;
                    velocity = 0f;
                    running = false;
                    lastFrameNs = 0L;
                    if (updateListener != null) {
                        updateListener.onUpdate(value);
                    }
                    if (endListener != null) {
                        endListener.onEnd(value);
                    }
                    return;
                }
                Choreographer.getInstance().postFrameCallback(this);
            }
        };

        public FluidSpring(float stiffness, float dampingRatio) {
            this.stiffness = stiffness;
            this.dampingRatio = dampingRatio;
        }

        public static FluidSpring fromCoui(float response, float bounce) {
            float r = Math.max(0.05f, response);
            float stiffness = (float) Math.pow((2.0 * Math.PI) / r, 2.0);
            float dampingRatio = Math.max(0.05f, 1.0f - bounce);
            return new FluidSpring(stiffness, dampingRatio);
        }

        public FluidSpring setCouiParams(float response, float bounce) {
            float r = Math.max(0.05f, response);
            this.stiffness = (float) Math.pow((2.0 * Math.PI) / r, 2.0);
            this.dampingRatio = Math.max(0.05f, 1.0f - bounce);
            return this;
        }

        /** Exact ColorOS 17 {@code TouchMotionRubberBand.apply}: hyperbolic asymptotic squeeze. */
        public static float couiRubberBand(float offset, float curveRatio, float maxDistancePx) {
            if (maxDistancePx <= 0f || curveRatio <= 0f || Math.abs(offset) < 0.01f) {
                return 0f;
            }
            float abs = Math.abs(offset);
            float mag = maxDistancePx * (1.0f - (1.0f / (1.0f + (curveRatio * abs) / maxDistancePx)));
            return offset >= 0f ? mag : -mag;
        }

        public static FluidSpring navPill() {
            FluidSpring s = new FluidSpring(380f, 0.78f);
            s.setThresholds(0.35f, 4.0f);
            return s;
        }

        public static FluidSpring touchPress() {
            FluidSpring s = fromCoui(0.13f, 0.18f);
            s.setThresholds(0.0005f, 0.005f);
            return s;
        }

        public static FluidSpring pageTransition() {
            FluidSpring s = new FluidSpring(320f, 0.86f);
            s.setThresholds(0.002f, 0.015f);
            return s;
        }

        public static FluidSpring ancSegment() {
            FluidSpring s = new FluidSpring(440f, 0.80f);
            s.setThresholds(0.35f, 4.0f);
            return s;
        }

        public FluidSpring setThresholds(float settleThreshold, float velocityThreshold) {
            this.settleThreshold = settleThreshold;
            this.velocityThreshold = velocityThreshold;
            return this;
        }

        public FluidSpring setSpringParams(float stiffness, float dampingRatio) {
            this.stiffness = stiffness;
            this.dampingRatio = dampingRatio;
            return this;
        }

        public void setListener(OnUpdateListener updateListener, OnEndListener endListener) {
            this.updateListener = updateListener;
            this.endListener = endListener;
        }

        public float getValue() {
            return value;
        }

        public float getVelocity() {
            return velocity;
        }

        public float getTarget() {
            return target;
        }

        public boolean isRunning() {
            return running;
        }

        public void setValue(float immediateValue) {
            cancel();
            this.value = immediateValue;
            this.target = immediateValue;
            this.velocity = 0f;
            if (updateListener != null) {
                updateListener.onUpdate(value);
            }
        }

        public void setVelocity(float velocity) {
            this.velocity = velocity;
        }

        public void animateTo(float newTarget) {
            this.target = newTarget;
            if (Math.abs(value - target) <= settleThreshold && Math.abs(velocity) <= velocityThreshold) {
                setValue(target);
                if (endListener != null) {
                    endListener.onEnd(value);
                }
                return;
            }
            if (!running) {
                running = true;
                lastFrameNs = 0L;
                Choreographer.getInstance().postFrameCallback(frameCallback);
            }
        }

        public void cancel() {
            if (running) {
                running = false;
                lastFrameNs = 0L;
                Choreographer.getInstance().removeFrameCallback(frameCallback);
            }
        }
    }

    // -------------------------------------------------------------------------
    // 3. COUITouchMotionAnimator-style Flexible Tactile Deformation & SpotLight
    // -------------------------------------------------------------------------
    public static final class TouchFeedback {
        public enum Profile {
            GLASS_CIRCLE,
            HERO_CARD,
            PRIMARY_BUTTON,
            ACTION_ROW
        }

        private TouchFeedback() {
        }

        public static void detach(View view) {
            if (view == null) return;
            Object prev = view.getTag(TAG_TOUCH_FEEDBACK);
            if (prev instanceof Holder) {
                ((Holder) prev).release();
                view.setTag(TAG_TOUCH_FEEDBACK, null);
            }
        }

        public static SpotLightDrawable attach(View view, Profile profile, boolean dark,
                                               float cornerRadiusDp, boolean isCircle, boolean isCapsule) {
            return attach(view, profile, dark, cornerRadiusDp, isCircle, isCapsule, true);
        }

        public static SpotLightDrawable attach(View view, Profile profile, boolean dark,
                                               float cornerRadiusDp, boolean isCircle, boolean isCapsule,
                                               boolean allowDrift) {
            if (view == null) return null;
            detach(view);

            float d = view.getResources().getDisplayMetrics().density;
            final float pressedScale;
            final float pressResp;
            final float pressBounce;
            final float relResp;
            final float relBounce;
            final float maxDriftPx;
            final float maxDeformGain;
            final ColorOsLivingFlowEngine.HapticKind hapticKind;
            final boolean useSpotLight;
            final EdgeStyle edgeStyle;
            final float spotIntensity;
            final float spotRadiusDp;

            boolean hwMat = ColorOsLivingFlowEngine.isHardwareMaterialSupported();
            switch (profile) {
                case GLASS_CIRCLE:
                    // Official Clock.apk COUITouchMotionAnimator + PressParams + 0.05 rubber curve
                    pressedScale = 0.93f;
                    pressResp = 0.22f;
                    pressBounce = 0.0f;
                    relResp = 0.40f;
                    relBounce = 0.24f;
                    maxDriftPx = allowDrift ? (28f * d) : 0f;
                    maxDeformGain = allowDrift ? 0.04f : 0f;
                    hapticKind = ColorOsLivingFlowEngine.HapticKind.TAP;
                    useSpotLight = true;
                    edgeStyle = hwMat ? EdgeStyle.NONE : EdgeStyle.CIRCLE;
                    spotIntensity = dark ? 0.24f : 0.28f;
                    spotRadiusDp = 76f;
                    break;
                case HERO_CARD:
                    pressedScale = 0.985f;
                    pressResp = 0.24f;
                    pressBounce = 0.0f;
                    relResp = 0.42f;
                    relBounce = 0.12f;
                    maxDriftPx = 0f;
                    maxDeformGain = 0f;
                    hapticKind = null;
                    useSpotLight = false;
                    edgeStyle = EdgeStyle.NONE;
                    spotIntensity = 0f;
                    spotRadiusDp = 0f;
                    break;
                case PRIMARY_BUTTON:
                    // Official Clock.apk wide capsule button: scaleRangeMax = 0.97f, in-place compression
                    pressedScale = 0.97f;
                    pressResp = 0.22f;
                    pressBounce = 0.0f;
                    relResp = 0.38f;
                    relBounce = 0.18f;
                    maxDriftPx = 0f;
                    maxDeformGain = 0f;
                    hapticKind = ColorOsLivingFlowEngine.HapticKind.TAP;
                    useSpotLight = true;
                    edgeStyle = hwMat ? EdgeStyle.NONE : EdgeStyle.CAPSULE;
                    spotIntensity = dark ? 0.18f : 0.20f;
                    spotRadiusDp = 96f;
                    break;
                case ACTION_ROW:
                default:
                    pressedScale = 0.99f;
                    pressResp = 0.20f;
                    pressBounce = 0.0f;
                    relResp = 0.36f;
                    relBounce = 0.08f;
                    maxDriftPx = 0f;
                    maxDeformGain = 0f;
                    hapticKind = ColorOsLivingFlowEngine.HapticKind.TICK;
                    useSpotLight = false;
                    edgeStyle = EdgeStyle.NONE;
                    spotIntensity = 0f;
                    spotRadiusDp = 0f;
                    break;
            }

            SpotLightDrawable spot = null;
            if (useSpotLight) {
                spot = new SpotLightDrawable(dark, d, cornerRadiusDp, isCircle, isCapsule,
                        spotIntensity, spotRadiusDp, edgeStyle);
                int accent = DynamicThemeHelper.getAccent(view.getContext());
                if (profile == Profile.PRIMARY_BUTTON) {
                    spot.setLightColor(0xFFFFFFFF);
                } else if (profile == Profile.HERO_CARD) {
                    int blended = blendColor(dark ? 0xFFEAF0FF : 0xFFFFFFFF, accent, dark ? 0.28f : 0.16f);
                    spot.setLightColor(blended);
                }
                view.setForeground(spot);
            }

            Holder holder = new Holder(view, spot, pressedScale,
                    pressResp, pressBounce, relResp, relBounce,
                    maxDriftPx, maxDeformGain, hapticKind, d);
            view.setTag(TAG_TOUCH_FEEDBACK, holder);
            view.setOnTouchListener(holder);
            view.addOnAttachStateChangeListener(holder);
            return spot;
        }

        private static int blendColor(int base, int tint, float ratio) {
            int r = Math.round(Color.red(base) * (1f - ratio) + Color.red(tint) * ratio);
            int g = Math.round(Color.green(base) * (1f - ratio) + Color.green(tint) * ratio);
            int b = Math.round(Color.blue(base) * (1f - ratio) + Color.blue(tint) * ratio);
            return Color.rgb(r, g, b);
        }

        private static final class Holder implements View.OnTouchListener, View.OnAttachStateChangeListener {
            private final View target;
            private final SpotLightDrawable spot;
            private final float pressedScale;
            private final float pressResp;
            private final float pressBounce;
            private final float relResp;
            private final float relBounce;
            private final float maxDriftPx;
            private final float maxDeformGain;
            private final float deformRangePx;
            private final ColorOsLivingFlowEngine.HapticKind hapticKind;

            private final FluidSpring scaleSpring = FluidSpring.touchPress();
            private final FluidSpring txSpring = FluidSpring.fromCoui(0.15f, 0.15f).setThresholds(0.15f, 1.5f);
            private final FluidSpring tySpring = FluidSpring.fromCoui(0.15f, 0.15f).setThresholds(0.15f, 1.5f);
            private final FluidSpring deformXSpring = FluidSpring.fromCoui(0.15f, 0.15f).setThresholds(0.0005f, 0.005f);
            private final FluidSpring deformYSpring = FluidSpring.fromCoui(0.15f, 0.15f).setThresholds(0.0005f, 0.005f);

            private float curPress = 1f;
            private float curDefX = 1f;
            private float curDefY = 1f;
            private float downRawX;
            private float downRawY;

            Holder(View target, SpotLightDrawable spot, float pressedScale,
                   float pressResp, float pressBounce, float relResp, float relBounce,
                   float maxDriftPx, float maxDeformGain,
                   ColorOsLivingFlowEngine.HapticKind hapticKind, float density) {
                this.target = target;
                this.spot = spot;
                this.pressedScale = pressedScale;
                this.pressResp = pressResp;
                this.pressBounce = pressBounce;
                this.relResp = relResp;
                this.relBounce = relBounce;
                this.maxDriftPx = maxDriftPx;
                this.maxDeformGain = maxDeformGain;
                this.deformRangePx = 72f * density;
                this.hapticKind = hapticKind;

                scaleSpring.setValue(1f);
                txSpring.setValue(0f);
                tySpring.setValue(0f);
                deformXSpring.setValue(1f);
                deformYSpring.setValue(1f);

                scaleSpring.setListener(v -> {
                    curPress = v;
                    applyScale();
                }, null);
                deformXSpring.setListener(v -> {
                    curDefX = v;
                    applyScale();
                }, null);
                deformYSpring.setListener(v -> {
                    curDefY = v;
                    applyScale();
                }, null);
                txSpring.setListener(target::setTranslationX, null);
                tySpring.setListener(target::setTranslationY, null);
            }

            private void applyScale() {
                target.setScaleX(curPress * curDefX);
                target.setScaleY(curPress * curDefY);
            }

            @Override
            public boolean onTouch(View v, MotionEvent ev) {
                int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE
                        || action == MotionEvent.ACTION_UP) {
                    v.setTag(TAG_TOUCH_POINT, new float[]{ev.getRawX(), ev.getRawY()});
                }
                if (!v.isEnabled()) {
                    return false;
                }
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = ev.getRawX();
                        downRawY = ev.getRawY();
                        if (v.getWidth() > 0 && v.getHeight() > 0) {
                            v.setPivotX(v.getWidth() * 0.5f);
                            v.setPivotY(v.getHeight() * 0.5f);
                        }
                        scaleSpring.setCouiParams(pressResp, pressBounce).animateTo(pressedScale);
                        if (maxDriftPx > 0f) {
                            txSpring.setCouiParams(0.15f, 0.15f);
                            tySpring.setCouiParams(0.15f, 0.15f);
                        }
                        if (maxDeformGain > 0f) {
                            deformXSpring.setCouiParams(0.15f, 0.15f).animateTo(1f);
                            deformYSpring.setCouiParams(0.15f, 0.15f).animateTo(1f);
                        }
                        if (spot != null) {
                            spot.onPress(ev.getX(), ev.getY());
                        }
                        if (hapticKind != null) {
                            ColorOsLivingFlowEngine.performHaptic(v, hapticKind);
                        }
                        break;
                    case MotionEvent.ACTION_MOVE:
                        float dx = ev.getRawX() - downRawX;
                        float dy = ev.getRawY() - downRawY;
                        if (maxDriftPx > 0f) {
                            if ((Math.abs(dx) > 4f || Math.abs(dy) > 4f) && v.getParent() != null) {
                                v.getParent().requestDisallowInterceptTouchEvent(true);
                            }
                            txSpring.animateTo(FluidSpring.couiRubberBand(dx, 0.05f, maxDriftPx));
                            tySpring.animateTo(FluidSpring.couiRubberBand(dy, 0.05f, maxDriftPx));
                        }
                        if (maxDeformGain > 0f) {
                            float absX = Math.abs(dx);
                            float absY = Math.abs(dy);
                            if (absX + absY > 2f) {
                                float rx = FluidSpring.couiRubberBand(absX, 0.55f, deformRangePx) / deformRangePx;
                                float ry = FluidSpring.couiRubberBand(absY, 0.55f, deformRangePx) / deformRangePx;
                                float hx = 1f + maxDeformGain * rx;
                                float hy = 1f / hx;
                                float vy = 1f + maxDeformGain * ry;
                                float vx = 1f / vy;
                                float wx = absX / (absX + absY);
                                float wy = 1f - wx;
                                deformXSpring.animateTo((float) Math.exp(wx * Math.log(hx) + wy * Math.log(vx)));
                                deformYSpring.animateTo((float) Math.exp(wx * Math.log(hy) + wy * Math.log(vy)));
                            }
                        }
                        if (spot != null) {
                            spot.onMove(ev.getX(), ev.getY());
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        scaleSpring.setCouiParams(relResp, relBounce).animateTo(1f);
                        if (maxDriftPx > 0f) {
                            txSpring.setCouiParams(0.40f, 0.22f).animateTo(0f);
                            tySpring.setCouiParams(0.40f, 0.22f).animateTo(0f);
                        }
                        if (maxDeformGain > 0f) {
                            deformXSpring.setCouiParams(0.40f, 0.22f).animateTo(1f);
                            deformYSpring.setCouiParams(0.40f, 0.22f).animateTo(1f);
                        }
                        if (spot != null) {
                            spot.onRelease();
                        }
                        break;
                }
                return false;
            }

            void release() {
                scaleSpring.cancel();
                txSpring.cancel();
                tySpring.cancel();
                deformXSpring.cancel();
                deformYSpring.cancel();
                curPress = 1f;
                curDefX = 1f;
                curDefY = 1f;
                if (spot != null) {
                    spot.cancel();
                    if (target.getForeground() == spot) {
                        target.setForeground(null);
                    }
                }
                target.setOnTouchListener(null);
                target.removeOnAttachStateChangeListener(this);
                target.setScaleX(1f);
                target.setScaleY(1f);
                target.setTranslationX(0f);
                target.setTranslationY(0f);
            }

            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                scaleSpring.cancel();
                txSpring.cancel();
                tySpring.cancel();
                deformXSpring.cancel();
                deformYSpring.cancel();
                if (spot != null) {
                    spot.cancel();
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // 4. Official ColorOS 17 Olivelink COUITouchMotionAnimator Nav Bar Controller
    // -------------------------------------------------------------------------
    public static final class NavBarElasticController {
        private final View target;
        private final float density;
        private final FluidSpring pressSpring = FluidSpring.fromCoui(0.25f, 0.0f).setThresholds(0.0005f, 0.005f);
        private final FluidSpring txSpring = FluidSpring.fromCoui(0.15f, 0.15f).setThresholds(0.12f, 1.5f);
        private final FluidSpring tySpring = FluidSpring.fromCoui(0.15f, 0.15f).setThresholds(0.12f, 1.5f);
        private final FluidSpring deformXSpring = FluidSpring.fromCoui(0.15f, 0.15f).setThresholds(0.0005f, 0.005f);
        private final FluidSpring deformYSpring = FluidSpring.fromCoui(0.15f, 0.15f).setThresholds(0.0005f, 0.005f);

        private float curPress = 1f;
        private float curDeformX = 1f;
        private float curDeformY = 1f;

        public NavBarElasticController(View target) {
            this.target = target;
            this.density = target.getResources().getDisplayMetrics().density;

            pressSpring.setValue(1f);
            txSpring.setValue(0f);
            tySpring.setValue(0f);
            deformXSpring.setValue(1f);
            deformYSpring.setValue(1f);

            pressSpring.setListener(v -> {
                curPress = v;
                applyScale();
            }, null);
            deformXSpring.setListener(v -> {
                curDeformX = v;
                applyScale();
            }, null);
            deformYSpring.setListener(v -> {
                curDeformY = v;
                applyScale();
            }, null);
            txSpring.setListener(target::setTranslationX, null);
            tySpring.setListener(target::setTranslationY, null);
        }

        private void applyScale() {
            target.setScaleX(curPress * curDeformX);
            target.setScaleY(curPress * curDeformY);
        }

        private static float computeOfficialDeformGain(int wPx, int hPx) {
            if (wPx <= 0 || hPx <= 0) return 0.055f;
            double area = (double) wPx * (double) hPx;
            double aspect = Math.max(wPx, hPx) / (double) Math.min(wPx, hPx);
            double areaRatio = Math.max(1.0, area / 38416.0);
            double decay = Math.min(1.0, 0.35 * Math.log(areaRatio) + 0.015 * Math.max(0.0, aspect - 1.0));
            float maxScale = Math.max(1.03f, 1.03f + 0.17f * (float) (1.0 - decay));
            return (maxScale - 1f) * 0.65f;
        }

        public void onDown() {
            int w = target.getWidth();
            int h = target.getHeight();
            if (w > 0 && h > 0) {
                target.setPivotX(w * 0.5f);
                target.setPivotY(h * 0.5f);
            }
            pressSpring.setCouiParams(0.25f, 0.0f).animateTo(0.97f);
            txSpring.setCouiParams(0.15f, 0.15f).animateTo(0f);
            tySpring.setCouiParams(0.15f, 0.15f).animateTo(0f);
            deformXSpring.setCouiParams(0.15f, 0.15f).animateTo(1f);
            deformYSpring.setCouiParams(0.15f, 0.15f).animateTo(1f);
        }

        public void onMove(float dxRaw, float dyRaw) {
            float deadZonePx = 5.0f * density;
            float dist = (float) Math.hypot(dxRaw, dyRaw);
            if (dist <= deadZonePx) {
                txSpring.animateTo(0f);
                tySpring.animateTo(0f);
                deformXSpring.animateTo(1f);
                deformYSpring.animateTo(1f);
                return;
            }

            float scaleFade = Math.min(1f, (dist - deadZonePx) / (12f * density));
            pressSpring.animateTo(0.97f + (0.985f - 0.97f) * scaleFade);

            float effScale = (dist - deadZonePx) / dist;
            float dx = dxRaw * effScale;
            float dy = dyRaw * effScale;

            float tx = FluidSpring.couiRubberBand(dx, 0.05f, 28f * density);
            float ty = FluidSpring.couiRubberBand(dy, 0.05f, 24f * density);
            txSpring.animateTo(tx);
            tySpring.animateTo(ty);

            float maxDeformPx = 150f * density;
            float absDx = Math.abs(dx);
            float absDy = Math.abs(dy);
            float rx = FluidSpring.couiRubberBand(absDx, 0.55f, maxDeformPx) / maxDeformPx;
            float ry = FluidSpring.couiRubberBand(absDy, 0.55f, maxDeformPx) / maxDeformPx;

            float gain = computeOfficialDeformGain(target.getWidth(), target.getHeight());
            float hx = 1f + gain * rx;
            float hy = 1f / hx;
            float vy = 1f + (gain * 0.88f) * ry;
            float vx = 1f / vy;

            float absSum = absDx + absDy;
            float defX = 1f;
            float defY = 1f;
            if (absSum > 0.5f) {
                float wx = absDx / absSum;
                float wy = 1f - wx;
                defX = (float) Math.exp(wx * Math.log(hx) + wy * Math.log(vx));
                defY = (float) Math.exp(wx * Math.log(hy) + wy * Math.log(vy));
            }
            deformXSpring.animateTo(defX);
            deformYSpring.animateTo(defY);
        }

        public void onRelease() {
            pressSpring.setCouiParams(0.42f, 0.18f).animateTo(1f);
            txSpring.setCouiParams(0.42f, 0.20f).animateTo(0f);
            tySpring.setCouiParams(0.42f, 0.20f).animateTo(0f);
            deformXSpring.setCouiParams(0.42f, 0.20f).animateTo(1f);
            deformYSpring.setCouiParams(0.42f, 0.20f).animateTo(1f);
        }

        public void cancel() {
            pressSpring.cancel();
            txSpring.cancel();
            tySpring.cancel();
            deformXSpring.cancel();
            deformYSpring.cancel();
            curPress = 1f;
            curDeformX = 1f;
            curDeformY = 1f;
            target.setScaleX(1f);
            target.setScaleY(1f);
            target.setTranslationX(0f);
            target.setTranslationY(0f);
        }
    }

    /**
     * Smooth dithered Gaussian radial aura drawable for dark/light headset pedestals
     * (avoids 8-bit OLED concentric banding and hard circular clip borders).
     */
    public static Drawable createSmoothRadialAuraDrawable(int baseColor, int alphaCenter) {
        return new SmoothAuraDrawable(baseColor, alphaCenter);
    }

    private static final class SmoothAuraDrawable extends Drawable {
        private static final String AGSL_AURA =
                "uniform float2 center;\n"
                        + "uniform float radius;\n"
                        + "uniform float3 auraCol;\n"
                        + "uniform float maxAlpha;\n"
                        + "float hash12(float2 p) {\n"
                        + "    float3 p3 = fract(float3(p.xyx) * 0.1031);\n"
                        + "    p3 += dot(p3, p3.yzx + 33.33);\n"
                        + "    return fract((p3.x + p3.y) * p3.z);\n"
                        + "}\n"
                        + "half4 main(float2 fragCoord) {\n"
                        + "    float t = length(fragCoord - center) / max(radius, 1.0);\n"
                        + "    float decay = exp(-3.6 * t * t) * smoothstep(1.0, 0.58, t);\n"
                        + "    float a = maxAlpha * decay;\n"
                        + "    float n = (hash12(fragCoord) - hash12(fragCoord + float2(17.13, 31.71))) * (1.5 / 255.0);\n"
                        + "    float outA = clamp(a + n * smoothstep(0.0, 0.015, a), 0.0, 1.0);\n"
                        + "    return half4(half3(auraCol) * half(outA), half(outA));\n"
                        + "}\n";

        private static final float[] FALLBACK_STOPS = {
                0f, 0.15f, 0.30f, 0.45f, 0.60f, 0.74f, 0.86f, 0.94f, 1.0f
        };
        private static final float[] FALLBACK_WEIGHTS = {
                1f, 0.92f, 0.72f, 0.48f, 0.27f, 0.12f, 0.035f, 0.006f, 0f
        };

        private final float rCol;
        private final float gCol;
        private final float bCol;
        private final float maxAlpha;
        private final int[] stopColors;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        private RuntimeShader runtimeShader;
        private boolean shaderFailed;
        private int lastW = -1;
        private int lastH = -1;
        private RadialGradient fallbackGradient;

        SmoothAuraDrawable(int baseColor, int alphaCenter) {
            int r = Color.red(baseColor);
            int g = Color.green(baseColor);
            int b = Color.blue(baseColor);
            this.rCol = r / 255f;
            this.gCol = g / 255f;
            this.bCol = b / 255f;
            int clampedA = Math.max(0, Math.min(255, alphaCenter));
            this.maxAlpha = clampedA / 255f;
            this.stopColors = new int[FALLBACK_STOPS.length];
            for (int i = 0; i < FALLBACK_STOPS.length; i++) {
                int a = Math.round(clampedA * FALLBACK_WEIGHTS[i]);
                stopColors[i] = Color.argb(a, r, g, b);
            }
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            if (b.isEmpty()) return;
            float cx = b.exactCenterX();
            float cy = b.exactCenterY();
            float rad = Math.max(1f, Math.min(b.width(), b.height()) * 0.49f);

            if (Build.VERSION.SDK_INT >= 33 && !shaderFailed) {
                try {
                    if (runtimeShader == null) {
                        runtimeShader = new RuntimeShader(AGSL_AURA);
                    }
                    runtimeShader.setFloatUniform("center", cx, cy);
                    runtimeShader.setFloatUniform("radius", rad);
                    runtimeShader.setFloatUniform("auraCol", rCol, gCol, bCol);
                    runtimeShader.setFloatUniform("maxAlpha", maxAlpha);
                    paint.setShader(runtimeShader);
                    canvas.drawRect(b, paint);
                    return;
                } catch (Throwable ignored) {
                    shaderFailed = true;
                }
            }

            if (fallbackGradient == null || b.width() != lastW || b.height() != lastH) {
                lastW = b.width();
                lastH = b.height();
                fallbackGradient = new RadialGradient(
                        cx, cy, rad, stopColors, FALLBACK_STOPS, Shader.TileMode.CLAMP);
            }
            paint.setShader(fallbackGradient);
            canvas.drawRect(b, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
