package com.su.launcherlayout;

import android.content.ContentResolver;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 分组提取 APK：按桌面文件夹分组列出应用，整组勾选后一键提取安装包。
 *
 * <p>分组数据来自桌面进程（广播 {@link Const#ACTION_GROUPS}，读桌面条目表）；
 * APK 定位与拷贝在模块 App 侧完成。
 *
 * <p>写入磁盘两条路：
 * <ol>
 *   <li><b>root 直写绝对路径</b>（默认、最稳）：可写任意非空目录，能自动建分组子文件夹；</li>
 *   <li><b>SAF</b>（系统文件选择器）：不需要 root，但受系统限制（部分目录不让选、URIs 易失效）。</li>
 * </ol>
 * 二者可自动兜底：SAF 失败自动改走 root。
 */
final class PageExtract extends Page {

    private static final String PREFS = "llb_extract";
    private static final String KEY_URI = "out_uri";
    private static final String KEY_PATH = "out_path";
    private static final String KEY_NAMING = "naming";
    private static final String KEY_PER_GROUP = "per_group";
    private static final String DEFAULT_NAMING = "{AN}_{AVI}.apk";
    private static final String DEFAULT_PATH = "/storage/emulated/0/Download";

    static final class AppItem {
        String group;
        /** 界面上显示的名字（优先系统本地化名称，和桌面一致）。 */
        String title;
        /** 桌面数据库里存的标注（可能和系统名称不同，仅作参考展示）。 */
        String dbTitle;
        String pkg;
        boolean checked;
    }

    private final LinkedHashMap<String, List<AppItem>> mGroups = new LinkedHashMap<>();
    /** 默认全部折叠：只展开用户点开的分组。 */
    private final HashSet<String> mExpanded = new HashSet<>();
    private final java.util.concurrent.ConcurrentHashMap<String, Drawable> mIconCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final ExecutorService mIconPool = Executors.newFixedThreadPool(2);

    private LinearLayout mList;
    private TextView mStatus;
    private TextView mOutText;
    private EditText mNaming;
    private EditText mPath;
    private Switch mPerGroup;
    private TextView mLog;
    private boolean mLoading;
    private boolean mExtracting;
    private boolean mLoaded;

    PageExtract(MainActivity h) {
        super(h);
    }

    // ================================================================== UI

    @Override
    protected View build() {
        ScrollView scroll = new ScrollView(host);
        LinearLayout box = UiKit.column(host);
        UiKit.pad(host, box, 16, 16, 16);
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(UiKit.pageTitle(host, "分组提取 APK"));
        box.addView(UiKit.rowSummary(host, "按桌面文件夹分组，整组勾选后一键提取安装包"));

        // ---------------- 存放位置 ----------------
        LinearLayout outCard = UiKit.card(host);
        LinearLayout outIn = UiKit.column(host);
        UiKit.pad(host, outIn, 16, 14, 14);
        outIn.addView(UiKit.rowTitle(host, "存放位置"));
        mOutText = UiKit.text(host, outLabel(), Theme.onSurfaceVariant, 12f);
        mOutText.setPadding(0, UiKit.dp(host, 3), 0, 0);
        outIn.addView(mOutText);

        mPath = new EditText(host);
        mPath.setSingleLine(true);
        mPath.setTextSize(12f);
        mPath.setTextColor(Theme.onSurface);
        mPath.setHint("绝对路径，例如 /storage/emulated/0/Download");
        mPath.setBackground(UiKit.round(host, Theme.dark ? 0xFF171B15 : 0xFFF2F6EC, Theme.R_SMALL));
        UiKit.pad(host, mPath, 12, 9, 9);
        mPath.setText(basePath());
        outIn.addView(mPath, UiKit.full(host, 8, 0));

        LinearLayout outBtns = UiKit.row(host);
        outBtns.addView(UiKit.filled(host, "保存路径", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String p = mPath.getText().toString().trim();
                if (p.isEmpty()) {
                    host.toast("路径不能为空");
                    return;
                }
                if (!p.startsWith("/")) {
                    host.toast("请填绝对路径（以 / 开头）");
                    return;
                }
                while (p.endsWith("/")) {
                    p = p.substring(0, p.length() - 1);
                }
                prefs().edit().putString(KEY_PATH, p).apply();
                mOutText.setText(outLabel());
                appendLog("存放路径已设为 " + p + "（写入方式：root 直写）");
                host.toast("已保存：" + p);
            }
        }), UiKit.weight(host, 1f, 0, 4));
        outBtns.addView(UiKit.subtle(host, "系统选择器（SAF）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.pickExtractDir();
            }
        }), UiKit.weight(host, 1f, 4, 0));
        outIn.addView(outBtns, UiKit.full(host, 8, 0));

        mPerGroup = new Switch(host);
        mPerGroup.setText("每个分组建独立文件夹");
        mPerGroup.setTextColor(Theme.onSurface);
        mPerGroup.setTextSize(14f);
        mPerGroup.setChecked(prefs().getBoolean(KEY_PER_GROUP, true));
        mPerGroup.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(android.widget.CompoundButton b, boolean c) {
                prefs().edit().putBoolean(KEY_PER_GROUP, c).apply();
                mOutText.setText(outLabel());
            }
        });
        outIn.addView(mPerGroup, UiKit.full(host, 10, 0));
        outIn.addView(UiKit.text(host,
                "root 直写方式下可写任意目录（含非空目录），也会自动创建目标文件夹。",
                Theme.onSurfaceVariant, 11f), UiKit.full(host, 6, 0));
        outCard.addView(outIn);
        box.addView(outCard, UiKit.full(host, 12, 10));

        // ---------------- 文件命名 ----------------
        LinearLayout cfg = UiKit.card(host);
        LinearLayout cfgIn = UiKit.column(host);
        UiKit.pad(host, cfgIn, 16, 14, 14);
        cfgIn.addView(UiKit.rowTitle(host, "文件命名"));
        mNaming = new EditText(host);
        mNaming.setSingleLine(true);
        mNaming.setTextSize(13f);
        mNaming.setTextColor(Theme.onSurface);
        mNaming.setBackground(UiKit.round(host, Theme.dark ? 0xFF171B15 : 0xFFF2F6EC, Theme.R_SMALL));
        UiKit.pad(host, mNaming, 12, 10, 10);
        mNaming.setText(loadNaming());
        cfgIn.addView(mNaming, UiKit.full(host, 8, 0));
        cfgIn.addView(UiKit.text(host, "点击下方标签可直接插入到光标处：",
                Theme.onSurfaceVariant, 11f), UiKit.full(host, 6, 0));

        LinearLayout tagsRow1 = UiKit.row(host);
        addInsertChip(tagsRow1, "{AN}", "应用名");
        addInsertChip(tagsRow1, "{AP}", "包名");
        addInsertChip(tagsRow1, "{AVI}", "版本名");
        addInsertChip(tagsRow1, "{AC}", "版本号");
        cfgIn.addView(tagsRow1, UiKit.full(host, 4, 0));
        LinearLayout tagsRow2 = UiKit.row(host);
        addInsertChip(tagsRow2, "{G}", "分组名");
        addInsertChip(tagsRow2, "{D}", "日期");
        addInsertChip(tagsRow2, "{T}", "时间戳");
        addInsertChip(tagsRow2, "{I}", "序号");
        cfgIn.addView(tagsRow2, UiKit.full(host, 4, 0));
        cfg.addView(cfgIn);
        box.addView(cfg, UiKit.full(host, 0, 10));

        // ---------------- 工具栏 ----------------
        LinearLayout bar1 = UiKit.row(host);
        bar1.addView(UiKit.mini(host, "刷新分组", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLoaded = false;
                reload();
            }
        }), UiKit.weight(host, 1f, 0, 5));
        bar1.addView(UiKit.mini(host, "展开全部", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mExpanded.addAll(mGroups.keySet());
                rebuildList();
            }
        }), UiKit.weight(host, 1f, 5, 5));
        bar1.addView(UiKit.mini(host, "全部折叠", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mExpanded.clear();
                rebuildList();
            }
        }), UiKit.weight(host, 1f, 5, 0));
        box.addView(bar1, UiKit.full(host, 0, 6));

        LinearLayout bar2 = UiKit.row(host);
        bar2.addView(UiKit.mini(host, "全选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllChecked(true);
            }
        }), UiKit.weight(host, 1f, 0, 5));
        bar2.addView(UiKit.mini(host, "全不选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllChecked(false);
            }
        }), UiKit.weight(host, 1f, 5, 5));
        bar2.addView(UiKit.mini(host, "导出表结构", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mStatus.setText("正在读取数据库表结构…");
                host.request(Const.ACTION_SCHEMA, null);
            }
        }), UiKit.weight(host, 1f, 5, 0));
        box.addView(bar2, UiKit.full(host, 0, 8));

        mStatus = UiKit.text(host, "正在加载分组…", Theme.onSurfaceVariant, 12f);
        box.addView(mStatus, UiKit.full(host, 0, 8));

        mList = UiKit.column(host);
        box.addView(mList, UiKit.full(host, 0, 4));

        box.addView(UiKit.filled(host, "开始提取", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startExtract();
            }
        }), UiKit.full(host, 8, 0));

        mLog = UiKit.mono(host, "", Theme.onSurfaceVariant, 11f);
        box.addView(mLog, UiKit.full(host, 8, 0));
        return scroll;
    }

    // ================================================================== 数据

    private void reload() {
        if (mLoading) {
            return;
        }
        mLoading = true;
        if (mStatus != null) {
            mStatus.setText("正在加载分组…");
        }
        host.request(Const.ACTION_GROUPS, null);
    }

    @Override
    void onShow() {
        if (!mLoaded) {
            reload();
        }
    }

    @Override
    void onResult(String op, boolean ok, String msg, String dir, String[] items, String[] grids) {
        if (Const.OP_SCHEMA.equals(op)) {
            if (!ok || items == null) {
                mStatus.setText("读取表结构失败：" + (msg == null ? "" : msg));
                return;
            }
            mStatus.setText("表结构已读取（" + items.length + " 行）");
            showSchemaDialog(items);
            return;
        }
        if (!Const.OP_GROUPS.equals(op)) {
            return;
        }
        mLoading = false;
        if (!ok) {
            mStatus.setText("加载失败：" + (msg == null ? "" : msg));
            return;
        }
        mGroups.clear();
        mExpanded.clear();     // 默认全部折叠
        if (items != null) {
            for (String s : items) {
                if (s == null) {
                    continue;
                }
                String[] parts = s.split("\t", -1);
                if (parts.length < 3) {
                    continue;
                }
                AppItem it = new AppItem();
                it.group = parts[0];
                it.dbTitle = parts[1];
                it.pkg = parts[2];
                it.title = preferDesktopName(it.pkg, it.dbTitle);
                it.checked = false;      // 默认全部不选
                List<AppItem> list = mGroups.get(it.group);
                if (list == null) {
                    list = new ArrayList<>();
                    mGroups.put(it.group, list);
                }
                list.add(it);
            }
        }
        mLoaded = true;
        rebuildList();
        updateStatus();
    }

    /**
     * 显示名优先用「系统里该应用的本地化名称」——桌面渲染应用图标时用的就是它，
     * 所以这样才和桌面上一字不差。取不到才退回数据库里存的标注。
     */
    private String preferDesktopName(String pkg, String dbTitle) {
        try {
            ApplicationInfo ai = host.getPackageManager().getApplicationInfo(pkg, 0);
            CharSequence cs = ai.loadLabel(host.getPackageManager());
            String label = cs == null ? null : cs.toString().trim();
            if (label != null && !label.isEmpty() && !label.equalsIgnoreCase(pkg)) {
                return label;
            }
        } catch (Throwable ignored) {
        }
        return (dbTitle == null || dbTitle.trim().isEmpty()) ? pkg : dbTitle.trim();
    }

    // ================================================================== 列表

    private void rebuildList() {
        mList.removeAllViews();
        for (final String group : mGroups.keySet()) {
            final List<AppItem> apps = mGroups.get(group);
            final boolean expanded = mExpanded.contains(group);

            LinearLayout card = UiKit.card(host);
            UiKit.pad(host, card, 12, 10, 10);

            LinearLayout head = UiKit.row(host);
            head.setBackground(UiKit.clickable(host, 0x00000000, Theme.R_SMALL));
            head.setPadding(0, UiKit.dp(host, 2), 0, UiKit.dp(host, 2));
            TextView caret = UiKit.text(host, expanded ? "▾" : "▸", Theme.onSurfaceVariant, 14f);
            head.addView(caret, UiKit.wrap(host, 0, 8));
            head.addView(UiKit.rowTitle(host,
                    group + "（" + apps.size() + (selectedIn(apps) > 0
                            ? "，已选 " + selectedIn(apps) : "") + "）"), UiKit.weight(host, 1f, 0, 0));
            head.addView(UiKit.mini(host, "整组选/取消", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean all = selectedIn(apps) == apps.size();
                    for (AppItem a : apps) {
                        a.checked = !all;
                    }
                    rebuildList();
                    updateStatus();
                }
            }), UiKit.wrap(host, 0, 0));
            head.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!mExpanded.remove(group)) {
                        mExpanded.add(group);
                    }
                    rebuildList();
                }
            });
            card.addView(head);

            if (expanded) {
                for (final AppItem it : apps) {
                    card.addView(buildRow(it));
                }
            }
            mList.addView(card, UiKit.full(host, 0, 8));
        }
    }

    private View buildRow(final AppItem it) {
        LinearLayout row = UiKit.row(host);
        row.setPadding(0, UiKit.dp(host, 3), 0, UiKit.dp(host, 3));

        final CheckBox cb = new CheckBox(host);
        cb.setChecked(it.checked);
        cb.setClickable(false);
        cb.setFocusable(false);
        row.addView(cb, UiKit.wrap(host, 0, 0));

        ImageView icon = new ImageView(host);
        row.addView(icon, new LinearLayout.LayoutParams(
                UiKit.dp(host, 30), UiKit.dp(host, 30)));
        bindIcon(icon, it.pkg);

        LinearLayout texts = UiKit.column(host);
        texts.addView(UiKit.text(host, it.title, Theme.onSurface, 13f));
        String sub = it.pkg;
        if (it.dbTitle != null && !it.dbTitle.trim().isEmpty()
                && !it.dbTitle.trim().equals(it.title)) {
            sub = it.pkg + " · 桌面标注：" + it.dbTitle.trim();
        }
        texts.addView(UiKit.text(host, sub, Theme.onSurfaceVariant, 10.5f));
        row.addView(texts, UiKit.weight(host, 1f, 8, 0));

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                it.checked = !it.checked;
                cb.setChecked(it.checked);
                updateStatus();
            }
        });
        return row;
    }

    /** 图标异步加载 + 缓存，避免每次重建列表都同步解析一遍（卡顿的主因）。 */
    private void bindIcon(final ImageView iv, final String pkg) {
        Drawable d = mIconCache.get(pkg);
        if (d != null) {
            iv.setImageDrawable(d);
            return;
        }
        iv.setImageDrawable(null);
        mIconPool.execute(new Runnable() {
            @Override
            public void run() {
                Drawable dd = null;
                try {
                    dd = host.getPackageManager().getApplicationIcon(pkg);
                } catch (Throwable ignored) {
                }
                if (dd == null) {
                    return;
                }
                mIconCache.put(pkg, dd);
                final Drawable fd = dd;
                host.H.post(new Runnable() {
                    @Override
                    public void run() {
                        iv.setImageDrawable(fd);
                    }
                });
            }
        });
    }

    private static int selectedIn(List<AppItem> apps) {
        int n = 0;
        for (AppItem a : apps) {
            if (a.checked) {
                n++;
            }
        }
        return n;
    }

    private int selectedTotal() {
        int n = 0;
        for (List<AppItem> l : mGroups.values()) {
            n += selectedIn(l);
        }
        return n;
    }

    private void updateStatus() {
        int total = 0;
        for (List<AppItem> l : mGroups.values()) {
            total += l.size();
        }
        if (mStatus != null) {
            mStatus.setText("共 " + mGroups.size() + " 个分组、" + total + " 个应用 · 已选 "
                    + selectedTotal());// 选中状态实时反馈
        }
    }

    private void setAllChecked(boolean v) {
        for (List<AppItem> l : mGroups.values()) {
            for (AppItem a : l) {
                a.checked = v;
            }
        }
        rebuildList();
        updateStatus();
    }

    // ================================================================== 提取

    private void startExtract() {
        if (mExtracting) {
            return;
        }
        final List<AppItem> selected = new ArrayList<>();
        for (List<AppItem> l : mGroups.values()) {
            for (AppItem a : l) {
                if (a.checked) {
                    selected.add(a);
                }
            }
        }
        if (selected.isEmpty()) {
            host.toast("还没有勾选任何应用（点分组标题展开，再点应用勾选）");
            return;
        }
        final String uriStr = prefs().getString(KEY_URI, null);
        final String template = mNaming.getText().toString().trim();
        final boolean perGroup = mPerGroup.isChecked();
        prefs().edit().putString(KEY_NAMING, template)
                .putBoolean(KEY_PER_GROUP, perGroup).apply();
        mExtracting = true;
        mLog.setText("");
        appendLog("开始提取 " + selected.size() + " 个应用…");
        appendLog("目标：" + basePath() + (uriStr == null ? "" : "（SAF 备用）")
                + (perGroup ? " · 每个分组一个子文件夹" : ""));

        new Thread(new Runnable() {
            @Override
            public void run() {
                int ok = 0;
                int fail = 0;
                int idx = 0;
                for (AppItem it : selected) {
                    idx++;
                    try {
                        String name = buildFileName(template, it, idx);
                        String apkPath = resolveApk(it.pkg);
                        if (apkPath == null) {
                            appendLog("✗ " + it.title + "：找不到 APK");
                            fail++;
                            continue;
                        }
                        String where = writeTo(uriStr, new File(apkPath), name,
                                perGroup ? sanitize(it.group) : null, it);
                        appendLog("✓ " + it.title + " → " + name);
                        if (where != null && !where.isEmpty()) {
                            appendLog("   " + where);
                        }
                        ok++;
                    } catch (Throwable t) {
                        appendLog("✗ " + it.title + "：" + shortErr(t));
                        fail++;
                    }
                }
                final String done = "提取完成：成功 " + ok + "，失败 " + fail;
                appendLog(done);
                host.H.post(new Runnable() {
                    @Override
                    public void run() {
                        mExtracting = false;
                        host.toast(done);
                    }
                });
            }
        }, "llb-extract").start();
    }

    private static String shortErr(Throwable t) {
        String m = t.getMessage();
        if (m == null || m.trim().isEmpty()) {
            return t.getClass().getSimpleName();
        }
        return m.length() > 160 ? m.substring(0, 160) + "…" : m;
    }

    /**
     * 写一个 APK 到目标位置。返回实际落地路径（root 方式）或 null（SAF 方式）。
     *
     * <p>策略：**优先 root 直写绝对路径**（可写非空目录、自动建子目录、不受 SAF 限制）；
     * root 不可用或失败时退回 SAF。
     */
    private String writeTo(String safUri, File src, String name, String groupFolder, AppItem it)
            throws Exception {
        String rootErr = null;
        try {
            return writeWithRoot(src, name, groupFolder);
        } catch (Throwable t) {
            rootErr = shortErr(t);
        }
        if (safUri != null && !safUri.isEmpty()) {
            try {
                writeWithSaf(Uri.parse(safUri), src, name, groupFolder);
                return null;
            } catch (Throwable t) {
                throw new Exception("root 失败（" + rootErr + "）；SAF 也失败：" + shortErr(t));
            }
        }
        throw new Exception("root 写入失败：" + rootErr
                + "（可点「系统选择器」选一个目录作为备用写入位置）");
    }

    /** root 直写：mkdir -p 目标目录，cp 覆盖，再用 stat 校验大小。 */
    private String writeWithRoot(File src, String name, String groupFolder) throws Exception {
        String dir = basePath() + (groupFolder == null || groupFolder.isEmpty()
                ? "" : "/" + groupFolder);
        String dst = dir + "/" + name;
        String script = "mkdir -p '" + esc(dir) + "' && cp -f '" + esc(src.getAbsolutePath())
                + "' '" + esc(dst) + "' && stat -c %s '" + esc(dst) + "'";
        String out = execSu(script);
        long size = -1;
        try {
            size = Long.parseLong(out.trim().split("\n")[0].trim());
        } catch (Throwable ignored) {
        }
        if (size <= 0) {
            throw new Exception(out.trim().isEmpty()
                    ? "root 不可用（su 没有授权？请在管理器里给本 App 授权 root）"
                    : "写入后校验失败：" + out.trim());
        }
        if (src.length() > 0 && size != src.length()) {
            throw new Exception("大小不一致（源 " + src.length() + " / 目标 " + size + "）");
        }
        return dst;
    }

    /** SAF 写入：先把 tree URI 规范成 document URI，再按需建分组子目录。 */
    private void writeWithSaf(Uri treeUri, File src, String name, String groupFolder)
            throws Exception {
        ContentResolver cr = host.getContentResolver();
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(treeUri,
                DocumentsContract.getTreeDocumentId(treeUri));
        if (groupFolder != null && !groupFolder.isEmpty()) {
            parent = ensureChildDir(cr, treeUri, parent, groupFolder);
        }
        Uri docUri = DocumentsContract.createDocument(cr, parent,
                "application/vnd.android.package-archive", name);
        if (docUri == null) {
            throw new Exception("创建文件失败：" + name);
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = cr.openOutputStream(docUri);
            if (out == null) {
                throw new Exception("打开输出流失败");
            }
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /** 找到（或创建）分组子目录，返回它的 document URI。 */
    private Uri ensureChildDir(ContentResolver cr, Uri treeUri, Uri parent, String name)
            throws Exception {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
        android.database.Cursor c = null;
        try {
            c = cr.query(children, new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null);
            while (c != null && c.moveToNext()) {
                if (name.equals(c.getString(1))
                        && DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2))) {
                    return DocumentsContract.buildDocumentUriUsingTree(treeUri, c.getString(0));
                }
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        Uri made = DocumentsContract.createDocument(cr, parent,
                DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (made == null) {
            throw new Exception("创建分组子目录失败：" + name);
        }
        return made;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static String esc(String s) {
        return s.replace("'", "'\\''");
    }

    /** 用 root 执行一段 shell，返回 stdout（失败返回空串）。 */
    private static String execSu(String script) {
        for (String su : BaseActivity.suCandidates()) {
            try {
                Process p = Runtime.getRuntime().exec(new String[]{su, "-c", script});
                StringBuilder sb = new StringBuilder();
                BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String l;
                while ((l = br.readLine()) != null) {
                    sb.append(l).append('\n');
                }
                br.close();
                // 把 stderr 也读掉，避免子进程阻塞
                try {
                    BufferedReader er = new BufferedReader(
                            new InputStreamReader(p.getErrorStream()));
                    while (er.readLine() != null) {
                        // 丢弃，但必须读完
                    }
                    er.close();
                } catch (Throwable ignored) {
                }
                int rc = p.waitFor();
                if (rc == 0) {
                    return sb.toString();
                }
            } catch (Throwable ignored) {
            }
        }
        return "";
    }

    private String resolveApk(String pkg) {
        try {
            ApplicationInfo ai = host.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.sourceDir;
        } catch (Throwable t) {
            return null;
        }
    }

    // ================================================================== 命名

    private String buildFileName(String template, AppItem it, int index) {
        String t = (template == null || template.trim().isEmpty()) ? DEFAULT_NAMING : template.trim();
        String an = sanitize(it.title);
        String ap = sanitize(it.pkg);
        String avi = "";
        String ac = "";
        try {
            PackageInfo pi = null;
            try {
                pi = host.getPackageManager().getPackageInfo(it.pkg, 0);
            } catch (Throwable ignored) {
            }
            if (pi != null) {
                avi = sanitize(pi.versionName == null ? "" : pi.versionName);
                ac = String.valueOf(pi.versionCode);
            }
        } catch (Throwable ignored) {
        }
        String g = sanitize(it.group);
        String now = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String date = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
        String name = t.replace("{AN}", an).replace("{AP}", ap)
                .replace("{AVI}", avi).replace("{AC}", ac)
                .replace("{G}", g).replace("{T}", now).replace("{D}", date)
                .replace("{I}", String.valueOf(index));
        name = sanitize(name);
        if (name.isEmpty()) {
            name = sanitize(it.pkg);
        }
        if (!name.toLowerCase(Locale.US).endsWith(".apk")) {
            name = name + ".apk";
        }
        return name;
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        String r = s.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
        // 目录名/文件名不能是 . 或 ..
        if (r.equals(".") || r.equals("..")) {
            r = "_";
        }
        if (r.length() > 120) {
            r = r.substring(0, 120);
        }
        return r;
    }

    // ================================================================== 目录选择

    @Override
    void onExtractDirPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        String norm = normalizeTreeUri(uri);
        try {
            host.getContentResolver().takePersistableUriPermission(Uri.parse(norm),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable t) {
            appendLog("⚠️ 持久化授权失败（重启后可能需要重选）：" + shortErr(t));
        }
        prefs().edit().putString(KEY_URI, norm).apply();
        // 若是内部存储路径，顺便把绝对路径也填上，这样 root 直写和 SAF 用同一个位置
        String abs = treeUriToPath(norm);
        if (abs != null) {
            prefs().edit().putString(KEY_PATH, abs).apply();
            if (mPath != null) {
                mPath.setText(abs);
            }
        }
        mOutText.setText(outLabel());
        host.toast("存放目录已设定");
    }

    /** 只保留 tree 形式：content://auth/tree/<treeId>。子目录/文档 URI 一律规范回 tree。 */
    private static String normalizeTreeUri(Uri uri) {
        List<String> segs = uri.getPathSegments();
        if (segs != null && segs.size() >= 2 && "tree".equals(segs.get(0))) {
            return new Uri.Builder().scheme("content").authority(uri.getAuthority())
                    .appendPath("tree").appendPath(segs.get(1)).build().toString();
        }
        return uri.toString();
    }

    /** content tree URI → 绝对路径（只处理内部存储 volume）。 */
    private static String treeUriToPath(String treeUri) {
        try {
            Uri u = Uri.parse(treeUri);
            List<String> segs = u.getPathSegments();
            if (segs.size() < 2) {
                return null;
            }
            String docId = Uri.decode(segs.get(1));
            if (docId.startsWith("primary:")) {
                String rest = docId.substring("primary:".length());
                int user = android.os.Process.myUid() / 100000;
                return "/storage/emulated/" + user + (rest.isEmpty() ? "" : "/" + rest);
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private String outLabel() {
        String p = basePath();
        String saf = prefs().getString(KEY_URI, null);
        StringBuilder sb = new StringBuilder("目标目录：").append(p);
        boolean per = mPerGroup != null ? mPerGroup.isChecked()
                : prefs().getBoolean(KEY_PER_GROUP, true);
        if (per) {
            sb.append("／<分组名>");
        }
        if (saf != null && !saf.isEmpty()) {
            sb.append("\nSAF 备用目录已设定");
        }
        return sb.toString();
    }

    // ================================================================== 表结构

    private void showSchemaDialog(String[] lines) {
        final String text = joinLines(lines);
        ScrollView sv = new ScrollView(host);
        TextView tv = UiKit.mono(host, text, Theme.onSurface, 10.5f);
        tv.setTextIsSelectable(true);
        UiKit.pad(host, tv, 12, 12, 12);
        sv.addView(tv);
        new android.app.AlertDialog.Builder(host)
                .setTitle("桌面数据库表结构")
                .setView(sv)
                .setPositiveButton("复制全部", new android.content.DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(android.content.DialogInterface d, int w) {
                        try {
                            android.content.ClipboardManager cm =
                                    (android.content.ClipboardManager)
                                            host.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                            if (cm != null) {
                                cm.setPrimaryClip(android.content.ClipData.newPlainText("schema", text));
                            }
                            host.toast("已复制，粘贴发回即可");
                        } catch (Throwable t) {
                            host.toast("复制失败：" + t);
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }

    private static String joinLines(String[] lines) {
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        return sb.toString();
    }

    // ================================================================== 杂项

    private SharedPreferences prefs() {
        return host.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    private String basePath() {
        String s = prefs().getString(KEY_PATH, null);
        if (s == null || s.trim().isEmpty()) {
            return DEFAULT_PATH;
        }
        return s.trim();
    }

    private String loadNaming() {
        String s = prefs().getString(KEY_NAMING, DEFAULT_NAMING);
        return (s == null || s.trim().isEmpty()) ? DEFAULT_NAMING : s;
    }

    private void addInsertChip(LinearLayout row, final String tag, String label) {
        TextView tv = UiKit.text(host, tag + " " + label, Theme.primary, 11f);
        tv.setBackground(UiKit.round(host, Theme.dark ? 0xFF232B20 : 0xFFE2EAD8, Theme.R_PILL));
        tv.setPadding(UiKit.dp(host, 8), UiKit.dp(host, 4), UiKit.dp(host, 8), UiKit.dp(host, 4));
        tv.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                insertTag(tag);
            }
        });
        row.addView(tv, UiKit.wrap(host, 0, 6));
    }

    private void insertTag(String tag) {
        if (mNaming == null) {
            return;
        }
        int start = Math.max(mNaming.getSelectionStart(), 0);
        int end = Math.max(mNaming.getSelectionEnd(), 0);
        mNaming.getText().replace(Math.min(start, end), Math.max(start, end),
                tag, 0, tag.length());
        mNaming.requestFocus();
    }

    private void appendLog(final String s) {
        host.H.post(new Runnable() {
            @Override
            public void run() {
                if (mLog != null) {
                    CharSequence cur = mLog.getText();
                    mLog.setText((cur == null || cur.length() == 0) ? s : cur + "\n" + s);
                }
            }
        });
    }
}
