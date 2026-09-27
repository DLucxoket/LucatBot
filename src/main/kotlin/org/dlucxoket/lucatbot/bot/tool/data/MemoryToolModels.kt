package org.dlucxoket.lucatbot.bot.tool.data

import com.fasterxml.jackson.annotation.JsonProperty

/** 记忆查询请求 */
data class MemoryQueryRequest(
    @JsonProperty("query")
    val query: String = "",

    @JsonProperty("userId")
    val userId: Long? = null
)

/** 记忆查询结果 */
data class MemoryQueryResult(
    @JsonProperty("memories")
    val memories: List<String> = emptyList(),

    @JsonProperty("success")
    val success: Boolean = true,

    @JsonProperty("error")
    val error: String? = null
)
