# 一键发版：构建 → 写 update.json → 推到 GitHub → 验证 CDN
#
#   ./release.ps1                      # 用默认仓库路径
#   ./release.ps1 -RepoDir "D:\xxx\zhaijia-repo" -Notes "修了某某"
#
# 前置：本机有部署密钥 ~/.ssh/zhaijia_deploy（已在 GitHub 仓库的 Deploy keys 里授权写入）
param(
    [string]$RepoDir = "D:\文档\deepseek-harness\default-workspace\zhaijia-repo",
    [string]$Notes = ""
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $root

# 1) 构建
& "$root\build.ps1"
if ($LASTEXITCODE -ne 0) { throw "构建失败" }

$mf = Get-Content "$root\AndroidManifest.xml" -Raw -Encoding UTF8
$vc = [regex]::Match($mf, 'android:versionCode="(\d+)"').Groups[1].Value
$vn = [regex]::Match($mf, 'android:versionName="([^"]+)"').Groups[1].Value
$apkName = "remote-printer-$vn.apk"
$apk = "$root\build\remote-printer.apk"
$sha = (Get-FileHash $apk -Algorithm SHA256).Hash

# 2) 拷进仓库（文件名带版本号，CDN 缓存无害）
$dstApk = Join-Path $RepoDir $apkName
Copy-Item $apk $dstApk -Force

# 3) 写 update.json（清单走 raw，APK 走 jsDelivr）
if (-not $Notes) { $Notes = "v$vn" }
$json = ('{{"versionCode":{0},"versionName":"{1}","url":"https://cdn.jsdelivr.net/gh/heibai082/zhaijia-dayinji@main/{2}","sha256":"{3}","notes":"{4}"}}' -f `
        $vc, $vn, $apkName, $sha, $Notes)
[IO.File]::WriteAllText((Join-Path $RepoDir "update.json"), $json, (New-Object Text.UTF8Encoding($false)))
Write-Host ("update.json: versionCode={0} versionName={1} apk={2}" -f $vc, $vn, $apkName)

# 4) 推送
$env:GIT_SSH_COMMAND = "ssh -i `"$env:USERPROFILE\.ssh\zhaijia_deploy`" -o IdentitiesOnly=yes -o StrictHostKeyChecking=accept-new"
Push-Location $RepoDir
& git add -A
& git -c user.name="heibai082" -c user.email="137638458+heibai082@users.noreply.github.com" commit -m "release $vn"
if ($LASTEXITCODE -ne 0) { Write-Host "没有改动可提交（版本号没变？）" }
& git push origin main
Pop-Location

# 5) 验证
Start-Sleep -Seconds 5
try {
    $r = Invoke-WebRequest "https://raw.githubusercontent.com/heibai082/zhaijia-dayinji/main/update.json" -UseBasicParsing -TimeoutSec 30
    Write-Host "清单已上线：" -NoNewline
    Write-Host $r.Content
} catch {
    Write-Host "清单验证失败（raw 可能有几秒延迟，稍后重试）：$($_.Exception.Message)"
}
Write-Host "完成：手机端「设置 → 版本与更新 → 检查更新」即可收到 v$vn"
