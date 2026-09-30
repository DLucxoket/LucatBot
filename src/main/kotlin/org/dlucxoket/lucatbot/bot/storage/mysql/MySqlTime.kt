package org.dlucxoket.lucatbot.bot.storage.mysql

import java.sql.ResultSet
import java.sql.Timestamp

/**
 * 时间戳列 ↔ epoch 毫秒 的对称转换（DATETIME(3) 存储映射）
 *
 * **领域模型保持 `Long`（epoch 毫秒）**——业务层/格式化/去重逻辑零改动，
 * 转换只发生在仓库的存储映射边界。
 *
 * 正确性依据：
 * - 列类型用 **DATETIME(3)**（毫秒精度）：`Timestamp` 的毫秒往返不被截断
 *   （DATETIME(0) 会丢掉小数秒）
 * - JDBC `setTimestamp`/`getTimestamp` 用 **JVM 默认时区**对称地渲染/解析
 *   DATETIME 字面量：同一 JVM 写读往返精确还原毫秒，与时钟时区无关；
 *   本机时区为 Asia/Shanghai，SQL 客户端里看到的时间即北京时间
 * - 旧 BIGINT 毫秒数据的迁移同样在 Kotlin 侧经本类转换（不用 SQL 函数），
 *   避免 session time_zone 差异引入偏移
 */
object MySqlTime {

    /** epoch 毫秒 → JDBC 时间戳（写入 DATETIME(3) 列） */
    fun of(epochMs: Long): Timestamp = Timestamp(epochMs)

    /**
     * DATETIME 列 → epoch 毫秒（读取）
     *
     * @param fallback 列为 NULL 时的回退值
     */
    fun toEpochMs(rs: ResultSet, column: String, fallback: Long = 0L): Long =
        rs.getTimestamp(column)?.time ?: fallback

    /** DATETIME 列 → epoch 毫秒（**可空列**；NULL → null，如 todos.completed_at） */
    fun toEpochMsOrNull(rs: ResultSet, column: String): Long? =
        rs.getTimestamp(column)?.time
}
