# 来源及修改

- 宿主 njpemo/mashangqu，固定e5245ed6a7cbc5a60b139dc4d9acda23b13bc2a9，MIT。
- 课表参考 lingion/sleepy，固定3dafa110f74ac4f32464ce1efddb24197a4091d9，GPL-3.0。
- 离线OCR源码/模型 hzkitty/RapidOCR4j-Android，固定8e11959786c0fe609c68f4b133ee3210940a0df8；其RapidOCR/PaddleOCR/Clipper/OpenCV/ONNX完整许可随模块assets保留。
- 矩阵闭合单元格参考 jsvine/pdfplumber，固定4c64b92d5caccd71c645e98e0fabb0c4dba7ff45，MIT。

2026-10-23至2026-10-31本地集成修改：独立身份及更新隔离、单宿主导航、Room无损升级至Schema4、课程/日期例外、本地导入及事务、来源与归属复核、独立作息、紧凑周视图投影及误合并防护。未合并第二个Application或更新器。逐文件来源清单在docs/stage-*/migration-manifest.csv及docs/stage-3/rapidocr-trial-source-manifest.csv。

本公开分支为脱敏源码快照；没有上传含工作站路径、设备标识及私人输入的开发历史/诊断。Android工程文件与已审查并安装APK对应的代码逐文件保持一致；发布说明另提供APK SHA256。构建环境和正式签名限制见README。

本次预览另包含：K70转存JPEG兼容修复、四入口底部导航，以及身份码跨平台批次刷新时的账号隔离防护。未修改Room Schema4及现有用户数据。
