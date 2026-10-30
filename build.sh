#!/data/data/com.dsharnessmobile.shell/files/usr/bin/bash
# 手动构建 com.dsh.deviceinfo：aapt2 → javac → d8 → 打包 → v1/v2 签名 → 校验
set -e
export LC_ALL=C
set -o pipefail
H=/data/user/0/com.dsharnessmobile.shell/files/home
T=$H/tchain/data/data/com.termux/files/usr
ALIB=$H/adbget/merged2/data/data/com.termux/files/usr/lib
JAVA=$T/lib/jvm/java-17-openjdk/bin/java
JAVAC=$T/lib/jvm/java-17-openjdk/bin/javac
AAPT2=$T/bin/aapt2
TOOLS=/storage/emulated/0/鹿/memo_app/tools
AJ=$TOOLS/android.jar
R8=$TOOLS/r8.jar
SK=/storage/emulated/0/鹿/huawei-apk-repatch-skill/scripts
P=$H/devinfo_app

# 注意：不要全局 export LD_LIBRARY_PATH —— 会让 termux 的 rm/find 等工具找不到
# libandroid-selinux.so；只在调用 java/aapt2 时按命令注入。
JL="env LD_LIBRARY_PATH=$T/lib:$ALIB"

rm -rf "$P/build"; mkdir -p "$P/build/gen" "$P/build/classes" "$P/build/dex" "$P/out"

echo "--- 1/6 aapt2 compile 资源"
$JL "$AAPT2" compile --dir "$P/res" -o "$P/build/res.zip"

echo "--- 2/6 aapt2 link（生成 APK 骨架 + R.java）"
$JL "$AAPT2" link -o "$P/build/app-skeleton.apk" \
    -I "$AJ" \
    --manifest "$P/AndroidManifest.xml" \
    -R "$P/build/res.zip" \
    --java "$P/build/gen" \
    --min-sdk-version 23 --target-sdk-version 33 \
    --version-code 1 --version-name 1.0 \
    --auto-add-overlay

echo "--- 3/6 javac（source/target 8）"
SRCS=$(find "$P/src" "$P/build/gen" -name '*.java')
$JL "$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -bootclasspath "$AJ" -classpath "$P/build/gen" \
    -d "$P/build/classes" $SRCS

echo "--- 4/6 d8 生成 classes.dex"
CLASSES=$(find "$P/build/classes" -name '*.class')
$JL "$JAVA" -cp "$R8" com.android.tools.r8.D8 --release --min-api 23 \
    --lib "$AJ" --output "$P/build/dex" $CLASSES

echo "--- 5/6 打包 classes.dex 进 APK"
python3 - "$P/build/app-skeleton.apk" "$P/build/dex/classes.dex" "$P/out/app.unsigned.apk" <<'PY'
import sys, zipfile, shutil
src, dex, out = sys.argv[1], sys.argv[2], sys.argv[3]
zin = zipfile.ZipFile(src)
with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as zo:
    for item in zin.infolist():
        zo.writestr(item, zin.read(item.filename))
    zo.write(dex, 'classes.dex')
zin.close()
print('  打包完成:', out)
PY

echo "--- 6/6 v1 + v2 签名"
export SIGN_KEY=$H/grok_build/key.pem
export SIGN_CERT=$H/grok_build/cert.pem
export FORGE_PATH=$H/apksign/node_modules/node-forge
python3 -c "exec(open('$SK/add_v1.py').read().split('if __name__')[0]); resign('$P/out/app.unsigned.apk','$P/out/app.v1.apk')"
node "$SK/sign_v2.js" "$P/out/app.v1.apk" "$P/out/DeviceInfo-v1.0.apk"

echo "--- 校验"
python3 "$SK/verify_apk.py" "$P/out/DeviceInfo-v1.0.apk"
ls -la "$P/out/DeviceInfo-v1.0.apk"
