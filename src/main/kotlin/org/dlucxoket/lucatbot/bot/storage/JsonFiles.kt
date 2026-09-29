package org.dlucxoket.lucatbot.bot.storage

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSON 文件仓库的共享文件操作（三个 JsonFile* 仓库的公共管线）
 *
 * 两个防数据丢失的保护：
 * - [writeAtomic]：先写临时文件再原子替换，进程崩溃不会留下截断的 JSON
 *   （截断 JSON 会让下次 load 失败，进而触发「空缓存覆盖原文件」的雪崩链）。
 * - [backupCorrupt]：load 失败时把损坏文件改名备份为 `.corrupt-<时间戳>`，
 *   原路径让位给新文件——原数据保留在备份里可人工恢复，绝不会被后续 flush 覆盖。
 */
object JsonFiles {

    private val log = LoggerFactory.getLogger(JsonFiles::class.java)

    /**
     * 原子写 JSON：写 `<file>.tmp` 后 move 到目标路径。
     *
     * 不支持 ATOMIC_MOVE 的文件系统回退为普通 REPLACE_EXISTING move
     * （至少也是「完整写完才替换」，优于直接覆盖写）。
     */
    fun writeAtomic(mapper: ObjectMapper, file: File, value: Any) {
        val target = file.absoluteFile
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, value)
        try {
            Files.move(
                tmp.toPath(), target.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    /**
     * 把损坏的文件改名备份为 `<name>.corrupt-<yyyyMMdd-HHmmss>`。
     *
     * @return 备份后的文件；失败返回 null（调用方仅记日志，不阻断启动）
     */
    fun backupCorrupt(file: File): File? {
        if (!file.exists()) return null
        val ts = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val backup = File(file.parentFile, file.name + ".corrupt-$ts")
        return try {
            Files.move(file.toPath(), backup.toPath())
            log.warn("损坏文件已备份为 {}（原数据保留，可人工恢复）", backup.name)
            backup
        } catch (e: Exception) {
            log.error("损坏文件备份失败（{}）: {}", file.name, e.message)
            null
        }
    }
}
