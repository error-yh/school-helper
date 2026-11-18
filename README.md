# 校园助手 Android

**[下载 v0.2.3 正式 APK](https://github.com/error-yh/school-helper/releases/download/v0.2.3/school-helper-v0.2.3.apk)** · [版本说明、完整源码与 SHA-256](https://github.com/error-yh/school-helper/releases/tag/v0.2.3)

正式包名 `io.github.schoolhelper.app`，Android 8.0 及以上。v0.2.3 / versionCode 5 使用原正式证书签名，支持从同签名正式版覆盖升级，无需卸载。K70 已从 v0.2.2 覆盖到 v0.2.3 并正常启动，已有课程可见；尚未逐项核对全部历史记录和本地配置。

## v0.2.3 快递更新

- 同步快递上游 v1.0.10 及后续淘宝风控修复，增加待取件、运输中、已签收三分区，包裹图片与物流详情时间线。
- 支持账号隔离的已取标记、并行刷新、详情缓存、共享会话探测及异步条码渲染。网络失败不会直接当作账号注销。
- 「从短信提取取件码」仅在用户点击并主动授权后读取近三天短信，不在启动或刷新时扫描，不保存原短信正文；拒绝权限不影响其余快递功能。部分 Android/OEM/安装渠道可能限制短信权限，不绕过系统限制。
- 课表、课程调整、Room Schema 5 和迁移、桌面小组件及宿主导航保留原有机制。应用内更新功能保持关闭，如意邻里运行入口不恢复。

快递真实账号登录、短信验证码/二次风控及真实短信提取仍需用户验证；合成测试不等同于真实服务成功。顺丰校园 mimp 会话暂不支持新版详情，其他已有列表/取件功能保留。详情见 [v0.2.3 验证与限制](docs/releases/v0.2.3.md)。

## 今日课程小组件

安装或升级后打开校园助手一次，在桌面长按空白处，从「小部件」添加「今日课程」。支持调整大小，读取当前启用课表，显示最多两门未结束课程；下课递补、无剩余课时预览明天第一门。点击可查看课程详情。Android 省电、息屏和后台调度可能推迟刷新，打开 App 时会同步。

保留课表、快递、身份码、我的四个入口，以及手动单次调整、停课、补课、历史和受保护撤销。Debug 与正式包独立；从 Debug 迁移课表请用「我的 → 课表数据备份与恢复」，不要卸载旧包后再寻找数据。

## 构建与许可

此仓库和每次 Release 的源码 ZIP 包含完整对应源码、资源与许可。JDK 17、Android SDK 36，在 `app` 下执行 `gradlew.bat :app:assembleDebug`；可加 `-PschoolHelperArmOnly=true` 构建 ARM 包。依赖需先可用。此正式附件是包含 ARM32/ARM64/x86/x86_64 的通用包，约 259 MiB。

Release 构建需要本地 `SCHOOL_HELPER_RELEASE_KEYSTORE` 与 `SCHOOL_HELPER_RELEASE_PASSWORD` 环境变量，并核验既定正式证书。密钥、密码和真实账户数据不随源码分发。自行构建的不同签名包无法直接覆盖原正式包。

来源和许可见 [NOTICE.md](NOTICE.md)、[SOURCE_ORIGINS.md](SOURCE_ORIGINS.md)、LICENSE 与 LICENSES。组合程序按 GPL-3.0 分发，保留原 MIT 与其他第三方许可及署名；不表示原作者认可集成版。
