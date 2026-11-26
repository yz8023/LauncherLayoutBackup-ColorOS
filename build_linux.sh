#!/usr/bin/env bash
# ColorOS 版 Linux 构建脚本（aapt2 + javac + d8 + jarsigner，无需 Android Studio / Gradle）
# 用法：bash build_linux.sh
# 依赖（可用环境变量覆盖）：
#   JDK_HOME (默认 /home/user/.build/jdk/jdk-17*), ANDROID_SDK_ROOT (默认 /home/user/.build/sdk)
#   LIBS_DIR (默认 /home/user/.build/libs), KEYSTORE_FILE/PASS/ALIAS
set -e
PROJ="$(cd "$(dirname "$0")" && pwd)"
LIBS="${LIBS_DIR:-/home/user/.build/libs}"
JDK_HOME="${JDK_HOME:-$(echo /home/user/.build/jdk/jdk-17*)}"
SDK="${ANDROID_SDK_ROOT:-/home/user/.build/sdk}"
AAPT2="$SDK/build-tools/34.0.0/aapt2"
D8="$SDK/build-tools/34.0.0/d8"
ANDROID_JAR="$SDK/platforms/android-34/android.jar"
LIBXPOSED_JAR="$LIBS/libxposed-api-102.jar"
KEYSTORE_PASS="${KEYSTORE_PASS:-llb123456}"
KEYSTORE_ALIAS="${KEYSTORE_ALIAS:-llb}"
KEYSTORE_FILE="${KEYSTORE_FILE:-$PROJ/llb.jks}"

cd "$PROJ"
OUT="$PROJ/_out/build-$(date +%Y%m%d_%H%M%S)"
mkdir -p "$OUT/classes" "$OUT/dex" "$OUT/gen" "$OUT/apk"

# libxposed：本地没有则从 Maven Central 拉 aar 解出 classes.jar
if [ ! -f "$LIBXPOSED_JAR" ]; then
  echo "  下载 io.github.libxposed:api:102.0.0 ..."
  mkdir -p "$LIBS/_aar"
  curl -sSL -o "$LIBS/_aar/api.aar" "https://repo1.maven.org/maven2/io/github/libxposed/api/102.0.0/api-102.0.0.aar"
  ( cd "$LIBS/_aar" && unzip -o -q api.aar classes.jar && cp classes.jar "$LIBXPOSED_JAR" )
fi

echo "=== 0) 环境 ==="
echo "javac : $("$JDK_HOME/bin/javac" -version 2>&1)"
echo "aapt2 : $AAPT2"
echo "a.jar : $ANDROID_JAR"
echo "out   : $OUT"

echo
echo "=== 1) aapt2 compile 资源 ==="
"$AAPT2" compile --dir res -o "$OUT/res.zip"
echo "OK"

echo
echo "=== 2) aapt2 link（同时生成 R.java） ==="
"$AAPT2" link \
  -o "$OUT/apk/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest "$PROJ/AndroidManifest.xml" \
  "$OUT/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 26 --target-sdk-version 29 \
  --version-code 36 --version-name 1.1.3
find "$OUT/gen" -name '*.java'

echo
echo "=== 3) javac ==="
find src -name '*.java' > "$OUT/sources.txt"
find "$OUT/gen" -name '*.java' >> "$OUT/sources.txt"
"$JDK_HOME/bin/javac" -J-Duser.language=en -J-Duser.country=US \
  --release 11 -encoding UTF-8 -nowarn \
  -cp "$ANDROID_JAR:$LIBXPOSED_JAR" -d "$OUT/classes" @"$OUT/sources.txt"
echo "OK, class 文件数: $(find "$OUT/classes" -name '*.class' | wc -l)"

echo
echo "=== 4) d8 -> classes.dex ==="
find "$OUT/classes" -name '*.class' > "$OUT/classes.list"
"$D8" --min-api 26 --lib "$ANDROID_JAR" --output "$OUT/dex" @"$OUT/classes.list"
ls -la "$OUT/dex"

echo
echo "=== 5) 组装 APK ==="
python3 - "$OUT" "$PROJ" <<'PY'
import sys, os, zipfile
out, proj = sys.argv[1], sys.argv[2]
src = os.path.join(out, "apk", "base.apk")
dst = os.path.join(out, "apk", "unsigned.apk")
with zipfile.ZipFile(src, "r") as zin, zipfile.ZipFile(dst, "w", zipfile.ZIP_DEFLATED) as zout:
    for item in zin.infolist():
        zout.writestr(item, zin.read(item.filename))
    zout.write(os.path.join(out, "dex", "classes.dex"), "classes.dex")
    base = os.path.join(proj, "resources", "META-INF", "xposed")
    for name in sorted(os.listdir(base)):
        zout.write(os.path.join(base, name), "META-INF/xposed/" + name)
print("packed ->", dst, os.path.getsize(dst), "bytes")
with zipfile.ZipFile(dst) as z:
    for n in z.namelist():
        print("   ", n)
PY

echo
echo "=== 6) 签名（v1） ==="
KS="$KEYSTORE_FILE"
if [ ! -f "$KS" ]; then
  echo "生成新密钥库: $KS"
  "$JDK_HOME/bin/keytool" -genkeypair -keystore "$KS" -storetype PKCS12 \
    -alias "$KEYSTORE_ALIAS" -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
    -dname "CN=LauncherLayoutBackup-ColorOS, OU=su, O=su, L=Taiyuan, ST=Shanxi, C=CN"
fi
"$JDK_HOME/bin/jarsigner" -keystore "$KS" -storepass "$KEYSTORE_PASS" -keypass "$KEYSTORE_PASS" \
  -sigalg SHA256withRSA -digestalg SHA-256 \
  -signedjar "$OUT/LauncherLayoutBackup-ColorOS.apk" "$OUT/apk/unsigned.apk" "$KEYSTORE_ALIAS"

echo
echo "=== 7) 产物 ==="
ls -la "$OUT/LauncherLayoutBackup-ColorOS.apk"
python3 -c "
import zipfile,hashlib
p=r'$OUT/LauncherLayoutBackup-ColorOS.apk'
z=zipfile.ZipFile(p)
print('entries:', len(z.namelist()))
print('dex:', [n for n in z.namelist() if n.endswith('.dex')])
print('xposed:', [n for n in z.namelist() if 'xposed' in n])
print('md5:', hashlib.md5(open(p,'rb').read()).hexdigest())
"
cp "$OUT/LauncherLayoutBackup-ColorOS.apk" "$PROJ/LauncherLayoutBackup-ColorOS.apk"
echo "APK=$PROJ/LauncherLayoutBackup-ColorOS.apk"
echo "DONE"
