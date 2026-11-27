#!/usr/bin/env bash
# 发布前自检：确认关键修复没有被回退（历史上踩过一次，整页代码被旧版本覆盖）。
# 用法：bash scripts/preflight.sh   —— 全部 OK 才继续 build + release
set -u
PROJ="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJ"
fail=0

need() { # need <描述> <文件> <关键字>
  if grep -q -- "$3" "$2"; then
    printf '  ✓ %s\n' "$1"
  else
    printf '  ✗ %s  (缺 %s in %s)\n' "$1" "$3" "$2"
    fail=1
  fi
}

echo "== 传输层 / 超时修复 =="
need "请求用前台广播队列"        src/com/su/launcherlayout/BaseActivity.java "FLAG_RECEIVER_FOREGROUND"
need "首次超时静默重发"          src/com/su/launcherlayout/BaseActivity.java "TIMEOUT_FIRST_MS"
need "回执也用前台队列"          src/com/su/launcherlayout/Bridge.java "FLAG_RECEIVER_FOREGROUND"
need "桥接注册 GROUPS"           src/com/su/launcherlayout/Bridge.java "ACTION_GROUPS"
need "桥接注册 SCHEMA"           src/com/su/launcherlayout/Bridge.java "ACTION_SCHEMA"

echo "== 桌面数据库兼容 =="
need "跨库识别条目表"            src/com/su/launcherlayout/BackupEngine.java "resolveItemSchema"
need "表结构导出"                src/com/su/launcherlayout/BackupEngine.java "schemaReport"
need "分组唯一键（同名文件夹区分）" src/com/su/launcherlayout/BackupEngine.java "分组唯一键"
need "接收端处理 SCHEMA"          src/com/su/launcherlayout/LauncherReceiver.java "OP_SCHEMA"

echo "== 分组提取 =="
need "默认折叠/展开状态"          src/com/su/launcherlayout/PageExtract.java "mExpanded"
need "按分组键归组"              src/com/su/launcherlayout/PageExtract.java "groupKey"
need "同名分组标签"              src/com/su/launcherlayout/PageExtract.java "buildGroupLabels"
need "root 直写"                 src/com/su/launcherlayout/PageExtract.java "writeWithRoot"
need "SAF 兜底"                  src/com/su/launcherlayout/PageExtract.java "writeWithSaf"
need "分组独立文件夹"             src/com/su/launcherlayout/PageExtract.java "groupDirName"
need "名称异步解析(不卡主线程)"    src/com/su/launcherlayout/PageExtract.java "resolveNameLater"
need "图标异步加载"              src/com/su/launcherlayout/PageExtract.java "bindIcon"

echo "== 门禁 / 去重 =="
need "门禁通过记忆"              src/com/su/launcherlayout/MainActivity.java "KEY_GATE_AT"
need "手动重新检查入口"           src/com/su/launcherlayout/MainActivity.java "recheckGate"
need "设置页唯一重启入口"          src/com/su/launcherlayout/PageSettings.java "重启桌面进程"

echo "== 恶意回退检查（不该再出现的旧代码）=="
for f in PageHome PageExtract PageBackup PageSettings; do
  if grep -q "重启桌面进程\", \"让模块注入" "src/com/su/launcherlayout/$f.java" 2>/dev/null; then
    echo "  ✗ $f 出现了已删除的重复「重启桌面」入口"; fail=1
  fi
done
if grep -q "persistableUriPermission" src/com/su/launcherlayout/PageExtract.java && \
   ! grep -q "writeWithRoot" src/com/su/launcherlayout/PageExtract.java; then
  echo "  ✗ PageExtract 退回到「只走 SAF」的旧实现"; fail=1
fi

echo
if [ $fail -eq 0 ]; then
  echo "自检通过 ✅  可以 build + release"
else
  echo "自检失败 ❌  先修好再发布"
fi
exit $fail
