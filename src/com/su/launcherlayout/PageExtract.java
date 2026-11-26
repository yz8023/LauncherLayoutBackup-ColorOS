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
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * 分组提取 APK：按桌面文件夹分组列出应用，整组勾选后一键提取安装包。
 *
 * <p>分组数据来自桌面进程（广播 {@link Const#ACTION_GROUPS}，读 favorites 表）；
 * APK 定位与拷贝在模块 App 侧完成（PackageManager 定位 + SAF 写入所选目录）。
 */
final class PageExtract extends Page {

    private static final String PREFS = "llb_extract";
    private static final String KEY_URI = "out_uri";
    private static final String KEY_NAMING = "naming";
    private static final String DEFAULT_NAMING = "{AN}_{AVI}.apk";

    static final class AppItem {
        String group;
        String title;
        String pkg;
        boolean checked;
    }

    private final LinkedHashMap<String, List<AppItem>> mGroups = new LinkedHashMap<>();
    private LinearLayout mList;
    private TextView mStatus;
    private TextView mOutText;
    private EditText mNaming;
    private TextView mLog;
    private boolean mLoaded;
    private boolean mExtracting;

    PageExtract(MainActivity h) {
        super(h);
    }

    @Override
    protected View build() {
        ScrollView scroll = new ScrollView(host);
        LinearLayout box = UiKit.column(host);
        UiKit.pad(host, box, 16, 16, 16);
        scroll.addView(box, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(UiKit.pageTitle(host, "分组提取 APK"));
        box.addView(UiKit.rowSummary(host, "按桌面文件夹分组，整组勾选后一键提取安装包"));
        box.addView(new View(host), UiKit.full(host, 8, 0));

        // 配置卡
        LinearLayout cfg = UiKit.card(host);
        UiKit.pad(host, cfg, 16, 14, 14);
        cfg.addView(UiKit.rowTitle(host, "存放与命名"));
        mOutText = UiKit.text(host, outDirLabel(), Theme.onSurfaceVariant, 12f);
        cfg.addView(mOutText, UiKit.full(host, 4, 0));
        cfg.addView(UiKit.subtle(host, "选择存放目录（默认 Download）", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.pickExtractDir();
            }
        }), UiKit.full(host, 6, 0));
        mNaming = new EditText(host);
        mNaming.setSingleLine(true);
        mNaming.setTextSize(13f);
        mNaming.setTextColor(Theme.onSurface);
        mNaming.setBackground(UiKit.round(host, Theme.dark ? 0xFF171B15 : 0xFFF2F6EC, Theme.R_SMALL));
        UiKit.pad(host, mNaming, 12, 10, 10);
        mNaming.setText(loadNaming());
        cfg.addView(mNaming, UiKit.full(host, 10, 0));
        cfg.addView(UiKit.text(host, "点击下方标签可直接插入变量：", Theme.onSurfaceVariant, 11f), UiKit.full(host, 6, 0));

        // 快捷点击插入标签按钮组（支持多行芯片或两行标签）
        LinearLayout tagsRow1 = UiKit.row(host);
        addInsertChip(tagsRow1, "{AN}", "应用名");
        addInsertChip(tagsRow1, "{AP}", "包名");
        addInsertChip(tagsRow1, "{AVI}", "版本名");
        addInsertChip(tagsRow1, "{AC}", "版本号");
        cfg.addView(tagsRow1, UiKit.full(host, 4, 0));

        LinearLayout tagsRow2 = UiKit.row(host);
        addInsertChip(tagsRow2, "{G}", "分组名");
        addInsertChip(tagsRow2, "{D}", "日期");
        addInsertChip(tagsRow2, "{T}", "时间戳");
        addInsertChip(tagsRow2, "{I}", "序号");
        cfg.addView(tagsRow2, UiKit.full(host, 4, 0));
        box.addView(cfg, UiKit.full(host, 0, 10));

        // 工具栏
        LinearLayout bar = UiKit.row(host);
        bar.addView(UiKit.mini(host, "刷新分组", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mLoaded = false;
                host.request(Const.ACTION_GROUPS, null);
            }
        }), UiKit.weight(host, 1f, 0, 5));
        bar.addView(UiKit.mini(host, "全选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllChecked(true);
            }
        }), UiKit.weight(host, 1f, 5, 5));
        bar.addView(UiKit.mini(host, "全不选", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                setAllChecked(false);
            }
        }), UiKit.weight(host, 1f, 5, 5));
        bar.addView(UiKit.mini(host, "导出表结构", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mStatus.setText("正在读取数据库表结构…");
                host.request(Const.ACTION_SCHEMA, null);
            }
        }), UiKit.weight(host, 1f, 5, 0));
        box.addView(bar, UiKit.full(host, 0, 8));

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

    /** 表结构报告：可全选复制，直接发回给作者就能一次改对。 */
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

    @Override
    void onShow() {
        if (!mLoaded) {
            host.request(Const.ACTION_GROUPS, null);
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
        if (!ok) {
            mStatus.setText("加载失败：" + (msg == null ? "" : msg));
            return;
        }
        mGroups.clear();
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
                it.title = parts[1];
                it.pkg = parts[2];
                it.checked = true;
                List<AppItem> list = mGroups.get(it.group);
                if (list == null) {
                    list = new ArrayList<>();
                    mGroups.put(it.group, list);
                }
                list.add(it);
            }
        }
        mLoaded = true;
        int total = 0;
        for (List<AppItem> l : mGroups.values()) {
            total += l.size();
        }
        mStatus.setText("共 " + mGroups.size() + " 个分组、" + total + " 个应用（默认全选）");
        rebuildList();
    }

    @Override
    void onExtractDirPicked(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            host.getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        prefs().edit().putString(KEY_URI, uri.toString()).apply();
        mOutText.setText(outDirLabel());
        host.toast("存放目录已设定");
    }

    // ------------------------------------------------------------------ 列表

    private void rebuildList() {
        mList.removeAllViews();
        for (final String group : mGroups.keySet()) {
            final List<AppItem> apps = mGroups.get(group);
            LinearLayout card = UiKit.card(host);
            UiKit.pad(host, card, 12, 10, 10);

            LinearLayout head = UiKit.row(host);
            head.addView(UiKit.rowTitle(host, group + "（" + apps.size() + "）"),
                    UiKit.weight(host, 1f, 0, 0));
            head.addView(UiKit.mini(host, "全选/取消", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean all = true;
                    for (AppItem a : apps) {
                        if (!a.checked) {
                            all = false;
                            break;
                        }
                    }
                    for (AppItem a : apps) {
                        a.checked = !all;
                    }
                    rebuildList();
                }
            }), UiKit.wrap(host, 0, 0));
            card.addView(head, UiKit.full(host, 0, 4));

            for (final AppItem it : apps) {
                LinearLayout row = UiKit.row(host);
                row.setPadding(0, UiKit.dp(host, 4), 0, UiKit.dp(host, 4));
                final CheckBox cb = new CheckBox(host);
                cb.setChecked(it.checked);
                cb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        it.checked = cb.isChecked();
                    }
                });
                row.addView(cb, UiKit.wrap(host, 0, 0));

                ImageView icon = new ImageView(host);
                try {
                    Drawable d = host.getPackageManager().getApplicationIcon(it.pkg);
                    icon.setImageDrawable(d);
                } catch (Throwable ignored) {
                }
                row.addView(icon, new LinearLayout.LayoutParams(
                        UiKit.dp(host, 32), UiKit.dp(host, 32)));

                LinearLayout texts = UiKit.column(host);
                texts.addView(UiKit.text(host, it.title, Theme.onSurface, 13f));
                texts.addView(UiKit.text(host, it.pkg, Theme.onSurfaceVariant, 11f));
                row.addView(texts, UiKit.weight(host, 1f, 8, 0));
                card.addView(row, UiKit.full(host, 0, 0));
            }
            mList.addView(card, UiKit.full(host, 0, 8));
        }
    }

    private void setAllChecked(boolean v) {
        for (List<AppItem> l : mGroups.values()) {
            for (AppItem a : l) {
                a.checked = v;
            }
        }
        rebuildList();
    }

    // ------------------------------------------------------------------ 提取

    private void startExtract() {
        if (mExtracting) {
            return;
        }
        final String uriStr = prefs().getString(KEY_URI, null);
        if (uriStr == null || uriStr.isEmpty()) {
            host.toast("请先选择存放目录");
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
            host.toast("没有勾选任何应用");
            return;
        }
        final String template = mNaming.getText().toString().trim();
        prefs().edit().putString(KEY_NAMING, template).apply();
        mExtracting = true;
        appendLog("开始提取 " + selected.size() + " 个应用…");
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
                            appendLog("✗ " + it.title + "（" + it.pkg + "）：找不到 APK");
                            fail++;
                            continue;
                        }
                        copyViaSaf(Uri.parse(uriStr), new File(apkPath), name);
                        appendLog("✓ " + it.title + " → " + name);
                        ok++;
                    } catch (Throwable t) {
                        appendLog("✗ " + it.title + "：" + t.getMessage());
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

    private String resolveApk(String pkg) {
        try {
            ApplicationInfo ai = host.getPackageManager().getApplicationInfo(pkg, 0);
            return ai.sourceDir;
        } catch (Throwable t) {
            return null;
        }
    }

    private void copyViaSaf(Uri treeUri, File src, String displayName) throws Exception {
        ContentResolver cr = host.getContentResolver();
        Uri docUri = DocumentsContract.createDocument(cr, treeUri,
                "application/vnd.android.package-archive", displayName);
        if (docUri == null) {
            throw new Exception("创建文件失败：" + displayName);
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
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ 命名

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
        return s.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ 配置

    private SharedPreferences prefs() {
        return host.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE);
    }

    private String loadNaming() {
        String s = prefs().getString(KEY_NAMING, DEFAULT_NAMING);
        return (s == null || s.trim().isEmpty()) ? DEFAULT_NAMING : s;
    }

    private String outDirLabel() {
        String s = prefs().getString(KEY_URI, null);
        if (s == null || s.isEmpty()) {
            return "存放目录：未选择（默认 Download，点下面按钮选一次）";
        }
        String tail = s;
        int i = tail.lastIndexOf('/');
        if (i >= 0) {
            tail = tail.substring(i + 1);
        }
        try {
            tail = Uri.decode(tail);
        } catch (Throwable ignored) {
        }
        return "存放目录：" + tail;
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
                    mLog.setText(mLog.getText() + "\n" + s);
                }
            }
        });
    }
}
