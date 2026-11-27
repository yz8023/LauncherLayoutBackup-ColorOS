# 桌面布局备份 · ColorOS 版（LauncherLayoutBackup-ColorOS）

> OPPO ColorOS 系统桌面图标排版的一键备份与恢复。**只备份排版数据（几百 KB ~ 数 MB），不含任何 APK。**
>
> ⚠️ **前提：手机已取得 root（KernelSU / Magisk / APatch 等）并且安装了 LSPosed。**
> 本项目是 LSPosed 模块，**离开 root 无法使用**。
>
> 需要澄清的是：**模块 App 自己申请零权限，日常备份也不需要给它授权 root**。
> 因为备份真正读写的代码跑在「系统桌面」自己的进程里，用的是桌面自己的身份。
> 唯一例外是**恢复**：为了在桌面进程之外安全替换数据库文件，
> 首次恢复会请求一次 root 授权（KernelSU / Magisk 弹窗，可记住），详见 §8。

**English**: An LSPosed module that backs up / restores the OPPO ColorOS launcher layout
(icon positions, folders, pages) as a tiny snapshot (**no APKs included**).

**Requires root (KernelSU / Magisk / APatch) + LSPosed** — it is an LSPosed module and cannot work without them.
**Backups** require no root grant for the app — the actual file I/O runs inside the system launcher process.
**Restoring** asks for a one-time root grant (see §8).

