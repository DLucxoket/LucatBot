package org.dlucxoket.lucatbot.bot.tool.data

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/** 记忆查询请求 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MemoryQueryRequest(
    @JsonProperty("query")
    val query: String = "",

    @JsonProperty("userId")
    val userId: Long? = null
)

/** 记忆查询结果 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class MemoryQueryResult(
    @JsonProperty("memories")
    val memories: List<String> = emptyList(),

    @JsonProperty("success")
    val success: Boolean = true,

    @JsonProperty("error")
    val error: String? = null
)
