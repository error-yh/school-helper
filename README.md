# 校园助手 Android

**[下载旧版 Debug 预览 APK](https://github.com/error-yh/school-helper/releases/download/pre-release-import-fixes-20261104/school-helper-arm-debug.apk)** · [版本说明及对应源码](https://github.com/error-yh/school-helper/releases/tag/pre-release-import-fixes-20261104)

当前源码对应已验收的正式签名 v0.2.1。上方下载是历史的 0.2.0 Debug 签名预发布版，约123.01 MiB。仅支持 ARM64/ARMv7，Android 8.0及以上。安装包位于 Releases 附件，绿色 Code 下载的是源码。APK 不包含运行时个人课表或账号；同签名、同包名的版本可覆盖升级。Debug 和正式版使用独立包名，课表通过「我的 → 课表数据备份与恢复」迁移。

保留课表、快递、身份码、我的四个入口，以及手动单次调整、停课、补课、历史和受保护撤销。文字通知导入/调课界面已取消，如意邻里已移除；应用内原作者自动更新源关闭。

## 本次更新

- 图片追加导入按课程身份与完整安排证据去重，保留不同实验、日期与班组；来源关系仍须核对，不擅自合并歧义课程。
- 编辑作息时事务内同步适配归一化状态，保持显式自定义时钟、来源和稳定标识；无法无损处理的残片会拒绝保存。
- 修复规范化指定日期/自定义时钟课程无法匹配单次调整的问题。
- 包含阶段6快递会话隔离、主动刷新和日志隐私修复；移除如意邻里运行时依赖。
- ARM 通用包排除 x86/x86_64 库，保留 OCR 模型、许可与双 ARM 库。不是正式签名 Release。

## 验证与限制

本轮相关领域测试57/57通过。K70/API36隔离综合用例通过：八行同名实验导入、调整、作息修改、来源变化重导零写入、撤销和数据库重开。此前作息适配Room用例及快递14项隔离测试证据沿用，不冒称本轮全工程重跑。

综合用例使用合成OCR与真实Room。用户原图片的界面全流程仍待最终验收；私有图片VM用例缺样本跳过。历史重复记录不会自动删除。真实线上平台、所有设备及长期运行不承诺全部验证。OCR结果请保存前核对；静态WebP支持，HEIC/动画WebP不支持。Room Schema5保留，禁止用旧Schema包盲目降级。

## 源码、许可与构建

以码上取为宿主并参考Sleepy课表，Kotlin/Compose/Room/Hilt。见 SOURCE_ORIGINS.md、NOTICE.md、LICENSE 与 LICENSES。保留上游署名及第三方许可，不表示上游作者认可集成版。

完整对应源码随同Release提供；使用JDK17、Android SDK36，在app目录执行 `gradlew.bat :app:assembleDebug -PschoolHelperArmOnly=true`。依赖需先可用；Debug证书应复用本地自己的证书，私钥不会发布。正式签名门禁保持。
