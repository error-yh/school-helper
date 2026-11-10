package com.aggapp.app.migration

import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files

/** Publication runs under the timetable write lease. The inode record survives a process exit. */
internal object MigrationPublication {
    private val dbName = Regex("timetable_[A-Za-z0-9_-]{1,64}\\.db")
    private val tokenPattern = Regex("[0-9a-f-]{36}")

    fun publish(staged: File, target: File, token: String) {
        require(dbName.matches(target.name) && tokenPattern.matches(token))
        val directory = checkNotNull(target.parentFile)
        check(listOf("", "-wal", "-shm", "-journal").none { File(directory, target.name + it).exists() }) {
            "目标课表数据库或其副文件已存在"
        }
        val part = File(directory, "${target.name}.part_$token")
        check(part.createNewFile()) { "迁移暂存文件已存在" }
        FileOutputStream(part).use { output ->
            staged.inputStream().use { it.copyTo(output) }
            output.fd.sync()
        }
        val stat = Os.lstat(part.absolutePath)
        val proof = File(directory, "${target.name}.identity_$token")
        check(proof.createNewFile()) { "迁移归属记录已存在" }
        FileOutputStream(proof).use { output ->
            output.write("${stat.st_dev}:${stat.st_ino}".toByteArray(Charsets.US_ASCII))
            output.fd.sync()
        }
        syncDirectory(directory)
        // Same directory, with the target-exists check enabled by omitting REPLACE_EXISTING.
        Files.move(part.toPath(), target.toPath())
        syncDirectory(directory)
    }

    fun rollback(directory: File, token: String, recorded: List<String>) {
        require(recorded.all(dbName::matches)) { "迁移日志含无效数据库名" }
        val proofs = sessionFiles(directory, token, "identity")
        val names = proofs.map { it.name.removeSuffix(".identity_$token") }.toSet()
        check(recorded.all { it in names || !File(directory, it).exists() }) {
            "无法证明目标数据库属于本次迁移"
        }
        for (proof in proofs) {
            val name = proof.name.removeSuffix(".identity_$token")
            val target = File(directory, name)
            val part = File(directory, "$name.part_$token")
            // The source still exists if publication never moved it. Leave any target alone.
            if (!part.exists() && target.exists()) {
                val targetStat = Os.lstat(target.absolutePath)
                val identity = proof.readText(Charsets.US_ASCII)
                check(identity.matches(Regex("-?[0-9]+:-?[0-9]+"))) { "迁移归属记录损坏" }
                if (identity == "${targetStat.st_dev}:${targetStat.st_ino}") {
                    check(target.delete()) { "无法清理未提交数据库" }
                    syncDirectory(directory)
                }
            }
        }
        finish(directory, token)
    }

    fun finish(directory: File, token: String) {
        val proofs = sessionFiles(directory, token, "identity")
        // Remove proof before abandoned parts so an interrupted cleanup cannot mistake an
        // unpublished file for a published one.
        for (proof in proofs) check(proof.delete()) { "无法清理迁移归属记录" }
        if (proofs.isNotEmpty()) syncDirectory(directory)
        val parts = sessionFiles(directory, token, "part")
        for (part in parts) check(part.delete()) { "无法清理迁移暂存文件" }
        if (parts.isNotEmpty()) syncDirectory(directory)
    }

    private fun sessionFiles(directory: File, token: String, kind: String): List<File> {
        if (!directory.exists() || token.isBlank()) return emptyList()
        require(tokenPattern.matches(token))
        val suffix = ".$kind" + "_$token"
        val files = checkNotNull(directory.listFiles()) { "无法枚举迁移数据库目录" }
        check(files.none { it.name.endsWith(".owner_$token") }) { "旧版迁移标记需要人工核对" }
        return files.filter { it.name.endsWith(suffix) && dbName.matches(it.name.removeSuffix(suffix)) }
    }

    internal fun syncDirectory(directory: File) {
        check(directory.isDirectory) { "缺少迁移目录" }
        val fd = Os.open(directory.absolutePath, OsConstants.O_RDONLY, 0)
        try {
            Os.fsync(fd)
        } finally {
            Os.close(fd)
        }
    }
}
