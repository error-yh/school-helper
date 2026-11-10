/* SPDX-License-Identifier: GPL-3.0-only
 * UI dialog and SAF launchers for Timetable backup and restoration.
 */
package com.aggapp.app.migration

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
fun TimetableBackupSection(
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var showExportPasswordDialog by remember { mutableStateOf(false) }
    var exportPassword by remember { mutableStateOf("") }
    var exportPasswordConfirm by remember { mutableStateOf("") }
    var exportPasswordError by remember { mutableStateOf<String?>(null) }

    var pendingRestoreUri by remember { mutableStateOf<Uri?>(null) }
    var restorePassword by remember { mutableStateOf("") }
    var showRestorePasswordDialog by remember { mutableStateOf(false) }
    var restorePasswordError by remember { mutableStateOf<String?>(null) }

    var previewResult by remember { mutableStateOf<ImportPreviewResult.Success?>(null) }
    var isProcessing by remember { mutableStateOf(false) }

    // SAF Create Document launcher for Export
    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        if (uri != null && exportPassword.isNotEmpty()) {
            isProcessing = true
            scope.launch {
                val result = TimetableMigrationService.export(context, uri, exportPassword.toCharArray())
                isProcessing = false
                exportPassword = ""
                exportPasswordConfirm = ""
                when (result) {
                    is ExportResult.Success -> {
                        Toast.makeText(context, "课表导出成功 (${result.rowCount}行数据)", Toast.LENGTH_LONG).show()
                    }
                    is ExportResult.Failed -> {
                        Toast.makeText(context, "导出失败: ${result.reason}", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
    }

    // SAF Open Document launcher for Restore
    val restoreLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            pendingRestoreUri = uri
            restorePassword = ""
            restorePasswordError = null
            showRestorePasswordDialog = true
        }
    }

    Card(
        modifier = modifier.fillMaxWidth().testTag("timetable-backup-card"),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "课表数据备份与恢复",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "通过强加密安全备份当前所有课表数据，并在新环境无损恢复（不包含平台登录密码与凭据）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        exportPassword = ""
                        exportPasswordConfirm = ""
                        exportPasswordError = null
                        showExportPasswordDialog = true
                    },
                    modifier = Modifier.weight(1f).testTag("timetable-export-button"),
                    enabled = !isProcessing,
                ) {
                    Text("导出备份")
                }

                OutlinedButton(
                    onClick = {
                        restoreLauncher.launch(arrayOf("*/*"))
                    },
                    modifier = Modifier.weight(1f).testTag("timetable-restore-button"),
                    enabled = !isProcessing,
                ) {
                    Text("恢复备份")
                }
            }
        }
    }

    // Export Password Dialog
    if (showExportPasswordDialog) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showExportPasswordDialog = false },
            title = { Text("设置备份密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请设置保护密码以加密课表数据。请牢记此密码，恢复时必须提供。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = exportPassword,
                        onValueChange = { exportPassword = it },
                        label = { Text("密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("export-password-input"),
                    )
                    OutlinedTextField(
                        value = exportPasswordConfirm,
                        onValueChange = { exportPasswordConfirm = it },
                        label = { Text("确认密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("export-password-confirm-input"),
                    )
                    exportPasswordError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (exportPassword.isBlank()) {
                            exportPasswordError = "密码不能为空"
                        } else if (exportPassword != exportPasswordConfirm) {
                            exportPasswordError = "两次输入的密码不一致"
                        } else {
                            showExportPasswordDialog = false
                            exportLauncher.launch("timetable_backup.agt")
                        }
                    },
                    modifier = Modifier.testTag("export-password-confirm-button"),
                ) {
                    Text("选择保存位置")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExportPasswordDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // Restore Password Dialog
    if (showRestorePasswordDialog && pendingRestoreUri != null) {
        AlertDialog(
            onDismissRequest = { if (!isProcessing) showRestorePasswordDialog = false },
            title = { Text("输入备份密码") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("请输入该备份文件设置的密码以进行安全预检。", style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = restorePassword,
                        onValueChange = { restorePassword = it },
                        label = { Text("备份密码") },
                        visualTransformation = PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("restore-password-input"),
                    )
                    restorePasswordError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (restorePassword.isBlank()) {
                            restorePasswordError = "密码不能为空"
                        } else {
                            isProcessing = true
                            scope.launch {
                                val result = TimetableMigrationService.inspect(
                                    context,
                                    pendingRestoreUri!!,
                                    restorePassword.toCharArray(),
                                )
                                isProcessing = false
                                when (result) {
                                    is ImportPreviewResult.Success -> {
                                        showRestorePasswordDialog = false
                                        previewResult = result
                                    }
                                    is ImportPreviewResult.Failed -> {
                                        restorePasswordError = result.reason
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("restore-password-inspect-button"),
                ) {
                    Text("预检备份")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRestorePasswordDialog = false }) {
                    Text("取消")
                }
            },
        )
    }

    // Restore Confirmation Preview Dialog
    previewResult?.let { success ->
        val preview = success.preview
        AlertDialog(
            onDismissRequest = { if (!isProcessing) previewResult = null },
            title = { Text("确认恢复课表数据") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("来源应用: ${preview.sourceAppId} (v${preview.sourceVersionCode})")
                    Text("包含Profile: ${preview.profileSummaries.size}个")
                    Text("课程总计: ${preview.totalCourses}门")
                    Text("节次安排: ${preview.totalMeetings}项")
                    Text("单次调课/例外: ${preview.totalExceptions}项")
                    Text("历史日志: ${preview.totalAdjustmentEvents}条")

                    if (preview.warnings.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text("安全提示与警告:", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium)
                        for (w in preview.warnings) {
                            Text("• $w", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        isProcessing = true
                        scope.launch {
                            val restoreRes = try {
                                val activator = dagger.hilt.android.EntryPointAccessors.fromApplication(
                                    context.applicationContext,
                                    com.aggapp.app.di.TimetableActivatorEntryPoint::class.java,
                                ).timetableProfileActivator()
                                activator.restoreBackup(pendingRestoreUri!!, restorePassword.toCharArray(), success.token)
                            } catch (e: kotlinx.coroutines.CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                RestoreResult.Failed("无法加载课表：${e.message}")
                            }
                            isProcessing = false
                            previewResult = null
                            pendingRestoreUri = null
                            restorePassword = ""
                            when (restoreRes) {
                                is RestoreResult.Success -> {
                                    Toast.makeText(context, "课表恢复成功！共恢复${restoreRes.totalRows}行数据，正在重新加载...", Toast.LENGTH_LONG).show()
                                    (context as? android.app.Activity)?.recreate()
                                }
                                is RestoreResult.AlreadyImported -> {
                                    Toast.makeText(context, "该备份已导入过，无需重复导入", Toast.LENGTH_LONG).show()
                                }
                                is RestoreResult.Rejected -> {
                                    Toast.makeText(context, "恢复已拒绝: ${restoreRes.reason}", Toast.LENGTH_LONG).show()
                                }
                                is RestoreResult.Failed -> {
                                    Toast.makeText(context, "恢复失败: ${restoreRes.reason}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    },
                    modifier = Modifier.testTag("restore-confirm-button"),
                    enabled = !isProcessing,
                ) {
                    Text("确认恢复")
                }
            },
            dismissButton = {
                TextButton(onClick = { previewResult = null }) {
                    Text("取消")
                }
            },
        )
    }
}
