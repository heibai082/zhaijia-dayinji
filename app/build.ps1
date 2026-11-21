# 宅家打印机 APK 构建（Windows 版，等价于 build.sh）
# aapt2 -> javac -> d8 -> 塞 classes.dex -> zipalign -> apksigner
$ErrorActionPreference = 'Continue'

$JDK   = "C:\Users\bqche\AppData\Local\Temp\jdk\jdk-17.0.2"
$SDK   = "$env:LOCALAPPDATA\Android\Sdk"
$BT    = Join-Path $SDK "build-tools\35.0.0"
$ANDROID_JAR = Join-Path $SDK "platforms\android-34\android.jar"

$env:JAVA_HOME = $JDK
$env:PATH = "$JDK\bin;$BT;$env:PATH"
$env:_JAVA_OPTIONS = ""

$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root
$BUILD = Join-Path $root "build"
if (Test-Path $BUILD) { Remove-Item $BUILD -Recurse -Force }
New-Item -ItemType Directory -Force -Path "$BUILD\classes", "$BUILD\dex" | Out-Null

Write-Host "== 1. keystore =="
if (-not (Test-Path "debug.keystore")) {
  & "$JDK\bin\keytool.exe" -genkeypair -keystore debug.keystore -alias androiddebugkey `
    -keyalg RSA -keysize 2048 -validity 10000 `
    -storepass android -keypass android `
    -dname "CN=RemotePrinter, OU=dev, O=shi, C=CN" -noprompt 2>&1 | Out-Null
}
Write-Host "   ok"

# ---- 生成 MailConfig.java：把发信邮箱打进包里（家里装上即用） ----
Write-Host "== 1.5 内置发信账号 =="
$tplPath   = Join-Path $root "tools\MailConfig.java.template"
$cfgPath   = Join-Path $root "src\com\shi\remoteprinter\MailConfig.java"
$propsPath = Join-Path $root "mail-account.properties"
$key = 0x5A
$vals = @()
if (Test-Path $propsPath) {
  $map = @{}
  foreach ($line in [IO.File]::ReadAllLines($propsPath, [Text.Encoding]::UTF8)) {
    $l = $line.Trim()
    if ($l -eq '' -or $l.StartsWith('#')) { continue }
    $i = $l.IndexOf('=')
    if ($i -gt 0) { $map[$l.Substring(0, $i).Trim().ToLower()] = $l.Substring($i + 1).Trim() }
  }
  foreach ($k in @('user', 'pass', 'host', 'port')) {
    $v = $map[$k]
    if (-not $v) { continue }
    $bytes = [Text.Encoding]::UTF8.GetBytes($v)
    for ($j = 0; $j -lt $bytes.Length; $j++) { $bytes[$j] = $bytes[$j] -bxor $key }
    $vals += '"' + [Convert]::ToBase64String($bytes) + '"'
  }
  Write-Host ("   已内置发信邮箱：" + $map['user'])
} else {
  Write-Host "   未找到 mail-account.properties —— APK 不内置账号，需在设置里手填"
}
$tpl = [IO.File]::ReadAllText($tplPath, [Text.Encoding]::UTF8)
$tpl = $tpl.Replace('__KEY__', ('0x{0:X2}' -f $key))
$tpl = $tpl.Replace('__T__', ($vals -join ', '))
[IO.File]::WriteAllText($cfgPath, $tpl, (New-Object Text.UTF8Encoding($false)))

Write-Host "== 2. aapt2 compile =="
& "$BT\aapt2.exe" compile --dir res -o "$BUILD\compiled.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile 失败" }

Write-Host "== 3. aapt2 link =="
& "$BT\aapt2.exe" link -o "$BUILD\app-unsigned.apk" -I $ANDROID_JAR `
  --manifest AndroidManifest.xml -A assets "$BUILD\compiled.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link 失败" }

Write-Host "== 4. javac =="
$srcs = (Get-ChildItem "src" -Recurse -Filter *.java).FullName
& "$JDK\bin\javac.exe" -encoding UTF-8 -source 8 -target 8 -Xlint:-options `
  -cp "$ANDROID_JAR;$root\libs\onnxruntime.jar" -d "$BUILD\classes" @srcs
if ($LASTEXITCODE -ne 0) { throw "javac 失败" }

Write-Host "== 5. d8 =="
& "$JDK\bin\jar.exe" cf "$BUILD\classes.jar" -C "$BUILD\classes" .
if ($LASTEXITCODE -ne 0) { throw "jar 失败" }
& "$BT\d8.bat" --lib $ANDROID_JAR --min-api 24 --output "$BUILD\dex" `
  "$BUILD\classes.jar" "$root\libs\onnxruntime.jar"
if ($LASTEXITCODE -ne 0) { throw "d8 失败" }

Write-Host "== 6. 塞 classes.dex 进 APK =="
$py = "C:\Users\bqche\.dsh\dsh-runtimes\dsh-primary-runtime\dependencies\python\python.exe"
& $py -c @"
import os, sys, zipfile
apk, dexdir, libdir = sys.argv[1], sys.argv[2], sys.argv[3]
with zipfile.ZipFile(apk, 'a', zipfile.ZIP_DEFLATED) as z:
    for name in sorted(os.listdir(dexdir)):
        if name.endswith('.dex'):
            z.write(os.path.join(dexdir, name), name)
            print('   ', name, '已写入')
    for abi in sorted(os.listdir(libdir)):
        d = os.path.join(libdir, abi)
        if not os.path.isdir(d):
            continue
        for so in sorted(os.listdir(d)):
            if so.endswith('.so'):
                z.write(os.path.join(d, so), 'lib/%s/%s' % (abi, so))
                print('    lib/%s/%s 已写入' % (abi, so))
"@ "$BUILD\app-unsigned.apk" "$BUILD\dex" "$root\jniLibs"
if ($LASTEXITCODE -ne 0) { throw "写入 classes.dex 失败" }

Write-Host "== 7. zipalign =="
& "$BT\zipalign.exe" -f -p 4 "$BUILD\app-unsigned.apk" "$BUILD\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign 失败" }

Write-Host "== 8. apksigner =="
& "$BT\apksigner.bat" sign --ks debug.keystore --ks-pass pass:android `
  --key-pass pass:android --out "$BUILD\remote-printer.apk" "$BUILD\app-aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner 失败" }

Write-Host "== 8.5 生成 update.json（给 APP 自动更新用） =="
$mf = Get-Content "AndroidManifest.xml" -Raw -Encoding UTF8
$vc = [regex]::Match($mf, 'android:versionCode="(\d+)"').Groups[1].Value
$vn = [regex]::Match($mf, 'android:versionName="([^"]+)"').Groups[1].Value
$sha = (Get-FileHash "$BUILD\remote-printer.apk" -Algorithm SHA256).Hash
$json = ('{{"versionCode":{0},"versionName":"{1}","url":"http://192.168.5.26:8021/remote-printer.apk","sha256":"{2}","notes":"v{1}"}}' -f $vc, $vn, $sha)
[IO.File]::WriteAllText("$BUILD\update.json", $json, (New-Object Text.UTF8Encoding($false)))
Write-Host ("   update.json: versionCode={0} versionName={1}" -f $vc, $vn)
Write-Host "== 9. 验证 =="
& "$BT\apksigner.bat" verify --print-certs "$BUILD\remote-printer.apk"
& "$BT\aapt2.exe" dump badging "$BUILD\remote-printer.apk" | Select-Object -First 3
Get-Item "$BUILD\remote-printer.apk" | Select-Object Name, Length
Write-Host "构建完成"

