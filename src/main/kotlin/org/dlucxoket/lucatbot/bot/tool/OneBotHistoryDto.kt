package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import tools.jackson.databind.JsonNode

/**
 * get_group/friend_msg_history 的自定义响应 DTO——**爬楼记录带上 message_id**
 *
 * Shiro 的类型化路径（`GetMsgListResp → MsgResp → MessageEvent`）没有
 * `message_id` 字段，爬楼拿不到消息 id → 只能落 `hist:` 兜底 id，与实时行
 * 形成孪生双份（存储层双倍膨胀的根因之一）。经
 * `Bot.customRequest(ActionPath, params, Class<T>)`（内部 Jackson 反序列化）
 * 用本 DTO 把 id 拿回来：
 *
 * - 显式 `@get:JsonProperty` 对齐 OneBot 蛇形字段（注解优先于任何命名策略）
 * - 类级 `ignoreUnknown`：OneBot 响应的多余字段（sub_type 等）不炸解析
 * - [OneBotHistoryMsg.message] 保留为 [JsonNode]：string 与 array 两种形态
 *   由调用方分别转 CQ 字符串（array → `MessageConverser.arraysToString`，
 * 与 Shiro 类型化路径行为一致）
 * - JsonNode 用 **Jackson 3（`tools.jackson`）类型**——Shiro 2.5.x 的
 *   `customRequest`/`JsonUtils` 走 Jackson 3 反序列化（注解仍是
 *   `com.fasterxml.jackson.annotation` 包，两代通用）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
class OneBotHistoryData {
    @get:JsonProperty("messages")
    var messages: List<OneBotHistoryMsg>? = null
}

/** 单条历史消息（只列消费到的字段，其余靠 ignoreUnknown） */
@JsonIgnoreProperties(ignoreUnknown = true)
class OneBotHistoryMsg {
    @get:JsonProperty("message_id")
    var messageId: Long? = null

    @get:JsonProperty("time")
    var time: Long? = null

    @get:JsonProperty("user_id")
    var userId: Long? = null

    /** string 或 array 两种形态（见 [OneBotHistoryData] 类 KDoc） */
    @get:JsonProperty("message")
    var message: JsonNode? = null

    @get:JsonProperty("sender")
    var sender: OneBotHistorySender? = null
}

@JsonIgnoreProperties(ignoreUnknown = true)
class OneBotHistorySender {
    /** 实现不一（number/string）——标量 coercion Jackson 默认允许，统一收 Long */
    @get:JsonProperty("user_id")
    var userId: Long? = null

    @get:JsonProperty("nickname")
    var nickname: String? = null

    @get:JsonProperty("card")
    var card: String? = null
}
