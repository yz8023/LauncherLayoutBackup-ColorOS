package com.su.launcherlayout;

import android.graphics.PorterDuff;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 单 Activity + 底部导航（仿 KernelSU 管理器的结构）。
 * 主页 / 备份 / 设置 三个页面，不重建视图，切换即时。
 */
public final class MainActivity extends BaseActivity {

    static final int TAB_HOME = 0;
    static final int TAB_BACKUP = 1;
    static final int TAB_EXTRACT = 2;
    static final int TAB_SETTINGS = 3;

    private static final int[] TAB_ICON = {
            R.drawable.ic_home, R.drawable.ic_box, R.drawable.ic_list, R.drawable.ic_tune
    };
    private static final String[] TAB_NAME = {"主页", "备份", "提取", "设置"};

    private FrameLayout mContent;
    private final Page[] mPages = new Page[4];
    private final ImageView[] mNavIcons = new ImageView[4];
    private final TextView[] mNavLabels = new TextView[4];
    private TextView mBadge;
    private int mTab = TAB_HOME;

    // ------------------------------------------------------------------ 权限门禁
    // 安装后首次使用前必须全部通过：Root 授权 / LSPosed 激活 / 备份目录。
    private FrameLayout mGate;
    private TextView mGateRootStatus;
    private TextView mGateRootDetail;
    private TextView mGateLsposedStatus;
    private TextView mGateLsposedDetail;
    private TextView mGateDirStatus;
    private TextView mGateDirDetail;
    private View mGateDirButton;
    private boolean mGateRootOk;
    private boolean mGateLsposedOk;
    private boolean mGateDirOk;
    private boolean mGateRootChecking;
    private boolean mGateLsposedChecking = true;

    @Override
    protected View buildContentView() {
        Theme.init(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.page);

        mContent = new FrameLayout(this);
        root.addView(mContent, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(buildNav());

        mPages[TAB_HOME] = new PageHome(this);
        mPages[TAB_BACKUP] = new PageBackup(this);
        mPages[TAB_EXTRACT] = new PageExtract(this);
        mPages[TAB_SETTINGS] = new PageSettings(this);
        showTab(TAB_HOME);
        // 权限门禁覆盖层：三项全过前挡住主界面与底部导航
        mGate = buildGate();
        root.addView(mGate, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** 状态栏图标跟随深浅色，并去掉启动白闪。 */
    private void tuneWindow() {
        try {
            getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Theme.page));
            if (android.os.Build.VERSION.SDK_INT >= 23) {
                View dv = getWindow().getDecorView();
                int flags = dv.getSystemUiVisibility();
                if (Theme.dark) {
                    flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                } else {
                    flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
                }
                dv.setSystemUiVisibility(flags);
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void afterCreate() {
        tuneWindow();
        startGateChecks();
        request(Const.ACTION_INFO, null);
    }

    // ------------------------------------------------------------------ 底部导航

    private View buildNav() {
        LinearLayout wrap = UiKit.column(this);
        wrap.setBackgroundColor(Theme.nav);

        View hair = new View(this);
        hair.setBackgroundColor(Theme.outlineVariant);
        wrap.addView(hair, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, UiKit.dp(this, 0.6f))));

        LinearLayout bar = UiKit.row(this);
        bar.setPadding(0, UiKit.dp(this, 4), 0, UiKit.dp(this, 4));
        for (int i = 0; i < 4; i++) {
            final int t = i;
            LinearLayout item = UiKit.column(this);
            item.setGravity(Gravity.CENTER);
            item.setPadding(0, UiKit.dp(this, 8), 0, UiKit.dp(this, 8));
            item.setBackground(UiKit.clickable(this, 0x00000000, Theme.R_SMALL));

            FrameLayout iconWrap = new FrameLayout(this);
            ImageView ic = UiKit.icon(this, TAB_ICON[i], 22, Theme.onSurfaceVariant);
            iconWrap.addView(ic);
            if (i == TAB_BACKUP) {
                mBadge = UiKit.badge(this, "0");
                mBadge.setVisibility(View.GONE);
                FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                // 贴在图标右上角：不要 margin，否则会被推出 FrameLayout 跑到隔壁 tab 上
                bp.gravity = Gravity.TOP | Gravity.END;
                iconWrap.addView(mBadge, bp);
            }
            // 竖向 LinearLayout 里 addView 不带参数时默认宽是 MATCH_PARENT，
            // 那样 iconWrap 会铺满整个 tab 宽度、角标就被推到隔壁 tab 上了。
            item.addView(iconWrap, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView lab = UiKit.text(this, TAB_NAME[i], Theme.onSurfaceVariant, 11f);
            // 竖向 LinearLayout 里 addView 不带参数时宽度是 MATCH_PARENT，
            // 文字会按左对齐铺满整格 → 和居中的图标错位。必须显式居中。
            lab.setGravity(Gravity.CENTER);
            lab.setPadding(0, UiKit.dp(this, 4), 0, 0);
            item.addView(lab);

            item.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showTab(t);
                }
            });
            mNavIcons[i] = ic;
            mNavLabels[i] = lab;
            bar.addView(item, UiKit.weight(this, 1f, 0, 0));
        }
        wrap.addView(bar);
        return wrap;
    }

