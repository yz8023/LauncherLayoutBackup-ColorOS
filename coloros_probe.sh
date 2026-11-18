#!/usr/bin/env bash
# ColorOS 桌面探测脚本：收集桌面包名 / 数据库文件名 / 表结构，供开发校准用。
# 用法：电脑连手机（USB 调试已开、已 root），运行 bash coloros_probe.sh
# 输出：coloros_probe_output.txt（把这个文件发回即可）
set -u
OUT="coloros_probe_output.txt"
PKG="com.android.launcher"
: > "$OUT"
log() { echo "$@" | tee -a "$OUT"; }
run() { log "+ $*"; "$@" 2>&1 | tee -a "$OUT"; log ""; }

log "===== ColorOS 桌面探测 $(date '+%Y-%m-%d %H:%M:%S') ====="
log "电脑 adb: $(adb version 2>&1 | head -1)"

# 先检查设备连接，避免全是 no devices 的空输出
log ""
log "----- 0. 设备连接检查 -----"
DEVICES=$(adb devices 2>&1 | tee -a "$OUT")
if ! echo "$DEVICES" | grep -qE "device$|device[[:space:]]"; then
  log "!! 未检测到已连接设备（adb devices 无 device 状态）。"
  log "!! 请先：1) 手机开 USB 调试并连线  2) 手机上点允许  3) 重新运行本脚本。"
  log "!! 本次输出无有效信息，请连上手机后重跑。"
  exit 1
fi
log "设备已连接，继续探测。"
log ""

log "----- 1. 设备 / 系统 -----"
run adb shell getprop ro.product.manufacturer
run adb shell getprop ro.product.model
run adb shell getprop ro.build.display.id
run adb shell getprop ro.build.version.release
run adb shell getprop ro.build.version.sdk
run adb shell getprop ro.build.version.opporom
run adb shell getprop ro.coloros.version

log "----- 2. 桌面包信息 -----"
run adb shell pm list packages
run adb shell dumpsys package "$PKG"
run adb shell pm path "$PKG"
run adb shell pm dump "$PKG"

log "----- 3. 桌面进程 / 权限 -----"
run adb shell "ps -A | grep -i launcher"
run adb shell "appops get $PKG MANAGE_EXTERNAL_STORAGE"
run adb shell "appops get $PKG READ_EXTERNAL_STORAGE"
run adb shell "appops get $PKG WRITE_EXTERNAL_STORAGE"

log "----- 4. databases 目录清单 -----"
run adb shell "su -c 'ls -la /data/user_de/0/$PKG/databases/'"
run adb shell "su -c 'ls -la /data/user/0/$PKG/databases/'"
run adb shell "su -c 'ls -la /data/data/$PKG/databases/'"

log "----- 5. shared_prefs / files 清单 -----"
run adb shell "su -c 'ls -la /data/user_de/0/$PKG/shared_prefs/'"
run adb shell "su -c 'ls -la /data/user_de/0/$PKG/files/'"

log "----- 6. 每个 .db 的表结构 -----"
log "（需要手机上有 sqlite3；若没有，改用 adb pull 拉 .db 到电脑后用 sqlite3 打开）"
adb shell "su -c 'ls /data/user_de/0/$PKG/databases/*.db /data/user/0/$PKG/databases/*.db 2>/dev/null'" 2>/dev/null | tr -d '\r' | while read -r DB; do
  [ -z "$DB" ] && continue
  log "### DB: $DB"
  run adb shell "su -c 'sqlite3 \"$DB\" \".tables\"'"
  run adb shell "su -c 'sqlite3 \"$DB\" \".schema\"'"
done

log "----- 7. 提取系统桌面 APK（校准反编译用）-----"
run adb shell pm path "$PKG"
log "看到 base.apk 路径后，手动执行："
log "  adb pull <上面那个路径> ./SystemLauncher.apk"
log "然后把 SystemLauncher.apk 一起发回。"

log "===== 探测完成，输出文件: $OUT ====="
log "请把 $OUT 和 SystemLauncher.apk 发回。"
