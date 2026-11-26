package com.su.launcherlayout;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 备份 / 恢复 引擎。运行在「ColorOS 桌面进程」内，因此：
 * <ul>
 *   <li>读写 /data/user_de/0/com.android.launcher/ 下的私有库天然合法，不需要额外授权
 *       （本模块的读写代码就跑在桌面进程内，用的是桌面自己的身份）；</li>
 *   <li>桌面进程以自身身份写共享存储。实测 ColorOS 桌面（16.6.8）未声明
 *       MANAGE_EXTERNAL_STORAGE / WRITE_EXTERNAL_STORAGE，默认不能直写 /sdcard/Download；
 *       本版在 Schedule.rootDir() 里做了回退：不可写时自动切到桌面自己的
 *       external files 目录（Android/data/com.android.launcher/files/）。</li>
 * </ul>
 *
 * <p>与小米版的区别：ColorOS 的主库文件名与表名未知（可能是 launcher.db、
 * oppo_launcher.db 或其它），因此本版<b>不硬编码库名</b>，而是自动探测
 * databases 目录下所有 .db 文件并全部备份；主库通过表名打分识别
 * （含 favorites / desktop / screen / widget 等表者优先）。
 */
final class BackupEngine {

    static final class Result {
        final boolean ok;
        final String message;
        final String dir;
        final String[] items;

        Result(boolean ok, String message, String dir, String[] items) {
            this.ok = ok;
            this.message = message;
            this.dir = dir;
            this.items = items;
        }
    }

    private BackupEngine() {
    }

    private static final String STAMP = "yyyyMMdd_HHmmss";

    /** VACUUM INTO 生成的一致性快照后缀。 */
    static final String SNAPSHOT_SUFFIX = ".db.snapshot";

    /** 小米版附属库名（仅为兼容旧备份保留；ColorOS 版实际备份 databases 下所有 .db）。 */
    private static final String[] AUX_DBS = {
            "miui_widget.db", "app_status.db", "app_category_new.db"
    };

    /** 数据库文件可能的后缀。 */
    private static final String[] DB_SUFFIX = {"", "-wal", "-shm", "-journal"};

    /**
     * 恢复时记录的「源文件 / 目标库」绝对路径。
     *
     * <p>为什么不直接在本进程里写库：本进程就是桌面进程，写完到被杀之间有窗口，
     * ColorOS 的 LauncherProvider 会把自己的内存状态回写覆盖掉刚写的内容
     * （实测：文件 mtime 变了但内容没变）。所以真正的写库交给一个**独立 shell 进程**，
     * 在桌面进程死掉之后再执行。
     */
    static volatile String sRestoreSrc;

    /** 待恢复库的后缀与标记文件名。 */
    static final String RESTORE_SUFFIX = ".pending";
    static final String RESTORE_FLAG = ".llb_restore_pending";

    /**
     * 桌面进程【启动最早期】调用（hook 在 ActivityThread.handleBindApplication 的 proceed 之前）。
     *
     * <p>若上次恢复留下了待替换的库，就在这里就地换掉。
     * 此刻 LauncherProvider 还没初始化、还没打开数据库，
     * 不存在"进程把自己的内存状态回写覆盖"的窗口 —— 这是恢复能生效的关键。
     */
    static void applyPendingRestore() {
        try {
            final File dbDir = new File("/data/user_de/0/" + Const.TARGET_PKG + "/databases");
            final File flag = new File(dbDir, RESTORE_FLAG);
            if (!flag.isFile()) {
                return;
            }
            String name = readText(flag).trim();
            if (name.length() > 0 && !name.endsWith(".db")) {
                name = name + ".db";
            }
            boolean done = false;
            if (name.length() > 0) {
                final File pending = new File(dbDir, name + RESTORE_SUFFIX);
                if (pending.isFile()) {
                    final File dst = new File(dbDir, name);
                    if (pending.renameTo(dst)) {
                        for (String suf : new String[]{"-wal", "-shm", "-journal"}) {
                            File f = new File(dbDir, name + suf);
                            if (f.exists()) {
                                f.delete();
                            }
                        }
                        done = true;
                        Bridge.log(Log.WARN, Const.TAG, "启动时接管：已用待恢复库替换 " + name);
                    }
                }
            }
            if (!done) {
                Bridge.log(Log.ERROR, Const.TAG, "启动时接管失败（待恢复库缺失或改名失败）");
            }
            flag.delete();
        } catch (Throwable t) {
            Bridge.log(Log.ERROR, Const.TAG, "启动时接管异常", t);
        }
    }
    static volatile String sRestoreDst;

    private static volatile File sRestoreDbDir;

    // =====================================================================
    // 路径探测
    // =====================================================================

    private static void addDir(List<File> out, File f) {
        if (f == null) {
            return;
        }
        for (File x : out) {
            if (x.getAbsolutePath().equals(f.getAbsolutePath())) {
                return;
            }
        }
        if (f.isDirectory()) {
            out.add(f);
        }
    }

    /** 候选 databases 目录：设备保护存储 / 普通存储 / 兜底硬编码。 */
    private static List<File> dbDirs(Context ctx) {
        List<File> out = new ArrayList<>();
        try {
            Context de = ctx.createDeviceProtectedStorageContext();
            if (de != null) {
                addDir(out, de.getDatabasePath("x").getParentFile());
            }
        } catch (Throwable ignored) {
        }
        try {
            addDir(out, ctx.getDatabasePath("x").getParentFile());
        } catch (Throwable ignored) {
        }
        // 用户号：uid / 100000（Context.getUserId() 是 @hide，编译用 jar 里没有）
        final int user = android.os.Process.myUid() / 100000;
        addDir(out, new File("/data/user_de/" + user + "/" + Const.TARGET_PKG + "/databases"));
        addDir(out, new File("/data/user/" + user + "/" + Const.TARGET_PKG + "/databases"));
        return out;
    }

    private static boolean hasLauncherDb(File dir) {
        File[] ls = dir.listFiles();
        if (ls == null) {
            return false;
        }
        for (File f : ls) {
            if (isAnyDb(f.getName())) {
                return true;
            }
        }
        return false;
    }

    /** 小米版主库判定（launcher*.db），仅为兼容旧备份保留。 */
    private static boolean isLauncherDb(String n) {
        return n != null && n.startsWith("launcher") && n.endsWith(".db");
    }

    /** ColorOS 通用判定：任意 .db 文件都算桌面相关库。 */
    static boolean isAnyDb(String n) {
        return n != null && n.endsWith(".db") && !n.contains("/");
    }

    /** 列出目录下所有 .db 文件（按大小降序，大的通常是主库）。 */
    static List<File> listDbFiles(File dir) {
        List<File> out = new ArrayList<>();
        if (dir == null) {
            return out;
        }
        File[] ls = dir.listFiles();
        if (ls != null) {
            for (File f : ls) {
                if (f.isFile() && isAnyDb(f.getName())) {
                    out.add(f);
                }
            }
        }
        Collections.sort(out, (a, b) -> Long.compare(b.length(), a.length()));
        return out;
    }

    /**
     * 识别「主库」：打开每个 .db 看表名打分，含 favorites / desktop / screen /
     * widget / item 等表者优先；打分全为 0 时退回最大的 .db。
     * 这样 ColorOS 无论库名叫什么都能找到主库。
     */
    static String detectMainDbName(File dir) {
        List<File> dbs = listDbFiles(dir);
        if (dbs.isEmpty()) {
            return null;
        }
        String best = null;
        int bestScore = -1;
        long bestSize = -1;
        for (File f : dbs) {
            int score = scoreDb(f);
            long size = f.length();
            if (score > bestScore || (score == bestScore && size > bestSize)) {
                bestScore = score;
                bestSize = size;
                best = f.getName();
            }
        }
        return best;
    }

