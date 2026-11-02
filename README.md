# 校园助手 Android

**[下载当前 Android 安装包（APK）](https://github.com/error-yh/school-helper/releases/download/stage5-notice-preview-20261102/school-helper-stage5-preview-debug.apk)** · [版本说明、完整对应源码及 SHA256](https://github.com/error-yh/school-helper/releases/tag/stage5-notice-preview-20261102)

当前为阶段5验收预览版（Debug签名，约267 MiB），不是正式签名Release。APK不包含个人课表、账号或测试运行时数据。绿色Code按钮下载的是源码，安装包请从上面的链接或Releases获取。

基于码上取宿主模块化集成Sleepy课表能力，使用Kotlin、Jetpack Compose、Room、Hilt。保留课表、快递、身份码、我的四个入口；支持本地课程、七天周视图、自定义作息、PDF/图片/文字导入及课程展示分组。每个安装用户的数据独立；原作者更新源已隔离，自有自动更新器未启用。

## 阶段5当前成果

- 本地文字通知解析，明确日期/参考日、原/新时间、大节映射和课程候选，预览后显式确认，仅调整一次实际发生。
- 课程详情提供调整一次、停课一次和补课一次；统一受保护事务、操作历史及有条件撤销。
- 无损Room Schema4→5新增操作日志，保留原课程、来源与profile；APPEND原样重导不重复或静默冲掉通知效果。
- 支持静态WebP真实内容嗅探，即使文件扩展名或MIME错误也按内容路由；沿用安全解码和资源预算。HEIF/HEIC等未支持格式有明确提示。
- 图像OCR可能存在误差，必须核对后保存；通知歧义必须补充依据，不能自动猜测写入。

## 测试与限制

已分层验证通知语义、真实Room事务/迁移、APPEND重导、应用及撤销后独立进程读回。联合UI在Android14通过4项；K70 Android16/API36初次因测试驱动重复点击已选标签失败，改为复用既有Tab选择方法后失败的3项全部复验通过，原先通过的取消用例证据保留。合成fixture使用独立测试数据库，未以真实用户课程制造测试成功。

真实个人课程最终用户验收仍待确认，不代表所有通知措辞、设备或线上快递平台均已验证。归一化课程替换已有调整仍保守拒绝，应按提示撤销旧调整或重新核对。请勿直接用只支持旧Schema4的APK降级现有Schema5数据库。

## 本地构建

使用JDK17、Android SDK36及Build Tools35.0.0，设置JAVA_HOME、ANDROID_HOME；本地SDK路径不得提交。Android工程位于app目录：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :feature:timetable:testDebugUnitTest
```

保持Gradle9.1、AGP8.13.0、Kotlin2.2.20、Room2.7.0等冻结版本，不整体升级。Debug APK位于app/app/build/outputs/apk/debug；内部版本0.2.0，包名io.github.schoolhelper.app.debug。正式Release未提供自有正式签名时由门禁拒绝，不回退Debug签名。离线OCR模型使通用APK较大。

测试资料仅保留必要合成fixture；个人文件及其期望值未公开，对应测试默认跳过/显式opt-in。需要人工选择器的设备测试不会自动等待用户。历史阶段未验证项目继续保留，不把编译成功当真机验证。

## 来源与许可证

组合程序依GPL-3.0分发，保留码上取MIT、Sleepy GPL和第三方完整许可、作者署名及修改说明。每个预览Release附固定tag的完整对应源码和SHA256。详见LICENSE、LICENSES、NOTICE.md和SOURCE_ORIGINS.md；上游作者不表示认可此集成版。

公开快照排除个人课表、数据库、账号/Cookie/Token、签名私钥、设备日志与原开发历史。请勿在Issue上传未经去敏的个人文件或凭据。源码和APK均免费获取。
