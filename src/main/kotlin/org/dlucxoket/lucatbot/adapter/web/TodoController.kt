package org.dlucxoket.lucatbot.adapter.web

import org.dlucxoket.lucatbot.bot.loop.ActionLog
import org.dlucxoket.lucatbot.bot.persona.TimeFormat
import org.dlucxoket.lucatbot.bot.todo.TodoService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 待办**取消** API——`web/` 面板「机器人状态」页的操作按钮
 *
 * 为什么需要：`todo_add` 只进不出，陈年待办会一直挂在清单里——每轮决策 prompt
 * 都注入一遍（白烧 token），还稀释「待办 = 现在该干的事」这个行动理由信号。
 *
 * 两个端点（**只动待办，不触发任何 LLM 调用**）：
 * - `DELETE /api/todos/{id}`：取消一条（id 精确或 ≥4 位前缀；找不到 → 404）
 * - `DELETE /api/todos`：清空全部**未完成**（已完成是历史痕迹，不动）
 *
 * 每次取消写一条操作日志（谁在什么时候取消了什么，事后可复盘）。
 */
@RestController
@RequestMapping("/api/todos")
class TodoController(
    private val todoService: TodoService,
    private val actionLog: ActionLog
) {

    data class CancelResultDto(
        val cancelled: Boolean,
        val id: String,
        val content: String,
        /** 剩余未完成条数——面板据此就地更新，不用整页重取 */
        val remainingPending: Int
    )

    data class ClearResultDto(
        val removed: Int,
        val contents: List<String>,
        val remainingPending: Int
    )

    /** 取消一条待办 */
    @DeleteMapping("/{id}")
    fun cancel(@PathVariable id: String): ResponseEntity<CancelResultDto> {
        val target = todoService.cancel(id)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        actionLog.append("取消待办：${target.content.take(30)}")
        return ResponseEntity.ok(
            CancelResultDto(
                cancelled = true,
                id = target.id,
                content = target.content,
                remainingPending = todoService.pending().size
            )
        )
    }

    /** 清空全部未完成待办（已完成保留） */
    @DeleteMapping
    fun cancelAll(): ClearResultDto {
        val removed = todoService.cancelAllPending()
        if (removed.isNotEmpty()) {
            actionLog.append("清空未完成待办 ${removed.size} 条", removed.take(3).joinToString("；") { it.content.take(20) })
        }
        return ClearResultDto(
            removed = removed.size,
            contents = removed.map { it.content },
            remainingPending = todoService.pending().size
        )
    }

    data class PlannedResultDto(
        val id: String,
        val content: String,
        /** 新的预计执行时间；null = 已清除 */
        val plannedAt: Long?,
        val remainingPending: Int
    )

    /**
     * 设置 / 清除**预计执行时间**
     *
     * `PUT /api/todos/{id}/planned-at?epoch=<毫秒>` → 设置；**不带 epoch = 清除**；
     * id 找不到 → 404。时间用毫秒（前端 `datetime-local` 本机时区转 epoch，后端按
     * [org.dlucxoket.lucatbot.bot.persona.TimeFormat.ZONE] 展示，同为北京时间）
     */
    @PutMapping("/{id}/planned-at")
    fun setPlannedAt(
        @PathVariable id: String,
        @RequestParam(required = false) epoch: Long?
    ): ResponseEntity<PlannedResultDto> {
        val updated = todoService.setPlannedAt(id, epoch)
            ?: return ResponseEntity.status(HttpStatus.NOT_FOUND).build()
        actionLog.append(
            (if (epoch == null) "清除待办预计：" else "待办预计 ${TimeFormat.listLine(epoch)}：") +
                updated.content.take(30)
        )
        return ResponseEntity.ok(
            PlannedResultDto(
                id = updated.id,
                content = updated.content,
                plannedAt = updated.plannedAt,
                remainingPending = todoService.pending().size
            )
        )
    }
}