    void showTab(int t) {
        if (t < 0 || t > 3) {
            return;
        }
        mTab = t;
        mContent.removeAllViews();
        View v = mPages[t].view();
        if (v.getParent() instanceof ViewGroup) {
            ((ViewGroup) v.getParent()).removeView(v);
        }
        mContent.addView(v, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        for (int i = 0; i < 4; i++) {
            final boolean sel = (i == t);
            mNavIcons[i].setColorFilter(sel ? Theme.primary : Theme.onSurfaceVariant,
                    PorterDuff.Mode.SRC_IN);
            mNavLabels[i].setTextColor(sel ? Theme.primary : Theme.onSurfaceVariant);
            mNavLabels[i].setTypeface(sel
                    ? android.graphics.Typeface.create("sans-serif-medium",
                    android.graphics.Typeface.NORMAL)
                    : android.graphics.Typeface.SANS_SERIF);
        }
        mPages[t].onShow();
    }

    int currentTab() {
        return mTab;
    }

    private void updateBadge() {
        if (mBadge == null) {
            return;
        }
        final int n = backupCount();
        if (n > 0) {
            mBadge.setText(n > 99 ? "99+" : String.valueOf(n));
            mBadge.setVisibility(View.VISIBLE);
        } else {
            mBadge.setVisibility(View.GONE);
        }
    }

    // ------------------------------------------------------------------ 回执分发

    @Override
    protected void onResult(String op, boolean ok, String msg, String dir,
                            String[] items, String[] grids) {
        if (Const.OP_INFO.equals(op) && ok) {
            currentGrid = UiKit.parseGrid(dir);
        }
        updateGateFromResult(op, ok, msg);
        // 只分发给**当前页**。
        // 其它页的视图是懒建的（没显示过就还没 build），提前塞结果会摸到 null 控件 ——
        // 之前就是这样空指针闪退的。未显示的页在 onShow() 里会自己重新拉一次数据。
        if (mTab >= 0 && mTab < mPages.length && mPages[mTab] != null) {
            mPages[mTab].onResult(op, ok, msg, dir, items, grids);
        }
        updateBadge();
    }

    @Override
    protected void onTimeout() {
        if (isGateShowing()) {
            mGateLsposedChecking = false;
            mGateLsposedOk = false;
            setGateStatus(mGateLsposedStatus, mGateLsposedDetail,
                    "✗ 未激活", "桌面进程无响应。请在 LSPosed 里启用本模块，"
                            + "作用域勾「系统桌面（com.android.launcher）」，然后重启桌面或手机。");
        }
        if (mTab >= 0 && mTab < mPages.length && mPages[mTab] instanceof PageHome) {
            ((PageHome) mPages[mTab]).showOffline();
        }
        if (mTab >= 0 && mTab < mPages.length && mPages[mTab] instanceof PageSettings) {
            ((PageSettings) mPages[mTab]).showOffline();
        }
    }

    /** 供各页面共用的请求刷新。 */
    void refreshAll() {
        request(Const.ACTION_INFO, null);
    }

    @Override
    protected void onDirPicked(String path) {
        if (mTab >= 0 && mTab < mPages.length && mPages[mTab] != null) {
            mPages[mTab].onDirPicked(path);
        }
    }

    @Override
    protected void onExtractDirPicked(android.net.Uri uri) {
        if (mTab >= 0 && mTab < mPages.length && mPages[mTab] != null) {
            mPages[mTab].onExtractDirPicked(uri);
        }
    }

    // ------------------------------------------------------------------ 权限门禁实现

    private boolean isGateShowing() {
        return mGate != null && mGate.getVisibility() == View.VISIBLE;
    }

    private FrameLayout buildGate() {
        FrameLayout gate = new FrameLayout(this);
        gate.setBackgroundColor(Theme.page);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = UiKit.column(this);
        UiKit.pad(this, box, 20, 24, 24);
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(UiKit.pageTitle(this, "使用前检查"));
        box.addView(UiKit.rowSummary(this, "三项全部通过后自动进入主界面"));
        box.addView(new View(this), UiKit.full(this, 8, 0));

        // 1) Root 授权
        LinearLayout rootCard = UiKit.card(this);
        UiKit.pad(this, rootCard, 16, 14, 14);
        rootCard.addView(UiKit.rowTitle(this, "1. Root 授权（恢复必需）"));
        mGateRootStatus = UiKit.text(this, "… 检测中", Theme.onSurfaceVariant, 13f);
        rootCard.addView(mGateRootStatus, UiKit.full(this, 4, 0));
        mGateRootDetail = UiKit.text(this,
                "正在尝试 su（首次会弹出管理器授权，请点允许）…", Theme.onSurfaceVariant, 12f);
        rootCard.addView(mGateRootDetail, UiKit.full(this, 2, 0));
        box.addView(rootCard, UiKit.full(this, 0, 10));

        // 2) LSPosed 激活
        LinearLayout lsCard = UiKit.card(this);
        UiKit.pad(this, lsCard, 16, 14, 14);
        lsCard.addView(UiKit.rowTitle(this, "2. LSPosed 模块激活（备份/恢复必需）"));
        mGateLsposedStatus = UiKit.text(this, "… 检测中", Theme.onSurfaceVariant, 13f);
        lsCard.addView(mGateLsposedStatus, UiKit.full(this, 4, 0));
        mGateLsposedDetail = UiKit.text(this,
                "正在联系桌面进程…", Theme.onSurfaceVariant, 12f);
        lsCard.addView(mGateLsposedDetail, UiKit.full(this, 2, 0));
        box.addView(lsCard, UiKit.full(this, 0, 10));

        // 3) 备份目录
        LinearLayout dirCard = UiKit.card(this);
        UiKit.pad(this, dirCard, 16, 14, 14);
        dirCard.addView(UiKit.rowTitle(this, "3. 备份目录（备份必需）"));
        mGateDirStatus = UiKit.text(this, "… 检测中", Theme.onSurfaceVariant, 13f);
        dirCard.addView(mGateDirStatus, UiKit.full(this, 4, 0));
        mGateDirDetail = UiKit.text(this, "正在读取配置…", Theme.onSurfaceVariant, 12f);
        dirCard.addView(mGateDirDetail, UiKit.full(this, 2, 0));
        mGateDirButton = UiKit.filled(this, "设定备份目录", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickDir();
            }
        });
        mGateDirButton.setVisibility(View.GONE);
        dirCard.addView(mGateDirButton, UiKit.full(this, 8, 0));
        box.addView(dirCard, UiKit.full(this, 0, 10));

