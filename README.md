# 校园助手 Android

**[下载最新版 APK](https://github.com/error-yh/school-helper/releases/download/stage5-manual-preview-20261102/school-helper-manual-only-debug.apk)** · [版本说明、对应源码及校验文件](https://github.com/error-yh/school-helper/releases/tag/stage5-manual-preview-20261102)

当前为手动课程管理预览版，内部版本0.2.0，Debug签名，约267MiB。安装包在Releases附件；绿色Code按钮下载的是源码。APK不包含运行时个人课表、账号或设备数据；更新安装请保留本机数据，不要通过卸载升级。

以码上取为唯一宿主，模块化引入Sleepy课表；Kotlin、Compose、Room、Hilt。保留课表、快递、身份码、我的四入口；原作者更新源隔离，应用内自动更新未启用。

## 本次变化

- 按用户要求移除文字通知导入及文字通知调课界面，不再要求填写通知基准或大节映射。
- 调整课程请点击现有课程，使用编辑或“调整一次/停课一次/补课一次”；共享历史与受保护撤销保留，原操作记录不删除。
- 保留今日课表、紧凑七天周视图、任意周次、课程管理和展示分组、自定义作息；PDF、图片与已有结构化来源保持支持。
- 静态WebP按内容识别，兼容被保存为.jpg的图片；OCR可能有误，保存前请核对。HEIF/HEIC、动画WebP等未支持范围不伪装成功。
- 原有课程、来源、profile及Room Schema5保留；不改变快递平台业务实现或登录凭据。

## 验证及限制

750个不同JVM单元用例分轮通过（含原宿主4项及三份私有PDF契约补验）。Debug构建、Lint及6项隔离检查通过；Lint仍有警告。

当前vivo/Android14设备24个不同用例均有通过结果。首次组合运行23/24：许可页返回选中态超时；同一用例独立重跑1/1及整个许可类3/3通过。首次超时原因尚未证实，保留稳定性观察，不宣称首轮全绿或已修复某个确定故障。手动调整、停补课、历史撤销、隔离会话、导航与JSON取消零写入已覆盖。

真实个人课程最终验收、真实快递账号线上功能、长期使用及所有Android设备并未全部验证。归一化课程替换已有调整仍保守拒绝，需按提示先撤销或重新核对。历史迁移/APPEND/独立进程读回证据沿用原验证，不称本轮再次全部运行。

## 构建

JDK17、Android SDK36、Build Tools34.0.0；设置JAVA_HOME和ANDROID_HOME，不提交local.properties或私钥。工程根目录为app：

```powershell
.\gradlew.bat :app:assembleDebug
.\gradlew.bat testDebugUnitTest :platform:taobao:test
```

冻结版本：Gradle9.1.0、AGP8.13.0、Kotlin2.2.20、KSP2.2.20-2.0.2、Compose BOM2025.09.00、Hilt2.57.1、Room2.7.0、Navigation2.9.4；minSdk26/compileSdk36/targetSdk34。

未配置自有正式签名，Release门禁会拒绝打包，禁止回退Debug签名。本附件是验收Debug包，不是正式签名Release。Schema5数据库不能直接安装仅支持旧Schema4的APK降级，禁止清库解决迁移。

测试仅公开必要合成fixture。私有样本测试默认跳过，人工选择器测试需显式启用；这些跳过不表示测试通过。设备测试须先核对隔离范围，不向日常课表插入fixture。

## 来源与许可

组合程序依GPL-3.0分发，保留码上取MIT、Sleepy GPL及第三方完整许可与作者署名。Release附固定tag完整对应源码及SHA256SUMS；LICENSE、LICENSES、NOTICE.md、SOURCE_ORIGINS.md和应用内离线许可页保持可访问。上游作者不表示认可此集成版。

发布采用去敏源码快照；排除真实样本、个人课表、数据库、账号/Cookie/Token、私钥、设备日志和原开发历史。请勿在Issue提交未经去敏的个人文件。
