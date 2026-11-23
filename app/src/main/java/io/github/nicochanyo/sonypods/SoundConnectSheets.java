package io.github.nicochanyo.sonypods;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;

/**
 * HeyMelody / ColorOS 17 native-layered secondary (Level-2) and tertiary (Level-3) settings pages
 * for Sony Sound Connect features:
 * <ul>
 *   <li>Mounts directly inside the host panel container ({@code mHostBinding.a} in Control Center
 *       detail panel or {@code android.R.id.content} in full-screen activities) on the exact same
 *       layer ({@code 同圖層}), preserving the panel's material blur and top rounded corners ({@code 上方R角}).</li>
 *   <li>Floating circular shadow back button ({@code ←}) + bold header title.</li>
 *   <li>ColorOS 17 Donut Radio single-choice card pages for presets and option lists.</li>
 *   <li>HeyMelody / ColorOS vertical multi-band equalizer card with hollow ring thumbs and {@code 還原預設}.</li>
 * </ul>
 */
public final class SoundConnectSheets {
    private static final int[] EQ_PRESET_IDS = new int[]{
            0x00, 0x10, 0x11, 0x12, 0x13, 0x14, 0x15, 0x16, 0x17, 0xA1, 0xA2, 0xA0
    };

    private static final int[] TOUCH_PRESET_IDS = new int[]{
            SonyTandemProtocol.TOUCH_PRESET_AMBIENT,
            SonyTandemProtocol.TOUCH_PRESET_PLAYBACK,
            SonyTandemProtocol.TOUCH_PRESET_VOLUME,
            SonyTandemProtocol.TOUCH_PRESET_VOICE,
            SonyTandemProtocol.TOUCH_PRESET_QUICK_ACCESS,
            SonyTandemProtocol.TOUCH_PRESET_NONE
    };

    private static final int[] AUTO_POWER_OFF_ELEMENTS_WEAR = new int[]{
            0x10, 0x00, 0x04, 0x01, 0x02, 0x03, 0x11
    };

    private static final int[] AUTO_POWER_OFF_ELEMENTS_BASIC = new int[]{
            0x00, 0x04, 0x01, 0x02, 0x03, 0x11
    };

    private static final PathInterpolator PAGE_PUSH_EASE = new PathInterpolator(0.30f, 0.0f, 0.10f, 1.0f);
    private static final PathInterpolator PAGE_POP_EASE = new PathInterpolator(0.30f, 0.0f, 0.15f, 1.0f);

    private static final WeakHashMap<FrameLayout, StageState> STAGE_STATES = new WeakHashMap<>();

    public interface BoolListener {
        void onChanged(boolean value);
    }

    private interface BoolExtractor {
        boolean read(ConfigStore.HeadsetState st);
    }

    public interface OnStateDispatched {
        void onUpdated();
    }

    /**
     * Same-layer host container descriptor so Level-2 and Level-3 pages render inside the exact
     * same panel container (matching top rounded corners and material blur) as Level-1.
     */
    public static final class HostStage {
        public final FrameLayout container;
        public final View level1Content;
        public final Dialog hostDialog;
        public final Activity hostActivity;
        public final boolean embeddedPanel;

        private HostStage(FrameLayout container, View level1Content, Dialog hostDialog,
                          Activity hostActivity, boolean embeddedPanel) {
            this.container = container;
            this.level1Content = level1Content;
            this.hostDialog = hostDialog;
            this.hostActivity = hostActivity;
            this.embeddedPanel = embeddedPanel;
        }

        public static HostStage forEmbeddedPanel(FrameLayout panelRoot, View level1ScrollView,
                                                 Dialog hostBottomSheetDialog, Context uiCtx) {
            if (panelRoot == null) {
                return fromContext(uiCtx);
            }
            return new HostStage(panelRoot, level1ScrollView, hostBottomSheetDialog,
                    unwrapActivity(uiCtx), true);
        }

        public static HostStage fromContext(Context uiCtx) {
            Activity act = unwrapActivity(uiCtx);
            if (act != null && act.getWindow() != null) {
                View content = act.findViewById(android.R.id.content);
                if (content instanceof FrameLayout) {
                    FrameLayout fl = (FrameLayout) content;
                    View level1 = fl.getChildCount() > 0 ? fl.getChildAt(0) : null;
                    return new HostStage(fl, level1, null, act, false);
                }
            }
            return null;
        }
    }

    private static final class StageState {
        final List<View> stack = new ArrayList<>();
        Object backInvokedCallback;
        Object backDispatcherOwner;
    }

    private SoundConnectSheets() {
    }

    // ==================== Category Availability & Summaries ====================

    public static boolean hasSoundFeatures(ConfigStore.HeadsetState st) {
        return st != null && st.connected && (st.supportsNcAsm || st.supportsEq || st.supportsDsee);
    }

    public static boolean hasInteractionFeatures(ConfigStore.HeadsetState st) {
        return st != null && st.connected
                && (st.supportsTouchSensor || st.supportsSpeakToChat || st.supportsWearDetect);
    }

    public static boolean hasConnectionFeatures(ConfigStore.HeadsetState st, ConfigStore.CodecState cs,
                                                String mac) {
        if (st == null || !st.connected) return false;
        boolean codecAvail = isCodecAvailable(st, cs, mac);
        return st.supportsMultipoint || st.supportsConnectionMode || codecAvail;
    }

    public static boolean hasPowerFeatures(ConfigStore.HeadsetState st) {
        return st != null && st.connected && st.supportsAutoPowerOff;
    }

    public static boolean isCodecAvailable(ConfigStore.HeadsetState st, ConfigStore.CodecState cs, String mac) {
        if (st == null || !st.connected || cs == null) return false;
        boolean macMatch = cs.mac.isEmpty()
                || (mac != null && !mac.isEmpty() && cs.mac.equalsIgnoreCase(mac))
                || (st.mac != null && !st.mac.isEmpty() && cs.mac.equalsIgnoreCase(st.mac));
        return macMatch && cs.supportsLdac && cs.supportsAac;
    }

    public static String formatSoundSummary(Context ctx, ConfigStore.HeadsetState st) {
        if (st == null || !st.connected) return "";
        List<String> parts = new ArrayList<>();
        if (st.supportsNcAsm) {
            parts.add(loc(ctx, "通透 ", "通透 ", "Amb ") + st.asmLevel);
        }
        if (st.supportsEq) {
            parts.add("EQ " + getEqPresetLabel(ctx, st.eqPresetId));
        }
        if (parts.isEmpty() && st.supportsDsee) {
            parts.add(st.dsee ? "DSEE ON" : "DSEE OFF");
        }
        return joinDot(parts);
    }

    public static String formatInteractionSummary(Context ctx, ConfigStore.HeadsetState st) {
        if (st == null || !st.connected) return "";
        List<String> parts = new ArrayList<>();
        if (st.supportsTouchSensor) {
            parts.add(loc(ctx, "左 ", "左 ", "L ") + shortTouchLabel(ctx, st.touchLeftPreset));
            parts.add(loc(ctx, "右 ", "右 ", "R ") + shortTouchLabel(ctx, st.touchRightPreset));
        } else if (st.supportsSpeakToChat) {
            parts.add(loc(ctx, "說話聊天 ", "说话聊天 ", "Speak ") + (st.speakToChat ? loc(ctx, "開", "开", "ON") : loc(ctx, "關", "关", "OFF")));
        } else if (st.supportsWearDetect) {
            parts.add(st.wearAutoPause ? loc(ctx, "取下暫停", "取下暂停", "Auto Pause") : loc(ctx, "佩戴偵測關", "佩戴检测关", "Wear Off"));
        }
        return joinDot(parts);
    }

    public static String formatConnectionSummary(Context ctx, ConfigStore.HeadsetState st, ConfigStore.CodecState cs) {
        if (st == null || !st.connected) return "";
        List<String> parts = new ArrayList<>();
        if (st.supportsMultipoint) {
            parts.add(st.multipointEnabled
                    ? loc(ctx, "多設備 開", "多设备 开", "2-Dev ON")
                    : loc(ctx, "多設備 關", "多设备 关", "2-Dev OFF"));
        }
        boolean codecAvail = isCodecAvailable(st, cs, st.mac);
        if (codecAvail || st.supportsConnectionMode) {
            parts.add(formatUnifiedQualityShort(ctx, st, cs, codecAvail));
        }
        return joinDot(parts);
    }

    public static String formatPowerSummary(Context ctx, ConfigStore.HeadsetState st) {
        if (st == null || !st.connected || !st.supportsAutoPowerOff) return "";
        return getAutoPowerOffShortLabel(ctx, st.autoPowerOffElement);
    }

    // ==================== Public Entry Points (4-arg & 5-arg with HostStage) ====================

    public static void showSoundAndEqSheet(Context uiCtx, Context cmdCtx, CouiBottomSheet.Palette pal,
                                           OnStateDispatched onDispatched) {
        showSoundAndEqSheet(uiCtx, cmdCtx, pal, HostStage.fromContext(uiCtx), onDispatched);
    }

    public static void showSoundAndEqSheet(Context uiCtx, Context cmdCtx, CouiBottomSheet.Palette pal,
                                           HostStage stage, OnStateDispatched onDispatched) {
        if (stage == null) stage = HostStage.fromContext(uiCtx);
        if (stage == null || stage.container == null) return;
        final HostStage hostStage = stage;

        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(cmdCtx);
        float d = uiCtx.getResources().getDisplayMetrics().density;
        int cardColor = resolveCardSurfaceColor(uiCtx, pal, hostStage);

        StagePage page = createStagePage(
                uiCtx, pal, hostStage, cardColor,
                loc(uiCtx, "聲音與降噪", "声音与降噪", "Sound & Noise Control"));

        boolean firstCard = true;

        // 1. Ambient Sound Card
        if (st.supportsNcAsm) {
            LinearLayout ambCard = createPageCard(uiCtx, pal, cardColor);
            page.body.addView(ambCard, matchWrapParams(uiCtx, firstCard ? 2 : 14));
            firstCard = false;

            final int[] chosenAsm = new int[]{ConfigStore.clampAsmLevel(st.asmLevel)};
            final boolean[] chosenVf = new boolean[]{st.voiceFocus};

            LinearLayout sliderRow = new LinearLayout(uiCtx);
            sliderRow.setOrientation(LinearLayout.VERTICAL);
            sliderRow.setPadding(Math.round(20f * d), Math.round(16f * d), Math.round(20f * d), Math.round(16f * d));

            LinearLayout topRow = new LinearLayout(uiCtx);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView lbl = new TextView(uiCtx);
            lbl.setText(loc(uiCtx, "通透強度", "通透强度", "Ambient Level"));
            lbl.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            lbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            lbl.setTextColor(pal.textPrimary);
            topRow.addView(lbl, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView badge = new TextView(uiCtx);
            badge.setText(chosenAsm[0] + " / 20");
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            badge.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            badge.setTextColor(pal.textSecondary);
            topRow.addView(badge);
            sliderRow.addView(topRow, matchWrapParams(uiCtx, 0));

            CouiPillSeekBar asmBar = new CouiPillSeekBar(uiCtx);
            asmBar.setCustomPalette(pal.seekbarTrack, pal.accent, pal.onAccent);
            asmBar.setMax(19);
            asmBar.setProgress(chosenAsm[0] - 1);
            final int asmTouchSlop = ViewConfiguration.get(uiCtx).getScaledTouchSlop();
            asmBar.setOnTouchListener(new View.OnTouchListener() {
                private float downX;
                private float downY;
                private boolean decided;
                private boolean yieldedToScroll;

                @Override
                public boolean onTouch(View v, MotionEvent ev) {
                    int act = ev.getActionMasked();
                    if (act == MotionEvent.ACTION_DOWN) {
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        decided = false;
                        yieldedToScroll = false;
                    } else if (act == MotionEvent.ACTION_MOVE) {
                        if (yieldedToScroll) {
                            return true;
                        }
                        if (!decided) {
                            float dx = Math.abs(ev.getRawX() - downX);
                            float dy = Math.abs(ev.getRawY() - downY);
                            if (dy > asmTouchSlop && dy > dx * 1.05f) {
                                decided = true;
                                yieldedToScroll = true;
                                requestDisallowParentIntercept(v, false);
                                return true;
                            } else if (dx > asmTouchSlop && dx >= dy) {
                                decided = true;
                                requestDisallowParentIntercept(v, true);
                            }
                        }
                    } else if (act == MotionEvent.ACTION_UP || act == MotionEvent.ACTION_CANCEL) {
                        decided = false;
                        yieldedToScroll = false;
                    }
                    return false;
                }
            });
            sliderRow.addView(asmBar, matchWrapParams(uiCtx, 10));

            Runnable sendAmb = () -> {
                ConfigStore.HeadsetState latest = ConfigStore.readGlobalState(cmdCtx);
                if (latest.connected) {
                    ConfigStore.sendCommandBroadcast(cmdCtx, ConfigStore.CMD_SET_AMBIENT_PARAMS,
                            latest.mac, latest.ancMode, chosenAsm[0], chosenVf[0], false);
                    if (onDispatched != null) onDispatched.onUpdated();
                }
            };
            asmBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int p, boolean fromUser) {
                    if (!fromUser) return;
                    chosenAsm[0] = p + 1;
                    badge.setText(chosenAsm[0] + " / 20");
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    chosenAsm[0] = bar.getProgress() + 1;
                    badge.setText(chosenAsm[0] + " / 20");
                    sendAmb.run();
                }
            });

