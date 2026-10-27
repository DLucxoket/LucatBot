package org.dlucxoket.lucatbot.adapter.onebot

import com.mikuac.shiro.core.Bot
import com.mikuac.shiro.core.CoreEvent
import org.dlucxoket.lucatbot.bot.LucatBot
import org.dlucxoket.lucatbot.bot.loop.MainLoopController
import org.dlucxoket.lucatbot.bot.readstate.ReadStateService
import org.dlucxoket.lucatbot.bot.tool.ToolDependencyBinder
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component

/**
 * NapCat（WS）**连上时刻**的钩子——`@Primary` 覆盖 Shiro 自带的 [CoreEvent]
 * （ShiroAutoConfiguration 扫 `com.mikuac.shiro` 注册的**空实现**，其连接链路
 * `WebSocketServerHandler.afterConnectionEstablished → ConnectionUtils.handleFirstConnect
 * → coreEvent.online(bot)` 在 bot 创建后异步回调；Shiro 按类型注入，@Primary 子类即接管）。
 *
 * 触发时机（Shiro 侧行为）：
 * - 启动后 NapCat 首次连上 → `online()`；
 * - 断开时 `afterConnectionClosed` 把 bot 移出容器 → 重连再走首连 → `online()` 再次触发
 * （即**每次连上都会触发**，NapCat 重启也不例外）。
 *
 * 连上后默认做三件事（模拟真人上线先扫一眼列表）：
 * 1. 绑定发送通道（`shiroBot` + Tool 依赖——不必等首条消息，连接即可用；幂等）
 * 2. 按已读水位重建未读（selfId 此刻已知；[ReadStateService] 幂等，首条消息处的兜底调用不冲突）
 * 3. 请求主循环**默认先真实查看一遍消息列表**（置位强制查看 + 唤醒空闲休眠）
 *
 * 断线（[offline]，Shiro `afterConnectionClosed` 两条分支都会异步回调）：
 * Shiro 在断线时**同步清空 WS session 属性**（`adapter` 被抹掉）并把 bot 移出容器，
 * 还持有该 Bot 的每个 OneBot action 都会抛 `ShiroException: Adapter type wrong`
 * （误导性报错，实为「已断线」）——故断线即清掉 `shiroBot` 与 Tool 绑定，
 * 窗口内的发送/爬楼走「Bot 未绑定」干净语义，重连由 [online] 重新绑定。
 */
@Component
@Primary
class NapCatOnlineHook(
    private val lucatBot: LucatBot,
    private val readStateService: ReadStateService,
    private val mainLoopController: MainLoopController
) : CoreEvent() {
    private val log = LoggerFactory.getLogger(NapCatOnlineHook::class.java)

    /** Shiro 连接线程异步回调（首连 / 断开重连） */
    override fun online(bot: Bot) {
        try {
            lucatBot.shiroBot = bot
            ToolDependencyBinder.bindBotToAll(bot)
            readStateService.rebuildUnread(lucatBot, bot.selfId)
            mainLoopController.requestViewOnBotOnline()
            log.info("NapCat 已连接（selfId={}）→ 重建未读并默认查看消息列表", bot.selfId)
        } catch (e: Exception) {
            log.warn("NapCat 连接钩子失败（忽略，不影响连接）: {}", e.message)
        }
    }

    /**
     * 断线清理——不清则断线窗口内所有动作撞 `Adapter type wrong`：
     * Shiro `afterConnectionClosed` 同步 `attributes.clear()`（`adapter` 属性被抹），
     * 旧 Bot 实例的每个 OneBot action 都过 `getAdapter` → 必抛。
     * 置空后走 Tool 的「Bot 未绑定」；selfId 不符（迟到回调）不动。
     */
    override fun offline(selfId: Long) {
        try {
            if (lucatBot.shiroBot?.selfId == selfId) {
                lucatBot.shiroBot = null
            }
            ToolDependencyBinder.unbindBotToAll(selfId)
            log.info("NapCat 已断线（selfId={}）→ 清除发送通道（窗口内动作走「Bot 未绑定」）", selfId)
        } catch (e: Exception) {
            log.warn("NapCat 断线清理失败（忽略）: {}", e.message)
        }
    }
}
