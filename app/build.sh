#!/bin/bash
# 宅家打印机 APK 手动构建：aapt2 + javac + d8 + zipalign + apksigner
# 不用 Gradle（内存只有 ~500MB 可用，且 sdkmanager 的 Java 代理有问题；
# 手动构建零 Maven 依赖，全部用已下载好的 SDK）。
set -e
cd "$(dirname "$0")"

export JAVA_HOME="$HOME/workspace/android/jdk17"
export ANDROID_HOME="$HOME/workspace/android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH"
# 限制所有 Java 工具堆内存，避免 OOM
export _JAVA_OPTIONS="-Xmx1g"

BUILD_DIR="$PWD/build"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex"

echo "== 1. keystore（如无则生成 debug key） =="
if [ ! -f debug.keystore ]; then
  keytool -genkeypair -keystore debug.keystore -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=RemotePrinter, OU=dev, O=shi, C=CN" -noprompt
fi

echo "== 2. aapt2 编译资源 =="
aapt2 compile --dir res -o "$BUILD_DIR/compiled.zip"

echo "== 3. aapt2 链接（生成未签名 APK 骨架） =="
aapt2 link -o "$BUILD_DIR/app-unsigned.apk" \
  -I "$ANDROID_HOME/platforms/android-34/android.jar" \
  --manifest AndroidManifest.xml \
  "$BUILD_DIR/compiled.zip"

echo "== 4. javac 编译 Java（UTF-8，Java 8 字节码） =="
javac -encoding UTF-8 -source 8 -target 8 -Xlint:-options \
  -cp "$ANDROID_HOME/platforms/android-34/android.jar" \
  -d "$BUILD_DIR/classes" \
  src/com/shi/remoteprinter/*.java

echo "== 5. 打 jar + d8 转 dex（min-api 24） =="
(cd "$BUILD_DIR/classes" && jar cf "$BUILD_DIR/classes.jar" .)
d8 --lib "$ANDROID_HOME/platforms/android-34/android.jar" \
  --min-api 24 --output "$BUILD_DIR/dex" "$BUILD_DIR/classes.jar"
ls -lh "$BUILD_DIR/dex/"

echo "== 6. 把 classes.dex 打进 APK =="
(cd "$BUILD_DIR/dex" && zip -q "$BUILD_DIR/app-unsigned.apk" classes.dex)
unzip -l "$BUILD_DIR/app-unsigned.apk" | grep -E "classes.dex|AndroidManifest"

echo "== 7. zipalign =="
zipalign -f -p 4 "$BUILD_DIR/app-unsigned.apk" "$BUILD_DIR/app-aligned.apk"

echo "== 8. apksigner 签名 =="
apksigner sign --ks debug.keystore --ks-pass pass:android --key-pass pass:android \
  --out "$BUILD_DIR/remote-printer.apk" "$BUILD_DIR/app-aligned.apk"

echo "== 9. 验证 =="
apksigner verify --print-certs "$BUILD_DIR/remote-printer.apk"
aapt2 dump badging "$BUILD_DIR/remote-printer.apk" | head -20

echo "== 10. 复制到目标位置 =="
mkdir -p "$HOME/workspace/goals/ad-free-remote-printer-app/files"
cp "$BUILD_DIR/remote-printer.apk" "$HOME/workspace/goals/ad-free-remote-printer-app/files/remote-printer.apk"
ls -lh "$HOME/workspace/goals/ad-free-remote-printer-app/files/remote-printer.apk"
echo "构建成功"
