#!/usr/bin/env bash
# 重建构建环境（Linux，无 Android Studio / Gradle）。
# 用法：bash bootstrap_env.sh && bash build_linux.sh
#
# 会下载到 /home/user/.build（可用 BUILD_HOME 覆盖）：
#   jdk/jdk-17*                        Temurin JDK 17（libxposed api 102 的 class 版本是 61，需要 17）
#   sdk/platforms/android-34/          android.jar
#   sdk/build-tools/34.0.0/aapt2       资源编译
#   sdk/build-tools/34.0.0/d8          d8 包装脚本（实际调用 libs/r8-*.jar）
#   libs/r8-8.1.72.jar                 d8 所在 jar
#   libs/libxposed-api-102.jar         由 build_linux.sh 自动拉取
set -e
BH="${BUILD_HOME:-/home/user/.build}"
mkdir -p "$BH/jdk" "$BH/sdk/platforms" "$BH/sdk/build-tools/34.0.0" "$BH/libs"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

dl() { # dl <url> <dest>
  echo "  下载 $(basename "$2") ..."
  curl -sSL --retry 3 -o "$2" "$1"
}

if ! ls "$BH"/jdk/jdk-17*/bin/javac >/dev/null 2>&1; then
  dl "https://api.adoptium.net/v3/binary/latest/17/ga/linux/x64/jdk/hotspot/normal/eclipse" "$TMP/jdk17.tar.gz"
  tar xzf "$TMP/jdk17.tar.gz" -C "$BH/jdk"
fi

if [ ! -f "$BH/sdk/platforms/android-34/android.jar" ]; then
  dl "https://dl.google.com/android/repository/platform-34-ext7_r03.zip" "$TMP/platform34.zip"
  unzip -q -o "$TMP/platform34.zip" -d "$TMP/p34"
  mv "$TMP/p34/android-34" "$BH/sdk/platforms/android-34"
fi

if [ ! -f "$BH/sdk/build-tools/34.0.0/aapt2" ]; then
  dl "https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.1.4-10154469/aapt2-8.1.4-10154469-linux.jar" "$TMP/aapt2.jar"
  unzip -q -o "$TMP/aapt2.jar" -d "$TMP/aapt2x"
  cp "$TMP/aapt2x/aapt2" "$BH/sdk/build-tools/34.0.0/aapt2"
  chmod +x "$BH/sdk/build-tools/34.0.0/aapt2"
fi

if [ ! -f "$BH/libs/r8-8.1.72.jar" ]; then
  dl "https://dl.google.com/dl/android/maven2/com/android/tools/r8/8.1.72/r8-8.1.72.jar" "$BH/libs/r8-8.1.72.jar"
fi

cat > "$BH/sdk/build-tools/34.0.0/d8" <<EOF
#!/usr/bin/env bash
exec java -cp "$BH/libs/r8-8.1.72.jar" com.android.tools.r8.D8 "\$@"
EOF
chmod +x "$BH/sdk/build-tools/34.0.0/d8"

echo "环境就绪：$BH"
