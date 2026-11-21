# third_party/zxing

本目录只放许可证说明，源码在 `src/com/google/zxing/`（vendored）。

- 项目：ZXing（Zebra Crossing）https://github.com/zxing/zxing
- 版本：zxing-3.5.3（core 模块）
- 许可证：Apache License 2.0，见同目录 LICENSE
- 引入范围：只拷了 QR 解码需要的包
  `com/google/zxing/{BarcodeFormat,Binarizer,BinaryBitmap,ChecksumException,DecodeHintType,
  FormatException,InvertedLuminanceSource,LuminanceSource,NotFoundException,Reader,
  ReaderException,Result,ResultMetadataType,ResultPoint,ResultPointCallback,RGBLuminanceSource}.java`
  ＋ `common/`、`common/reedsolomon/`、`common/detector/`、`qrcode/`（去掉 encoder）
- 没拷的：其它码制（Aztec/DataMatrix/PDF417/一维码）、MultiFormatReader/Writer、awt/javase 模块
- 用法：`QrScan.decode(Bitmap)` → 文本。零 AAR、零 Gradle 依赖，正常参与 javac 编译。