package com.su.launcherlayout;

import android.content.Context;
import android.content.res.Configuration;

/**
 * Material 3 TonalSpot 配色，仿 KernelSU / Miuix 观感。
 *
 * <p>KernelSU 管理器的配色是 materialkolor 从种子色动态生成的（源码里没有写死的 hex），
 * 它内置的种子色里有 {@code 0xFF4FAF50} 这个绿，正好对应截图里的那个绿色卡片。
 * 这里没有动态取色能力（不依赖 androidx material-color-utilities），
 * 所以直接固化一套该绿种子下的 TonalSpot 明/暗色，跟随系统深浅色切换。
 *
 * <p>字体不用特殊处理：HyperOS 的系统默认字体就是 MiSans，和 KernelSU 一致。
 */
final class Theme {

    static boolean dark;

    static int primary;
    static int onPrimary;
    static int primaryContainer;
    static int onPrimaryContainer;

    static int surface;
    static int onSurface;
    static int onSurfaceVariant;
    static int outlineVariant;
    /** 卡片描边用的细线色（比 outlineVariant 更淡一档）。 */

    static int card;          // 卡片底色
    static int page;          // 页面底色
    static int nav;           // 底栏底色
    static int error;
    static int ok;
    static int outline;

    /** Miuix / M3 圆角。 */
    static final int R_CARD = 18;
    static final int R_BTN = 22;
    static final int R_SMALL = 12;
    static final int R_PILL = 100;

    private Theme() {
    }

    static void init(Context c) {
        final int mode = c.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        dark = (mode == Configuration.UI_MODE_NIGHT_YES);
        if (dark) {
            primary = 0xFF7FD68F;
            onPrimary = 0xFF04340F;
            primaryContainer = 0xFF11331B;
            onPrimaryContainer = 0xFFB7F0C2;
            page = 0xFF101310;
            surface = 0xFF101310;
            onSurface = 0xFFE6E9E2;
            onSurfaceVariant = 0xFFA8B0A4;
            card = 0xFF1B1F1A;
            nav = 0xFF151814;
            outlineVariant = 0xFF2C312A;
            outline = 0xFF272C25;
            error = 0xFFFFB4AB;
            ok = 0xFF7FD68F;
        } else {
            primary = 0xFF1B6B35;
            onPrimary = 0xFFFFFFFF;
            primaryContainer = 0xFFD7F0DC;
            onPrimaryContainer = 0xFF06280F;
            page = 0xFFF4F6F2;
            surface = 0xFFFFFFFF;
            onSurface = 0xFF161A15;
            onSurfaceVariant = 0xFF5C635A;
            card = 0xFFFFFFFF;
            nav = 0xFFFFFFFF;
            outlineVariant = 0xFFDCE1D7;
            outline = 0xFFE6EAE1;
            error = 0xFFB3261E;
            ok = 0xFF1B6B35;
        }
    }

    /** 状态卡的底色（仿截图里那块绿底「工作中」卡）。 */
    static int statusBg() {
        return primaryContainer;
    }

    static int statusFg() {
        return onPrimaryContainer;
    }
}
