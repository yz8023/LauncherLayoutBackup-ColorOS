package com.su.launcherlayout;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * 主页：一屏看完「现在什么状态 / 该点什么」。
 *
 * <p>设计原则（v1.2.0 起）：
 * <ul>
 *   <li>每个功能只有一个入口 —— 备份/恢复在这儿，重启桌面在设置，备份路径在设置；</li>
 *   <li>层级只有三层：状态 → 主操作 → 次要信息；</li>
 *   <li>不再把「重启桌面」「一键恢复最近备份」在多个地方各放一份。</li>
 * </ul>
 */
final class PageHome extends Page {

    /** 状态点在「已连接 / 未响应」时的颜色由 {@link Theme} 决定。 */
    private View mDot;
    private TextView mStatusTitle;
    private TextView mStatusSub;
    private TextView mGridChip;
    private TextView mAutoSum;
    private LinearLayout mRecentBody;
    private LinearLayout mGhostCard;
    private TextView mGhostText;
    private View mLogPanel;

    PageHome(MainActivity h) {
        super(h);
    }

    @Override
    protected View build() {
        LinearLayout root = UiKit.column(host);
        root.setBackgroundColor(Theme.page);

        ScrollView scroll = new ScrollView(host);
        LinearLayout c = UiKit.column(host);
        UiKit.pad(host, c, 20, 24, 20);
        scroll.addView(c, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mLogPanel = host.buildLogPanel(132);
        root.addView(mLogPanel, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, UiKit.dp(host, 132)));

        // ---------- 标题 ----------
        LinearLayout bar = UiKit.row(host);
        bar.addView(UiKit.pageTitle(host, "桌面布局备份"), UiKit.weight(host, 1f, 0, 0));
        ImageView more = UiKit.icon(host, R.drawable.ic_more, 22, Theme.onSurfaceVariant);
        more.setBackground(UiKit.clickable(host, 0x00000000, Theme.R_PILL));
        UiKit.pad(host, more, 10, 10, 10);
        more.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showMenu();
            }
        });
        bar.addView(more);
        c.addView(bar);

        TextView sub = UiKit.text(host, "只备份排版数据，不含 APK",
                Theme.onSurfaceVariant, 12.5f);
        sub.setPadding(0, UiKit.dp(host, 5), 0, 0);
        c.addView(sub);

        // ---------- 状态条（一行，不是一大块） ----------
        LinearLayout status = UiKit.row(host);
        status.setBackground(UiKit.round(host, Theme.dark ? 0xFF1D211A : 0xFFFBFDF7,
                Theme.R_CARD));
        UiKit.pad(host, status, 16, 13, 13);

        mDot = new View(host);
        mDot.setBackground(UiKit.round(host, Theme.onSurfaceVariant, Theme.R_PILL));
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(
                UiKit.dp(host, 9), UiKit.dp(host, 9));
        dotLp.rightMargin = UiKit.dp(host, 10);
        status.addView(mDot, dotLp);

        LinearLayout scol = UiKit.column(host);
        mStatusTitle = UiKit.text(host, "检测中…", Theme.onSurface, 15f);
        mStatusTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mStatusSub = UiKit.text(host, "", Theme.onSurfaceVariant, 11.5f);
        mStatusSub.setPadding(0, UiKit.dp(host, 2), 0, 0);
        scol.addView(mStatusTitle);
        scol.addView(mStatusSub);
        status.addView(scol, UiKit.weight(host, 1f, 0, 8));

        mGridChip = UiKit.text(host, "—", Theme.onPrimaryContainer, 11f);
        mGridChip.setBackground(UiKit.round(host, Theme.primaryContainer, Theme.R_PILL));
        mGridChip.setPadding(UiKit.dp(host, 10), UiKit.dp(host, 4),
                UiKit.dp(host, 10), UiKit.dp(host, 4));
        status.addView(mGridChip);
        c.addView(status, UiKit.full(host, 16, 0));

        // ---------- 主操作 ----------
        LinearLayout act = UiKit.card(host);
        LinearLayout actIn = UiKit.column(host);
        UiKit.pad(host, actIn, 16, 16, 16);

        TextView actTitle = UiKit.text(host, "立即备份", Theme.onSurface, 17f);
        actTitle.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        actIn.addView(actTitle);
        TextView actSub = UiKit.text(host, "抓一份当前排版，通常只有几百 KB，几秒完成",
                Theme.onSurfaceVariant, 12f);
        actSub.setPadding(0, UiKit.dp(host, 3), 0, 0);
        actIn.addView(actSub);

        android.widget.Button go = UiKit.filled(host, "备份当前桌面布局", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                UiLog.log("▶ 立即备份");
                host.request(Const.ACTION_BACKUP, null);
            }
        });
        actIn.addView(go, UiKit.full(host, 14, 0));
        act.addView(actIn);
        c.addView(act, UiKit.full(host, 12, 0));

        // ---------- 最近备份 ----------
        LinearLayout rk = UiKit.card(host);
        LinearLayout rkIn = UiKit.column(host);
        UiKit.pad(host, rkIn, 16, 14, 14);
        LinearLayout rkHead = UiKit.row(host);
        rkHead.addView(UiKit.sectionLabel(host, "最近一次备份"), UiKit.weight(host, 1f, 0, 0));
        TextView all = UiKit.text(host, "全部 →", Theme.primary, 12.5f);
        all.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        all.setBackground(UiKit.clickable(host, 0x00000000, Theme.R_PILL));
        UiKit.pad(host, all, 10, 5, 5);
        all.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showTab(MainActivity.TAB_BACKUP);
            }
        });
        rkHead.addView(all);
        rkIn.addView(rkHead);

        mRecentBody = UiKit.column(host);
        rkIn.addView(mRecentBody, UiKit.full(host, 8, 0));
        rk.addView(rkIn);
        c.addView(rk, UiKit.full(host, 12, 0));

        // ---------- 自动备份（一行摘要，点进设置） ----------
        LinearLayout ack = UiKit.card(host);
        LinearLayout ar = UiKit.row(host);
        ar.setBackground(UiKit.clickable(host, 0x00000000, Theme.R_CARD));
        UiKit.pad(host, ar, 16, 14, 14);
        ar.addView(UiKit.iconMr(host, R.drawable.ic_tune, 20, Theme.primary, 12));
        LinearLayout arc = UiKit.column(host);
        TextView aT = UiKit.text(host, "自动备份", Theme.onSurface, 14.5f);
        arc.addView(aT);
        mAutoSum = UiKit.text(host, "读取中…", Theme.onSurfaceVariant, 11.5f);
        mAutoSum.setPadding(0, UiKit.dp(host, 2), 0, 0);
        arc.addView(mAutoSum);
        ar.addView(arc, UiKit.weight(host, 1f, 0, 0));
        ar.addView(UiKit.icon(host, R.drawable.ic_chevron, 18, Theme.onSurfaceVariant));
        ar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showTab(MainActivity.TAB_SETTINGS);
            }
        });
        ack.addView(ar);
        c.addView(ack, UiKit.full(host, 12, 0));

        // ---------- 删除前的安全备份（有才显示） ----------
        mGhostCard = UiKit.card(host);
        LinearLayout gh = UiKit.column(host);
        UiKit.pad(host, gh, 16, 14, 14);
        mGhostText = UiKit.text(host, "", Theme.onSurface, 13f);
        gh.addView(mGhostText);
        LinearLayout gr = UiKit.row(host);
        gr.addView(UiKit.mini(host, "找回这份备份", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                recoverGhost();
            }
        }));
        gr.addView(UiKit.mini(host, "丢弃", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dropGhost();
            }
        }), UiKit.wrap(host, 8, 0));
        gh.addView(gr, UiKit.full(host, 10, 0));
        mGhostCard.addView(gh);
        mGhostCard.setVisibility(View.GONE);
        c.addView(mGhostCard, UiKit.full(host, 12, 0));

        renderRecent(host.lastDirs());
        updateAutoSummary();
        updateGhost();
        updateLogVisibility();
        return root;
    }

    /** 「显示操作日志」关掉后，主页就跟其它页一样干净。 */
    private void updateLogVisibility() {
        if (mLogPanel == null) {
            return;
        }
        final boolean on = !"0".equals(host.cfg.get("logOn"));
        mLogPanel.setVisibility(on ? View.VISIBLE : View.GONE);
    }

    // ------------------------------------------------------------------ 状态

    void showOffline() {
        if (mStatusTitle == null) {
            return;
        }
        mStatusTitle.setText("桌面进程未响应");
        mStatusTitle.setTextColor(Theme.error);
        mDot.setBackground(UiKit.round(host, Theme.error, Theme.R_PILL));
        mStatusSub.setText("点右上角 ⋯ → 重启桌面，或去设置里检查连接");
    }

    private void showOnline() {
        mStatusTitle.setText("已连接");
        mStatusTitle.setTextColor(Theme.onSurface);
        mDot.setBackground(UiKit.round(host, Theme.ok, Theme.R_PILL));
        StringBuilder s = new StringBuilder("模块已注入桌面进程");
        String root = host.rootPath;
        if (root != null && !root.isEmpty()) {
            s.append(" · ").append(root);
        }
        mStatusSub.setText(s.toString());
        mGridChip.setText(host.currentGrid);
    }

    // ------------------------------------------------------------------ 回执

    @Override
    void onResult(String op, boolean ok, String msg, String dir, String[] items, String[] grids) {
        if (Const.OP_INFO.equals(op)) {
            if (ok) {
                showOnline();
                host.request(Const.ACTION_LIST, null);
                host.request(Const.ACTION_GET_CONFIG, null);
            } else {
                showOffline();
            }
            return;
        }
        if (Const.OP_LIST.equals(op) && items != null) {
            renderRecent(items);
            updateGhost();
            return;
        }
        if (Const.OP_GET_CONFIG.equals(op) || Const.OP_SET_CONFIG.equals(op)
                || Const.OP_SET_ROOT.equals(op) || Const.OP_SET_EXTRA.equals(op)
                || Const.OP_DELETE_ALL.equals(op) || Const.OP_DROP_GHOST.equals(op)
                || Const.OP_RECOVER_GHOST.equals(op)) {
            updateAutoSummary();
            updateGhost();
            updateLogVisibility();
            if (Const.OP_RECOVER_GHOST.equals(op)) {
                UiLog.log((ok ? "✅ " : "❌ ") + msg);
                host.toast(ok ? "已找回，去「备份」页查看" : msg);
                host.request(Const.ACTION_LIST, null);
            }
            return;
        }
        if (Const.OP_FILES.equals(op)) {
            if (ok && items != null && items.length > 0) {
                host.showFilePicker(host.pendingRestoreDir, items);
            } else {
                UiLog.log("❌ 读取可选文件失败：" + msg);
            }
            return;
        }
        if (Const.OP_BACKUP.equals(op)) {
            UiLog.log((ok ? "✅ " : "❌ ") + "备份 → " + (ok ? dir : msg));
            if (ok) {
                host.request(Const.ACTION_LIST, null);
                host.request(Const.ACTION_GET_CONFIG, null);
            }
            return;
        }
        if (Const.OP_RESTORE.equals(op)) {
            UiLog.log((ok ? "✅ " : "❌ ") + "恢复 → " + msg);
        }
    }

    // ------------------------------------------------------------------ 渲染

    private void updateAutoSummary() {
        if (mAutoSum == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        boolean any = "true".equals(host.cfg.get("en7")) || "true".equals(host.cfg.get("en30"))
                || "true".equals(host.cfg.get("en180"));
        if (any) {
            if ("true".equals(host.cfg.get("en7"))) {
                sb.append("每 7 天");
            } else if ("true".equals(host.cfg.get("en30"))) {
                sb.append("每 30 天");
            } else {
                sb.append("每 180 天");
            }
            sb.append(" · 上次 ").append(UiKit.fmtTs(host.cfg.get("lastAuto"), "还没有"));
        } else {
            sb.append("已关闭 · 点这里去打开");
        }
        mAutoSum.setText(sb.toString());
    }

    private void renderRecent(String[] items) {
        if (mRecentBody == null) {
            return;
        }
        mRecentBody.removeAllViews();
        if (items == null || items.length == 0) {
            TextView t = UiKit.text(host, "还没有备份", Theme.onSurface, 14f);
            mRecentBody.addView(t);
            TextView s = UiKit.text(host, "点上面「备份当前桌面布局」存第一份", Theme.onSurfaceVariant, 11.5f);
            s.setPadding(0, UiKit.dp(host, 3), 0, 0);
            mRecentBody.addView(s);
            return;
        }
        final String name = items[0];
        String g = host.grids.containsKey(name) ? host.grids.get(name) : "通用";
        boolean same = g.equals(host.currentGrid);
        String rel = UiKit.relativeTime(name);

        LinearLayout line = UiKit.row(host);
        TextView when = UiKit.text(host, rel.isEmpty() ? UiKit.prettyTime(name) : rel,
                Theme.onSurface, 14.5f);
        when.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        line.addView(when, UiKit.weight(host, 1f, 0, 8));
        TextView chip = UiKit.text(host, same ? ("网格 " + g + " ✓") : ("网格 " + g + " ≠ " + host.currentGrid),
                same ? Theme.primary : Theme.error, 11f);
        chip.setBackground(UiKit.round(host,
                same ? Theme.primaryContainer : (Theme.dark ? 0xFF3B1A18 : 0xFFFFDAD6), Theme.R_PILL));
        chip.setPadding(UiKit.dp(host, 9), UiKit.dp(host, 4), UiKit.dp(host, 9), UiKit.dp(host, 4));
        line.addView(chip);
        mRecentBody.addView(line);

        TextView time = UiKit.text(host, UiKit.prettyTime(name), Theme.onSurfaceVariant, 11.5f);
        time.setPadding(0, UiKit.dp(host, 3), 0, 0);
        mRecentBody.addView(time);

        LinearLayout br = UiKit.row(host);
        br.addView(UiKit.tonal(host, "恢复这份", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.startRestoreFlow(name);
            }
        }), UiKit.weight(host, 1f, 0, 5));
        br.addView(UiKit.subtle(host, "查看全部 " + items.length + " 份", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                host.showTab(MainActivity.TAB_BACKUP);
            }
        }), UiKit.weight(host, 1f, 5, 0));
        mRecentBody.addView(br, UiKit.full(host, 12, 0));
    }

    private void updateGhost() {
        if (mGhostCard == null) {
            return;
        }
        boolean has = "1".equals(host.cfg.get("ghost"));
        mGhostCard.setVisibility(has ? View.VISIBLE : View.GONE);
        if (has) {
            mGhostText.setText("上次「删除全部备份」前留了一份安全备份");
        }
    }

    // ------------------------------------------------------------------ 动作

    private void recoverGhost() {
        new AlertDialog.Builder(host)
                .setTitle("找回删除前的备份？")
                .setMessage("把它变回一份正常备份，出现在「备份」页，之后你自己决定要不要恢复。\n"
                        + "不会直接动你的桌面。")
                .setPositiveButton("找回", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        UiLog.log("▶ 找回删除前的安全备份（转为可见）");
                        host.recoverGhost();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void dropGhost() {
        new AlertDialog.Builder(host)
                .setTitle("丢弃安全备份？")
                .setMessage("删除后无法恢复。")
                .setPositiveButton("丢弃", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        host.discardGhost();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 菜单只放「一次性维护动作」，不放日常操作（日常操作都在卡片上）。 */
    private void showMenu() {
        final String[] items = {"重新检测桌面进程", "打开备份目录", "复制备份路径", "重新走一遍使用前检查"};
        new AlertDialog.Builder(host)
                .setTitle("更多")
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0:
                                UiLog.log("▶ 重新检测");
                                host.request(Const.ACTION_INFO, null);
                                break;
                            case 1:
                                host.openDir(null);
                                break;
                            case 2:
                                host.copyPath(host.rootPath);
                                break;
                            default:
                                host.recheckGate();
                                break;
                        }
                    }
                })
                .setNegativeButton("关闭", null)
                .show();
    }
}
