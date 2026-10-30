# 校园助手：来源、修改与许可

本集成程序基于 njpemo/mashangqu (MIT) 与 lingion/sleepy (GPL-3.0)。组合程序按根目录GPL-3.0条款分发；原MIT文件保留其原MIT授权及Copyright (c) 2026 njpemo。原作者的许可文本保存在LICENSES/，不构成MIT/GPL任选双许可。

固定来源：
- https://github.com/njpemo/mashangqu commit e5245ed6a7cbc5a60b139dc4d9acda23b13bc2a9
- https://github.com/lingion/sleepy commit 3dafa110f74ac4f32464ce1efddb24197a4091d9

修改日期：2026-10-23。校园助手贡献者修改了宿主身份/更新策略/导航，加入最小独立课表模块。Sleepy贡献者的周次判断及SectionHeader组件经过最小摘取；逐文件来源、范围与修改见docs/stage-1/migration-manifest.csv。没有迁入Sleepy的Application、Activity、导航、更新器、OEM、小组件、地图或采集器。

未来分发APK时，须在同一Release提供该版本完整对应源码、构建说明、必要脚本/资源及许可；仅提供原仓库地址不足。阶段1没有发布APK，正式签名/自有更新源未启用，未配置签名的Release构建拒绝执行。原作者名称不表示对校园助手的认可。

阶段3 PDF提取依赖（2026-10-24）：com.tom-roush:pdfbox-android:2.0.27.0，Apache-2.0。固定上游 https://github.com/TomRoush/PdfBox-Android/tree/v2.0.27.0 。许可及附带第三方条款完整保存在LICENSES/Apache-2.0-pdfbox-android.txt，原NOTICE保存在LICENSES/NOTICE-pdfbox-android.txt；APK assets/open_source亦附带两份完整文本。仅提取文字坐标，不执行PDF动作、附件或外部链接。新增解析器代码为校园助手贡献者原创，不复制上游解析器实现。