- 作用域：`com.android.launcher`（ColorOS 系统桌面 / System Launcher 16.x）
- 模块入口：`io.github.libxposed.api.XposedModule` · **libxposed API 102**
- 语言：Java（无 androidx 依赖，UI 全部代码构建）
- 移植自：[12-yuba/LauncherLayoutBackup](https://github.com/12-yuba/LauncherLayoutBackup)（小米/HyperOS 版）

---

## 1. 它解决什么问题

ColorOS 桌面一旦崩溃或被清数据，所有图标就会挤成一堆，只能手动一个一个拖回去。

官方「备份与恢复」确实能存布局，但它会把**所有 APK 一起打包**，动辄几个 GB，平时根本不想用。

这个模块只搬**排版数据**：

| 备份内容 | 说明 |
|---|---|
| `*.db`(+`-wal`/`-journal`) | 桌面数据库（ColorOS 库名未知，故 databases 下**所有** .db 全备） |
| `*.db.snapshot` | `VACUUM INTO` 生成的一致性**单文件快照**，最稳的恢复来源 |
| `子目录/` | databases 下所有子目录（抽屉分组等） |
| `prefs/*.xml` | SharedPreferences（含网格行列数等配置） |
| `readable/*.csv` | 每个表导成表格，人可直接看 |
| `README.txt` | 本次备份的完整说明（设备/库名/恢复步骤/署名） |
| `MANIFEST.sha256` | 所有文件的 SHA-256 校验 |

一次备份通常 **几百 KB ~ 数 MB**，可以放心地自动备份。

---

## 2. 特性

- **一键备份 / 一键恢复**，恢复前自动把当前状态另存为回滚点
- **自动备份**：每 7 / 30 / 180 天三档独立周期，跑在桌面进程里，App 不用常驻
- **布局指纹**：只在排版真的变化时才自动备份，不堆一模一样的备份
- **保留策略**：自动备份只保留最近 N 份（默认 5），**手动备份永不自动删除**
- **隐藏安全备份**：一键删除全部备份前会先留一份隐藏备份，可随时找回
- **首次使用强制设定备份目录**，避免备份混在 Download 里被误删
- **备份路径可自选**，但只允许手机内部存储
- **关机 / 重启前自动备份**（非正常断电会漏，见 §7）
- **主库自动识别**：按表名打分（含 favorites / desktop / screen / widget 者优先），不硬编码库名
- **恢复后自动校验**：比对源文件与目标库的 md5，不一致会明确报失败而不是假装成功
- 界面仿 KernelSU / Miuix 观感，支持系统深浅色

---

## 3. 工作原理

### 3.1 为什么备份不用给模块 App 授权 root

模块通过 LSPosed 注入到**系统桌面自己的进程**里（这一步需要 root + LSPosed）。于是：

- 读自己的 `/data/user_de/0/com.android.launcher/databases/` —— 天然合法，**不需要额外授权**
- 桌面进程以自身身份写共享存储（`/sdcard/Download`）

所以模块 App 本身**不申请任何权限**，只是个遥控器；
日常备份你也不需要像用 root 脚本那样，给某个 App 单独开 root 授权。
（恢复是另一条路，需要一次 root 授权，见 §8。）

> ⚠️ **ColorOS 特殊注意（已实测 System Launcher 16.6.8）**：ColorOS 桌面**未声明**
> `MANAGE_EXTERNAL_STORAGE` / `WRITE_EXTERNAL_STORAGE`，桌面进程默认不能直写
> `/sdcard/Download`。本版已做自动回退：配置的备份目录不可写时，会自动切到
> 桌面自己的 external files 目录（`Android/data/com.android.launcher/files/LauncherLayoutBackup/`），
> 无需额外授权，文件管理器/电脑 USB 均可取出。
> 若想把备份直接写到 Download，可用 root 执行：
> ```bash
> adb shell "su -c 'appops set com.android.launcher MANAGE_EXTERNAL_STORAGE allow'"
> ```
> 探测脚本 `coloros_probe.sh` 会自动检查这一项。

### 3.2 数据流

```
┌──────────────────────┐        广播 (setPackage=com.android.launcher)     ┌───────────────────────────┐
│ 模块 App              │ ───────────────────────────────────────────► │ 系统桌面进程                │
│ MainActivity          │                                              │ LauncherReceiver          │
│  ├ PageHome           │ ◄─────────────────────────────────────────── │   ↓                       │
│  ├ PageBackup         │        回执 (setPackage=模块)                 │ BackupEngine              │
│  └ PageSettings       │                                              │   ↓ 直接读写自己的私有库     │
│ 不申请权限，只发广播     │                                              │ /sdcard/… 或自选目录        │
└──────────────────────┘                                              └───────────────────────────┘
                                                                              ▲
                                                              Schedule（定时排程 + 关机广播）
```

### 3.3 入口与 hook

```java
public final class ModuleEntry extends XposedModule {          // libxposed API 102
    @Override public void onPackageReady(PackageReadyParam p) {
        if (!"com.android.launcher".equals(p.getPackageName())) { detach(); return; }
        hook(Application.class.getMethod("onCreate"))          // onPackageReady 不给 Context
            .setId("llb-application-oncreate")
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept(chain -> {
                Object r = chain.proceed();
                Bridge.install((Context) chain.getThisObject(), this);
                return r;
            });
    }
}
```

`Bridge.install()` 里注册一个**动态广播接收器**，并启动自动备份排程。
之后每一次「备份 / 恢复 / 列举 / 配置」都是一个广播请求 + 一个回执。

### 3.4 恢复是怎么做的

1. 先把**当前状态**另存为一份回滚点（`恢复前自动`）
2. 把选定文件写回数据库目录：**临时文件 → fsync → rename 覆盖**，
   保留原 inode，从而保住属主与 SELinux 上下文
3. 以备份里的文件集合为准增删 `-wal` / `-journal`；`-shm` 一律不恢复
4. **在进程被 kill 前最后一步**再清一次 `-wal` / `-shm`（否则桌面可能重建 wal，
   重启后套到新库上，反而弄坏数据）
5. `Process.killProcess(myPid())` 让桌面进程重启，重新加载新库
6. root 兜底：模块 App 用 root 在进程外执行 `am force-stop → cp → rm wal/shm → chown/chmod/restorecon → am start`

### 3.5 布局指纹

优先对 `favorites` / `screens` 表的排版相关列做 SHA-256；
若 ColorOS 主库没有这两张表（表名未知），自动回退到**通用指纹**：
遍历所有表，哈希除易变列（`icon` / `launchCount` / `itemFlags` / `sortMode` /
`displayMode` / `modified`）外的全部列。

### 3.6 自动备份排程

跑在**桌面进程**里 —— 桌面是常驻进程、开机就活着，所以：

- 不需要 `AlarmManager`
- 不需要后台保活
- **不要求模块 App 在运行**

配置持久化在 `/data/user_de/0/com.android.launcher/files/llb_schedule.properties`，
App 通过广播读写。检查间隔 1 小时。

### 3.7 与小米版的差异（移植要点）

| 项 | 小米/HyperOS 版 | ColorOS 版（本项目） |
|---|---|---|
| 作用域 | `com.miui.home` | `com.android.launcher` |
| 主库识别 | 硬编码 `launcher*.db` | **自动探测所有 .db，按表名打分选主库**（实测主库 `launcher.db` + `launcher_X_by_Y.db` 网格变体） |
| 备份范围 | `launcher*.db` + 3 个附属库 | **databases 下所有 .db 全备**（含 badge/app-prediction 等缓存库） |
| 子目录 | `drawer/` / `elderlyMan/` | **所有子目录全备** |
| 网格识别 | 库名 `launcher5x8.db` → 5×8 | 库名 `launcher_4_by_5.db` → 4×5（另兼容 `launcher_4x4.db`），认不出显示「通用」 |
| 指纹 | `favorites` / `screens` | 列名按实际表结构动态拼装（含 `vCellX/vCellY/rank`），屏幕表兼容 `workspacescreens`，无 favorites 时回退通用全表哈希 |
| 可读导出 | `favorites.csv` / `screens.csv` | **所有表**各导一份 CSV，布局图兼容 `workspacescreens` + `screen/screenId` |
| 外部存储权限 | 桌面自带 `MANAGE_EXTERNAL_STORAGE` | 桌面未声明该权限，**自动回退到 external files 目录**（见 §3.1） |

---

## 4. 安装与启用

1. 安装 APK
   - 若提示 `INSTALL_FAILED_USER_RESTRICTED`，说明 ColorOS 关掉了 USB 安装：
     开发者选项里打开「**通过 USB 安装**」；或
     `adb push x.apk /data/local/tmp/ && adb shell su -c 'pm install -r /data/local/tmp/x.apk'`
2. **LSPosed 管理器 → 模块 → 桌面布局备份 → 打开开关**
3. 作用域只需勾 **系统桌面（com.android.launcher）**，不要勾系统框架
4. 重启桌面进程（模块里有按钮），或重启手机
5. 打开 App：**首次会强制要求你指定一个备份目录**（不设好进不去）

> 注意：**本模块需要「已 root + 已安装 LSPosed」才能运行**（LSPosed 本身就是 root 方案的一部分）。
> 上面说的「零权限」指的是 **模块 App 不申请任何权限、也不需要被单独授予 root**，
> 而不是说可以在没 root 的手机上使用。

---

## 5. 怎么编译

无需 Android Studio / Gradle，`build.sh` 直接用 `aapt2 + javac + d8 + jarsigner` 出包：

```bash
bash build.sh          # Windows / Git Bash（原版脚本）
bash build_linux.sh    # Linux（本机已验证，产物 LauncherLayoutBackup-ColorOS.apk）
```

依赖：

| 工具 | 说明 |
|---|---|
| JDK 17+ | 提供 `javac` / `keytool` / `jarsigner` |
| `android.jar` | 编译用（minSdk 26 就够） |
| `aapt2` | 编译资源 + 生成 `R.java` |
| `r8.jar` | 内含 `D8`，把 class 转成 dex |
| `io.github.libxposed:api:102.0.0` | 从 Maven Central 取；**注意它的 `.jar` 是空壳，真正的类在 `.aar` 的 `classes.jar` 里** |

链路：`aapt2 compile → aapt2 link(+--java R.java) → javac → d8 → 组装 APK → jarsigner(v1)`

`build.sh` 的依赖路径全部可用环境变量覆盖（`JDK_HOME` / `ANDROID_JAR` / `AAPT2_BIN` / `R8_JAR` /
`LIBS_DIR` / `KEYSTORE_FILE` / `KEYSTORE_PASS` / `KEYSTORE_ALIAS`），
`libxposed` 的 aar 若本地没有会自动从 Maven Central 下载 —— **所以从干净检出也能直接构建**。

> 本项目用 **v1 签名 + `targetSdkVersion=29`**：因为 Android 11+ 只对 `targetSdk >= 30` 强制 v2/v3，
> 29 走 v1 可正常安装（已实测）。副作用是设置里可能显示"专为旧版 Android 打造"。

---

## 6. 目录结构

```
LauncherLayoutBackup-ColorOS/
├── AndroidManifest.xml                  # 无任何 xposed* meta-data
├── build.sh                             # 一键编译
├── coloros_probe.sh                     # ★ ColorOS 桌面探测脚本（给开发者看库结构用）
├── res/
│   ├── drawable/ic_*.xml                # 手写 VectorDrawable（不依赖任何 UI 库）
│   ├── mipmap-xxhdpi/ic_launcher.png
│   ├── values/styles.xml                # NoActionBar + 浅色
│   └── values-night/styles.xml          # 深色
├── resources/META-INF/xposed/           # ★ 新版 API 的模块声明
│   ├── java_init.list                   #   入口类
│   ├── module.prop                      #   minApiVersion/targetApiVersion/staticScope
│   └── scope.list                       #   com.android.launcher
└── src/com/su/launcherlayout/
    ├── ModuleEntry.java                 # 入口，extends XposedModule
    ├── Bridge.java                      # 在桌面进程里注册动态广播接收器
    ├── LauncherReceiver.java            # 处理所有请求
    ├── BackupEngine.java                # 备份/恢复/CSV导出/校验和/布局指纹/删除/安全备份
    ├── Schedule.java                    # 定时排程、关机广播、配置持久化
    ├── Theme.java / UiKit.java / UiLog.java / BaseActivity.java / Page.java
    ├── MainActivity.java                # 单 Activity + 底部导航
    ├── PageHome.java / PageBackup.java / PageSettings.java
    └── Const.java
```

约 5000 行 Java，**零第三方依赖**（连 androidx 都没用）。

### 与旧版 Xposed API 的差异

| 项 | 旧版 | 本项目（API 102） |
|---|---|---|
| 入口注册 | `assets/xposed_init` | `META-INF/xposed/java_init.list` |
| 模块元数据 | 4 个 `xposed*` meta-data | `META-INF/xposed/module.prop` |
| 作用域 | `xposedscope` 资源 | `META-INF/xposed/scope.list` |
| 基类 | `IXposedHookLoadPackage` | `extends XposedModule` |
| Hook | `XposedHelpers.findAndHookMethod` | `hook(Executable).setId().setPriority().setExceptionMode().intercept(chain -> …)` |

> `targetApiVersion >= 102` 时，classloader 层就禁止访问旧 API，
> 所以 `XposedHelpers` / `XSharedPreferences` / `XposedBridge` 全部不可用，本项目自带反射工具。

---

## 6.5 一直提示「请求超时，桌面进程无响应」？

先读 **[docs/超时排查.md](docs/超时排查.md)**（v1.1.1 已修复主因）。

一句话版：桌面进程退到后台后会被 ColorOS 冻结，发给它的广播会掉进**后台广播队列**排队十几秒，
所以 App 9 秒就判超时了。v1.1.1 起：

- 请求 / 回执广播全部带上 `FLAG_RECEIVER_FOREGROUND`，走前台队列，通常几十毫秒送达；
- 首次 3.5 秒无回音会**自动重发一次**（重发本身就能唤醒被冻结的进程），再等 9 秒；
- 设置页新增「桌面连接诊断」：`测试连接` 显示真实往返延迟，`唤醒桌面` 用 root 解冻后重测。

模块更新**不需要重启手机**：设置页 →「热重载：快速重启桌面」让桌面进程重新加载模块即可。

## 6.6 「加载失败：主库无 favorites 表」？

见 **[docs/表结构兼容.md](docs/表结构兼容.md)**（v1.1.2 已修复）。

一句话版：旧版把条目表名写死成 `favorites`（AOSP/小米桌面的叫法），ColorOS 改过的桌面
可能叫别的名字，或放在别的 `.db` 里。v1.1.2 起改为**跨所有库按结构识别**条目表
（主键 + container + 组件三件套），并兼容各种列名写法。

**备份/恢复不受此问题影响**（它们是文件级整库拷贝）；只有分组提取需要表结构。
若仍认不出，分组提取页右上角 **「导出表结构」** → 复制 → 发回即可。

## 6.7 分组提取失败 / 只能选空目录 / 名字和桌面不一致？

见 **[docs/分组提取使用说明.md](docs/分组提取使用说明.md)**（v1.1.3）。

- **提取全失败、报 `Invalid URI`**：旧版只走 SAF，URI 形式一变就挂。v1.1.3 改为 **root 直写绝对路径**
  （`mkdir -p` + `cp` + `stat` 校验），任意目录可写，SAF 仅作备用。
- **只能选空目录**：那是 Android 11+ 系统选择器的限制（Download 根目录等会被拒），
  改用输入框填路径即可。
- **每个分组建独立文件夹**：新增开关，默认开，输出到 `<目标目录>/<分组名>/`。
- **名字和桌面不一致**（桌面「交通导航」/这里 `Navigation`）：现在**优先用系统本地化名称**，
  数据库旧标注放在第二行「桌面标注：…」里。
- **默认全部折叠 + 默认全不选**，勾选不再重建列表，图标异步加载 → 不再卡顿。
- **同名文件夹不再合并**（v1.1.4）：分组按文件夹的数据库 `_id` 归组，
  同名时标题显示「第 N 屏」区分，独立文件夹也不会混到一起。

## 6.8 v1.2.0：不再重复检查、功能去重、界面重排

见 **[docs/v1.2.0-界面与去重.md](docs/v1.2.0-界面与去重.md)**。

- **检查只拦一次**：通过后记在本地，之后启动直接进主界面（后台静默复核），
  只有复核连续失败 2 次才重新亮出检查页；想手动重查：设置 →「使用前检查」。
- **功能去重**：重启桌面只剩「设置 → 桌面进程」一个按钮（失败自动 root 兜底）；
  「测试连接 / 重新检测 / 唤醒桌面」合并；主页不再重复放「一键恢复」「重启桌面」。
- **界面**：主页改成「状态一行 → 主操作 → 最近备份 → 自动备份」三层；
  备份页每条一张卡，最新那条淡绿底 +「最新」胶囊，相对时间当主标题。

## 6.9 v1.2.1：提取页回归修复 + 视觉定版

见 **[docs/v1.2.1-回退修复与主题.md](docs/v1.2.1-回退修复与主题.md)**。

- v1.2.0 打包时 `PageExtract.java` 被旧版本覆盖，导致**折叠消失、进入即卡、名字又变英文**；
  v1.2.1 已从 v1.1.4 原样恢复并进一步异步化（名称/图标都不再占用主线程）。
- 新增 `scripts/preflight.sh`：发布前自检 20 项关键修复，回退了就不许发。
- 视觉定版：中性底 + 白色描边卡 + 克制的绿（`#1B6B35`），分组小标题改为灰色小字。

## 7. 已知边界（请先读）

- **关机前备份是尽力而为**。`ACTION_SHUTDOWN` 是系统保护广播，
  manifest 静态注册在 Android 8+ 收不到，动态注册能收到（本项目就是这么做的）；
  但**非正常断电**（长按电源强制重启、拔电、内核 panic、`reboot -f`）**根本不发广播**，必然漏。
  另外关机本身并不会弄乱桌面，所以它只是个"顺手多一层"的保险，不需要可以关掉。
- **网格必须一致**。ColorOS 库名不编码网格，恢复时请确认当前网格与备份一致，
  否则图标可能错位。界面上网格不一致会红字提醒。
- **云同步可能覆盖**：OPPO 云服务在联网时可能回写布局，恢复后建议关掉或立刻反向同步一次。
- **备份路径只允许内置存储**：外置 SD / U 盘拔掉后，备份会在 App 里看不到。
- **修改包名 / 签名后 LSPosed 状态会重置**。换签名只能卸载重装，会清掉启用状态与作用域。
- 编译用的是 API 26～32 区间的公共 `android.jar`，因此
  `Context.RECEIVER_EXPORTED`（API 33+）只能反射调用，`Context.getUserId()` 是 `@hide` 需要绕开。
- **已按 System Launcher 16.6.8 校准**：包名 `com.android.launcher`、主库 `launcher.db` /
  `launcher_X_by_Y.db`、表 `favorites` / `workspacescreens` 均已对齐。其它 ColorOS 版本
  若库名/表名不同，通用探测（所有 .db 全备 + 表名打分）仍可兜底。

---

## 8. 隐私

- 模块 App **不申请任何权限**，不联网，无统计，无广告
- **备份不做 root 提权**：读写由桌面进程用自身身份（普通应用 uid）完成
- **恢复需要一次 root 授权**（KernelSU / Magisk 弹窗，可记住）：因为要在桌面进程之外替换它的数据库文件，
  并修正属主与 SELinux 上下文 —— 桌面进程跑在 `platform_app` 域，自己既不能 exec、写库也会被回写覆盖
- 备份文件**只写在你指定的目录**，不上传任何地方
- 权限清单为空 —— 可以在系统设置里自己核对

> **关于 root**：安装与运行前提是「手机已 root + 已装 LSPosed」，因为 LSPosed 本身属于 root 方案。
>
> **备份全程不提权**：桌面进程以普通应用 uid 读写自己的数据库。
>
> **恢复需要一次 root 授权**。原因是恢复必须在**桌面进程之外**完成：
> 桌面跑在 `platform_app` 域，自己不能 exec 任何东西；而在进程内写库又会被它自己的内存状态回写覆盖。

---

## 9. 给测试者的 ADB 探测（校准用）

开发 ColorOS 版最关键的是确认桌面包名、数据库文件名和表名。
请在电脑上连手机（已开 USB 调试 + root），运行：

```bash
bash coloros_probe.sh
```

脚本会输出一份 `coloros_probe_output.txt`，包含：

- 设备/系统版本、桌面包名与版本、APK 路径
- databases / shared_prefs / files 目录清单
- 每个 .db 的表名与建表语句（`sqlite3 .schema`）
- 桌面进程的外部存储权限状态

把输出文件 + 系统桌面 APK（`pm path` 得到的那个）发回即可校准。
提取 APK：

```bash
adb shell pm path com.android.launcher
adb pull /data/app/.../base.apk ./SystemLauncher.apk
```

---

## 致谢

备份格式与整体思路：酷安 @瞑望、@桃酥打水（原小米版作者）。
本 ColorOS 版为其移植，署名保留。
