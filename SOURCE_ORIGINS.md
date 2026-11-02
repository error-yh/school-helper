# 来源及修改

- 宿主 njpemo/mashangqu，固定e5245ed6a7cbc5a60b139dc4d9acda23b13bc2a9，MIT。
- 课表参考 lingion/sleepy，固定3dafa110f74ac4f32464ce1efddb24197a4091d9，GPL-3.0。
- 离线OCR源码/模型 hzkitty/RapidOCR4j-Android，固定8e11959786c0fe609c68f4b133ee3210940a0df8；其RapidOCR/PaddleOCR/Clipper/OpenCV/ONNX完整许可随模块assets保留。
- 矩阵闭合单元格参考 jsvine/pdfplumber，固定4c64b92d5caccd71c645e98e0fabb0c4dba7ff45，MIT。

2026-10-23至2026-10-31本地集成修改：独立身份及更新隔离、单宿主导航、Room无损升级至Schema4、课程/日期例外、本地导入及事务、来源与归属复核、独立作息、紧凑周视图投影及误合并防护。未合并第二个Application或更新器。逐文件来源清单在docs/stage-*/migration-manifest.csv及docs/stage-3/rapidocr-trial-source-manifest.csv。

本公开分支为脱敏源码快照；没有上传含工作站路径、设备标识及私人输入的开发历史/诊断。Android工程文件与已审查并安装APK对应的代码逐文件保持一致；发布说明另提供APK SHA256。构建环境和正式签名限制见README。

本次预览另包含：K70转存JPEG兼容修复、四入口底部导航，以及身份码跨平台批次刷新时的账号隔离防护。未修改Room Schema4及现有用户数据。

2026-11-01追加修改：独立离线许可集中页、快递重复身份码入口清理；课程管理展示聚合、用户确认的分组与独立本地配置，以及作用域/代次隔离。仍保留原始课程、Room Schema4与导入语义。

2026-11-02阶段5增量：静态WebP内容识别兼容；本地文字通知语义/候选/预览、手动单次调整/停课/补课、Schema5增量操作日志及受保护事务/历史/撤销。复用原宿主与课表模块，保留全部原作者与第三方版权，不公开个人样本或原开发历史。

2026-11-02手动管理预览增量：应用户要求移除文字通知导入与文字通知调课独占界面/VM及相关入口。共享手动单次调整、历史、撤销及OCR解析工具保留；保留已有结构化导入、全部许可与原作者署名。Schema5与快递核心未改，不公开任何个人输入或设备原始证据。
