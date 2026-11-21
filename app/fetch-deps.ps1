# 拉取构建所需的原生依赖（不放进 git：太大且是二进制）
#
#   ./fetch-deps.ps1                  # 用默认源
#   ./fetch-deps.ps1 -Proxy http://127.0.0.1:2080
#
# 产物：
#   libs/onnxruntime.jar
#   jniLibs/arm64-v8a/libonnxruntime.so, libonnxruntime4j_jni.so
#   assets/u2netp.onnx
param(
    [string]$Proxy = "",
    [string]$OrtVersion = "1.20.0"
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

function Get-WithProxy($url, $out) {
    $args = @{ Uri = $url; OutFile = $out; UseBasicParsing = $true; TimeoutSec = 600 }
    if ($Proxy) { $args.Proxy = $Proxy }
    Invoke-WebRequest @args
}

Write-Host "== 1. ONNX Runtime AAR ($OrtVersion) =="
$tmp = Join-Path $env:TEMP "ort-aar"
New-Item -ItemType Directory -Force -Path $tmp | Out-Null
$aar = Join-Path $tmp "ort.aar"
$mirrors = @(
    "https://repo1.maven.org/maven2/com/microsoft/onnxruntime/onnxruntime-android/$OrtVersion/onnxruntime-android-$OrtVersion.aar",
    "https://maven.aliyun.com/repository/public/com/microsoft/onnxruntime/onnxruntime-android/$OrtVersion/onnxruntime-android-$OrtVersion.aar"
)
$ok = $false
foreach ($u in $mirrors) {
    try { Get-WithProxy $u $aar; $ok = $true; Write-Host "   来自 $u"; break } catch { Write-Host "   失败：$u" }
}
if (-not $ok) { throw "AAR 下载失败，试试 -Proxy http://127.0.0.1:2080" }

$zip = Join-Path $tmp "ort.zip"
Copy-Item $aar $zip -Force
$ex = Join-Path $tmp "aar"
if (Test-Path $ex) { Remove-Item $ex -Recurse -Force }
Expand-Archive $zip -DestinationPath $ex
New-Item -ItemType Directory -Force -Path "libs", "jniLibs\arm64-v8a" | Out-Null
Copy-Item (Join-Path $ex "classes.jar") "libs\onnxruntime.jar" -Force
foreach ($so in "libonnxruntime.so", "libonnxruntime4j_jni.so") {
    Copy-Item (Join-Path $ex "jni\arm64-v8a\$so") "jniLibs\arm64-v8a\$so" -Force
}
Write-Host "   libs/ 与 jniLibs/arm64-v8a/ 已就绪"

Write-Host "== 2. U2Net 模型 (u2netp.onnx) =="
New-Item -ItemType Directory -Force -Path "assets" | Out-Null
$model = "assets\u2netp.onnx"
if (Test-Path $model) {
    Write-Host "   已存在，跳过"
} else {
    $url = "https://hf-mirror.com/tomjackson2023/rembg/resolve/main/u2netp.onnx"
    Get-WithProxy $url $model
    Write-Host ("   已下载：{0} MB" -f [math]::Round((Get-Item $model).Length / 1MB, 2))
}
Write-Host "完成。现在可以跑 ./build.ps1"