    private static int scoreDb(File f) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            Cursor c = null;
            try {
                c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
                int score = 0;
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    if (name == null) {
                        continue;
                    }
                    String low = name.toLowerCase();
                    if (low.contains("favorite")) {
                        score += 10;
                    } else if (low.contains("desktop") || low.contains("single")) {
                        score += 8;
                    } else if (low.contains("screen") || low.contains("page")) {
                        score += 5;
                    } else if (low.contains("widget")) {
                        score += 5;
                    } else if (low.contains("item") || low.contains("shortcut")
                            || low.contains("folder")) {
                        score += 3;
                    }
                }
                return score;
            } finally {
                if (c != null) {
                    c.close();
                }
            }
        } catch (Throwable ignored) {
            return 0;
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** 选「真的装着 .db 且最新」的那个目录。 */
    static File primaryDbDir(Context ctx) {
        File best = null;
        long bestTs = -1L;
        for (File d : dbDirs(ctx)) {
            if (!hasLauncherDb(d)) {
                continue;
            }
            File[] ls = d.listFiles();
            long ts = 0L;
            if (ls != null) {
                for (File f : ls) {
                    if (isAnyDb(f.getName())) {
                        ts = Math.max(ts, f.lastModified());
                    }
                }
            }
            if (ts > bestTs) {
                bestTs = ts;
                best = d;
            }
        }
        if (best == null) {
            List<File> cs = dbDirs(ctx);
            best = cs.isEmpty() ? null : cs.get(0);
        }
        return best;
    }

    private static File prefsDir(Context ctx) {
        try {
            Context de = ctx.createDeviceProtectedStorageContext();
            if (de != null) {
                File d = new File(de.getDataDir(), "shared_prefs");
                if (d.isDirectory()) {
                    return d;
                }
            }
        } catch (Throwable ignored) {
        }
        File d = new File(ctx.getDataDir(), "shared_prefs");
        if (d.isDirectory()) {
            return d;
        }
        try {
            File de = new File(ctx.createDeviceProtectedStorageContext().getDataDir(), "shared_prefs");
            if (de.isDirectory()) {
                return de;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =====================================================================
    // 备份
    // =====================================================================

    static Result backup(Context ctx) throws IOException {
        return backup(ctx, "手动");
    }

    static Result backup(Context ctx, String trigger) throws IOException {
        final String name = Const.BACKUP_PREFIX + stamp();
        // 注意：目标必须是「根目录 / 名字」，不能直接传 destDir()，
        // 否则所有备份文件会平铺进备份根目录（曾经踩过的坑）。
        Result r = backupInto(ctx, new File(destDir(), name), name, trigger);
        if (r.ok) {
            // 已经有新的正式备份了，删除前的安全备份（幽灵）就可以退役了
            retireGhost();
        }
        return r;
    }

    /** 与 backup 相同，但**不**退役幽灵（恢复前自动备份用，避免把正在恢复的来源删掉）。 */
    static Result backupNoRetire(Context ctx, String trigger) throws IOException {
        final String name = Const.BACKUP_PREFIX + stamp();
        return backupInto(ctx, new File(destDir(), name), name, trigger);
    }

    static File destDir() {
        return Schedule.rootDir();
    }

    private static String stamp() {
        return new SimpleDateFormat(STAMP, Locale.US).format(new Date());
    }

    /** 把当前桌面状态写进 dest（目录会被创建）。name 只用于结果文案。 */
    static Result backupInto(Context ctx, File dest, String name, String trigger) throws IOException {
        // 安全阀：备份目标绝不能是备份根目录本身（否则会把文件平铺进根目录）
        if (dest == null || dest.equals(Schedule.rootDir())) {
            return new Result(false, "内部错误：备份目标不能是备份根目录本身", null, null);
        }
        File dbDir = primaryDbDir(ctx);
        if (dbDir == null || !dbDir.isDirectory()) {
            return new Result(false, "找不到桌面数据库目录", null, null);
        }
        if (!dest.isDirectory() && !dest.mkdirs()) {
            return new Result(false, "无法创建备份目录：" + dest, null, null);
        }

        int count = 0;
        StringBuilder notes = new StringBuilder();
        String grid = "通用";

        // 1) 桌面布局库：ColorOS 版备份 databases 下【所有】.db 及其 wal/shm/journal，
        //    不硬编码库名（小米版只备 launcher*.db + 附属库，ColorOS 库名未知必须全备）。
        File[] files = dbDir.listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (f.isDirectory()) {
                    continue;
                }
                if (isLauncherDb(n) && grid.equals("通用")) {
                    String base = n.substring(0, n.length() - 3);
                    int x = base.indexOf('x');
                    if (x > 0) {
                        grid = base.substring(x - 1, x) + " x " + base.substring(x + 1);
                    }
                }
                boolean isDb = isAnyDb(n);
                boolean isDbSidecar = n.endsWith(".db-wal") || n.endsWith(".db-shm")
                        || n.endsWith(".db-journal");
                if (isDb || isDbSidecar) {
                    copyFile(f, new File(dest, n));
                    count++;
                }
            }
        }

        // 2) databases 下所有子目录（抽屉分组等，ColorOS 子目录名未知故全备）
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) {
                    count += copyDir(f, new File(dest, f.getName()));
                }
            }
        }

        // 3) shared_prefs -> prefs/
        File prefs = prefsDir(ctx);
        if (prefs != null) {
            count += copyDirFiltered(prefs, new File(dest, "prefs"), ".xml");
            notes.append("prefs 源: ").append(prefs.getAbsolutePath()).append('\n');
        }

        // 4) 可读导出（best-effort）：主库按表名打分识别，不硬编码库名
        String sourceDbName = detectMainDbName(dbDir);
        File readable = new File(dest, "readable");
        readable.mkdirs();
        try {
            if (sourceDbName != null) {
                exportReadable(new File(dbDir, sourceDbName), readable);
            }
            makeSnapshot(dbDir, sourceDbName, dest);
        } catch (Throwable t) {
            notes.append("可读导出部分失败: ").append(t).append('\n');
        }

        // 5) 触发方式标记（保留策略只敢删带这个标记、且标记为自动的目录）
        try {
            writeText(new File(dest, ".llb_trigger"), trigger == null ? "手动" : trigger);
        } catch (Throwable ignored) {
        }

        // 6) README + 校验和
        writeReadme(dest, dbDir, sourceDbName, grid, notes.toString(), trigger);
        writeManifest(dest);

        String msg = "备份完成：" + name + "\n触发：" + trigger
                + "\n文件 " + count + " 个（另有 readable/ 与 MANIFEST.sha256）";
        Bridge.log(Log.INFO, Const.TAG, msg);
        return new Result(true, msg, name, null);
    }

    /** 用 VACUUM INTO 生成一份「一致性单文件快照」，方便用任意 SQLite 工具直接打开。 */
    private static void makeSnapshot(File dbDir, String dbName, File dest) {
        if (dbName == null) {
            return;
        }
        File src = new File(dbDir, dbName);
        if (!src.isFile()) {
            return;
        }
        File snap = new File(dest, dbName + ".snapshot");
        if (snap.exists()) {
            snap.delete();
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(src.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            db.execSQL("VACUUM INTO ?", new Object[]{snap.getAbsolutePath()});
        } catch (Throwable t) {
            Bridge.log(Log.WARN, Const.TAG, "VACUUM INTO 快照失败（可忽略）: " + t);
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // =====================================================================
    // 列表 / 恢复
    // =====================================================================

    static String[] listBackups() {
        File root = Schedule.rootDir();
        File[] ls = root.listFiles();
        List<String> out = new ArrayList<>();
        if (ls != null) {
            for (File f : ls) {
                if (f.isDirectory() && f.getName().startsWith(Const.BACKUP_PREFIX)) {
                    out.add(f.getName());
                }
            }
        }
        Collections.sort(out);
        Collections.reverse(out); // 新的在前
        return out.toArray(new String[0]);
    }

    /** 与 {@link #listBackups()} 平行的网格数组（用于列表上标注 5×8 / 5×9）。 */
    static String[] backupGrids(String[] dirs) {
        String[] g = new String[dirs.length];
        for (int i = 0; i < dirs.length; i++) {
            g[i] = gridOfDir(new File(Schedule.rootDir(), dirs[i]));
        }
        return g;
    }

    private static String gridOfDir(File d) {
        String main = detectMainDbName(d);
        if (main != null) {
            return parseGrid(main);
        }
        File[] ls = d.listFiles();
        if (ls != null) {
            for (File f : ls) {
                if (isAnyDb(f.getName())) {
                    return parseGrid(f.getName());
                }
            }
        }
        return "通用";
    }

    // =====================================================================
    // 网格识别
    // =====================================================================

    private static final Pattern DB_PAT = Pattern.compile("^launcher(\\d+)x(\\d+)\\.db$");
    private static final Pattern DB_PAT_BY = Pattern.compile("^launcher_(\\d+)_by_(\\d+)\\.db$");
    private static final Pattern DB_PAT_USCORE_X = Pattern.compile("^launcher_(\\d+)x(\\d+)\\.db$");

    /** 从 launcher5x8.db（小米）/ launcher_4_by_5.db / launcher_4x4.db（ColorOS）解析网格；认不出返回 "通用"。 */
    static String parseGrid(String dbName) {
        if (dbName == null) {
            return "通用";
        }
        Matcher m = DB_PAT.matcher(dbName);
        if (m.matches()) {
            return m.group(1) + "×" + m.group(2);
        }
        m = DB_PAT_BY.matcher(dbName);
        if (m.matches()) {
            return m.group(1) + "×" + m.group(2);
        }
        m = DB_PAT_USCORE_X.matcher(dbName);
        if (m.matches()) {
            return m.group(1) + "×" + m.group(2);
        }
        return "通用";
    }

    /** 当前桌面主库名（按表名打分识别；识别不到退回最大的 .db）。 */
    static String currentDbName(Context ctx) {
        File dir = primaryDbDir(ctx);
        if (dir == null) {
            return null;
        }
        return detectMainDbName(dir);
    }

    static String[] currentDbNames(Context ctx) {
        File dir = primaryDbDir(ctx);
        List<String> out = new ArrayList<>();
        if (dir != null) {
            for (File f : listDbFiles(dir)) {
                out.add(f.getName());
            }
        }
        Collections.sort(out);
        return out.toArray(new String[0]);
    }

    // =====================================================================
    // 信息查询（给「当前是什么界面」用）
    // =====================================================================

    /** 当前状态：模块是否活着、当前网格、当前库名、库目录、目录里所有 .db。 */
    static Result info(Context ctx) {
        File dir = primaryDbDir(ctx);
        String dbn = currentDbName(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("模块已激活：桌面进程注入成功\n");
        sb.append("当前网格：").append(parseGrid(dbn)).append('\n');
        sb.append("当前库：").append(dbn == null ? "(未找到)" : dbn).append('\n');
        if (dir != null) {
            sb.append("数据库目录：").append(dir.getAbsolutePath());
        }
        return new Result(true, sb.toString(), dbn, currentDbNames(ctx));
    }

    /**
     * 列出一个备份目录里所有「可恢复」的文件：
     * 所有 {@code *.db} 原始库 + {@code *.db.snapshot} 一致性快照。
     * 由用户自己选要还原哪一个（ColorOS 库名未知，故全部列出）。
     */
    static Result files(Context ctx, String dirName) {
        if (dirName == null || dirName.isEmpty()) {
            return new Result(false, "没有指定备份目录", null, null);
        }
        if (dirName.contains("/") || dirName.contains("..")) {
            return new Result(false, "非法的目录名", null, null);
        }
        File d = new File(Schedule.rootDir(), dirName);
        if (!d.isDirectory()) {
            return new Result(false, "备份目录不存在：" + d, null, null);
        }
        List<String> out = new ArrayList<>();
        File[] ls = d.listFiles();
        if (ls != null) {
            for (File f : ls) {
                String n = f.getName();
                if (isAnyDb(n) || n.endsWith(SNAPSHOT_SUFFIX)) {
                    out.add(n);
                }
            }
        }
        Collections.sort(out);
        if (out.isEmpty()) {
            return new Result(false, "该备份里没有 .db 文件", null, null);
        }
        return new Result(true, "共 " + out.size() + " 个可选文件", null, out.toArray(new String[0]));
    }

    /**
     * 恢复。
     *
     * @param dirName 备份目录名
     * @param chosen  用户指定要还原的文件名（{@code launcher*.db} 或 {@code launcher*.db.snapshot}）；
     *                传 null/空则取目录里第一个 {@code launcher*.db}（兼容旧调用）
     */
    static Result restore(Context ctx, String dirName, String chosen) throws IOException {
        if (dirName == null || dirName.isEmpty()) {
            return new Result(false, "没有指定备份目录", null, null);
        }
        if (dirName.contains("/") || dirName.contains("..")) {
            return new Result(false, "非法的目录名", null, null);
        }
        File srcDir = new File(Schedule.rootDir(), dirName);
        if (!srcDir.isDirectory()) {
            return new Result(false, "备份目录不存在：" + srcDir, null, null);
        }
        File dbDir = primaryDbDir(ctx);
        if (dbDir == null || !dbDir.isDirectory()) {
            return new Result(false, "找不到桌面数据库目录", null, null);
        }

        // 决定还原哪个库
        String dbName = null;
        boolean fromSnapshot = false;
        if (chosen != null && chosen.length() > 0) {
            if (chosen.contains("/") || chosen.contains("..")) {
                return new Result(false, "非法的文件名", null, null);
            }
            if (!new File(srcDir, chosen).isFile()) {
                return new Result(false, "备份里没有这个文件：" + chosen, null, null);
            }
            if (chosen.endsWith(SNAPSHOT_SUFFIX)) {
                dbName = chosen.substring(0, chosen.length() - SNAPSHOT_SUFFIX.length());
                // SNAPSHOT_SUFFIX 是 ".db.snapshot"，去掉后会丢扩展名，必须补回来
                if (!dbName.endsWith(".db")) {
                    dbName = dbName + ".db";
                }
                fromSnapshot = true;
            } else {
                dbName = chosen;
            }
        } else {
            // 没指定文件：优先用一致性快照（自洽、最稳），没有才退回原始库
            File[] ls0 = srcDir.listFiles();
            String snap = null;
            String raw = null;
            if (ls0 != null) {
                for (File f : ls0) {
                    final String n = f.getName();
                    if (n.endsWith(SNAPSHOT_SUFFIX)) {
                        if (snap == null) {
                            snap = n;
                        }
                    } else if (isAnyDb(n)) {
                        if (raw == null) {
                            raw = n;
                        }
                    }
                }
            }
            if (snap != null) {
                chosen = snap;
                dbName = snap.substring(0, snap.length() - SNAPSHOT_SUFFIX.length());
                if (!dbName.endsWith(".db")) {
                    dbName = dbName + ".db";
                }
                fromSnapshot = true;
            } else {
                dbName = raw;
            }
        }
        if (dbName == null) {
            return new Result(false, "备份里没有 .db 文件", null, null);
        }
        final String gridBackup = parseGrid(dbName);
        final String gridNow = parseGrid(currentDbName(ctx));

        // 先把「当前状态」另存一份，作为回滚点
        String safety = null;
        try {
            Result b = backupNoRetire(ctx, "恢复前自动");
            if (b.ok) {
                safety = b.dir;
            }
        } catch (Throwable t) {
            Bridge.log(Log.WARN, Const.TAG, "恢复前自动备份失败: " + t);
        }

        int restored = 0;
        List<String> targets = new ArrayList<>();
        targets.add(dbName);
        // ColorOS 版：备份里有的所有 .db 都一起恢复（库名未知，不能只恢复主库）
        File[] srcLs = srcDir.listFiles();
        if (srcLs != null) {
            for (File f : srcLs) {
                if (f.isFile() && isAnyDb(f.getName()) && !targets.contains(f.getName())) {
                    targets.add(f.getName());
                }
            }
        }
        // 兼容旧备份：AUX 库若存在也恢复（已被上面覆盖则跳过）
        for (String aux : AUX_DBS) {
            if (new File(srcDir, aux).isFile() && !targets.contains(aux)) {
                targets.add(aux);
            }
        }

        for (String t : targets) {
            // 主库若是快照来源：快照是自洽单文件，直接落地，
            // 并且必须清掉同名的 -wal/-journal/-shm，否则旧日志会在下次打开时
            // 盖到快照上，反而弄坏数据。
            if (t.equals(dbName) && fromSnapshot) {
                copyOver(new File(srcDir, chosen), new File(dbDir, t));
                for (String suf : new String[]{"-wal", "-journal", "-shm"}) {
                    File d = new File(dbDir, t + suf);
                    if (d.exists()) {
                        d.delete();
                    }
                }
                restored++;
                continue;
            }

            File main = new File(srcDir, t);
            if (main.isFile()) {
                copyOver(main, new File(dbDir, t));
                restored++;
            }
            for (String suf : new String[]{"-wal", "-journal"}) {
                File s = new File(srcDir, t + suf);
                File d = new File(dbDir, t + suf);
                if (s.isFile()) {
                    copyOver(s, d);
                } else if (d.exists()) {
                    d.delete();
                }
            }
            // -shm 一律不恢复（运行时自动重建），并清掉
            File shm = new File(dbDir, t + "-shm");
            if (shm.exists()) {
                shm.delete();
            }
        }

        sRestoreSrc = new File(srcDir, chosen).getAbsolutePath();
        sRestoreDst = new File(dbDir, dbName).getAbsolutePath();

        sRestoreDbDir = dbDir;
        purgeRuntimeFiles();

        // 关键：除了上面就地写库（可能被桌面回写覆盖），再另存一份 .pending 并留标记。
        // 桌面进程下次启动的最早期会用它替换正式库 —— 那时还没人打开数据库，一定生效。
        try {
            File pending = new File(dbDir, dbName + RESTORE_SUFFIX);
            copyOver(new File(srcDir, chosen), pending);
            writeText(new File(dbDir, RESTORE_FLAG), dbName);
            Bridge.log(Log.WARN, Const.TAG, "已备好待恢复库：" + dbName + RESTORE_SUFFIX);
        } catch (Throwable t) {
            Bridge.log(Log.ERROR, Const.TAG, "备待恢复库失败", t);
        }

        final String gridWarn = gridBackup.equals(gridNow) ? ""
                : "\n⚠️ 网格不一致：备份 " + gridBackup + " / 当前 " + gridNow
                + "\n请先在桌面设置里把网格改成 " + gridBackup + " 再恢复，否则图标会错位";
        String msg = "已恢复 " + restored + " 个文件：" + dbName
                + "\n来源 " + dirName + "（备份网格 " + gridBackup + "，当前网格 " + gridNow + "）"
                + (fromSnapshot ? "\n（用一致性快照还原）" : "")
                + gridWarn
                + (safety != null ? "\n恢复前状态已另存为 " + safety : "\n（警告：恢复前自动备份失败）");
        Bridge.log(Log.INFO, Const.TAG, msg);
        return new Result(true, msg, dirName, null);
    }

    /**
     * 删除 -wal / -shm / -journal。必须在进程被 kill 前最后一步调用，
     * 否则桌面可能重建 wal，重启后把旧 wal 套到新库上导致错乱。
     */
    static void purgeRuntimeFiles() {
        File dbDir = sRestoreDbDir;
        if (dbDir == null) {
            return;
        }
        File[] ls = dbDir.listFiles();
        if (ls == null) {
            return;
        }
        for (File f : ls) {
            String n = f.getName();
            if ((isAnyDb(n) || n.endsWith(".db-wal") || n.endsWith(".db-shm")
                    || n.endsWith(".db-journal"))
                    && (n.endsWith("-wal") || n.endsWith("-shm") || n.endsWith("-journal"))) {
                if (f.delete()) {
                    Bridge.log(Log.INFO, Const.TAG, "已清除 " + n);
                }
            }
        }
    }

    // =====================================================================
    // 布局指纹（判断排版有没有真的变化）
    // =====================================================================

    /**
     * 只对「排版相关」的列做 SHA-256。
     *
     * <p>故意排除 {@code launchCount}（使用次数，用户每点一次图标就变）、
     * {@code icon}（BLOB，体积大且可能被主题重绘）、{@code itemFlags} / {@code sortMode} /
     * {@code displayMode} 等易变列 —— 否则每次都会算成"变了"，自动备份就退化成无脑堆文件。
     */
    static String layoutDigest(Context ctx) {
        final File dir = primaryDbDir(ctx);
        final String name = currentDbName(ctx);
        if (dir == null || name == null) {
            return null;
        }
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(new File(dir, name).getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            final MessageDigest md = MessageDigest.getInstance("SHA-256");
            boolean hasFavorites = hasTable(db, "favorites");
            if (hasFavorites) {
                // 列名按实际表结构动态拼装：ColorOS favorites 含 vCellX/vCellY/rank，
                // 小米版含 isShortcut/label，两者取交集，避免缺列导致整表取样失败。
                String favSql = selectExisting(db, "favorites",
                        new String[]{"_id", "title", "intent", "container", "screen", "screenId",
                                "cellX", "cellY", "vCellX", "vCellY", "spanX", "spanY",
                                "itemType", "appWidgetId", "profileId", "rank", "iconType",
                                "iconPackage", "iconResource", "appWidgetProvider",
                                "isShortcut", "label"},
                        "_id");
                if (favSql != null) {
                    feedDigest(db, md, favSql);
                }
                // 屏幕表：小米叫 screens，ColorOS 叫 workspacescreens，两者都试
                String scrSql = null;
                if (hasTable(db, "workspacescreens")) {
                    scrSql = selectExisting(db, "workspacescreens",
                            new String[]{"_id", "title", "screenOrder", "screenType"}, "_id");
                }
                if (scrSql == null && hasTable(db, "screens")) {
                    scrSql = selectExisting(db, "screens",
                            new String[]{"_id", "title", "screenOrder", "screenType"}, "_id");
                }
                if (scrSql != null) {
                    feedDigest(db, md, scrSql);
                }
            } else {
                // ColorOS 通用回退：表名未知时，对所有表的排版相关列做哈希
                genericDigest(db, md);
            }
            final byte[] d = md.digest();
            final StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Throwable t) {
            Bridge.log(Log.WARN, Const.TAG, "布局指纹计算失败: " + t);
            return null;
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static boolean hasTable(SQLiteDatabase db, String table) {
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name=?",
                    new String[]{table});
            return c.moveToFirst();
        } catch (Throwable ignored) {
            return false;
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    /** 按实际表结构拼 SELECT：只取 desired 中真实存在的列，避免缺列整表失败。 */
    private static String selectExisting(SQLiteDatabase db, String table, String[] desired,
                                         String orderBy) {
        List<String> actual = tableColumns(db, table);
        if (actual.isEmpty()) {
            return null;
        }
        List<String> keep = new ArrayList<>();
        for (String col : desired) {
            if (actual.contains(col)) {
                keep.add(col);
            }
        }
        if (keep.isEmpty()) {
            return null;
        }
        StringBuilder sql = new StringBuilder("SELECT ");
        for (int i = 0; i < keep.size(); i++) {
            if (i > 0) {
                sql.append(',');
            }
            sql.append(keep.get(i));
        }
        sql.append(" FROM ").append(table);
        if (orderBy != null && actual.contains(orderBy)) {
            sql.append(" ORDER BY ").append(orderBy);
        }
        return sql.toString();
    }

    /** 通用指纹：遍历所有表，哈希除易变列（icon/launchCount 等）外的全部列。 */
    private static void genericDigest(SQLiteDatabase db, MessageDigest md) {
        List<String> tables = listTables(db);
        for (String table : tables) {
            if ("sqlite_sequence".equals(table) || "android_metadata".equals(table)) {
                continue;
            }
            List<String> cols = tableColumns(db, table);
            if (cols.isEmpty()) {
                continue;
            }
            List<String> keep = new ArrayList<>();
            for (String col : cols) {
                String low = col.toLowerCase();
                if (low.equals("icon") || low.equals("launchcount")
                        || low.equals("itemflags") || low.equals("sortmode")
                        || low.equals("displaymode") || low.equals("modified")) {
                    continue;
                }
                keep.add(col);
            }
            if (keep.isEmpty()) {
                continue;
            }
            StringBuilder sql = new StringBuilder("SELECT ");
            for (int i = 0; i < keep.size(); i++) {
                if (i > 0) {
                    sql.append(',');
                }
                sql.append(keep.get(i));
            }
            sql.append(" FROM ").append(table).append(" ORDER BY rowid");
            feedDigest(db, md, sql.toString());
        }
    }

    private static List<String> listTables(SQLiteDatabase db) {
        List<String> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null) {
                    out.add(n);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    private static List<String> tableColumns(SQLiteDatabase db, String table) {
        List<String> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (c.moveToNext()) {
                out.add(c.getString(c.getColumnIndexOrThrow("name")));
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    private static void feedDigest(SQLiteDatabase db, MessageDigest md, String sql) {
        Cursor c = null;
        try {
            c = db.rawQuery(sql, null);
            final int n = c.getColumnCount();
            while (c.moveToNext()) {
                for (int i = 0; i < n; i++) {
                    final String v = c.getString(i);
                    if (v != null) {
                        md.update(v.getBytes("UTF-8"));
                    }
                    md.update((byte) 0x1F);
                }
                md.update((byte) 0x1E);
            }
        } catch (Throwable t) {
            Bridge.log(Log.WARN, Const.TAG, "指纹取样失败: " + t);
        } finally {
            if (c != null) {
                c.close();
            }
        }
    }

    // =====================================================================
    // 可读导出
    // =====================================================================

    private static void exportReadable(File dbFile, File readable) {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(dbFile.getAbsolutePath(), null, SQLiteDatabase.OPEN_READONLY);
            // ColorOS 版：导出【所有】表（表名未知），同时保留 favorites/screens 的布局图
            List<String> tables = listTables(db);
            for (String table : tables) {
                if ("sqlite_sequence".equals(table) || "android_metadata".equals(table)) {
                    continue;
                }
                exportTable(db, table, new File(readable, table + ".csv"));
            }
            writeText(new File(readable, "layout_map.txt"), buildLayoutMap(db));
        } catch (Throwable t) {
            Bridge.log(Log.WARN, Const.TAG, "可读导出失败: " + t);
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void exportTable(SQLiteDatabase db, String table, File out) throws IOException {
        List<String> cols = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (c.moveToNext()) {
                cols.add(c.getString(c.getColumnIndexOrThrow("name")));
            }
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (cols.isEmpty()) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cols.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(csv(cols.get(i)));
        }
        sb.append('\n');

        Cursor r = null;
        try {
            r = db.rawQuery("SELECT * FROM " + table, null);
            while (r.moveToNext()) {
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) {
                        sb.append(',');
                    }
                    sb.append(csv(r.getString(i)));
                }
                sb.append('\n');
            }
        } finally {
            if (r != null) {
                r.close();
            }
        }
        writeText(out, sb.toString());
    }

    private static String buildLayoutMap(SQLiteDatabase db) {
        StringBuilder sb = new StringBuilder();
        // 屏幕表：ColorOS 叫 workspacescreens，小米叫 screens
        String screenTable = hasTable(db, "workspacescreens") ? "workspacescreens"
                : (hasTable(db, "screens") ? "screens" : null);
        sb.append("屏幕列表（").append(screenTable == null ? "无" : screenTable).append("）\n");
        sb.append("----------------------------------------------------------------\n");
        sb.append("screen_id\tscreenOrder\t每屏条目数\n");
        if (screenTable != null) {
            List<String> cols = tableColumns(db, screenTable);
            if (cols.contains("_id") && cols.contains("screenOrder")) {
                Cursor c = null;
                try {
                    c = db.rawQuery("SELECT _id, screenOrder FROM " + screenTable
                            + " ORDER BY screenOrder", null);
                    while (c.moveToNext()) {
                        int id = c.getInt(0);
                        int order = c.getInt(1);
                        sb.append(id).append('\t').append(order).append('\t')
                                .append(countInScreen(db, id)).append('\n');
                    }
                } catch (Throwable t) {
                    sb.append("读取 ").append(screenTable).append(" 失败: ").append(t).append('\n');
                } finally {
                    if (c != null) {
                        c.close();
                    }
                }
            } else {
                sb.append("（").append(screenTable).append(" 表无 _id/screenOrder 列，跳过）\n");
            }
        }

        sb.append('\n').append("桌面条目（container=-100 即桌面本身，按 screen / 行 / 列 排序）\n");
        sb.append("----------------------------------------------------------------\n");
        sb.append("screen\tcellX\tcellY\tspanX\tspanY\titemType\ttitle\n");
        if (hasTable(db, "favorites")) {
            List<String> fcols = tableColumns(db, "favorites");
            // screen 列：ColorOS 主表叫 screen，个别版本叫 screenId，兼容两者
            String screenCol = fcols.contains("screen") ? "screen"
                    : (fcols.contains("screenId") ? "screenId" : null);
            if (screenCol != null && fcols.contains("cellX") && fcols.contains("cellY")) {
                Cursor r = null;
                try {
                    r = db.rawQuery("SELECT " + screenCol + ", cellX, cellY, spanX, spanY, itemType, title "
                            + "FROM favorites WHERE container = -100 ORDER BY "
                            + screenCol + ", cellY, cellX", null);
                    while (r.moveToNext()) {
                        sb.append(r.getInt(0)).append('\t')
                                .append(r.getInt(1)).append('\t')
                                .append(r.getInt(2)).append('\t')
                                .append(r.getInt(3)).append('\t')
                                .append(r.getInt(4)).append('\t')
                                .append(r.getInt(5)).append('\t')
                                .append(r.getString(6) == null ? "" : r.getString(6)).append('\n');
                    }
                } catch (Throwable t) {
                    sb.append("读取 favorites 失败: ").append(t).append('\n');
                } finally {
                    if (r != null) {
                        r.close();
                    }
                }
            } else {
                sb.append("（favorites 表缺 screen/cellX/cellY 列，跳过）\n");
            }
        } else {
            sb.append("（无 favorites 表）\n");
        }
        return sb.toString();
    }

    private static int countInScreen(SQLiteDatabase db, int screenId) {
        Cursor c = null;
        try {
            List<String> fcols = tableColumns(db, "favorites");
            String screenCol = fcols.contains("screen") ? "screen"
                    : (fcols.contains("screenId") ? "screenId" : "screen");
            c = db.rawQuery("SELECT COUNT(*) FROM favorites WHERE container = -100 AND "
                    + screenCol + " = ?", new String[]{String.valueOf(screenId)});
            if (c.moveToFirst()) {
                return c.getInt(0);
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return 0;
    }

    private static String csv(String s) {
        if (s == null) {
            return "";
        }
        boolean need = s.indexOf(',') >= 0 || s.indexOf('"') >= 0
                || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0;
        if (!need) {
            return s;
        }
        return '"' + s.replace("\"", "\"\"") + '"';
    }

    // =====================================================================
    // README / MANIFEST
    // =====================================================================

    private static void writeReadme(File dest, File dbDir, String dbName, String grid, String notes,
                                    String trigger) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("OPPO ColorOS 桌面布局备份\n");
        sb.append("================================================\n");
        sb.append("备份时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append('\n');
        sb.append("触发方式: ").append(trigger == null ? "手动" : trigger).append('\n');
        sb.append("设备信息: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("系统版本: ").append(Build.DISPLAY).append('\n');
        sb.append("桌面应用: ").append(Const.TARGET_PKG).append('\n');
        sb.append("网格: ").append(grid).append('\n');
        sb.append("数据库: ").append(dbName == null ? "(未找到)" : dbName).append('\n');
        sb.append("数据库目录: ").append(dbDir.getAbsolutePath()).append('\n');
        sb.append("产出于: 桌面布局备份 (LSPosed 模块, libxposed API 102)\n");
        sb.append('\n');

        sb.append("【文件说明】\n");
        sb.append("------------------------------------------------\n");
        sb.append("*.db                 桌面布局库（ColorOS 库名未知，故 databases 下所有 .db 全备）\n");
        sb.append("*.db-wal             WAL 日志，必须与 .db 一起存放，否则会丢最近改动\n");
        sb.append("*.db.snapshot        VACUUM INTO 生成的一致性单文件快照，可直接用 SQLite 工具打开\n");
        sb.append("子目录/              databases 下所有子目录（抽屉分组等）\n");
        sb.append("prefs/*.xml          桌面 SharedPreferences（含网格行列数等配置）\n");
        sb.append("readable/*.csv       把 favorites / screens 导成表格，可直接看\n");
        sb.append("readable/layout_map.txt 每屏坐标一览\n");
        sb.append("MANIFEST.sha256      所有文件的 SHA-256 校验值\n");
        sb.append('\n');

        sb.append("【关键配置项】\n");
        sb.append("------------------------------------------------\n");
        sb.append("(ColorOS 桌面配置键名未知，见 prefs/*.xml 原始文件)\n");
        sb.append(readPrefKeys());
        sb.append('\n');

        sb.append("【怎么恢复】\n");
        sb.append("------------------------------------------------\n");
        sb.append("方法 A（推荐）: 打开「桌面布局备份」App -> 刷新备份列表 -> 点对应备份的「恢复」\n");
        sb.append("  说明: 恢复会自动把当前状态另存为 LauncherLayoutBackup-<时间>/ 回滚点，\n");
        sb.append("        然后覆盖数据库并重启桌面进程，全程在手机上一次点完，无需电脑。\n");
        sb.append('\n');
        sb.append("方法 B（手动 / 电脑端）:\n");
        sb.append("  1) su\n");
        sb.append("  2) am force-stop com.android.launcher\n");
        sb.append("  3) cp <备份目录>/*.db ").append(dbDir.getAbsolutePath()).append("/\n");
        sb.append("  4) rm -f ").append(dbDir.getAbsolutePath()).append("/*.db-{journal,wal,shm}\n");
        sb.append("  5) 重启手机或 am start -a android.intent.action.MAIN -c android.intent.category.HOME\n");
        sb.append('\n');

        sb.append("【重要提醒】\n");
        sb.append("------------------------------------------------\n");
        sb.append("1. 桌面数据库在 user_de（设备保护存储），不是 /data/data。\n");
        sb.append("2. ColorOS 库名不编码网格，恢复时请确认当前网格与备份一致，否则图标可能错位。\n");
        sb.append("3. 云同步可能在联网时回写布局，恢复后请立刻关掉或反向同步一次。\n");
        if (notes != null && notes.length() > 0) {
            sb.append("5. 本次备注:\n").append(notes);
        }

        sb.append('\n');
        sb.append("【来源与致谢】\n");
        sb.append("------------------------------------------------\n");
        sb.append("备份格式与整体思路：酷安 @").append(nz(Schedule.link1Name)).append('\n');
        sb.append("  ").append(nz(Schedule.link1Url)).append('\n');
        sb.append("模块作者：酷安 @").append(nz(Schedule.link2Name)).append('\n');
        sb.append("  ").append(nz(Schedule.link2Url)).append('\n');
        sb.append("备份文件脱离本 App 单独流传时，以上署名请一并保留。\n");
        writeText(new File(dest, "README.txt"), sb.toString());
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // =====================================================================
    // 保留策略（只动我们自己创建的自动备份）
    // =====================================================================

    /** 读取备份目录的触发方式；没有标记文件返回 null。 */
    static String readTrigger(File dir) {
        final File f = new File(dir, ".llb_trigger");
        if (!f.isFile()) {
            return null;
        }
        try {
            return readText(f).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按保留份数清理**自动备份**。
     *
     * <p>安全约束（缺一不可，避免误删用户的东西）：
     * <ol>
     *   <li>目录名必须匹配 {@code LauncherLayoutBackup-*}（我们自己造的前缀）；</li>
     *   <li>目录里必须有 {@code .llb_trigger} 标记文件（证明是本程序写的）；</li>
     *   <li>标记内容不能是「手动」或「恢复前自动」——<b>手动备份永不自动删除</b>。</li>
     * </ol>
     * 老备份（本版本之前生成的）没有标记文件，因此天然不会被删。
     */
    /** 只有自动排程产生的备份才会被保留策略清理；其它（手动 / 安全备份）永不自动删。 */
    private static boolean isAutoTrigger(String t) {
        // 「恢复前自动」产生的回滚点也算自动备份，纳入保留份数，否则每恢复一次就永久多一份
        return t != null && (t.startsWith("定时") || t.startsWith("关机前")
                || t.startsWith("恢复前自动"));
    }

    static int pruneAuto(int keep) {
        if (keep <= 0) {
            return 0;
        }
        final String[] dirs = listBackups(); // 新 -> 旧
        int autoKept = 0;
        int removed = 0;
        for (String d : dirs) {
            final File dir = new File(Schedule.rootDir(), d);
            final String trig = readTrigger(dir);
            if (!isAutoTrigger(trig)) {
                continue; // 手动备份、找回的安全备份 —— 一律不动
            }
            autoKept++;
            if (autoKept > keep) {
                if (deleteRecursively(dir)) {
                    removed++;
                    Bridge.log(Log.WARN, Const.TAG, "保留策略：删除最早的自动备份 " + d);
                }
            }
        }
        return removed;
    }

    private static boolean deleteRecursively(File f) {
        if (f.isDirectory()) {
            final File[] ls = f.listFiles();
            if (ls != null) {
                for (File c : ls) {
                    deleteRecursively(c);
                }
            }
        }
        return f.delete();
    }

    // =====================================================================
    // 隐藏安全备份（幽灵）+ 一键删除全部
    // =====================================================================

    /** 隐藏安全备份的目录名（点开头，文件管理器默认不显示；也不会出现在备份列表里）。 */
    static final String GHOST_DIR = ".llb_ghost";

    static File ghostDir() {
        return new File(Schedule.rootDir(), GHOST_DIR);
    }

    static boolean ghostExists() {
        final File g = ghostDir();
        if (!g.isDirectory()) {
            return false;
        }
        final File[] ls = g.listFiles();
        if (ls != null) {
            for (File f : ls) {
                if (isAnyDb(f.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 出现新的正式备份后，幽灵退役。 */
    static void retireGhost() {
        final File g = ghostDir();
        if (!g.isDirectory()) {
            return;
        }
        if (deleteRecursively(g)) {
            Bridge.log(Log.INFO, Const.TAG, "已有新的正式备份，删除前的安全备份已退役并清除");
        }
    }

    /**
     * 把隐藏安全备份「转正」：**去掉名字前面的点**，让它变成一份正常可见的备份。
     *
     * <p>不做自动覆盖桌面的动作 —— 只是让它出现在备份列表里，
     * 由用户自己在列表里点「打开」看内容、点「恢复」决定要不要还原。
     */
    static Result revealGhost(Context ctx) {
        final File g = ghostDir();
        if (!ghostExists()) {
            return new Result(false, "没有可找回的安全备份", null, null);
        }
        final long ts = g.lastModified() > 0 ? g.lastModified() : System.currentTimeMillis();
        final String stamp = new SimpleDateFormat(STAMP, Locale.US).format(new Date(ts));
        File dest = new File(Schedule.rootDir(), Const.BACKUP_PREFIX + stamp);
        int i = 1;
        while (dest.exists()) {
            dest = new File(Schedule.rootDir(), Const.BACKUP_PREFIX + stamp + "_" + (i++));
        }
        if (!g.renameTo(dest)) {
            return new Result(false, "重命名失败：" + g.getAbsolutePath(), null, null);
        }
        // 标记成非自动备份，保留策略永远不会删它
        try {
            writeText(new File(dest, ".llb_trigger"), "找回的安全备份（删除前）");
        } catch (Throwable ignored) {
        }
        Bridge.log(Log.WARN, Const.TAG, "安全备份已找回并转为可见备份：" + dest.getName());
        return new Result(true, "已找回：\n" + dest.getName()
                + "\n\n现在到「备份」页就能看到它，点「恢复」即可还原桌面排版。",
                dest.getName(), null);
    }

    /** 判断这个目录是不是我们自己造的（有 README.txt 或 .llb_trigger 才算）。 */
    private static boolean isOurs(File dir) {
        return new File(dir, "README.txt").isFile() || new File(dir, ".llb_trigger").isFile();
    }

    /**
     * 一键删除全部正式备份。
     *
     * <p>删除前先留一份**隐藏安全备份**（幽灵），放在 {@code <备份目录>/.llb_ghost}：
     * <ul>
     *   <li>不出现在备份列表里，用户翻列表看不到；</li>
     *   <li>已经存在幽灵时**不覆盖**，保住最早那份最干净的；</li>
     *   <li>用户手动备份成功、或自动备份成功之后，幽灵自动退役删除。</li>
     * </ul>
     *
     * <p>删除范围严格限定：目录名以 {@code LauncherLayoutBackup-} 开头 **且** 内含
     * README.txt / .llb_trigger（证明是本程序生成的）。不会碰任何其它文件。
     */
    static Result deleteAll(Context ctx) {
        final File root = Schedule.rootDir();
        final File[] ls = root.listFiles();
        final List<File> victims = new ArrayList<File>();
        if (ls != null) {
            for (File f : ls) {
                if (f.isDirectory() && f.getName().startsWith(Const.BACKUP_PREFIX) && isOurs(f)) {
                    victims.add(f);
                }
            }
        }
        if (victims.isEmpty()) {
            return new Result(false, "当前目录下没有可删除的备份", null, null);
        }

        boolean hasGhost = ghostExists();
        String ghostNote;
        if (hasGhost) {
            ghostNote = "沿用上次留下的隐藏安全备份（未覆盖）";
        } else {
            try {
                Result r = backupInto(ctx, ghostDir(), GHOST_DIR, "安全备份（删除前）");
                hasGhost = r.ok;
                ghostNote = r.ok ? "已生成隐藏安全备份" : ("⚠️ 安全备份生成失败：" + r.message);
            } catch (Throwable t) {
                Bridge.log(Log.ERROR, Const.TAG, "生成安全备份失败", t);
                ghostNote = "⚠️ 安全备份生成失败：" + t;
            }
        }

        int n = 0;
        for (File v : victims) {
            if (deleteRecursively(v)) {
                n++;
                Bridge.log(Log.WARN, Const.TAG, "一键删除：已删除 " + v.getName());
            }
        }
        String msg = "已删除 " + n + " 份备份\n" + ghostNote
                + (hasGhost ? "\n可随时点「找回删除前的备份」还原" : "");
        Bridge.log(Log.WARN, Const.TAG, msg);
        return new Result(true, msg, null, null);
    }

    private static final Pattern PREF_LINE =
            Pattern.compile("<(?:boolean|int|long|float|string|set)\\s+name=\"([^\"]+)\"[^>]*?(?:value=\"([^\"]*)\")?[^>]*/?>");

    private static String readPrefKeys() {
        StringBuilder sb = new StringBuilder();
        try {
            File dir = Schedule.rootDir();
            sb.append("  (见 prefs/*.xml)\n");
            // 从刚写出的 prefs 目录里抽取已知键
            File latest = null;
            File[] ls = dir.listFiles();
            if (ls != null) {
                for (File f : ls) {
                    if (f.isDirectory() && f.getName().startsWith(Const.BACKUP_PREFIX)) {
                        if (latest == null || f.lastModified() > latest.lastModified()) {
                            latest = f;
                        }
                    }
                }
            }
            if (latest != null) {
                File pd = new File(latest, "prefs");
                File[] xs = pd.listFiles();
                if (xs != null) {
                    for (File x : xs) {
                        if (!x.getName().endsWith(".xml")) {
                            continue;
                        }
                        String text = readText(x);
                        Matcher m = PREF_LINE.matcher(text);
                        while (m.find()) {
                            String k = m.group(1);
                            if (k != null && (k.contains("cell") || k.contains("screen_cells")
                                    || k.contains("auto_fill") || k.contains("layout")
                                    || k.contains("grid") || k.contains("column")
                                    || k.contains("row") || k.contains("desktop")
                                    || k.contains("launcher"))) {
                                sb.append("  ").append(x.getName()).append(" :: ")
                                        .append(k).append(" = ").append(m.group(2)).append('\n');
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static void writeManifest(File dest) throws IOException {
        List<String> lines = new ArrayList<>();
        collect(dest, dest, lines);
        Collections.sort(lines);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            sb.append(l).append('\n');
        }
        writeText(new File(dest, "MANIFEST.sha256"), sb.toString());
    }

    private static void collect(File root, File dir, List<String> out) throws IOException {
        File[] ls = dir.listFiles();
        if (ls == null) {
            return;
        }
        for (File f : ls) {
            if (f.isDirectory()) {
                collect(root, f, out);
            } else if (!f.getName().equals("MANIFEST.sha256")) {
                String rel = root.toURI().relativize(f.toURI()).getPath();
                out.add(sha256(f) + "  " + rel);
            }
        }
    }

    private static String sha256(File f) throws IOException {
        InputStream in = null;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                md.update(buf, 0, n);
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IOException("sha256 failed: " + e);
        } finally {
            closeQuietly(in);
        }
    }

    // =====================================================================
    // 分组列表（供分组提取 APK：文件夹分组 + 应用包名）
    // =====================================================================

    /** 一条 favorites 记录（仅保留分组提取需要的字段）。 */
    private static final class FavRow {
        long id;
        String title;
        String intent;
        long container;
        int itemType;
        int screen;
        int cellX;
        int cellY;
    }

    /**
     * 列出桌面分组与应用：返回 items 为 {@code 分组名\t应用名\t包名} 数组。
     * 分组 = 桌面文件夹（container 被其它条目引用的正数 _id）+「桌面」+「Dock」。
     * 只返回能解析出包名的应用/快捷方式条目（文件夹本身不算应用）。
     */

    // ---- 条目表识别（不写死 favorites：ColorOS 各版本表名/列名都不一样） ----

    private static final String[] CAND_ID = {"_id", "id", "itemId", "item_id"};
    private static final String[] CAND_TITLE = {"title", "name", "displayName", "display_name",
            "label", "appName", "app_name"};
    private static final String[] CAND_INTENT = {"intent", "intentUri", "intent_uri",
            "componentName", "component_name", "component", "cmp"};
    private static final String[] CAND_CONTAINER = {"container", "containerId", "container_id",
            "parentId", "parent_id", "parent", "folderId", "folder_id"};
    private static final String[] CAND_TYPE = {"itemType", "item_type", "type"};
    private static final String[] CAND_SCREEN = {"screen", "screenId", "screen_id", "page",
            "pageIndex", "page_index", "screenRank"};
    private static final String[] CAND_X = {"cellX", "cell_x", "cellx", "posX", "pos_x",
            "column", "col"};
    private static final String[] CAND_Y = {"cellY", "cell_y", "celly", "posY", "pos_y",
            "row"};

    /** 解析出来的「桌面条目表」——哪个库、哪张表、每列叫什么。 */
    static final class ItemSchema {
        File dbFile;
        String table;
        String cId;
        String cTitle;
        String cIntent;
        String cContainer;
        String cType;
        String cScreen;
        String cX;
        String cY;
        int score;

        String where() {
            return dbFile.getName() + " / " + table;
        }
    }

    /** 在候选列名里挑一个真实存在的列（大小写不敏感）。 */
    private static String pickCol(List<String> cols, String[] cands) {
        for (String cand : cands) {
            for (String c : cols) {
                if (c != null && c.equalsIgnoreCase(cand)) {
                    return c;
                }
            }
        }
        return null;
    }

    /** 模糊列名匹配（fuzzy 里任一片段出现在列名中就命中）。 */
    private static String pickColFuzzy(List<String> cols, String[] fuzzy) {
        for (String f : fuzzy) {
            for (String c : cols) {
                if (c != null && c.toLowerCase().contains(f)) {
                    return c;
                }
            }
        }
        return null;
    }

    private static List<String> tableNames(SQLiteDatabase db) {
        List<String> out = new ArrayList<>();
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table'", null);
            while (c.moveToNext()) {
                String n = c.getString(0);
                if (n != null && !n.startsWith("sqlite_")) {
                    out.add(n);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return out;
    }

    /**
     * 跨【所有 .db】寻找「桌面条目表」。
     *
     * <p>ColorOS 不同版本的库名、表名、列名都不同（favorites / iteminfo / homeitem…），
     * 所以这里不写死任何名字：只要求「有主键列 + 有 container 列 + 有组件列」，
     * 再按表名/额外列打分挑最像的那张。
     */
    static ItemSchema resolveItemSchema(File dir) {
        if (dir == null) {
            return null;
        }
        ItemSchema best = null;
        for (File f : listDbFiles(dir)) {
            SQLiteDatabase db = null;
            try {
                db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READONLY);
                for (String t : tableNames(db)) {
                    if ("android_metadata".equals(t)) {
                        continue;
                    }
                    List<String> cols = tableColumns(db, t);
                    if (cols.isEmpty()) {
                        continue;
                    }
                    String id = pickCol(cols, CAND_ID);
                    String cont = pickCol(cols, CAND_CONTAINER);
                    if (cont == null) {
                        cont = pickColFuzzy(cols, new String[]{"container", "folderid", "parent"});
                    }
                    String intent = pickCol(cols, CAND_INTENT);
                    if (intent == null) {
                        intent = pickColFuzzy(cols, new String[]{"intent", "component"});
                    }
                    // 三个必需列：主键、容器（决定分组）、组件（决定包名）
                    if (id == null || cont == null || intent == null) {
                        continue;
                    }
                    String low = t.toLowerCase();
                    int score = 10;
                    if (low.contains("favorit")) {
                        score += 12;
                    }
                    if (low.contains("item")) {
                        score += 6;
                    }
                    if (low.contains("home") || low.contains("desktop")
                            || low.contains("workspace")) {
                        score += 5;
                    }
                    if (pickCol(cols, CAND_SCREEN) != null) {
                        score += 3;
                    }
                    if (pickCol(cols, CAND_X) != null) {
                        score += 2;
                    }
                    if (best == null || score > best.score) {
                        ItemSchema s = new ItemSchema();
                        s.dbFile = f;
                        s.table = t;
                        s.cId = id;
                        s.cTitle = pickCol(cols, CAND_TITLE);
                        s.cIntent = intent;
                        s.cContainer = cont;
                        s.cType = pickCol(cols, CAND_TYPE);
                        s.cScreen = pickCol(cols, CAND_SCREEN);
                        s.cX = pickCol(cols, CAND_X);
                        s.cY = pickCol(cols, CAND_Y);
                        s.score = score;
                        best = s;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                if (db != null) {
                    try {
                        db.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return best;
    }

    /**
     * 全部数据库的表结构报告 —— 认不出条目表时让用户一键复制发回来，
     * 有这份东西就能一次改对，不用来回猜。
     */
    static String schemaReport(Context ctx) {
        File dir = primaryDbDir(ctx);
        StringBuilder sb = new StringBuilder();
        sb.append("== 桌面数据库结构报告 ==\n");
        if (dir == null) {
            sb.append("找不到数据库目录\n");
            return sb.toString();
        }
        sb.append("目录: ").append(dir.getAbsolutePath()).append('\n');
        ItemSchema s = resolveItemSchema(dir);
        sb.append("识别到的条目表: ").append(s == null ? "(无)" : s.where()).append('\n');
        if (s != null) {
            sb.append("列映射: id=").append(s.cId).append(" title=").append(s.cTitle)
                    .append(" intent=").append(s.cIntent).append(" container=").append(s.cContainer)
                    .append(" type=").append(s.cType).append(" screen=").append(s.cScreen)
                    .append(" x=").append(s.cX).append(" y=").append(s.cY).append('\n');
        }
        List<File> dbs = listDbFiles(dir);
        sb.append("库文件数: ").append(dbs.size()).append('\n');
        for (File f : dbs) {
            sb.append("\n[").append(f.getName()).append("] ").append(f.length()).append(" bytes\n");
            SQLiteDatabase db = null;
            try {
                db = SQLiteDatabase.openDatabase(f.getAbsolutePath(), null,
                        SQLiteDatabase.OPEN_READONLY);
                for (String t : tableNames(db)) {
                    List<String> cols = tableColumns(db, t);
                    long n = -1;
                    Cursor c = null;
                    try {
                        c = db.rawQuery("SELECT COUNT(*) FROM \"" + t + "\"", null);
                        if (c.moveToFirst()) {
                            n = c.getLong(0);
                        }
                    } catch (Throwable ignored) {
                    } finally {
                        if (c != null) {
                            c.close();
                        }
                    }
                    sb.append("  - ").append(t).append(" (").append(n).append(" 行): ");
                    for (int i = 0; i < cols.size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(cols.get(i));
                    }
                    sb.append('\n');
                }
            } catch (Throwable t) {
                sb.append("  读取失败: ").append(t).append('\n');
            } finally {
                if (db != null) {
                    try {
                        db.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (sb.length() > 12000) {
                sb.append("…(已截断)");
                return sb.toString();
            }
        }
        return sb.toString();
    }

    /**
     * 列出桌面分组与应用：返回 items 为 {@code 分组名\t应用名\t包名} 数组。
     * 分组 = 桌面文件夹（container 被其它条目引用的正数 id）+「桌面」+「Dock」。
     */
    static Result listGroups(Context ctx) {
        File dir = primaryDbDir(ctx);
        if (dir == null) {
            return new Result(false, "找不到桌面数据库目录", null, null);
        }
        ItemSchema s = resolveItemSchema(dir);
        if (s == null) {
            int n = listDbFiles(dir).size();
            Bridge.log(Log.WARN, Const.TAG, "未识别到桌面条目表，共 " + n + " 个库");
            return new Result(false, "认不出桌面的条目表（已扫描 " + n
                    + " 个库，均无「主键 + container + 组件」三列俱全的表）\n"
                    + "点右上角「导出表结构」复制发我，我照着改", null, null);
        }
        Bridge.log(Log.INFO, Const.TAG, "条目表: " + s.where() + " 列: id=" + s.cId
                + " title=" + s.cTitle + " intent=" + s.cIntent + " container=" + s.cContainer);

        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(s.dbFile.getAbsolutePath(), null,
                    SQLiteDatabase.OPEN_READONLY);
            StringBuilder sql = new StringBuilder("SELECT ");
            sql.append(s.cId).append(" AS c_id");
            sql.append(", ").append(s.cTitle == null ? "NULL" : s.cTitle).append(" AS c_title");
            sql.append(", ").append(s.cIntent).append(" AS c_intent");
            sql.append(", ").append(s.cContainer).append(" AS c_container");
            sql.append(", ").append(s.cType == null ? "0" : s.cType).append(" AS c_type");
            sql.append(", ").append(s.cScreen == null ? "0" : s.cScreen).append(" AS c_screen");
            sql.append(", ").append(s.cX == null ? "0" : s.cX).append(" AS c_x");
            sql.append(", ").append(s.cY == null ? "0" : s.cY).append(" AS c_y");
            sql.append(" FROM ").append(s.table);
            if (s.cIntent == null) {
                return new Result(false, "条目表没有组件列", null, null);
            }

            List<FavRow> rows = new ArrayList<>();
            Cursor c = null;
            try {
                c = db.rawQuery(sql.toString(), null);
                while (c.moveToNext()) {
                    FavRow r = new FavRow();
                    r.id = c.getLong(0);
                    r.title = c.isNull(1) ? null : c.getString(1);
                    r.intent = c.isNull(2) ? null : c.getString(2);
                    r.container = c.isNull(3) ? 0 : c.getLong(3);
                    r.itemType = c.isNull(4) ? 0 : c.getInt(4);
                    r.screen = c.isNull(5) ? 0 : c.getInt(5);
                    r.cellX = c.isNull(6) ? 0 : c.getInt(6);
                    r.cellY = c.isNull(7) ? 0 : c.getInt(7);
                    rows.add(r);
                }
            } finally {
                if (c != null) {
                    c.close();
                }
            }
            if (rows.isEmpty()) {
                return new Result(false, "条目表 " + s.table + " 是空的（读到了 0 行）", null, null);
            }

            // 文件夹 = 被其它条目当 container 引用的正数 _id
            java.util.Set<Long> ids = new java.util.HashSet<>();
            for (FavRow r : rows) {
                ids.add(r.id);
            }
            java.util.Set<Long> folderIds = new java.util.HashSet<>();
            for (FavRow r : rows) {
                if (r.container > 0 && ids.contains(r.container)) {
                    folderIds.add(r.container);
                }
            }
            java.util.Map<Long, FavRow> byId = new java.util.HashMap<>();
            for (FavRow r : rows) {
                byId.put(r.id, r);
            }
            // 分组名 + 排序键（文件夹按桌面位置排，与显示一致）
            java.util.Map<String, int[]> groupOrder = new java.util.HashMap<>();
            java.util.Map<String, String> groupName = new java.util.HashMap<>();
            for (Long fid : folderIds) {
                FavRow f = byId.get(fid);
                String name = (f != null && f.title != null && !f.title.trim().isEmpty())
                        ? f.title.trim() : ("文件夹 " + fid);
                groupName.put(String.valueOf(fid), name);
                groupOrder.put(String.valueOf(fid),
                        new int[]{f == null ? 0 : f.screen, f == null ? 0 : f.cellY,
                                f == null ? 0 : f.cellX});
            }
            // 收集应用
            List<String[]> apps = new ArrayList<>(); // [groupKey, groupName, title, pkg, screen, cellY, cellX]
            for (FavRow r : rows) {
                if (folderIds.contains(r.id)) {
                    continue; // 文件夹本身不是应用
                }
                if (r.itemType == 2) {
                    continue; // 文件夹类型
                }
                String pkg = parsePackage(r.intent);
                if (pkg == null || pkg.isEmpty()) {
                    continue;
                }
                String gKey;
                String gName;
                if (folderIds.contains(r.container)) {
                    gKey = String.valueOf(r.container);
                    gName = groupName.get(gKey);
                } else if (r.container == -100) {
                    gKey = "desktop";
                    gName = "桌面";
                } else if (r.container == -101) {
                    gKey = "dock";
                    gName = "Dock";
                } else {
                    gKey = "other";
                    gName = "其它";
                }
                String title = (r.title != null && !r.title.trim().isEmpty())
                        ? r.title.trim() : pkg;
                apps.add(new String[]{gKey, gName, title, pkg,
                        String.valueOf(r.screen), String.valueOf(r.cellY), String.valueOf(r.cellX)});
            }
            // 分组排序：桌面在前，其次文件夹按位置，Dock/其它在后
            final java.util.Map<String, int[]> order = groupOrder;
            Collections.sort(apps, (a, b) -> {
                int ga = groupRank(a[0], order);
                int gb = groupRank(b[0], order);
                if (ga != gb) {
                    return Integer.compare(ga, gb);
                }
                int[] oa = order.get(a[0]);
                int[] ob = order.get(b[0]);
                if (oa != null && ob != null) {
                    for (int i = 0; i < 3; i++) {
                        if (oa[i] != ob[i]) {
                            return Integer.compare(oa[i], ob[i]);
                        }
                    }
                }
                int cmpScreen = Integer.compare(Integer.parseInt(a[4]), Integer.parseInt(b[4]));
                if (cmpScreen != 0) {
                    return cmpScreen;
                }
                int cmpCell = Integer.compare(Integer.parseInt(a[5]), Integer.parseInt(b[5]));
                if (cmpCell != 0) {
                    return cmpCell;
                }
                return a[2].compareTo(b[2]);
            });
            List<String> out = new ArrayList<>();
            for (String[] a : apps) {
                // 4th = 分组唯一键（文件夹 id / desktop / dock / other）：同名文件夹靠它区分，
                // 5th = 所在屏序号（同名时用来提示「第几屏」）
                out.add(cleanField(a[1]) + "\t" + cleanField(a[2]) + "\t" + a[3]
                        + "\t" + a[0] + "\t" + a[4]);
            }
            if (out.isEmpty()) {
                return new Result(false, "桌面上没有可提取的应用", null, null);
            }
            return new Result(true, "共 " + out.size() + " 个应用（条目表 " + s.table + "）",
                    null, out.toArray(new String[0]));
        } catch (Throwable t) {
            Bridge.log(Log.WARN, Const.TAG, "分组列表读取失败: " + t);
            return new Result(false, "读取分组失败: " + t, null, null);
        } finally {
            if (db != null) {
                try {
                    db.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static int groupRank(String key, java.util.Map<String, int[]> order) {
        if ("desktop".equals(key)) {
            return 0;
        }
        if ("dock".equals(key)) {
            return 90;
        }
        if ("other".equals(key)) {
            return 99;
        }
        return 10; // 文件夹
    }

    private static String cleanField(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** 从 favorites.intent 解析包名（Intent URI 的 component= 部分）。 */
    private static String parsePackage(String intentStr) {
        if (intentStr == null || intentStr.isEmpty()) {
            return null;
        }
        try {
            Intent it = Intent.parseUri(intentStr, Intent.URI_INTENT_SCHEME);
            if (it != null && it.getComponent() != null) {
                return it.getComponent().getPackageName();
            }
        } catch (Throwable ignored) {
        }
        // 回退：手动找 component=包名/...
        try {
            int i = intentStr.indexOf("component=");
            if (i >= 0) {
                String rest = intentStr.substring(i + "component=".length());
                int slash = rest.indexOf('/');
                if (slash > 0) {
                    return rest.substring(0, slash);
                }
            }
        } catch (Throwable ignored) {
        }
        // 回退：整串就是 ComponentName（有些库的 component 列直接存 pkg/cls）
        try {
            String t = intentStr.trim();
            if (t.indexOf("://") < 0 && t.indexOf(' ') < 0 && !t.startsWith("component=")) {
                int slash = t.indexOf('/');
                if (slash > 0) {
                    String pkg = t.substring(0, slash).trim();
                    if (pkg.indexOf('.') > 0 && pkg.matches("[A-Za-z0-9_.]+")) {
                        return pkg;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // =====================================================================
    // 文件工具
    // =====================================================================

    static void copyFile(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        InputStream in = null;
        OutputStream out = null;
        try {
            in = new FileInputStream(src);
            out = new FileOutputStream(dst);
            byte[] buf = new byte[65536];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            try {
                ((FileOutputStream) out).getFD().sync();
            } catch (Throwable ignored) {
            }
        } finally {
            closeQuietly(in);
            closeQuietly(out);
        }
    }

    /**
     * 覆盖式写回：先写同目录临时文件并 fsync，再原子 rename。
     * 这样可保留原 inode 的属主与 SELinux 上下文。
     */
    static void copyOver(File src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("mkdirs failed: " + parent);
        }
        File tmp = new File(dst.getAbsolutePath() + ".llbtmp");
        copyFile(src, tmp);
        if (!tmp.renameTo(dst)) {
            // rename 失败（极少数情况）退化为直接覆盖写
            copyFile(tmp, dst);
            tmp.delete();
        }
        try {
            dst.setReadable(true, false);
            dst.setWritable(true, false);
        } catch (Throwable ignored) {
        }
    }

    private static int copyDir(File src, File dst) throws IOException {
        return copyDirFiltered(src, dst, null);
    }

    private static int copyDirFiltered(File src, File dst, String onlyExt) throws IOException {
        File[] ls = src.listFiles();
        if (ls == null) {
            return 0;
        }
        if (!dst.isDirectory() && !dst.mkdirs() && !dst.isDirectory()) {
            throw new IOException("mkdirs failed: " + dst);
        }
        int n = 0;
        for (File f : ls) {
            if (f.isDirectory()) {
                n += copyDirFiltered(f, new File(dst, f.getName()), onlyExt);
            } else if (onlyExt == null || f.getName().endsWith(onlyExt)) {
                copyFile(f, new File(dst, f.getName()));
                n++;
            }
        }
        return n;
    }

    private static void writeText(File f, String text) throws IOException {
        File parent = f.getParentFile();
        if (parent != null && !parent.isDirectory()) {
            parent.mkdirs();
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
        } finally {
            closeQuietly(out);
        }
    }

    private static String readText(File f) {
        InputStream in = null;
        try {
            in = new FileInputStream(f);
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, "UTF-8"));
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            closeQuietly(in);
        }
    }

    private static void closeQuietly(Object c) {
        try {
            if (c instanceof InputStream) {
                ((InputStream) c).close();
            } else if (c instanceof OutputStream) {
                ((OutputStream) c).close();
            }
        } catch (Throwable ignored) {
        }
    }
}