        box.addView(UiKit.filled(this, "重新检测全部", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGateChecks();
                request(Const.ACTION_INFO, null);
            }
        }), UiKit.full(this, 4, 0));

        gate.addView(scroll, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return gate;
    }

    private void startGateChecks() {
        mGateRootOk = false;
        mGateLsposedOk = false;
        mGateDirOk = false;
        mGateRootChecking = true;
        mGateLsposedChecking = true;
        setGateStatus(mGateRootStatus, mGateRootDetail,
                "… 检测中", "正在尝试 su（首次会弹出管理器授权，请点允许）…");
        setGateStatus(mGateLsposedStatus, mGateLsposedDetail,
                "… 检测中", "正在联系桌面进程…");
        setGateStatus(mGateDirStatus, mGateDirDetail,
                "… 检测中", "正在读取配置…");
        if (mGateDirButton != null) {
            mGateDirButton.setVisibility(View.GONE);
        }
        checkRootAsync();
    }

    private void checkRootAsync() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String found = null;
                String detail = "";
                for (String su : suCandidates()) {
                    try {
                        java.lang.Process proc = Runtime.getRuntime()
                                .exec(new String[]{su, "-c", "id"});
                        boolean done = proc.waitFor(15, java.util.concurrent.TimeUnit.SECONDS);
                        if (!done) {
                            proc.destroy();
                            detail = su + " 等待授权超时（请在管理器里点允许）";
                            continue;
                        }
                        int rc = proc.exitValue();
                        String out = readAll(proc.getInputStream());
                        if (rc == 0 && out.contains("uid=0")) {
                            found = su;
                            break;
                        }
                        detail = su + " exit=" + rc + (out.trim().isEmpty() ? "" : " " + out.trim());
                    } catch (Throwable t) {
                        detail = su + " " + t.getMessage();
                    }
                }
                final boolean ok = found != null;
                final String f = found;
                final String d = detail;
                H.post(new Runnable() {
                    @Override
                    public void run() {
                        mGateRootChecking = false;
                        mGateRootOk = ok;
                        if (ok) {
                            setGateStatus(mGateRootStatus, mGateRootDetail,
                                    "✓ 已授权", "su 可用：" + f);
                        } else {
                            setGateStatus(mGateRootStatus, mGateRootDetail,
                                    "✗ 未授权",
                                    "打开 KernelSU / Magisk / APatch 管理器，找到「桌面布局备份」"
                                            + "并授予 root 授权，然后点「重新检测全部」。"
                                            + (d.isEmpty() ? "" : "\n（" + d + "）"));
                        }
                        maybeHideGate();
                    }
                });
            }
        }, "llb-gate-root").start();
    }

    private static String readAll(java.io.InputStream in) {
        try {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private void updateGateFromResult(String op, boolean ok, String msg) {
        if (!isGateShowing()) {
            return;
        }
        if (Const.OP_INFO.equals(op)) {
            mGateLsposedChecking = false;
            if (ok) {
                mGateLsposedOk = true;
                setGateStatus(mGateLsposedStatus, mGateLsposedDetail,
                        "✓ 已激活", "桌面进程已注入，当前库已就绪。");
            } else {
                mGateLsposedOk = false;
                setGateStatus(mGateLsposedStatus, mGateLsposedDetail,
                        "✗ 未激活", msg == null ? "桌面进程无响应" : msg);
            }
            updateGateDir();
            maybeHideGate();
        } else if (Const.OP_SET_ROOT.equals(op)) {
            if (ok) {
                updateGateDir();
                maybeHideGate();
            }
        }
    }

    private void updateGateDir() {
        if ("1".equals(cfg.get("rootSet"))) {
            mGateDirOk = true;
            String rp = cfg.get("root");
            setGateStatus(mGateDirStatus, mGateDirDetail,
                    "✓ 已设定", rp == null ? "" : rp);
            if (mGateDirButton != null) {
                mGateDirButton.setVisibility(View.GONE);
            }
        } else {
            mGateDirOk = false;
            setGateStatus(mGateDirStatus, mGateDirDetail,
                    "✗ 未设定", "请先给备份指定一个单独目录（不要放 Download）。");
            if (mGateDirButton != null) {
                mGateDirButton.setVisibility(View.VISIBLE);
            }
        }
    }

    private void setGateStatus(TextView status, TextView detail, String s, String d) {
        if (status != null) {
            status.setText(s);
            status.setTextColor(s.startsWith("✓") ? Theme.primary : Theme.onSurfaceVariant);
        }
        if (detail != null) {
            detail.setText(d);
        }
    }

    private void maybeHideGate() {
        if (!isGateShowing()) {
            return;
        }
        if (mGateRootOk && mGateLsposedOk && mGateDirOk) {
            mGate.setVisibility(View.GONE);
            UiLog.log("✅ 权限检测全部通过，进入主界面");
            showTab(TAB_HOME);
        }
    }
}