            ambCard.addView(sliderRow, matchWrapParams(uiCtx, 0));
            addCardDivider(uiCtx, ambCard, pal);

            addToggleRow(uiCtx, ambCard, pal,
                    loc(uiCtx, "專注語音", "专注语音", "Voice Focus"),
                    loc(uiCtx, "強化人聲並過濾背景噪音", "强化人声并过滤背景噪音",
                            "Emphasize voices over background noise"),
                    chosenVf[0], false, val -> {
                        chosenVf[0] = val;
                        sendAmb.run();
                    });
        }

        // 2. HeyMelody / ColorOS Equalizer (Top Preset Selector Card + Vertical Multi-Band Equalizer Card)
        if (st.supportsEq) {
            final int[] curPreset = new int[]{st.eqPresetId};
            final int bandCount = Math.max(5, Math.min(6, st.eqBandCount > 0 ? st.eqBandCount : 6));
            final int[] curBands = new int[bandCount];
            for (int i = 0; i < bandCount; i++) {
                curBands[i] = (st.eqBands != null && i < st.eqBands.length) ? st.eqBands[i] : 0;
            }
            if (!isCustomEqPreset(curPreset[0]) && curPreset[0] != 0x00) {
                boolean allZero = true;
                for (int i = 0; i < bandCount; i++) {
                    if (curBands[i] != 0) {
                        allZero = false;
                        break;
                    }
                }
                if (allZero) {
                    int[] seedCurve = getDefaultEqBandsForPreset(curPreset[0]);
                    if (seedCurve != null) {
                        for (int i = 0; i < Math.min(bandCount, seedCurve.length); i++) {
                            curBands[i] = seedCurve[i];
                        }
                    }
                }
            }

            final TextView[] presetValRef = new TextView[1];
            final TextView[] eqCardTitleRef = new TextView[1];
            final VerticalEqualizerView[] eqViewRef = new VerticalEqualizerView[1];

            // Card A: "等化器 | <目前預設> ⇅" (Reference Screenshot 2 top card -> opens Reference Screenshot 1 Level-3 Donut Radio card)
            LinearLayout eqSelectCard = createPageCard(uiCtx, pal, cardColor);
            page.body.addView(eqSelectCard, matchWrapParams(uiCtx, firstCard ? 2 : 14));
            firstCard = false;

            presetValRef[0] = addSelectorRowWithUpDownChevron(
                    uiCtx, eqSelectCard, pal,
                    loc(uiCtx, "等化器", "均衡器", "Equalizer"),
                    getEqPresetLabel(uiCtx, curPreset[0]),
                    v -> {
                        String[] names = new String[EQ_PRESET_IDS.length];
                        int selIdx = 0;
                        for (int i = 0; i < EQ_PRESET_IDS.length; i++) {
                            names[i] = getEqPresetLabel(uiCtx, EQ_PRESET_IDS[i]);
                            if (EQ_PRESET_IDS[i] == curPreset[0]) selIdx = i;
                        }
                        pushChoiceSubPage(
                                uiCtx, pal, hostStage, cardColor,
                                loc(uiCtx, "音效預設", "音效预设", "EQ Preset"),
                                loc(uiCtx, "等化器模式", "均衡器模式", "Equalizer Presets"),
                                names, null, selIdx, idx -> {
                                    curPreset[0] = EQ_PRESET_IDS[idx];
                                    String label = getEqPresetLabel(uiCtx, curPreset[0]);
                                    if (presetValRef[0] != null) presetValRef[0].setText(label);
                                    if (eqCardTitleRef[0] != null) eqCardTitleRef[0].setText(label);
                                    if (!isCustomEqPreset(curPreset[0])) {
                                        int[] presetCurve = getDefaultEqBandsForPreset(curPreset[0]);
                                        if (presetCurve != null) {
                                            for (int i = 0; i < Math.min(bandCount, presetCurve.length); i++) {
                                                curBands[i] = presetCurve[i];
                                            }
                                            if (eqViewRef[0] != null) eqViewRef[0].invalidate();
                                        }
                                    }
                                    ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                                    String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                                    ConfigStore.sendEqCommandBroadcast(cmdCtx, activeMac, curPreset[0], null);
                                    if (eqViewRef[0] != null) {
                                        final int expectedPreset = curPreset[0];
                                        Runnable syncFromHeadset = () -> {
                                            ConfigStore.HeadsetState latest = ConfigStore.readGlobalState(cmdCtx);
                                            if (latest.eqPresetId == expectedPreset && curPreset[0] == expectedPreset
                                                    && latest.eqBands != null && eqViewRef[0] != null) {
                                                for (int i = 0; i < Math.min(bandCount, latest.eqBands.length); i++) {
                                                    curBands[i] = latest.eqBands[i];
                                                }
                                                eqViewRef[0].invalidate();
                                            }
                                        };
                                        eqViewRef[0].postDelayed(syncFromHeadset, 250L);
                                        eqViewRef[0].postDelayed(syncFromHeadset, 600L);
                                    }
                                    if (onDispatched != null) onDispatched.onUpdated();
                                });
                    });

            // Card B: Vertical Multi-Band Equalizer Card (Reference Screenshot 2 bottom card)
            LinearLayout eqGraphCard = createPageCard(uiCtx, pal, cardColor);
            eqGraphCard.setPadding(0, Math.round(4f * d), 0, Math.round(10f * d));
            page.body.addView(eqGraphCard, matchWrapParams(uiCtx, 14));

            LinearLayout graphHeader = new LinearLayout(uiCtx);
            graphHeader.setOrientation(LinearLayout.HORIZONTAL);
            graphHeader.setGravity(Gravity.CENTER_VERTICAL);
            graphHeader.setPadding(Math.round(20f * d), Math.round(14f * d), Math.round(16f * d), Math.round(6f * d));

            TextView tvGraphTitle = new TextView(uiCtx);
            tvGraphTitle.setText(getEqPresetLabel(uiCtx, curPreset[0]));
            tvGraphTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            tvGraphTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
            tvGraphTitle.setTextColor(pal.textPrimary);
            eqCardTitleRef[0] = tvGraphTitle;
            graphHeader.addView(tvGraphTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            TextView tvReset = new TextView(uiCtx);
            tvReset.setText(loc(uiCtx, "還原預設", "还原预设", "Reset"));
            tvReset.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            tvReset.setTextColor(pal.textSecondary);
            tvReset.setClickable(true);
            tvReset.setFocusable(true);
            tvReset.setPadding(Math.round(10f * d), Math.round(6f * d), Math.round(10f * d), Math.round(6f * d));
            GradientDrawable rstMask = new GradientDrawable();
            rstMask.setShape(GradientDrawable.RECTANGLE);
            rstMask.setCornerRadius(12f * d);
            rstMask.setColor(Color.WHITE);
            tvReset.setBackground(new RippleDrawable(ColorStateList.valueOf(pal.ripple), null, rstMask));
            tvReset.setOnClickListener(v -> {
                for (int i = 0; i < bandCount; i++) {
                    curBands[i] = 0;
                }
                if (!isCustomEqPreset(curPreset[0])) {
                    curPreset[0] = 0xA0;
                }
                String label = getEqPresetLabel(uiCtx, curPreset[0]);
                if (presetValRef[0] != null) presetValRef[0].setText(label);
                if (eqCardTitleRef[0] != null) eqCardTitleRef[0].setText(label);
                if (eqViewRef[0] != null) eqViewRef[0].invalidate();
                ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                ConfigStore.sendEqCommandBroadcast(cmdCtx, activeMac, curPreset[0], curBands);
                if (onDispatched != null) onDispatched.onUpdated();
            });
            graphHeader.addView(tvReset);
            eqGraphCard.addView(graphHeader, matchWrapParams(uiCtx, 0));

            String[] bandLabels = bandCount >= 6
                    ? new String[]{"400", "1K", "2.5K", "6.3K", "16K", "BASS"}
                    : new String[]{"400", "1K", "2.5K", "6.3K", "16K"};
            VerticalEqualizerView eqView = new VerticalEqualizerView(
                    uiCtx, pal, cardColor, curBands, bandLabels,
                    () -> {
                        if (!isCustomEqPreset(curPreset[0])) {
                            curPreset[0] = 0xA0;
                            String label = getEqPresetLabel(uiCtx, curPreset[0]);
                            if (presetValRef[0] != null) presetValRef[0].setText(label);
                            if (eqCardTitleRef[0] != null) eqCardTitleRef[0].setText(label);
                        }
                    },
                    () -> {
                        ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                        String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                        ConfigStore.sendEqCommandBroadcast(cmdCtx, activeMac, curPreset[0], curBands);
                        if (onDispatched != null) onDispatched.onUpdated();
                    });
            eqViewRef[0] = eqView;
            eqGraphCard.addView(eqView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, Math.round(224f * d)));
        }

        // 3. DSEE Extreme Card
        if (st.supportsDsee) {
            LinearLayout dseeCard = createPageCard(uiCtx, pal, cardColor);
            page.body.addView(dseeCard, matchWrapParams(uiCtx, firstCard ? 2 : 14));

            final CouiToggleSwitch[] dseeSwRef = new CouiToggleSwitch[1];
            dseeSwRef[0] = addToggleRow(uiCtx, dseeCard, pal,
                    "DSEE Extreme",
                    loc(uiCtx, "即時還原壓縮音源的高頻細節", "实时还原压缩音源的高频细节",
                            "Restore high-frequency detail in compressed audio"),
                    st.dsee, false, val -> {
                        ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                        String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                        ConfigStore.sendCommandBroadcast(cmdCtx, ConfigStore.CMD_SET_DSEE,
                                activeMac, latestSt.ancMode, latestSt.asmLevel, latestSt.voiceFocus, val);
                        scheduleToggleSync(dseeSwRef[0], cmdCtx, s -> s.dsee);
                        if (onDispatched != null) onDispatched.onUpdated();
                    });
        }

        pushStagePage(hostStage, page.root);
    }

    public static void showTouchAndSmartSheet(Context uiCtx, Context cmdCtx, CouiBottomSheet.Palette pal,
                                              OnStateDispatched onDispatched) {
        showTouchAndSmartSheet(uiCtx, cmdCtx, pal, HostStage.fromContext(uiCtx), onDispatched);
    }

    public static void showTouchAndSmartSheet(Context uiCtx, Context cmdCtx, CouiBottomSheet.Palette pal,
                                              HostStage stage, OnStateDispatched onDispatched) {
        if (stage == null) stage = HostStage.fromContext(uiCtx);
        if (stage == null || stage.container == null) return;
        final HostStage hostStage = stage;

        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(cmdCtx);
        int cardColor = resolveCardSurfaceColor(uiCtx, pal, hostStage);

        StagePage page = createStagePage(
                uiCtx, pal, hostStage, cardColor,
                loc(uiCtx, "觸碰與智慧互動", "触控与智能交互", "Touch & Smart Controls"));

        boolean firstSection = true;

        // 1. Touch Sensor Assignment
        if (st.supportsTouchSensor) {
            addPageSectionHeader(uiCtx, page.body, pal,
                    loc(uiCtx, "觸碰感測器", "触控传感器", "Touch Sensors"), firstSection);
            firstSection = false;
            LinearLayout touchCard = createPageCard(uiCtx, pal, cardColor);
            page.body.addView(touchCard, matchWrapParams(uiCtx, 0));

            final int[] chosenLeft = new int[]{st.touchLeftPreset};
            final int[] chosenRight = new int[]{st.touchRightPreset};
            final TextView[] leftValRef = new TextView[1];
            final TextView[] rightValRef = new TextView[1];

            String[] touchLabels = new String[TOUCH_PRESET_IDS.length];
            for (int i = 0; i < TOUCH_PRESET_IDS.length; i++) {
                touchLabels[i] = getTouchPresetLabel(uiCtx, TOUCH_PRESET_IDS[i]);
            }

            leftValRef[0] = addJumpRow(uiCtx, touchCard, pal,
                    loc(uiCtx, "左耳", "左耳", "Left Earbud"),
                    null,
                    getTouchPresetLabel(uiCtx, chosenLeft[0]), true, v -> {
                        int sel = indexOfInt(TOUCH_PRESET_IDS, chosenLeft[0]);
                        pushChoiceSubPage(
                                uiCtx, pal, hostStage, cardColor,
                                loc(uiCtx, "左耳觸碰感測器", "左耳触控传感器", "Left Touch Sensor"),
                                loc(uiCtx, "指派功能", "分配功能", "Assigned Function"),
                                touchLabels, null, sel, idx -> {
                                    chosenLeft[0] = TOUCH_PRESET_IDS[idx];
                                    if (leftValRef[0] != null) leftValRef[0].setText(touchLabels[idx]);
                                    ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                                    String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                                    ConfigStore.sendTouchSensorCommandBroadcast(cmdCtx, activeMac, chosenLeft[0], chosenRight[0]);
                                    if (leftValRef[0] != null) {
                                        Runnable syncTouch = () -> {
                                            ConfigStore.HeadsetState latest = ConfigStore.readGlobalState(cmdCtx);
                                            if (latest.supportsTouchSensor) {
                                                chosenLeft[0] = latest.touchLeftPreset;
                                                chosenRight[0] = latest.touchRightPreset;
                                                if (leftValRef[0] != null) leftValRef[0].setText(getTouchPresetLabel(uiCtx, chosenLeft[0]));
                                                if (rightValRef[0] != null) rightValRef[0].setText(getTouchPresetLabel(uiCtx, chosenRight[0]));
                                            }
                                        };
                                        leftValRef[0].postDelayed(syncTouch, 600L);
                                        leftValRef[0].postDelayed(syncTouch, 1400L);
                                    }
                                    if (onDispatched != null) onDispatched.onUpdated();
                                });
                    });

            rightValRef[0] = addJumpRow(uiCtx, touchCard, pal,
                    loc(uiCtx, "右耳", "右耳", "Right Earbud"),
                    null,
                    getTouchPresetLabel(uiCtx, chosenRight[0]), false, v -> {
                        int sel = indexOfInt(TOUCH_PRESET_IDS, chosenRight[0]);
                        pushChoiceSubPage(
                                uiCtx, pal, hostStage, cardColor,
                                loc(uiCtx, "右耳觸碰感測器", "右耳触控传感器", "Right Touch Sensor"),
                                loc(uiCtx, "指派功能", "分配功能", "Assigned Function"),
                                touchLabels, null, sel, idx -> {
                                    chosenRight[0] = TOUCH_PRESET_IDS[idx];
                                    if (rightValRef[0] != null) rightValRef[0].setText(touchLabels[idx]);
                                    ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                                    String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                                    ConfigStore.sendTouchSensorCommandBroadcast(cmdCtx, activeMac, chosenLeft[0], chosenRight[0]);
                                    if (rightValRef[0] != null) {
                                        Runnable syncTouch = () -> {
                                            ConfigStore.HeadsetState latest = ConfigStore.readGlobalState(cmdCtx);
                                            if (latest.supportsTouchSensor) {
                                                chosenLeft[0] = latest.touchLeftPreset;
                                                chosenRight[0] = latest.touchRightPreset;
                                                if (leftValRef[0] != null) leftValRef[0].setText(getTouchPresetLabel(uiCtx, chosenLeft[0]));
                                                if (rightValRef[0] != null) rightValRef[0].setText(getTouchPresetLabel(uiCtx, chosenRight[0]));
                                            }
                                        };
                                        rightValRef[0].postDelayed(syncTouch, 600L);
                                        rightValRef[0].postDelayed(syncTouch, 1400L);
                                    }
                                    if (onDispatched != null) onDispatched.onUpdated();
                                });
                    });
        }

        // 2. Speak-to-Chat + Level-3 Donut Radio Cards
        if (st.supportsSpeakToChat) {
            addPageSectionHeader(uiCtx, page.body, pal,
                    loc(uiCtx, "說話至聊天", "说话至聊天", "Speak-to-Chat"), firstSection);
            firstSection = false;
            LinearLayout speakCard = createPageCard(uiCtx, pal, cardColor);
            page.body.addView(speakCard, matchWrapParams(uiCtx, 0));

            final int[] chosenSens = new int[]{clampRange(st.speakSensitivity, 0, 2)};
            final int[] chosenOut = new int[]{clampRange(st.speakTimeout, 0, 3)};
            final TextView[] sensValRef = new TextView[1];
            final TextView[] outValRef = new TextView[1];
            final CouiToggleSwitch[] speakSwRef = new CouiToggleSwitch[1];

            speakSwRef[0] = addToggleRow(uiCtx, speakCard, pal,
                    loc(uiCtx, "說話至聊天", "说话至聊天", "Speak-to-Chat"),
                    loc(uiCtx, "開口說話時自動切換環境音並暫停播放",
                            "开口说话时自动切换环境音并暂停播放",
                            "Pause audio and enable ambient while talking"),
                    st.speakToChat, true, val -> {
                        ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                        String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                        ConfigStore.sendCommandBroadcast(cmdCtx, ConfigStore.CMD_SET_SPEAK_TO_CHAT,
                                activeMac, latestSt.ancMode, latestSt.asmLevel, latestSt.voiceFocus, val);
                        scheduleToggleSync(speakSwRef[0], cmdCtx, s -> s.speakToChat);
                        if (onDispatched != null) onDispatched.onUpdated();
                    });

            String[] sensOptions = new String[]{
                    loc(uiCtx, "自動", "自动", "Auto"),
                    loc(uiCtx, "高靈敏度", "高灵敏度", "High"),
                    loc(uiCtx, "低靈敏度", "低灵敏度", "Low")
            };
            sensValRef[0] = addJumpRow(uiCtx, speakCard, pal,
                    loc(uiCtx, "語音偵測靈敏度", "语音检测灵敏度", "Voice Sensitivity"),
                    null,
                    sensOptions[chosenSens[0]], true, v ->
                            pushChoiceSubPage(
                                    uiCtx, pal, hostStage, cardColor,
                                    loc(uiCtx, "語音偵測靈敏度", "语音检测灵敏度", "Voice Sensitivity"),
                                    loc(uiCtx, "靈敏度", "灵敏度", "Sensitivity"),
                                    sensOptions, null, chosenSens[0], idx -> {
                                        chosenSens[0] = idx;
                                        if (sensValRef[0] != null) sensValRef[0].setText(sensOptions[idx]);
                                        ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                                        String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                                        ConfigStore.sendSpeakDetailCommandBroadcast(cmdCtx, activeMac, chosenSens[0], chosenOut[0]);
                                        if (onDispatched != null) onDispatched.onUpdated();
                                    }));

            String[] timeoutOptions = new String[]{
                    loc(uiCtx, "短 (5 秒)", "短 (5 秒)", "Short (5 sec)"),
                    loc(uiCtx, "標準 (15 秒)", "标准 (15 秒)", "Standard (15 sec)"),
                    loc(uiCtx, "長 (30 秒)", "长 (30 秒)", "Long (30 sec)"),
                    loc(uiCtx, "不關閉", "不关闭", "Do not close")
            };
            outValRef[0] = addJumpRow(uiCtx, speakCard, pal,
                    loc(uiCtx, "自動關閉時間", "自动关闭时间", "Mode Closure Time"),
                    null,
                    timeoutOptions[chosenOut[0]], false, v ->
                            pushChoiceSubPage(
                                    uiCtx, pal, hostStage, cardColor,
                                    loc(uiCtx, "自動關閉時間", "自动关闭时间", "Mode Closure Time"),
                                    loc(uiCtx, "停止說話後關閉時間", "停止说话后关闭时间", "Close After"),
                                    timeoutOptions, null, chosenOut[0], idx -> {
                                        chosenOut[0] = idx;
                                        if (outValRef[0] != null) outValRef[0].setText(timeoutOptions[idx]);
                                        ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                                        String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                                        ConfigStore.sendSpeakDetailCommandBroadcast(cmdCtx, activeMac, chosenSens[0], chosenOut[0]);
                                        if (onDispatched != null) onDispatched.onUpdated();
                                    }));
        }

        // 3. Wear Detection
        if (st.supportsWearDetect) {
            addPageSectionHeader(uiCtx, page.body, pal,
                    loc(uiCtx, "佩戴偵測", "佩戴检测", "Wear Detection"), firstSection);
            LinearLayout wearCard = createPageCard(uiCtx, pal, cardColor);
            page.body.addView(wearCard, matchWrapParams(uiCtx, 0));

            final CouiToggleSwitch[] wearSwRef = new CouiToggleSwitch[1];
            wearSwRef[0] = addToggleRow(uiCtx, wearCard, pal,
                    loc(uiCtx, "取下耳機時自動暫停", "摘下耳机时自动暂停", "Pause When Removed"),
                    loc(uiCtx, "取下耳機自動暫停播放，戴回後繼續播放",
                            "摘下耳机自动暂停播放，戴回后恢复播放",
                            "Pause playback when headphones are taken off"),
                    st.wearAutoPause, false, val -> {
                        ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                        String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                        ConfigStore.sendCommandBroadcast(cmdCtx, ConfigStore.CMD_SET_WEAR_DETECT,
                                activeMac, latestSt.ancMode, latestSt.asmLevel, latestSt.voiceFocus, val);
                        scheduleToggleSync(wearSwRef[0], cmdCtx, s -> s.wearAutoPause);
                        if (onDispatched != null) onDispatched.onUpdated();
                    });
        }

        pushStagePage(hostStage, page.root);
    }

    public static void showConnectionAndMultipointSheet(Context uiCtx, Context cmdCtx, CouiBottomSheet.Palette pal,
                                                        OnStateDispatched onDispatched) {
        showConnectionAndMultipointSheet(uiCtx, cmdCtx, pal, HostStage.fromContext(uiCtx), onDispatched);
    }

    public static void showConnectionAndMultipointSheet(Context uiCtx, Context cmdCtx, CouiBottomSheet.Palette pal,
                                                        HostStage stage, OnStateDispatched onDispatched) {
        if (stage == null) stage = HostStage.fromContext(uiCtx);
        if (stage == null || stage.container == null) return;
        final HostStage hostStage = stage;

        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(cmdCtx);
        ConfigStore.CodecState cs = ConfigStore.readGlobalCodecState(cmdCtx);
        int cardColor = resolveCardSurfaceColor(uiCtx, pal, hostStage);

        StagePage page = createStagePage(
                uiCtx, pal, hostStage, cardColor,
                loc(uiCtx, "連線與多設備", "连接与多设备", "Connection & Multipoint"));

        boolean firstSection = true;

        // 1. Multipoint Switch + Paired Device List
        if (st.supportsMultipoint) {
            addPageSectionHeader(uiCtx, page.body, pal,
                    loc(uiCtx, "多設備連接", "多设备连接", "Multipoint Connection"), firstSection);
            firstSection = false;
            LinearLayout switchCard = createPageCard(uiCtx, pal, cardColor);
            page.body.addView(switchCard, matchWrapParams(uiCtx, 0));

            final CouiToggleSwitch[] multiSwRef = new CouiToggleSwitch[1];
            multiSwRef[0] = addToggleRow(uiCtx, switchCard, pal,
                    loc(uiCtx, "同時連接 2 台裝置", "同时连接 2 台设备", "Connect to 2 devices simultaneously"),
                    loc(uiCtx, "在手機、平板或電腦間自動切換音訊輸入",
                            "在手机、平板或电脑间自动切换音频输入",
                            "Keep audio active across two paired Bluetooth devices"),
                    st.multipointEnabled, false, val -> {
                        ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                        String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                        ConfigStore.sendCommandBroadcast(cmdCtx, ConfigStore.CMD_SET_MULTIPOINT,
                                activeMac, latestSt.ancMode, latestSt.asmLevel, latestSt.voiceFocus, val);
                        scheduleToggleSync(multiSwRef[0], cmdCtx, s -> s.multipointEnabled);
                        if (onDispatched != null) onDispatched.onUpdated();
                    });

            List<MultipointDevice> devices = parseMultipointDevices(st.multipointDevices);
            if (!devices.isEmpty()) {
                addPageSectionHeader(uiCtx, page.body, pal,
                        loc(uiCtx, "已配對裝置", "已配对设备", "Paired Devices"), false);
                LinearLayout listCard = createPageCard(uiCtx, pal, cardColor);
                page.body.addView(listCard, matchWrapParams(uiCtx, 0));

                final List<MultipointRowHolder> holders = new ArrayList<>();
                final long[] pollDelays = new long[]{700L, 1600L, 3200L, 5500L, 8500L};
                for (int i = 0; i < devices.size(); i++) {
                    final MultipointDevice dev = devices.get(i);
                    final MultipointRowHolder holder = addMultipointDeviceRow(
                            uiCtx, listCard, pal, dev, i < devices.size() - 1);
                    holders.add(holder);

                    View.OnClickListener clickAction = v -> {
                        if (holder.busy) return;
                        final boolean wantConnect = !holder.connected;
                        final int myGen = ++holder.attemptGen;
                        holder.busy = true;
                        holder.targetConnect = wantConnect;
                        holder.overrideStatus = null;
                        holder.refreshVisual.run();

                        ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                        String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                        int action = wantConnect
                                ? SonyTandemProtocol.PERI_ACTION_CONNECT
                                : SonyTandemProtocol.PERI_ACTION_DISCONNECT;
                        ConfigStore.sendMultipointActionBroadcast(cmdCtx, activeMac, dev.mac, action);

                        for (int pIdx = 0; pIdx < pollDelays.length; pIdx++) {
                            final boolean isFinal = (pIdx == pollDelays.length - 1);
                            listCard.postDelayed(() -> {
                                if (!listCard.isAttachedToWindow()) return;
                                ConfigStore.HeadsetState polled = ConfigStore.readGlobalState(cmdCtx);
                                List<MultipointDevice> polledList = parseMultipointDevices(polled.multipointDevices);
                                MultipointDevice matchedTarget = null;
                                for (MultipointRowHolder h : holders) {
                                    MultipointDevice found = findMultipointDevice(polledList, h.mac);
                                    if (found != null) {
                                        if (h == holder) {
                                            matchedTarget = found;
                                        } else if (!h.busy && h.connected != found.connected) {
                                            h.connected = found.connected;
                                            h.refreshVisual.run();
                                        }
                                    }
                                }
                                if (holder.attemptGen != myGen || !holder.busy) return;
                                if (matchedTarget != null && matchedTarget.connected == wantConnect) {
                                    holder.connected = matchedTarget.connected;
                                    holder.busy = false;
                                    holder.overrideStatus = null;
                                    holder.refreshVisual.run();
                                    if (onDispatched != null) onDispatched.onUpdated();
                                    return;
                                }
                                if (isFinal) {
                                    if (matchedTarget != null) {
                                        holder.connected = matchedTarget.connected;
                                    }
                                    holder.busy = false;
                                    holder.overrideStatus = wantConnect
                                            ? loc(uiCtx, "裝置未回應，可能未開機或不在附近",
                                                    "设备未响应，可能未开机或不在附近",
                                                    "No response (device may be offline)")
                                            : loc(uiCtx, "無法中斷連線", "无法断开连接", "Could not disconnect");
                                    holder.refreshVisual.run();
                                    listCard.postDelayed(() -> {
                                        if (listCard.isAttachedToWindow() && holder.attemptGen == myGen && !holder.busy) {
                                            holder.overrideStatus = null;
                                            holder.refreshVisual.run();
                                        }
                                    }, 2800L);
                                    if (onDispatched != null) onDispatched.onUpdated();
                                }
                            }, pollDelays[pIdx]);
                        }
                    };
                    holder.row.setOnClickListener(clickAction);
                    holder.actionBtn.setOnClickListener(clickAction);
                }
            }
        }

        // 2. Unified Audio Codec & Bluetooth Connection Quality
        boolean codecAvail = isCodecAvailable(st, cs, st.mac);
        if (st.supportsConnectionMode || codecAvail) {
            addPageSectionHeader(uiCtx, page.body, pal,
                    loc(uiCtx, "音訊與連線", "音频与连接", "Audio & Connection"), firstSection);
            LinearLayout qualCard = createPageCard(uiCtx, pal, cardColor);
            page.body.addView(qualCard, matchWrapParams(uiCtx, 0));

            final boolean[] isStable = new boolean[]{
                    codecAvail ? (cs.activeCodec == ConfigStore.CODEC_AAC) : (st.connectionPriorMode == 1)
            };
            final TextView[] qualityValRef = new TextView[1];

            String[] optionTitles = new String[]{
                    loc(uiCtx, "音質優先 (LDAC)", "音质优先 (LDAC)", "Sound Quality Priority (LDAC)"),
                    loc(uiCtx, "穩定連線優先 (AAC)", "稳定连接优先 (AAC)", "Stable Connection Priority (AAC)")
            };
            String[] optionSubtitles = new String[]{
                    loc(uiCtx, "優先使用 LDAC 高解析度傳輸，提供最佳音質",
                            "优先使用 LDAC 高解析度传输，提供最佳音质",
                            "Prioritize high-resolution LDAC audio playback"),
                    loc(uiCtx, "切換為 AAC 編碼，減少干擾並提升連線穩定度",
                            "切换为 AAC 编码，减少干扰并提升连接稳定性",
                            "Switch to AAC codec for maximum wireless stability")
            };

            qualityValRef[0] = addJumpRow(uiCtx, qualCard, pal,
                    loc(uiCtx, "音訊編碼與連線品質", "音频编码与连接质量", "Codec & Connection Quality"),
                    null,
                    formatUnifiedQualityLabel(uiCtx, isStable[0], codecAvail), false,
                    v -> pushChoiceSubPage(
                            uiCtx, pal, hostStage, cardColor,
                            loc(uiCtx, "音訊編碼與連線品質", "音频编码与连接质量", "Codec & Connection Quality"),
                            loc(uiCtx, "連線品質模式", "连接质量模式", "Connection Mode"),
                            optionTitles, optionSubtitles,
                            isStable[0] ? 1 : 0, idx -> {
                                isStable[0] = (idx == 1);
                                if (qualityValRef[0] != null) {
                                    qualityValRef[0].setText(formatUnifiedQualityLabel(uiCtx, isStable[0], codecAvail));
                                }
                                if (codecAvail) {
                                    String targetMac = !cs.mac.isEmpty() ? cs.mac : st.mac;
                                    int codec = isStable[0] ? ConfigStore.CODEC_AAC : ConfigStore.CODEC_LDAC;
                                    ConfigStore.sendCodecCommandBroadcast(cmdCtx, targetMac, codec);
                                } else if (st.supportsConnectionMode) {
                                    ConfigStore.sendIntCommandBroadcast(
                                            cmdCtx, ConfigStore.CMD_SET_CONNECTION_MODE,
                                            st.mac, isStable[0] ? 1 : 0);
                                }
                                if (onDispatched != null) onDispatched.onUpdated();
                            }));
        }

        pushStagePage(hostStage, page.root);
    }

    public static void showPowerAndSystemSheet(Context uiCtx, Context cmdCtx, CouiBottomSheet.Palette pal,
                                               OnStateDispatched onDispatched) {
        showPowerAndSystemSheet(uiCtx, cmdCtx, pal, HostStage.fromContext(uiCtx), onDispatched);
    }

    public static void showPowerAndSystemSheet(Context uiCtx, Context cmdCtx, CouiBottomSheet.Palette pal,
                                               HostStage stage, OnStateDispatched onDispatched) {
        if (stage == null) stage = HostStage.fromContext(uiCtx);
        if (stage == null || stage.container == null) return;
        final HostStage hostStage = stage;

        ConfigStore.HeadsetState st = ConfigStore.readGlobalState(cmdCtx);
        int cardColor = resolveCardSurfaceColor(uiCtx, pal, hostStage);

        int[] elements = (st.autoPowerOffInquiredType == SonyTandemProtocol.POWER_INQ_AUTO_POWER_OFF_WEARING)
                ? AUTO_POWER_OFF_ELEMENTS_WEAR
                : AUTO_POWER_OFF_ELEMENTS_BASIC;
        String[] labels = new String[elements.length];
        int selIdx = 0;
        for (int i = 0; i < elements.length; i++) {
            labels[i] = getAutoPowerOffLabel(uiCtx, elements[i]);
            if (elements[i] == st.autoPowerOffElement) selIdx = i;
        }

        pushChoiceSubPage(
                uiCtx, pal, hostStage, cardColor,
                loc(uiCtx, "自動關機", "自动关机", "Auto Power Off"),
                loc(uiCtx, "自動關閉時間", "自动关闭时间", "Power Off Timer"),
                labels, null, selIdx, idx -> {
                    int elem = elements[idx];
                    ConfigStore.HeadsetState latestSt = ConfigStore.readGlobalState(cmdCtx);
                    String activeMac = !latestSt.mac.isEmpty() ? latestSt.mac : st.mac;
                    ConfigStore.sendIntCommandBroadcast(cmdCtx, ConfigStore.CMD_SET_AUTO_POWER_OFF, activeMac, elem);
                    if (onDispatched != null) onDispatched.onUpdated();
                });
    }

    // ==================== Same-Layer Stage Stack Management ====================

    private static final class StagePage {
        final ViewGroup root;
        final LinearLayout body;

        StagePage(ViewGroup root, LinearLayout body) {
            this.root = root;
            this.body = body;
        }
    }

    public static boolean hasActiveStage(Context ctx) {
        HostStage stage = HostStage.fromContext(ctx);
        if (stage == null || stage.container == null) return false;
        synchronized (STAGE_STATES) {
            StageState st = STAGE_STATES.get(stage.container);
            return st != null && !st.stack.isEmpty();
        }
    }

    public static void resetHostStage(HostStage stage) {
        if (stage == null || stage.container == null) return;
        StageState st;
        synchronized (STAGE_STATES) {
            st = STAGE_STATES.remove(stage.container);
        }
        if (st != null) {
            for (int i = st.stack.size() - 1; i >= 0; i--) {
                View v = st.stack.get(i);
                if (v != null) {
                    v.animate().cancel();
                    try {
                        stage.container.removeView(v);
                    } catch (Throwable ignored) {
                    }
                }
            }
            st.stack.clear();
            unregisterBackCallback(st);
        }
        if (stage.level1Content != null) {
            stage.level1Content.animate().cancel();
            stage.level1Content.setTranslationX(0f);
            stage.level1Content.setAlpha(1f);
            stage.level1Content.setVisibility(View.VISIBLE);
        }
    }

    private static StagePage createStagePage(Context uiCtx, CouiBottomSheet.Palette pal,
                                             HostStage stage, int cardColor, String title) {
        float d = uiCtx.getResources().getDisplayMetrics().density;
        boolean embedded = stage.embeddedPanel;

        ProgressiveBlurFrameLayout root = new ProgressiveBlurFrameLayout(uiCtx);
        root.setClickable(true);
        root.setFocusable(true);
        root.setFocusableInTouchMode(true);

        if (embedded) {
            // Transparent root inside mHostBinding.a so it shares Level-1's exact panel blur & background layer
            root.setBackgroundColor(Color.TRANSPARENT);
            final int topRadiusPx = Math.round(28f * d);
            root.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    int w = view.getWidth();
                    int h = view.getHeight();
                    if (w > 0 && h > 0) {
                        outline.setRoundRect(0, 0, w, h + topRadiusPx, topRadiusPx);
                    }
                }
            });
            root.setClipToOutline(true);
        } else {
            root.setBackgroundColor(resolveFullScreenPageBg(uiCtx, pal));
        }

        int statusInset = embedded ? 0 : resolveStatusBarFallbackPx(uiCtx);
        int headerPadH = Math.round(14f * d);
        int headerPadTop = statusInset + Math.round((embedded ? 6f : 10f) * d);
        int headerPadBot = Math.round((embedded ? 6f : 8f) * d);
        int btnBoxSize = Math.round(52f * d); // 42dp circle + 5dp shadow margin each side
        int headerHeightPx = headerPadTop + btnBoxSize + headerPadBot;
        int blurExtraPx = Math.round((embedded ? 8f : 22f) * d);

        LinearLayout body = new LinearLayout(uiCtx);
        body.setOrientation(LinearLayout.VERTICAL);

        CouiBounceScrollView scrollView = new CouiBounceScrollView(uiCtx);
        scrollView.setDisallowParentOnDown(embedded);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setFillViewport(true);
        scrollView.setClipToPadding(false);
        int scrollPadH = Math.round(16f * d);
        int navBottomFallback = embedded ? Math.round(28f * d) : (resolveNavBarFallbackPx(uiCtx) + Math.round(28f * d));
        scrollView.setPadding(scrollPadH, headerHeightPx + Math.round((embedded ? 8f : 4f) * d), scrollPadH, navBottomFallback);

        scrollView.addView(body, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        FrameLayout contentContainer = new FrameLayout(uiCtx);
        contentContainer.addView(scrollView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(contentContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TopGradientBlurView topBlur = new TopGradientBlurView(uiCtx);
        topBlur.setClickable(false);
        topBlur.setFocusable(false);
        topBlur.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        topBlur.setStatusBarTopPx(statusInset);
        topBlur.setSurfaceColor(embedded
                ? (pal.dark ? 0xFF1C1C1E : 0xFFEFEFEF)
                : resolveFullScreenPageBg(uiCtx, pal));
        root.addView(topBlur, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, headerHeightPx + blurExtraPx, Gravity.TOP));

        root.bindProgressiveBlur(contentContainer, topBlur);
        scrollView.getViewTreeObserver().addOnScrollChangedListener(topBlur::notifyTargetInvalidated);
        scrollView.setOnOverscrollListener(translationY -> topBlur.notifyTargetInvalidated());

        // Top header bar: floating circular back button + bold title (non-collapsing)
        LinearLayout headerWrap = new LinearLayout(uiCtx);
        headerWrap.setOrientation(LinearLayout.HORIZONTAL);
        headerWrap.setGravity(Gravity.CENTER_VERTICAL);
        headerWrap.setPadding(headerPadH, headerPadTop, Math.round(20f * d), headerPadBot);

        int backBtnFill = pal.dark ? 0xFF28282C : (Color.alpha(cardColor) >= 0xE0 ? cardColor : 0xFFFFFFFF);
        FloatingBackButtonView backBtn = new FloatingBackButtonView(uiCtx, pal, backBtnFill);
        backBtn.setOnClickListener(v -> popStagePage(stage));
        headerWrap.addView(backBtn, new LinearLayout.LayoutParams(btnBoxSize, btnBoxSize));

        TextView tvTitle = new TextView(uiCtx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(pal.textPrimary);
        tvTitle.setSingleLine(true);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginStart(Math.round(9f * d));
        headerWrap.addView(tvTitle, titleLp);

        root.addView(headerWrap, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        if (!embedded) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                int top = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()).top
                        : insets.getSystemWindowInsetTop();
                int bottom = Build.VERSION.SDK_INT >= 30
                        ? insets.getInsets(WindowInsets.Type.systemBars()).bottom
                        : insets.getSystemWindowInsetBottom();
                int actualTop = top > 0 ? top : statusInset;
                int newHeaderTopPad = actualTop + Math.round(10f * d);
                int newHeaderH = newHeaderTopPad + btnBoxSize + headerPadBot;
                headerWrap.setPadding(headerPadH, newHeaderTopPad, Math.round(20f * d), headerPadBot);
                scrollView.setPadding(scrollPadH, newHeaderH + Math.round(4f * d), scrollPadH, bottom + Math.round(28f * d));
                topBlur.setStatusBarTopPx(actualTop);
                ViewGroup.LayoutParams blurLp = topBlur.getLayoutParams();
                if (blurLp != null && blurLp.height != newHeaderH + blurExtraPx) {
                    blurLp.height = newHeaderH + blurExtraPx;
                    topBlur.setLayoutParams(blurLp);
                }
                topBlur.notifyTargetInvalidated();
                return insets;
            });
        }

        root.setOnKeyListener((v, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                popStagePage(stage);
                return true;
            }
            return false;
        });

        return new StagePage(root, body);
    }

    private static void pushStagePage(HostStage stage, View newPage) {
        if (stage == null || stage.container == null || newPage == null) return;
        StageState st;
        synchronized (STAGE_STATES) {
            st = STAGE_STATES.get(stage.container);
            if (st == null) {
                st = new StageState();
                STAGE_STATES.put(stage.container, st);
            }
        }

        final View outgoing = st.stack.isEmpty() ? stage.level1Content : st.stack.get(st.stack.size() - 1);
        st.stack.add(newPage);
        ensureBackCallbackRegistered(stage, st);

        int width = stage.container.getWidth();
        if (width <= 0) {
            width = stage.container.getResources().getDisplayMetrics().widthPixels;
        }
        final float slideW = Math.max(1, width);

        newPage.setTranslationX(slideW);
        newPage.setAlpha(0f);
        stage.container.addView(newPage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        newPage.requestApplyInsets();
        newPage.requestFocus();

        if (outgoing != null) {
            outgoing.animate().cancel();
            outgoing.animate()
                    .translationX(-slideW * 0.25f)
                    .alpha(0f)
                    .setDuration(380L)
                    .setInterpolator(PAGE_PUSH_EASE)
                    .withEndAction(() -> {
                        if (outgoing != null) {
                            outgoing.setVisibility(View.INVISIBLE);
                        }
                    })
                    .start();
        }

        newPage.animate()
                .translationX(0f)
                .alpha(1f)
                .setDuration(420L)
                .setInterpolator(PAGE_PUSH_EASE)
                .start();
    }

    public static boolean popStagePage(HostStage stage) {
        if (stage == null || stage.container == null) return false;
        StageState st;
        synchronized (STAGE_STATES) {
            st = STAGE_STATES.get(stage.container);
        }
        if (st == null || st.stack.isEmpty()) return false;

        final View top = st.stack.remove(st.stack.size() - 1);
        final View incoming = st.stack.isEmpty() ? stage.level1Content : st.stack.get(st.stack.size() - 1);
        if (st.stack.isEmpty()) {
            unregisterBackCallback(st);
        }

        int width = stage.container.getWidth();
        if (width <= 0) {
            width = stage.container.getResources().getDisplayMetrics().widthPixels;
        }
        final float slideW = Math.max(1, width);

        if (incoming != null) {
            incoming.animate().cancel();
            incoming.setVisibility(View.VISIBLE);
            incoming.setTranslationX(-slideW * 0.25f);
            incoming.setAlpha(0f);
            incoming.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(380L)
                    .setInterpolator(PAGE_PUSH_EASE)
                    .start();
            incoming.requestFocus();
        }

        top.animate().cancel();
        top.animate()
                .translationX(slideW)
                .alpha(0f)
                .setDuration(360L)
                .setInterpolator(PAGE_POP_EASE)
                .withEndAction(() -> {
                    try {
                        stage.container.removeView(top);
                    } catch (Throwable ignored) {
                    }
                })
                .start();
        return true;
    }

    private static void ensureBackCallbackRegistered(HostStage stage, StageState st) {
        if (Build.VERSION.SDK_INT < 33 || st.backInvokedCallback != null) return;
        try {
            OnBackInvokedDispatcher disp = null;
            if (stage.hostDialog != null) {
                disp = stage.hostDialog.getOnBackInvokedDispatcher();
            } else if (stage.hostActivity != null) {
                disp = stage.hostActivity.getOnBackInvokedDispatcher();
            }
            if (disp != null) {
                OnBackInvokedCallback cb = () -> popStagePage(stage);
                disp.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
                st.backInvokedCallback = cb;
                st.backDispatcherOwner = disp;
            }
        } catch (Throwable ignored) {
        }
    }

    private static void unregisterBackCallback(StageState st) {
        if (Build.VERSION.SDK_INT < 33 || st == null || st.backInvokedCallback == null) return;
        try {
            if (st.backDispatcherOwner instanceof OnBackInvokedDispatcher
                    && st.backInvokedCallback instanceof OnBackInvokedCallback) {
                ((OnBackInvokedDispatcher) st.backDispatcherOwner)
                        .unregisterOnBackInvokedCallback((OnBackInvokedCallback) st.backInvokedCallback);
            }
        } catch (Throwable ignored) {
        }
        st.backInvokedCallback = null;
        st.backDispatcherOwner = null;
    }

    // ==================== Level-3 Donut-Radio Card Page (Reference Screenshot 1) ====================

    private static void pushChoiceSubPage(Context uiCtx, CouiBottomSheet.Palette pal,
                                          HostStage stage, int cardColor,
                                          String pageTitle, String sectionLabel,
                                          String[] options, String[] subtitles,
                                          int selectedIdx, CouiBottomSheet.IntCallback onSelect) {
        float d = uiCtx.getResources().getDisplayMetrics().density;
        StagePage sub = createStagePage(uiCtx, pal, stage, cardColor, pageTitle);

        if (sectionLabel != null && !sectionLabel.isEmpty()) {
            addPageSectionHeader(uiCtx, sub.body, pal, sectionLabel, true);
        }

        LinearLayout card = createPageCard(uiCtx, pal, cardColor);
        sub.body.addView(card, matchWrapParams(uiCtx, (sectionLabel != null && !sectionLabel.isEmpty()) ? 0 : 4));

        final CouiDonutRadioView[] radios = new CouiDonutRadioView[options.length];
        for (int i = 0; i < options.length; i++) {
            final int idx = i;
            String itemSub = (subtitles != null && i < subtitles.length) ? subtitles[i] : null;
            boolean checked = (i == selectedIdx);

            LinearLayout row = new LinearLayout(uiCtx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setClickable(true);
            row.setFocusable(true);
            row.setMinimumHeight(Math.round(58f * d));
            row.setBackground(CouiBottomSheet.createRowBackground(pal.ripple, 0));
            row.setPadding(Math.round(20f * d), Math.round(15f * d), Math.round(20f * d), Math.round(15f * d));

            LinearLayout textCol = new LinearLayout(uiCtx);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView tvTitle = new TextView(uiCtx);
            tvTitle.setText(options[i]);
            tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
            tvTitle.setTextColor(pal.textPrimary);
            textCol.addView(tvTitle);

            if (itemSub != null && !itemSub.isEmpty()) {
                TextView tvSub = new TextView(uiCtx);
                tvSub.setText(itemSub);
                tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                tvSub.setTextColor(pal.textSecondary);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.topMargin = Math.round(3f * d);
                textCol.addView(tvSub, slp);
            }

            LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            colLp.setMarginEnd(Math.round(14f * d));
            row.addView(textCol, colLp);

            CouiDonutRadioView radio = new CouiDonutRadioView(uiCtx, pal, checked);
            radios[i] = radio;
            int radioSize = Math.round(22f * d);
            row.addView(radio, new LinearLayout.LayoutParams(radioSize, radioSize));

            row.setOnClickListener(v -> {
                for (int k = 0; k < radios.length; k++) {
                    radios[k].setChecked(k == idx);
                }
                if (onSelect != null) {
                    onSelect.onSelected(idx);
                }
            });

            card.addView(row, matchWrapParams(uiCtx, 0));
            if (i < options.length - 1) {
                addCardDivider(uiCtx, card, pal);
            }
        }

        pushStagePage(stage, sub.root);
    }

    // ==================== Reusable COUI 17 Card & Row Builders ====================

    private static void addPageSectionHeader(Context ctx, LinearLayout parent, CouiBottomSheet.Palette pal,
                                             String text, boolean first) {
        float d = ctx.getResources().getDisplayMetrics().density;
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setTextColor(pal.textSecondary);
        tv.setPadding(Math.round(16f * d), 0, Math.round(16f * d), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round((first ? 4f : 18f) * d);
        lp.bottomMargin = Math.round(9f * d);
        parent.addView(tv, lp);
    }

    private static LinearLayout createPageCard(Context ctx, CouiBottomSheet.Palette pal, int cardColor) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(cardColor);
        bg.setCornerRadius(24f * d);
        if (pal.dark) {
            bg.setStroke(Math.max(1, Math.round(0.5f * d)), 0x14FFFFFF);
        }
        card.setBackground(bg);
        card.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);
        card.setElevation(0f);
        return card;
    }

    private static void addCardDivider(Context ctx, LinearLayout parent, CouiBottomSheet.Palette pal) {
        float d = ctx.getResources().getDisplayMetrics().density;
        View div = new View(ctx);
        div.setBackgroundColor(pal.dark ? 0x16FFFFFF : 0x0F000000);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, Math.round(0.5f * d)));
        lp.setMarginStart(Math.round(20f * d));
        lp.setMarginEnd(Math.round(20f * d));
        parent.addView(div, lp);
    }

    private static CouiToggleSwitch addToggleRow(Context ctx, LinearLayout group, CouiBottomSheet.Palette pal,
                                                 String title, String subtitle, boolean checked,
                                                 boolean addBottomDivider, BoolListener listener) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Math.round(58f * d));
        row.setBackground(CouiBottomSheet.createRowBackground(pal.ripple, 0));
        row.setPadding(Math.round(20f * d), Math.round(14f * d), Math.round(18f * d), Math.round(14f * d));

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setTextColor(pal.textPrimary);
        textCol.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tvSub.setLineSpacing(0f, 1.2f);
            tvSub.setMaxLines(2);
            tvSub.setEllipsize(TextUtils.TruncateAt.END);
            tvSub.setTextColor(pal.textSecondary);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Math.round(3f * d);
            textCol.addView(tvSub, slp);
        }

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginEnd(Math.round(14f * d));
        row.addView(textCol, colLp);

        CouiToggleSwitch sw = new CouiToggleSwitch(ctx, pal, checked);
        row.addView(sw, new LinearLayout.LayoutParams(Math.round(48f * d), Math.round(28f * d)));
        row.setOnClickListener(v -> {
            boolean next = !sw.isChecked();
            sw.setChecked(next, true);
            if (listener != null) listener.onChanged(next);
        });

        group.addView(row, matchWrapParams(ctx, 0));
        if (addBottomDivider) {
            addCardDivider(ctx, group, pal);
        }
        return sw;
    }

    private static void scheduleToggleSync(CouiToggleSwitch sw, Context cmdCtx, BoolExtractor extractor) {
        if (sw == null || extractor == null) return;
        Runnable check = () -> {
            if (!sw.isAttachedToWindow()) return;
            ConfigStore.HeadsetState latest = ConfigStore.readGlobalState(cmdCtx);
            if (!latest.connected) return;
            boolean actual = extractor.read(latest);
            if (sw.isChecked() != actual) {
                sw.setChecked(actual, true);
            }
        };
        sw.postDelayed(check, 550L);
        sw.postDelayed(check, 1300L);
    }

    private static TextView addJumpRow(Context ctx, LinearLayout group, CouiBottomSheet.Palette pal,
                                       String title, String subtitle, String valueLabel,
                                       boolean addBottomDivider, View.OnClickListener onClick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Math.round(58f * d));
        row.setBackground(CouiBottomSheet.createRowBackground(pal.ripple, 0));
        row.setPadding(Math.round(20f * d), Math.round(15f * d), Math.round(16f * d), Math.round(15f * d));
        row.setOnClickListener(onClick);

        LinearLayout textCol = new LinearLayout(ctx);
        textCol.setOrientation(LinearLayout.VERTICAL);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tvTitle.setTextColor(pal.textPrimary);
        textCol.addView(tvTitle);

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView tvSub = new TextView(ctx);
            tvSub.setText(subtitle);
            tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            tvSub.setTextColor(pal.textSecondary);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Math.round(3f * d);
            textCol.addView(tvSub, slp);
        }

        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.setMarginEnd(Math.round(10f * d));
        row.addView(textCol, titleLp);

        TextView tvVal = new TextView(ctx);
        tvVal.setText(valueLabel != null ? valueLabel : "");
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tvVal.setTextColor(pal.textSecondary);
        tvVal.setSingleLine(true);
        tvVal.setEllipsize(TextUtils.TruncateAt.END);
        tvVal.setMaxWidth(Math.round(156f * d));
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMarginEnd(Math.round(4f * d));
        row.addView(tvVal, vlp);

        int chevColor = (0x8A << 24) | (pal.textSecondary & 0x00FFFFFF);
        CouiChevronView chev = new CouiChevronView(ctx, chevColor);
        row.addView(chev, new LinearLayout.LayoutParams(Math.round(16f * d), Math.round(16f * d)));

        group.addView(row, matchWrapParams(ctx, 0));
        if (addBottomDivider) {
            addCardDivider(ctx, group, pal);
        }
        return tvVal;
    }

    private static TextView addSelectorRowWithUpDownChevron(Context ctx, LinearLayout group,
                                                            CouiBottomSheet.Palette pal,
                                                            String title, String valueLabel,
                                                            View.OnClickListener onClick) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Math.round(60f * d));
        row.setBackground(CouiBottomSheet.createRowBackground(pal.ripple, 0));
        row.setPadding(Math.round(20f * d), Math.round(16f * d), Math.round(18f * d), Math.round(16f * d));
        row.setOnClickListener(onClick);

        TextView tvTitle = new TextView(ctx);
        tvTitle.setText(title);
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tvTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        tvTitle.setTextColor(pal.textPrimary);
        row.addView(tvTitle, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvVal = new TextView(ctx);
        tvVal.setText(valueLabel != null ? valueLabel : "");
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        tvVal.setTextColor(pal.textSecondary);
        tvVal.setSingleLine(true);
        tvVal.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMarginEnd(Math.round(6f * d));
        row.addView(tvVal, vlp);

        int chevColor = (0x9A << 24) | (pal.textSecondary & 0x00FFFFFF);
        CouiUpDownChevronView upDown = new CouiUpDownChevronView(ctx, chevColor);
        row.addView(upDown, new LinearLayout.LayoutParams(Math.round(15f * d), Math.round(18f * d)));

        group.addView(row, matchWrapParams(ctx, 0));
        return tvVal;
    }

    private static final class MultipointRowHolder {
        final String mac;
        final boolean hasName;
        final LinearLayout row;
        final TextView actionBtn;
        boolean connected;
        boolean busy;
        boolean targetConnect;
        String overrideStatus;
        int attemptGen;
        Runnable refreshVisual;

        MultipointRowHolder(String mac, String name, boolean connected,
                            LinearLayout row, TextView actionBtn) {
            this.mac = mac;
            this.hasName = name != null && !name.isEmpty();
            this.connected = connected;
            this.row = row;
            this.actionBtn = actionBtn;
        }
    }

    private static MultipointDevice findMultipointDevice(List<MultipointDevice> list, String mac) {
        if (list == null || mac == null) return null;
        for (MultipointDevice d : list) {
            if (mac.equalsIgnoreCase(d.mac)) return d;
        }
        return null;
    }

    private static MultipointRowHolder addMultipointDeviceRow(Context ctx, LinearLayout group, CouiBottomSheet.Palette pal,
                                                              MultipointDevice dev, boolean addBottomDivider) {
        float d = ctx.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setClickable(true);
        row.setFocusable(true);
        row.setMinimumHeight(Math.round(62f * d));
        row.setBackground(CouiBottomSheet.createRowBackground(pal.ripple, 0));
        row.setPadding(Math.round(20f * d), Math.round(13f * d), Math.round(18f * d), Math.round(13f * d));

        LinearLayout col = new LinearLayout(ctx);
        col.setOrientation(LinearLayout.VERTICAL);

        boolean hasName = !dev.name.isEmpty();
        TextView nameTv = new TextView(ctx);
        nameTv.setText(hasName ? dev.name : dev.mac);
        nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        nameTv.setTextColor(pal.textPrimary);
        nameTv.setSingleLine(true);
        nameTv.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(nameTv);

        TextView statusTv = new TextView(ctx);
        statusTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stLp.topMargin = Math.round(2f * d);
        col.addView(statusTv, stLp);

        LinearLayout.LayoutParams colLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        colLp.setMarginEnd(Math.round(12f * d));
        row.addView(col, colLp);

        TextView actionBtn = new TextView(ctx);
        actionBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        actionBtn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        actionBtn.setGravity(Gravity.CENTER);
        actionBtn.setSingleLine(true);
        actionBtn.setMinWidth(Math.round(62f * d));
        actionBtn.setPadding(Math.round(14f * d), 0, Math.round(14f * d), 0);

        final MultipointRowHolder holder = new MultipointRowHolder(dev.mac, dev.name, dev.connected, row, actionBtn);
        holder.refreshVisual = () -> {
            if (holder.busy) {
                statusTv.setText(holder.targetConnect
                        ? loc(ctx, "連接中...", "连接中...", "Connecting...")
                        : loc(ctx, "中斷中...", "断开中...", "Disconnecting..."));
                statusTv.setTextColor(pal.textSecondary);
                actionBtn.setText(holder.targetConnect
                        ? loc(ctx, "連接中", "连接中", "Connecting")
                        : loc(ctx, "中斷中", "断开中", "Disconnecting"));
                actionBtn.setTextColor(pal.textSecondary);
                stylePillAction(actionBtn, pal.btnSecondaryBg, pal.ripple, d);
                row.setAlpha(0.75f);
            } else {
                row.setAlpha(1f);
                boolean hasHint = holder.overrideStatus != null && !holder.overrideStatus.isEmpty();
                if (hasHint) {
                    statusTv.setText(holder.overrideStatus);
                    statusTv.setTextColor(0xFFE5534B);
                } else if (holder.connected) {
                    statusTv.setText(holder.hasName
                            ? loc(ctx, "已連線", "已连接", "Connected")
                            : (loc(ctx, "已連線 · ", "已连接 · ", "Connected · ") + dev.mac));
                    statusTv.setTextColor(pal.accent);
                } else {
                    statusTv.setText(holder.hasName
                            ? loc(ctx, "未連線", "未连接", "Disconnected")
                            : (loc(ctx, "未連線 · ", "未连接 · ", "Disconnected · ") + dev.mac));
                    statusTv.setTextColor(pal.textSecondary);
                }
                if (holder.connected) {
                    actionBtn.setText(loc(ctx, "中斷", "断开", "Disconnect"));
                    actionBtn.setTextColor(pal.textPrimary);
                    stylePillAction(actionBtn, pal.btnSecondaryBg, pal.ripple, d);
                } else {
                    actionBtn.setText(loc(ctx, "連接", "连接", "Connect"));
                    actionBtn.setTextColor(pal.accent);
                    stylePillAction(actionBtn, pal.tonal, pal.ripple, d);
                }
            }
        };
        holder.refreshVisual.run();

        row.addView(actionBtn, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, Math.round(32f * d)));

        group.addView(row, matchWrapParams(ctx, 0));
        if (addBottomDivider) {
            addCardDivider(ctx, group, pal);
        }
        return holder;
    }

    private static void stylePillAction(TextView btn, int fillColor, int rippleColor, float d) {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.RECTANGLE);
        shape.setCornerRadius(16f * d);
        shape.setColor(fillColor);
        btn.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleColor), shape, shape));
    }

    // ==================== Drawn Vector & Interactive Equalizer Widgets ====================

    /**
     * Floating circular back button ({@code ←}) with soft ambient drop shadow in light mode
     * (and no shadow in dark mode), matching HeyMelody and ColorOS 17 sub-page headers.
     */
    private static final class FloatingBackButtonView extends View {
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint pressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint arrowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path arrowPath = new Path();
        private final boolean dark;
        private final float shadowInset;
        private boolean pressedState;

        FloatingBackButtonView(Context ctx, CouiBottomSheet.Palette pal, int fillColor) {
            super(ctx);
            this.dark = pal.dark;
            float d = ctx.getResources().getDisplayMetrics().density;
            this.shadowInset = 5f * d;

            circlePaint.setStyle(Paint.Style.FILL);
            circlePaint.setColor(fillColor);
            if (!dark) {
                circlePaint.setShadowLayer(5.5f * d, 0f, 2f * d, 0x16000000);
            }

            pressPaint.setStyle(Paint.Style.FILL);
            pressPaint.setColor(pal.ripple);

            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(Math.max(1f, 0.6f * d));
            strokePaint.setColor(dark ? 0x18FFFFFF : 0x08000000);

            arrowPaint.setStyle(Paint.Style.STROKE);
            arrowPaint.setStrokeCap(Paint.Cap.ROUND);
            arrowPaint.setStrokeJoin(Paint.Join.ROUND);
            arrowPaint.setStrokeWidth(1.9f * d);
            arrowPaint.setColor(pal.textPrimary);

            setClickable(true);
            setFocusable(true);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pressedState = true;
                animate().scaleX(0.92f).scaleY(0.92f).setDuration(100L).start();
                invalidate();
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pressedState = false;
                animate().scaleX(1f).scaleY(1f).setDuration(140L).start();
                invalidate();
            }
            return super.onTouchEvent(event);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            float cx = w * 0.5f;
            float cy = h * 0.5f;
            float r = Math.min(w, h) * 0.5f - shadowInset;
            if (r <= 0f) return;

            canvas.drawCircle(cx, cy, r, circlePaint);
            if (pressedState) {
                canvas.drawCircle(cx, cy, r, pressPaint);
            }
            canvas.drawCircle(cx, cy, r, strokePaint);

            float span = r * 0.36f;
            float wing = r * 0.28f;
            arrowPath.reset();
            arrowPath.moveTo(cx - span + wing, cy - wing);
            arrowPath.lineTo(cx - span, cy);
            arrowPath.lineTo(cx - span + wing, cy + wing);
            arrowPath.moveTo(cx - span + 0.5f, cy);
            arrowPath.lineTo(cx + span, cy);
            canvas.drawPath(arrowPath, arrowPaint);
        }
    }

    /**
     * ColorOS 17 Donut Radio Button (Reference Screenshot 1 {@code 經典鈴聲}):
     * Selected = thick accent ring with hollow inner center; Unselected = thin light-gray circle ring.
     */
    private static final class CouiDonutRadioView extends View {
        private static final PathInterpolator EASE = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
        private final Paint selectedRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint unselectedRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        private boolean checked;

        CouiDonutRadioView(Context ctx, CouiBottomSheet.Palette pal, boolean checked) {
            super(ctx);
            this.checked = checked;
            this.d = ctx.getResources().getDisplayMetrics().density;

            selectedRingPaint.setStyle(Paint.Style.STROKE);
            selectedRingPaint.setColor(pal.accent);
            selectedRingPaint.setStrokeWidth(5.6f * d);

            unselectedRingPaint.setStyle(Paint.Style.STROKE);
            unselectedRingPaint.setColor(pal.dark ? 0xFF525258 : 0xFFC8CBD2);
            unselectedRingPaint.setStrokeWidth(1.5f * d);
        }

        void setChecked(boolean target) {
            if (this.checked != target) {
                this.checked = target;
                invalidate();
                if (target) {
                    setScaleX(0.84f);
                    setScaleY(0.84f);
                    animate().scaleX(1f).scaleY(1f).setDuration(180L).setInterpolator(EASE).start();
                }
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float cx = getWidth() * 0.5f;
            float cy = getHeight() * 0.5f;
            float outerR = Math.min(cx, cy) * 0.90f;
            if (checked) {
                float sw = Math.min(selectedRingPaint.getStrokeWidth(), outerR * 0.58f);
                selectedRingPaint.setStrokeWidth(sw);
                canvas.drawCircle(cx, cy, outerR - sw * 0.5f, selectedRingPaint);
            } else {
                float sw = unselectedRingPaint.getStrokeWidth();
                canvas.drawCircle(cx, cy, outerR - sw * 0.5f, unselectedRingPaint);
            }
        }
    }

    /**
     * HeyMelody / ColorOS 17 Vertical Multi-Band Equalizer Graph (Reference Screenshot 2 {@code 自訂}):
     * Left dB scale ({@code +10db / 0db / -10db}) + vertical sliders with light upper track,
     * dark lower stem, hollow circular ring thumb, and bottom frequency labels.
     */
    private static final class VerticalEqualizerView extends View {
        private final int[] bands;
        private final String[] labels;
        private final Runnable onDragChanged;
        private final Runnable onDragCommitted;
        private final float d;
        private final Paint upperTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lowerTrackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint scaleTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint freqTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int normalStemColor;
        private final int activeStemColor;
        private int activeCol = -1;

        VerticalEqualizerView(Context ctx, CouiBottomSheet.Palette pal, int cardFillColor,
                              int[] bands, String[] labels,
                              Runnable onDragChanged, Runnable onDragCommitted) {
            super(ctx);
            this.bands = bands;
            this.labels = labels;
            this.onDragChanged = onDragChanged;
            this.onDragCommitted = onDragCommitted;
            this.d = ctx.getResources().getDisplayMetrics().density;
            float sp = ctx.getResources().getDisplayMetrics().scaledDensity;

            this.normalStemColor = pal.dark ? 0xFFE5E5EA : 0xFF45454A;
            this.activeStemColor = pal.accent;

            upperTrackPaint.setStyle(Paint.Style.STROKE);
            upperTrackPaint.setStrokeCap(Paint.Cap.ROUND);
            upperTrackPaint.setStrokeWidth(2.0f * d);
            upperTrackPaint.setColor(pal.dark ? 0xFF38383E : 0xFFE6E7EC);

            lowerTrackPaint.setStyle(Paint.Style.STROKE);
            lowerTrackPaint.setStrokeCap(Paint.Cap.ROUND);
            lowerTrackPaint.setStrokeWidth(2.2f * d);
            lowerTrackPaint.setColor(normalStemColor);

            int opaqueThumbFill = Color.alpha(cardFillColor) >= 0xF0
                    ? cardFillColor
                    : (pal.dark ? 0xFF222226 : 0xFFFFFFFF);
            thumbFillPaint.setStyle(Paint.Style.FILL);
            thumbFillPaint.setColor(opaqueThumbFill);

            thumbRingPaint.setStyle(Paint.Style.STROKE);
            thumbRingPaint.setStrokeWidth(2.6f * d);
            thumbRingPaint.setColor(normalStemColor);

            int labelColor = pal.dark ? 0xFF8E8E93 : 0xFF9898A0;
            scaleTextPaint.setColor(labelColor);
            scaleTextPaint.setTextSize(11.5f * sp);
            scaleTextPaint.setTextAlign(Paint.Align.LEFT);

            freqTextPaint.setColor(labelColor);
            freqTextPaint.setTextSize(11.5f * sp);
            freqTextPaint.setTextAlign(Paint.Align.CENTER);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || bands == null || bands.length == 0) return;

            float leftScaleX = 20f * d;
            float slidersLeft = 62f * d;
            float slidersRight = w - 16f * d;
            float topTrackY = 18f * d;
            float bottomTrackY = h - 34f * d;
            float midTrackY = (topTrackY + bottomTrackY) * 0.5f;
            float halfSpan = (bottomTrackY - topTrackY) * 0.5f;
            float thumbR = 7.5f * d;

            Paint.FontMetrics fm = scaleTextPaint.getFontMetrics();
            float textOffset = -(fm.ascent + fm.descent) * 0.5f;
            canvas.drawText("+10db", leftScaleX, topTrackY + textOffset, scaleTextPaint);
            canvas.drawText("0db", leftScaleX + 6f * d, midTrackY + textOffset, scaleTextPaint);
            canvas.drawText("-10db", leftScaleX, bottomTrackY + textOffset, scaleTextPaint);

            int count = bands.length;
            float colStep = (slidersRight - slidersLeft) / count;
            float freqLabelY = bottomTrackY + 21f * d;

            for (int i = 0; i < count; i++) {
                float cx = slidersLeft + colStep * (i + 0.5f);
                int val = clampRange(bands[i], -10, 10);
                float thumbY = midTrackY - (val / 10f) * halfSpan;

                // Upper light track
                if (thumbY - thumbR > topTrackY) {
                    canvas.drawLine(cx, topTrackY, cx, thumbY - thumbR, upperTrackPaint);
                }
                // Lower dark/accent stem
                int stemColor = (i == activeCol) ? activeStemColor : normalStemColor;
                lowerTrackPaint.setColor(stemColor);
                if (bottomTrackY > thumbY + thumbR) {
                    canvas.drawLine(cx, thumbY + thumbR, cx, bottomTrackY, lowerTrackPaint);
                }

                // Hollow circular thumb with thick border
                thumbRingPaint.setColor(stemColor);
                canvas.drawCircle(cx, thumbY, thumbR, thumbFillPaint);
                canvas.drawCircle(cx, thumbY, thumbR, thumbRingPaint);

                String lbl = (labels != null && i < labels.length) ? labels[i] : String.valueOf(i + 1);
                canvas.drawText(lbl, cx, freqLabelY, freqTextPaint);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            int w = getWidth();
            int h = getHeight();
            if (w <= 0 || h <= 0 || bands == null || bands.length == 0) return false;

            float slidersLeft = 62f * d;
            float slidersRight = w - 16f * d;
            float topTrackY = 18f * d;
            float bottomTrackY = h - 34f * d;
            int count = bands.length;
            float colStep = (slidersRight - slidersLeft) / count;

            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                float x = event.getX();
                float y = event.getY();
                if (y < topTrackY - 14f * d || y > bottomTrackY + 14f * d || x < slidersLeft) {
                    activeCol = -1;
                    return false;
                }
                int bestCol = -1;
                float bestDist = Float.MAX_VALUE;
                for (int i = 0; i < count; i++) {
                    float cx = slidersLeft + colStep * (i + 0.5f);
                    float dist = Math.abs(x - cx);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestCol = i;
                    }
                }
                float hitHalfW = Math.min(colStep * 0.36f, 16f * d);
                if (bestCol < 0 || bestDist > hitHalfW) {
                    activeCol = -1;
                    return false;
                }
                activeCol = bestCol;
                requestDisallowParentIntercept(this, true);
                updateBandFromY(activeCol, y, topTrackY, bottomTrackY);
                invalidate();
                return true;
            } else if (action == MotionEvent.ACTION_MOVE) {
                if (activeCol < 0 || activeCol >= count) {
                    return false;
                }
                requestDisallowParentIntercept(this, true);
                updateBandFromY(activeCol, event.getY(), topTrackY, bottomTrackY);
                invalidate();
                return true;
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (activeCol < 0) {
                    return false;
                }
                activeCol = -1;
                invalidate();
                if (onDragCommitted != null) onDragCommitted.run();
                requestDisallowParentIntercept(this, false);
                return true;
            }
            return super.onTouchEvent(event);
        }

        private void updateBandFromY(int col, float y, float topY, float bottomY) {
            float midY = (topY + bottomY) * 0.5f;
            float halfSpan = Math.max(1f, (bottomY - topY) * 0.5f);
            float norm = (midY - y) / halfSpan;
            int newVal = clampRange(Math.round(norm * 10f), -10, 10);
            if (bands[col] != newVal) {
                bands[col] = newVal;
                if (onDragChanged != null) onDragChanged.run();
            }
        }
    }

    /** Double up/down chevron ({@code ⌄⌃}) matching Screenshot 2's {@code 等化器 | 自訂 ⇅} selector row. */
    private static final class CouiUpDownChevronView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CouiUpDownChevronView(Context ctx, int color) {
            super(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.5f * d);
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            path.reset();
            // Upper chevron ^
            path.moveTo(w * 0.22f, h * 0.36f);
            path.lineTo(w * 0.50f, h * 0.14f);
            path.lineTo(w * 0.78f, h * 0.36f);
            // Lower chevron v
            path.moveTo(w * 0.22f, h * 0.64f);
            path.lineTo(w * 0.50f, h * 0.86f);
            path.lineTo(w * 0.78f, h * 0.64f);
            canvas.drawPath(path, paint);
        }
    }

    private static final class CouiChevronView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        CouiChevronView(Context ctx, int color) {
            super(ctx);
            float d = ctx.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeWidth(1.6f * d);
            paint.setColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            path.reset();
            path.moveTo(w * 0.35f, h * 0.25f);
            path.lineTo(w * 0.65f, h * 0.50f);
            path.lineTo(w * 0.35f, h * 0.75f);
            canvas.drawPath(path, paint);
        }
    }

    /** Native ColorOS 17 capsule toggle switch with zero host resource ID dependencies. */
    private static final class CouiToggleSwitch extends View {
        private static final PathInterpolator EASE = new PathInterpolator(0.22f, 1.0f, 0.36f, 1.0f);
        private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint thumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF trackRect = new RectF();
        private final int offTrackColor;
        private final int onTrackColor;
        private boolean checked;
        private float frac;
        private ValueAnimator anim;

        CouiToggleSwitch(Context ctx, CouiBottomSheet.Palette pal, boolean initialChecked) {
            super(ctx);
            this.checked = initialChecked;
            this.frac = initialChecked ? 1f : 0f;
            this.onTrackColor = pal.accent;
            this.offTrackColor = pal.dark ? 0xFF3A3A3F : 0xFFD8DCE3;
            thumbPaint.setColor(0xFFFFFFFF);
            thumbPaint.setStyle(Paint.Style.FILL);
            trackPaint.setStyle(Paint.Style.FILL);
        }

        boolean isChecked() {
            return checked;
        }

        void setChecked(boolean target, boolean animate) {
            if (this.checked == target) return;
            this.checked = target;
            float end = target ? 1f : 0f;
            if (anim != null) anim.cancel();
            if (!animate) {
                frac = end;
                invalidate();
                return;
            }
            anim = ValueAnimator.ofFloat(frac, end);
            anim.setDuration(200L);
            anim.setInterpolator(EASE);
            anim.addUpdateListener(a -> {
                frac = (Float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0f || h <= 0f) return;
            float r = h * 0.5f;
            trackPaint.setColor(blendColor(offTrackColor, onTrackColor, frac));
            trackRect.set(0f, 0f, w, h);
            canvas.drawRoundRect(trackRect, r, r, trackPaint);

            float pad = h * 0.14f;
            float thumbR = r - pad;
            float minX = pad + thumbR;
            float maxX = w - pad - thumbR;
            float cx = minX + (maxX - minX) * frac;
            canvas.drawCircle(cx, h * 0.5f, thumbR, thumbPaint);
        }

        private static int blendColor(int c0, int c1, float t) {
            int a = Math.round(Color.alpha(c0) + (Color.alpha(c1) - Color.alpha(c0)) * t);
            int r = Math.round(Color.red(c0) + (Color.red(c1) - Color.red(c0)) * t);
            int g = Math.round(Color.green(c0) + (Color.green(c1) - Color.green(c0)) * t);
            int b = Math.round(Color.blue(c0) + (Color.blue(c1) - Color.blue(c0)) * t);
            return Color.argb(a, r, g, b);
        }
    }

    // ==================== Label & Theme Resolution Helpers ====================

    private static void requestDisallowParentIntercept(View v, boolean disallow) {
        if (v != null && v.getParent() != null) {
            v.getParent().requestDisallowInterceptTouchEvent(disallow);
        }
    }

    private static Activity unwrapActivity(Context ctx) {
        Context cur = ctx;
        for (int i = 0; i < 8 && cur != null; i++) {
            if (cur instanceof Activity) return (Activity) cur;
            if (cur instanceof ContextWrapper) {
                cur = ((ContextWrapper) cur).getBaseContext();
            } else {
                break;
            }
        }
        return null;
    }

    private static int resolveCardSurfaceColor(Context ctx, CouiBottomSheet.Palette pal, HostStage stage) {
        if (stage != null && stage.embeddedPanel && stage.level1Content != null) {
            int sampled = sampleExistingCardColor(stage.level1Content);
            if (sampled != 0) return sampled;
        }
        if (ctx != null && ctx.getTheme() != null) {
            String[] cardAttrs = new String[]{"couiColorCardBackground", "couiColorSurfaceWithCard"};
            String pkg = ctx.getPackageName();
            TypedValue tv = new TypedValue();
            for (String attr : cardAttrs) {
                try {
                    int attrId = ctx.getResources().getIdentifier(attr, "attr", pkg);
                    if (attrId != 0 && ctx.getTheme().resolveAttribute(attrId, tv, true)) {
                        int c = tv.resourceId != 0 ? ctx.getColor(tv.resourceId) : tv.data;
                        if (Color.alpha(c) >= 0x18) return c;
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return pal.dark ? 0xFF1C1C1E : 0xFFFFFFFF;
    }

    private static int sampleExistingCardColor(View root) {
        if (root == null) return 0;
        Drawable bg = root.getBackground();
        if (bg instanceof ColorDrawable) {
            int c = ((ColorDrawable) bg).getColor();
            if (Color.alpha(c) >= 0x20) return c;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View child = vg.getChildAt(i);
                if (child != null && child.getClass().getName().endsWith("BlueToothCardView")) {
                    Drawable cbg = child.getBackground();
                    if (cbg instanceof ColorDrawable) {
                        int c = ((ColorDrawable) cbg).getColor();
                        if (Color.alpha(c) >= 0x20) return c;
                    }
                }
            }
        }
        return 0;
    }

    private static int resolveFullScreenPageBg(Context ctx, CouiBottomSheet.Palette pal) {
        if (pal.dark) return 0xFF000000;
        if (ctx != null && ctx.getTheme() != null) {
            try {
                int attrId = ctx.getResources().getIdentifier("couiColorBackground", "attr", ctx.getPackageName());
                if (attrId != 0) {
                    TypedValue tv = new TypedValue();
                    if (ctx.getTheme().resolveAttribute(attrId, tv, true)) {
                        int c = tv.resourceId != 0 ? ctx.getColor(tv.resourceId) : tv.data;
                        if (Color.alpha(c) >= 0xF0) return c;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return 0xFFF5F6F8;
    }

    private static String formatUnifiedQualityShort(Context ctx, ConfigStore.HeadsetState st,
                                                    ConfigStore.CodecState cs, boolean codecAvail) {
        if (codecAvail) {
            return ConfigStore.codecDisplayName(cs.activeCodec);
        }
        return st.connectionPriorMode == 1
                ? loc(ctx, "穩定連線", "稳定连接", "Stable")
                : loc(ctx, "音質優先", "音质优先", "Sound Prior");
    }

    private static String formatUnifiedQualityLabel(Context ctx, boolean isStable, boolean codecAvail) {
        if (codecAvail) {
            return isStable
                    ? loc(ctx, "AAC · 穩定連線", "AAC · 稳定连接", "AAC · Stable")
                    : loc(ctx, "LDAC · 音質優先", "LDAC · 音质优先", "LDAC · Quality");
        }
        return isStable
                ? loc(ctx, "穩定連線優先 (AAC)", "稳定连接优先 (AAC)", "Stable (AAC)")
                : loc(ctx, "音質優先 (LDAC)", "音质优先 (LDAC)", "Quality (LDAC)");
    }

    public static String getEqPresetLabel(Context ctx, int presetId) {
        switch (presetId) {
            case 0x00: return loc(ctx, "關閉", "关闭", "Off");
            case 0x10: return loc(ctx, "歡快", "欢快", "Bright");
            case 0x11: return loc(ctx, "激昂", "激昂", "Excited");
            case 0x12: return loc(ctx, "醇美", "醇美", "Mellow");
            case 0x13: return loc(ctx, "放鬆", "放松", "Relaxed");
            case 0x14: return loc(ctx, "人聲", "人声", "Vocal");
            case 0x15: return loc(ctx, "高音增強", "高音增强", "Treble Boost");
            case 0x16: return loc(ctx, "低音增強", "低音增强", "Bass Boost");
            case 0x17: return loc(ctx, "演說", "演讲", "Speech");
            case 0xA0: return loc(ctx, "自訂", "自定义", "Custom");
            case 0xA1: return loc(ctx, "自訂 1", "自定义 1", "User 1");
            case 0xA2: return loc(ctx, "自訂 2", "自定义 2", "User 2");
            case 0x01: return "Rock";
            case 0x02: return "Pop";
            case 0x03: return "Jazz";
            case 0x04: return "Dance";
            case 0x05: return "EDM";
            default: return "EQ #" + presetId;
        }
    }

    public static String getTouchPresetLabel(Context ctx, int presetId) {
        switch (presetId) {
            case SonyTandemProtocol.TOUCH_PRESET_AMBIENT:
                return loc(ctx, "環境聲音控制", "环境声音控制", "Ambient Sound Control");
            case SonyTandemProtocol.TOUCH_PRESET_PLAYBACK:
                return loc(ctx, "播放控制", "播放控制", "Playback Control");
            case SonyTandemProtocol.TOUCH_PRESET_VOLUME:
                return loc(ctx, "音量控制", "音量控制", "Volume Control");
            case SonyTandemProtocol.TOUCH_PRESET_VOICE:
                return loc(ctx, "語音助理", "语音助手", "Voice Assistant");
            case SonyTandemProtocol.TOUCH_PRESET_QUICK_ACCESS:
                return loc(ctx, "快速存取", "快速访问", "Quick Access");
            case SonyTandemProtocol.TOUCH_PRESET_NONE:
            default:
                return loc(ctx, "未指派", "未分配", "Not Assigned");
        }
    }

    private static String shortTouchLabel(Context ctx, int presetId) {
        switch (presetId) {
            case SonyTandemProtocol.TOUCH_PRESET_AMBIENT:
                return loc(ctx, "環境音", "环境音", "Ambient");
            case SonyTandemProtocol.TOUCH_PRESET_PLAYBACK:
                return loc(ctx, "播放", "播放", "Playback");
            case SonyTandemProtocol.TOUCH_PRESET_VOLUME:
                return loc(ctx, "音量", "音量", "Volume");
            case SonyTandemProtocol.TOUCH_PRESET_VOICE:
                return loc(ctx, "語音", "语音", "Voice");
            case SonyTandemProtocol.TOUCH_PRESET_QUICK_ACCESS:
                return loc(ctx, "快取", "快访", "Quick");
            default:
                return loc(ctx, "無", "无", "None");
        }
    }

    public static String getAutoPowerOffLabel(Context ctx, int elem) {
        switch (elem) {
            case 0x10: return loc(ctx, "取下耳機時關閉", "摘下耳机时关闭", "Off when headphones removed");
            case 0x00: return loc(ctx, "5 分鐘無音訊時關閉", "5 分钟无音频时关闭", "Off after 5 minutes");
            case 0x04: return loc(ctx, "15 分鐘無音訊時關閉", "15 分钟无音频时关闭", "Off after 15 minutes");
            case 0x01: return loc(ctx, "30 分鐘無音訊時關閉", "30 分钟无音频时关闭", "Off after 30 minutes");
            case 0x02: return loc(ctx, "60 分鐘無音訊時關閉", "60 分钟无音频时关闭", "Off after 60 minutes");
            case 0x03: return loc(ctx, "180 分鐘無音訊時關閉", "180 分钟无音频时关闭", "Off after 180 minutes");
            case 0x11: return loc(ctx, "不自動關閉", "不自动关闭", "Do not turn off");
            default: return loc(ctx, "自訂 (" + elem + ")", "自定义 (" + elem + ")", "Mode " + elem);
        }
    }

    private static String getAutoPowerOffShortLabel(Context ctx, int elem) {
        switch (elem) {
            case 0x10: return loc(ctx, "取下時關閉", "摘下时关闭", "When removed");
            case 0x00: return loc(ctx, "5 分鐘", "5 分钟", "5 min");
            case 0x04: return loc(ctx, "15 分鐘", "15 分钟", "15 min");
            case 0x01: return loc(ctx, "30 分鐘", "30 分钟", "30 min");
            case 0x02: return loc(ctx, "60 分鐘", "60 分钟", "60 min");
            case 0x03: return loc(ctx, "180 分鐘", "180 分钟", "180 min");
            case 0x11: return loc(ctx, "不關閉", "不关闭", "Disabled");
            default: return "";
        }
    }

    private static boolean isCustomEqPreset(int id) {
        return id == 0xA0 || id == 0xA1 || id == 0xA2;
    }

    /** Default 6-band curves [400Hz, 1kHz, 2.5kHz, 6.3kHz, 16kHz, Clear Bass] matching Sony presets. */
    private static int[] getDefaultEqBandsForPreset(int presetId) {
        switch (presetId) {
            case 0x00: return new int[]{0, 0, 0, 0, 0, 0};
            case 0x10: return new int[]{0, 5, 7, 7, 9, -1};
            case 0x11: return new int[]{6, 4, 0, 6, 9, 8};
            case 0x12: return new int[]{0, 0, 0, -4, -6, 3};
            case 0x13: return new int[]{0, 3, 0, -3, -5, 0};
            case 0x14: return new int[]{-3, 2, 6, 4, -2, -2};
            case 0x15: return new int[]{0, 0, 3, 7, 10, 0};
            case 0x16: return new int[]{0, 0, 0, 0, 0, 8};
            case 0x17: return new int[]{-4, 3, 7, 5, 0, -4};
            default: return null;
        }
    }

    private static int clampRange(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int indexOfInt(int[] arr, int val) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == val) return i;
        }
        return 0;
    }

    private static LinearLayout.LayoutParams matchWrapParams(Context ctx, int topMarginDp) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (topMarginDp > 0 && ctx != null) {
            lp.topMargin = Math.round(topMarginDp * ctx.getResources().getDisplayMetrics().density);
        }
        return lp;
    }

    private static String joinDot(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(" · ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private static int resolveStatusBarFallbackPx(Context ctx) {
        int resId = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        if (resId > 0) {
            return ctx.getResources().getDimensionPixelSize(resId);
        }
        return Math.round(32f * ctx.getResources().getDisplayMetrics().density);
    }

    private static int resolveNavBarFallbackPx(Context ctx) {
        int resId = ctx.getResources().getIdentifier("navigation_bar_height", "dimen", "android");
        if (resId > 0) {
            return ctx.getResources().getDimensionPixelSize(resId);
        }
        return Math.round(28f * ctx.getResources().getDisplayMetrics().density);
    }

    private static final class MultipointDevice {
        final String mac;
        final String name;
        final boolean connected;

        MultipointDevice(String mac, String name, boolean connected) {
            this.mac = mac;
            this.name = name;
            this.connected = connected;
        }
    }

    private static List<MultipointDevice> parseMultipointDevices(String raw) {
        List<MultipointDevice> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        String[] entries = raw.split(";", -1);
        for (String entry : entries) {
            if (entry.isEmpty()) continue;
            String[] fields = entry.split("~", -1);
            if (fields.length >= 3) {
                out.add(new MultipointDevice(fields[0], fields[1], "1".equals(fields[2])));
            }
        }
        return out;
    }

    public static String loc(Context ctx, String tw, String cn, String en) {
        return ColorOsNativeExtraPrefsHelper.locText(ctx, tw, cn, en);
    }
}
