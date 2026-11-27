package com.su.launcherlayout;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

/** 备份页：滚动列表（不分页，自带快速滚动条）+ 一键删除全部。 */
final class PageBackup extends Page {

    private TextView mHeader;
    private ListView mList;
    private LinearLayout mEmpty;
    private Adapter mAdapter;
    private String[] mDirs = new String[0];

    PageBackup(MainActivity h) {
        super(h);
    }

    @Override
    protected View build() {
        LinearLayout root = UiKit.column(host);
        root.setBackgroundColor(Theme.page);

        LinearLayout bar = UiKit.row(host);
        UiKit.pad(host, bar, 20, 22, 4);
        bar.addView(UiKit.pageTitle(host, "全部备份"), UiKit.weight(host, 1f, 0, 0));
        root.addView(bar);

        mHeader = UiKit.rowSummary(host, "读取中…");
        mHeader.setPadding(UiKit.dp(host, 20), 0, UiKit.dp(host, 20), UiKit.dp(host, 8));
        root.addView(mHeader);

        mList = new ListView(host);
        mList.setBackgroundColor(Theme.page);
        mList.setDivider(new ColorDrawable(Theme.page));
        mList.setDividerHeight(UiKit.dp(host, 10));
        mList.setPadding(UiKit.dp(host, 16), 0, UiKit.dp(host, 16), UiKit.dp(host, 8));
        mList.setClipToPadding(false);
        mList.setFastScrollEnabled(true);
        mList.setVerticalScrollBarEnabled(true);
        mAdapter = new Adapter();
        mList.setAdapter(mAdapter);
        root.addView(mList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mEmpty = UiKit.column(host);
        mEmpty.setPadding(UiKit.dp(host, 20), UiKit.dp(host, 24), UiKit.dp(host, 20), 0);
        root.addView(mEmpty, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout foot = UiKit.row(host);
        UiKit.pad(host, foot, 20, 6, 8);
        foot.addView(UiKit.danger(host, "删除全部备份", new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                confirmDeleteAll();
            }
        }), UiKit.weight(host, 1f, 0, 0));
        root.addView(foot);
        return root;
    }

    @Override
    void onShow() {
        host.request(Const.ACTION_LIST, null);
        host.request(Const.ACTION_GET_CONFIG, null);
    }

    @Override
    void onResult(String op, boolean ok, String msg, String dir, String[] items, String[] grids) {
        if (mAdapter == null) {
            return; // 视图还没建（本页没显示过），onShow() 会重新拉
        }
        if (Const.OP_INFO.equals(op) && ok) {
            host.currentGrid = UiKit.parseGrid(dir);
            mAdapter.notifyDataSetChanged();
        }
        if (Const.OP_LIST.equals(op) && items != null) {
            mDirs = items;
            mAdapter.notifyDataSetChanged();
            updateHeader();
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
        if (Const.OP_DELETE_ALL.equals(op)) {
            UiLog.log((ok ? "✅ " : "❌ ") + "删除全部备份 → " + msg);
            host.request(Const.ACTION_LIST, null);
            host.request(Const.ACTION_GET_CONFIG, null);
            return;
        }
        if (Const.OP_RESTORE.equals(op)) {
            UiLog.log((ok ? "✅ " : "❌ ") + "恢复 → " + msg);
        }
    }

    private void updateHeader() {
        final int n = mDirs.length;
        if (n == 0) {
            mList.setVisibility(View.GONE);
            mEmpty.setVisibility(View.VISIBLE);
            mEmpty.removeAllViews();
            LinearLayout card = UiKit.card(host);
            UiKit.pad(host, card, 18, 18, 18);
            card.addView(UiKit.rowTitle(host, "当前目录下没有备份"));
            TextView t = UiKit.rowSummary(host, host.rootPath);
            t.setPadding(0, UiKit.dp(host, 6), 0, 0);
            card.addView(t);
            mEmpty.addView(card);
            mHeader.setText("共 0 份");
            return;
        }
        mList.setVisibility(View.VISIBLE);
        mEmpty.setVisibility(View.GONE);
        mHeader.setText("共 " + n + " 份 · 按时间倒序 · 右侧可快速滚动\n" + host.rootPath);
    }

    private void confirmDeleteAll() {
        if (mDirs.length == 0) {
            host.toast("没有可删除的备份");
            return;
        }
        new AlertDialog.Builder(host)
                .setTitle("删除全部 " + mDirs.length + " 份备份？")
                .setMessage("将删除 " + host.rootPath + " 下全部备份。\n"
                        + "删除前会自动留一份安全备份，可随时找回。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        UiLog.log("▶ 删除全部备份（会先留安全备份）");
                        host.request(Const.ACTION_DELETE_ALL, null);
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    // ================================================================== 适配器

    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mDirs.length;
        }

        @Override
        public Object getItem(int position) {
            return mDirs[position];
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        /**
         * 每条备份一张卡：
         * 第一行 = 时间 + 「最新」胶囊，
         * 第二行 = 相对时间 · 网格一致性，
         * 右侧 = 恢复。
         *
         * <p>备份数量是十几到几十条，直接每次建视图（不做 convertView 复用），
         * 省掉原来那套「按固定子节点位置回填」的脆弱写法。
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final String name = mDirs[position];
            final boolean newest = (position == 0);
            final String g = host.grids.containsKey(name) ? host.grids.get(name) : "通用";
            final boolean same = g.equals(host.currentGrid);

            LinearLayout card = UiKit.column(host);
            card.setBackground(UiKit.round(host,
                    newest ? (Theme.dark ? 0xFF20281E : 0xFFF0F8EC) : Theme.card, Theme.R_CARD));
            UiKit.pad(host, card, 16, 13, 13);

            LinearLayout top = UiKit.row(host);
            TextView when = UiKit.text(host, UiKit.prettyTime(name), Theme.onSurface, 14.5f);
            when.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    newest ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            top.addView(when, UiKit.wrap(host, 0, 8));

            if (newest) {
                TextView pill = UiKit.text(host, "最新", Theme.onPrimary, 10.5f);
                pill.setBackground(UiKit.round(host, Theme.primary, Theme.R_PILL));
                pill.setPadding(UiKit.dp(host, 8), UiKit.dp(host, 3),
                        UiKit.dp(host, 8), UiKit.dp(host, 3));
                top.addView(pill);
            }
            top.addView(new LinearLayout(host), UiKit.weight(host, 1f, 0, 8));
            top.addView(UiKit.mini(host, "恢复", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    host.startRestoreFlow(name);
                }
            }));
            card.addView(top);

            LinearLayout meta = UiKit.row(host);
            String rel = UiKit.relativeTime(name);
            TextView m1 = UiKit.text(host, (rel.isEmpty() ? "" : rel + " · ")
                    + "网格 " + g + (same ? " ✓" : " ≠ " + host.currentGrid),
                    same ? Theme.onSurfaceVariant : Theme.error, 11.5f);
            meta.addView(m1, UiKit.weight(host, 1f, 0, 0));
            meta.addView(UiKit.text(host, "#" + (position + 1), Theme.onSurfaceVariant, 10.5f));
            meta.setPadding(0, UiKit.dp(host, 3), 0, 0);
            card.addView(meta);

            return card;
        }
    }
}
