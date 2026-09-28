package org.dlucxoket.lucatbot.bot.loop

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 滚动操作日志（纯 string，保留最近约 20 行）
 *
 * 让机器人知道自己刚才干了什么。只记客观动作，不写 reason（内心戏）。
 * 进入会话不清空；消息列表 / 会话内决策的 prompt 都传同一份。
 */
class ActionLog(
    private val maxLines: Int = 20
) {
    private val lines = CopyOnWriteArrayList<String>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")

    /** 追加一行（自动带时间戳） */
    fun append(action: String) {
        val line = "[${LocalTime.now().format(timeFormat)}] $action"
        lines.add(line)
        while (lines.size > maxLines) {
            lines.removeAt(0)
        }
    }

    /** 导出为多行文本（注入 prompt）；空日志返回占位说明 */
    fun dump(): String {
        return if (lines.isEmpty()) "（暂无）" else lines.joinToString("\n")
    }

    fun size(): Int = lines.size

    fun clear() = lines.clear()
}
