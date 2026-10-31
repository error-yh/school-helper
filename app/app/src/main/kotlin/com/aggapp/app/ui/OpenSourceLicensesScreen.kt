package com.aggapp.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aggapp.app.update.AppUpdate

/**
 * 开源许可与说明集中展示页（阶段4 S4-02）：
 * - 归属于「我的」Tab 子页面，沿用唯一 NavHost 与 Material3；
 * - 聚合展示项目简介、修改说明、原作者署名、对应源码提供说明及完整许可目录；
 * - 全文查看复用 LicenseDetailDialog 离线弹窗与 asset 读取机制。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OpenSourceLicensesScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeDocument by remember { mutableStateOf<LicenseDocument?>(null) }

    Scaffold(
        modifier = modifier.testTag("open-source-screen"),
        topBar = {
            TopAppBar(
                title = { Text("开源许可与说明") },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("open-source-back")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // 1. 项目简介与版本状态
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("校园助手 (School Helper)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("当前状态：${AppUpdate.statusMessage}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    Text(
                        "本程序整合校园课表、快递查件与身份码等功能。组合程序依据 GNU 通用公共许可证第 3 版 (GPL-3.0) 授权分发；原 MIT 授权组件保留其原 MIT 授权及版权。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 2. 来源、修改说明与对应源码提供
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("开源来源与源码提供说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "本项目代码公开托管于：\nhttps://github.com/error-yh/school-helper\n\n" +
                            "对应源码与构建说明：预发布与正式发布版本均在 Releases 页面提供对应版本的完整源代码包与 SHA256 校验和（阶段3预览版已随 Release 提供完整对应源码及构建说明）。未来分发 APK 时均会在同一 Release 页面提供对应完整源码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "上游固定基线：\n" +
                            "• 码上取 (MIT): github.com/njpemo/mashangqu (commit e5245ed)\n" +
                            "• Sleepy (GPL-3.0): github.com/lingion/sleepy (commit 3dafa11)\n" +
                            "• PdfBox-Android (Apache-2.0): v2.0.27.0\n" +
                            "• pdfplumber 表格判定算法 (MIT): Jeremy Singer-Vine\n" +
                            "• 本地离线 OCR (Apache-2.0 / Boost / MIT): RapidOCR4j 适配源集",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "修改与架构说明：\n" +
                            "统一采用单宿主 Application 与 NavHost 四 Tab 导航体系，消除重复入口；课表模块使用本地独立 Room 数据库与事务仓库，实现离线紧凑周课表展示与本地导入；本地接入离线 OCR 与 PDF 课表提取算法，不上传课表或图片数据，无第三方云识别或未授权分析采集代码。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 3. 原作者署名与致谢
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("原作者署名与版权保留", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "• 码上取：Copyright (c) 2026 njpemo (MIT)\n" +
                            "• Sleepy 课表算法及组件：Sleepy 贡献者 (GPL-3.0)\n" +
                            "• pdfplumber 算法适配：Copyright (c) 2015 Jeremy Singer-Vine (MIT)\n" +
                            "• PdfBox-Android：Tom Roush / Apache Software Foundation (Apache-2.0)\n" +
                            "• 离线 OCR / ONNXRuntime / OpenCV / Clipper：各项目原作者与开源社区\n\n" +
                            "注：原作者名称与原项目标识不代表对本集成程序的认可或背书。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // 4. 开源许可证目录（点击查看全文）
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("开源许可证全文目录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "点击以下条目可离线查看随包附带的完整许可证与声明全文：",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LicenseDocument.entries.forEach { entry ->
                        OutlinedButton(
                            onClick = { activeDocument = entry },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }

    activeDocument?.let { entry ->
        LicenseDetailDialog(entry = entry, onDismiss = { activeDocument = null })
    }
}
