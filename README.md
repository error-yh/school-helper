# 校园助手 Android

在码上取宿主中模块化加入课表，使用Kotlin、Jetpack Compose、Room及Hilt。保留快递、身份码与登录入口，支持本地课程管理、今日/本周及PDF、图片、文字通知导入。

## 当前预览版

这是阶段4第二批联合UI与课程分组的Debug测试预览版，应用内部版本0.2.0、包名io.github.schoolhelper.app.debug。源码及该版本APK通过本仓库Releases提供。默认离线保存每个安装用户的数据；原作者更新源已隔离，自有在线更新器未启用。

底栏提供课表、快递、身份码、我的四个入口，复用原身份码功能并增加账号切换时旧响应隔离。七天周视图按节次显示，支持自定义作息与课程详情。另含JPEG缺失EOI及未定义EXIF方向的解码兼容修复。图片识别结果仍需整体核对，来源不明确时提供一次批次确认；未知时间不伪造排课。新增独立离线许可集中页、移除快递页重复身份码入口；课程列表支持用户一次多选确认展示分组，独立本地持久化，可调整及解除，不改原始课程记录。最终整理课程完整流程与其他Android版本仍需用户验证。本次发布不是正式签名Release，不提供正式签名私钥。

## 本地构建（Windows）

安装JDK17、Android SDK36及Build Tools34.0.0，设置JAVA_HOME、ANDROID_HOME（或在app/local.properties设置sdk.dir，不提交该文件）。在app目录执行：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :feature:timetable:testDebugUnitTest
```

使用仓库原Gradle9.1 Wrapper、AGP8.13.0、Kotlin2.2.20、Room2.7.0；不要整体升级依赖。APK输出在app/app/build/outputs/apk/debug。正式Release在未配置正式签名时由门禁拒绝，不回退Debug签名。项目包含离线OCR模型，因此Debug通用APK约267MiB。个人PDF/图片测试参考未公开，对应私有样本测试默认跳过；仓库保留合成fixture。

## 来源与许可证

组合程序按GPL-3.0分发，原MIT版权及所有第三方文本保留。码上取和Sleepy是上游来源，不表示原作者认可本集成版。详见LICENSE、NOTICE.md、LICENSES和SOURCE_ORIGINS.md。每个预览Release附固定tag对应完整源码，源码与APK均可免费获取。

公开快照不包含个人课表、账户/Cookie/Token、签名私钥、设备诊断、开发工作日志或历史提交。请勿在Issue中粘贴登录凭据或未经脱敏的个人文件。


