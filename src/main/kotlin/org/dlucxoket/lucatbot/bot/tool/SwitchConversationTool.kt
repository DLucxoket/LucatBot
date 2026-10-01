package org.dlucxoket.lucatbot.bot.tool

import org.dlucxoket.lucatbot.bot.LucatBot

/** 切换会话入参（type: private/group；target: QQ号/群号） */
data class SwitchConversationRequest(
    val type: String = "",
    val target: String = "",
    val reason: String? = null
)

/** 切换会话结果（switched=true 时编排层会立刻结束当前会话并打开目标） */
data class SwitchConversationResult(
    val success: Boolean = true,
    val switched: Boolean = false,
    val note: String = "",
    val error: String? = null
)

/**
 * 切换会话工具——会话内直接转到另一个会话
 *
 * 与 `return` 再让选会话挑一遍不同：这是**明确的目标跳转**（在 A 会话说完事、
 * 转到 B 群/某人私聊继续）。**执行在编排层**（与 SendMessageTool 同理——
 * 会话进出、冷却打点、记忆整理都归 ConversationController）：本工具只**置位**
 * 切换请求（[LucatBot.requestConversationSwitch]），编排层随后结束当前会话、
 * 立刻打开目标（同一把会话锁内直连）。
 *
 * JSON 边界：`{"type": …, "target": …}` ↔ [SwitchConversationRequest]
 */
object SwitchConversationTool : Tool<SwitchConversationRequest, SwitchConversationResult>() {
    override val name: String = "SwitchConversationTool"

    override val description: String =
        "功能：切换会话——结束当前会话并立刻打开目标会话（type=private 私聊 / group 群聊，target 填 QQ号或群号）。\n" +
            "何时使用：在会话里办完一件事、要转到另一个私聊或群里继续时调用；比先 return 再让消息列表挑一遍更直接，调用后当前会话立即结束、目标会话马上打开。"

    override val parameterHint: String = "JSON: {\"type\": \"private|group\", \"target\": \"123456\", \"reason\": \"为什么切\"}"

    override val inputClass: Class<SwitchConversationRequest> = SwitchConversationRequest::class.java

    /** 状态机引用（置位切换请求；启动时经 [bindServices] 注入） */
    @Volatile
    private var lucatBot: LucatBot? = null

    override fun bindServices(services: ToolServices) {
        lucatBot = services.lucatBot
    }

    override fun handle(input: SwitchConversationRequest): SwitchConversationResult {
        val bot = lucatBot ?: return SwitchConversationResult(success = false, error = "Bot 未绑定")
        val type = input.type.trim().lowercase()
        if (type != "private" && type != "group") {
            return SwitchConversationResult(success = false, error = "type 必须是 private 或 group")
        }
        val target = input.target.trim()
        if (target.isEmpty() || !target.all { it.isDigit() }) {
            return SwitchConversationResult(success = false, error = "target 必须是纯数字的 QQ号/群号")
        }
        return try {
            bot.requestConversationSwitch(type, target)
            SwitchConversationResult(
                success = true,
                switched = true,
                note = "已请求切换到 $type:$target——当前会话随即结束，目标会话立即打开"
            )
        } catch (e: Exception) {
            SwitchConversationResult(success = false, error = "${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
