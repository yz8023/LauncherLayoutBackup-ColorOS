# ColorOS 版测试指南（给测试者）

## 我需要你发回什么（按重要性排序）

### 1. 系统桌面 APK（最重要，你已选这项）
这是我反编译看 ColorOS 数据库结构、表名、字段的唯一依据。没有它，我只能靠猜。

```bash
adb shell pm path com.android.launcher
# 会输出类似：package:/data/app/~~xxx/com.android.launcher-xxx/base.apk
adb pull /data/app/~~xxx/com.android.launcher-xxx/base.apk ./SystemLauncher.apk
```

把 `SystemLauncher.apk` 发我即可（通常 20~40MB）。

> 如果包名不是 `com.android.launcher`，先跑 `adb shell pm list packages | grep -i launcher` 确认，
> 把实际包名告诉我，我改 `Const.TARGET_PKG` 和 `scope.list`。

### 2. ADB 探测输出（第二重要）
电脑连手机（USB 调试 + root），运行：

```bash
bash coloros_probe.sh
```

会生成 `coloros_probe_output.txt`，里面有：
- 设备/系统版本、桌面版本
- databases 目录下有哪些 .db 文件
- 每个 .db 的表名和建表语句（`.schema`）
- 桌面进程的外部存储权限状态

把这个 txt 发我，我就能校准主库识别和指纹列。

### 3. 装上后的测试日志（第三重要）
编译出 APK 后（见下），装上测试，把以下信息发我：
- LSPosed 里模块是否已激活（作用域勾了系统桌面）
- App 首页显示的「当前库 / 当前网格 / 数据库目录」
- 备份是否成功、备份目录里有哪些文件
- 恢复是否成功、恢复后桌面是否正常
- 任何报错截图 + App 里的操作日志面板内容

## 不需要你发的东西

- **不需要微信/QQ 等第三方 App 的 APK**：备份只存排版数据，不含 APK。
  测试时桌面上有几个图标、文件夹、Widget 就够了（有文件夹和 Widget 最好，
  能覆盖更多表）。
- 不需要整机备份、照片等个人数据。

## 怎么编译 APK

需要 JDK 17+ 和 Android SDK（aapt2 / android.jar / r8.jar）。
最简单是直接用 GitHub Actions（仓库自带 `.github/workflows/build.yml`），
或者在本机按 README §5 的 `bash build.sh` 编译。

> ⚠️ 签名密钥必须固定：换签名只能卸载重装，会清掉 LSPosed 启用状态。
> 首次编译生成的 `llb.jks` 请妥善保存。

## 测试步骤（建议顺序）

1. 跑 `coloros_probe.sh`，把输出 + `SystemLauncher.apk` 发我（先校准）
2. 编译 APK → 安装 → LSPosed 勾选 `com.android.launcher` → 重启桌面/手机
3. 打开 App，设定备份目录（不要放 Download）
4. 手动备份一次，检查备份目录里有 `.db` 文件和 `readable/*.csv`
5. 故意打乱桌面（拖几个图标、建个文件夹），再恢复，看是否回到备份前状态
6. 把第 3 步的日志发我，有问题我改代码

## 已知风险（先看）

- ColorOS 主库名/表名尚未实测，本版是「所有 .db 全备 + 表名打分」通用方案，
  首次备份后请确认 `readable/` 里导出的 CSV 是否包含图标坐标。
- 若备份报「无法创建备份目录」，是 ColorOS 桌面缺 `MANAGE_EXTERNAL_STORAGE`，
  先执行 README §3.1 的授权命令再重试。
