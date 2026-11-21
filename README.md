# 宅家打印机（zhaijia-dayinji）

自用的零广告安卓打印/扫描 App：远程打印（走打印机云邮箱）、内网直打（ESC/P-R）、
扫描（爱普生 ESC/I）、试卷去手写、证件照、AI 学习助手。纯原生 Java，手写构建脚本。

## 下载 / 自动更新

- 最新 APK：`dist/remote-printer.apk`
- 更新清单：`dist/update.json`

App 里「设置 → 版本与更新 → 检查更新」会读 `dist/update.json`，
按 versionCode 判断是否有新版本，有则下载到「下载」目录并拉起系统安装界面。

## 从源码构建

需要：JDK 17、Android SDK（build-tools 35.0.0 / platform android-34）。

```powershell
# 1) 拉取原生依赖（ONNX Runtime 的 jar + arm64 so，以及 U2Net 模型）
./fetch-deps.ps1

# 2) 构建
./build.ps1            # 产物在 build/remote-printer.apk，同时生成 update.json
```

`fetch-deps.ps1` 需要网络；构建产物为 debug 签名（`debug.keystore` 在仓库里，
**换钥匙会导致无法覆盖安装**）。

## 目录

```
src/             Java 源码（含 vendored 的 ZXing QR 解码，Apache-2.0）
res/             图标与字符串
assets/          分割模型（fetch-deps 下载）
jniLibs/         ONNX Runtime 原生库（fetch-deps 下载）
libs/            ONNX Runtime 的 Java API jar（fetch-deps 下载）
tools/           图标生成、MailConfig 模板
dist/            发布产物：APK + update.json
```

## 说明

- 没有任何第三方账号、密钥、邮箱写死在代码里；打印机邮箱在首次使用时扫码或粘贴配置绑定。
- 扫描协议、内网直打（ESC/P-R）的实现细节见源码注释。