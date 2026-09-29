package org.dlucxoket.lucatbot.model.message

import org.dlucxoket.lucatbot.model.message.member.AtMessageMember
import org.dlucxoket.lucatbot.model.message.member.FaceMessageMember
import org.dlucxoket.lucatbot.model.message.member.ImageMessageMember
import org.dlucxoket.lucatbot.model.message.member.MemeMessageMember
import org.dlucxoket.lucatbot.model.message.member.MessageMember
import org.dlucxoket.lucatbot.model.message.member.ReplyMessageMember
import org.dlucxoket.lucatbot.model.message.member.TextMessageMember

/**
 * OneBot CQ 码字符串 ↔ model 消息片段的纯函数转换
 *
 * **为什么在 model 层**：爬楼入档（`ChatHistoryTool`，core 层）需要把远端
 * `message` 字段里的 CQ 码转成富文本——它只依赖 model 类型（Text/At/…），
 * 不碰任何 Shiro/adapter 类型。放这里让依赖方向保持 core→model，
 * 消除 core→adapter 的反向依赖（adapter 的 [org.dlucxoket.lucatbot.adapter.onebot.ShiroConverter]
 * 只负责 Shiro DTO → model 的转换，两侧各司其职）。
 */
object CqParser {

    /** CQ 码正则：`[CQ:type,k=v,...]`（值内的逗号在协议层已转义为 &#44;） */
    private val cqCodeRegex = Regex("""\[CQ:([a-zA-Z_0-9]+),([^\]]*)]""")

    /**
     * 把 OneBot CQ 码字符串（爬楼远端 message 字段）转为与 describe 同风格的富文本
     *
     * 解析失败或纯文本时尽量保留原文；禁止把大段 base64 写入。
     */
    fun describe(raw: String): String {
        if (raw.isBlank()) return ""
        if (!raw.contains("[CQ:")) {
            return raw
        }
        return try {
            val described = ChatMessage(toMembers(raw)).describe()
            if (described.isNotBlank()) described else raw
        } catch (_: Exception) {
            raw
        }
    }

    /**
     * CQ 码字符串 → model MessageMember 列表（保持段顺序）
     */
    fun toMembers(raw: String): List<MessageMember> {
        val members = mutableListOf<MessageMember>()
        if (raw.isEmpty()) return members
        var last = 0
        for (match in cqCodeRegex.findAll(raw)) {
            val before = raw.substring(last, match.range.first)
            if (before.isNotEmpty()) {
                members += TextMessageMember(before)
            }
            val type = match.groupValues[1].lowercase()
            val params = parseCqParams(match.groupValues[2])
            when (type) {
                "text" -> params["text"]?.let { if (it.isNotEmpty()) members += TextMessageMember(it) }
                "at" -> {
                    val qq = params["qq"]?.toLongOrNull() ?: 0L
                    val name = firstNotBlank(params["card"], params["nickname"], params["name"])
                    members += AtMessageMember(qq, name)
                }
                "image" -> {
                    val file = firstNotBlank(params["file"])
                    val url = firstNotBlank(params["url"], file).orEmpty()
                    val subType = params["sub_type"] ?: ""
                    val summary = params["summary"] ?: ""
                    val isMemeLike = subType == "1" || summary.contains("表情")
                    if (isMemeLike) {
                        members += MemeMessageMember(url, file)
                    } else {
                        members += ImageMessageMember(url, file)
                    }
                }
                "mface", "marketface" -> {
                    val file = firstNotBlank(params["file"])
                    val url = firstNotBlank(params["url"], file).orEmpty()
                    members += MemeMessageMember(url, file)
                }
                "face" -> members += FaceMessageMember(params["id"]?.toIntOrNull() ?: 0)
                "reply" -> {
                    val id = params["id"]?.toLongOrNull() ?: 0L
                    members += ReplyMessageMember(id, firstNotBlank(params["text"]))
                }
                // 其余段（forward/poke 等）不进 describe 正文，避免污染
                else -> Unit
            }
            last = match.range.last + 1
        }
        val tail = raw.substring(last)
        if (tail.isNotEmpty()) {
            members += TextMessageMember(tail)
        }
        return members
    }

    /**
     * 从 CQ 原文里按 file 标签取图片的下载地址（**图片地址刷新**用）
     *
     * QQ 图片地址短效（rkey 过期即 400），prompt 时对过期地址用
     * `get_msg` 现拉消息原文、这里按 `file=` 精确匹配取**新鲜 url**；
     * 找不到精确匹配且消息里只有一张图时取那一张（file 标签可能与
     * 占位符命名来源不同）。
     *
     * @param raw CQ 字符串（`get_msg` 的 message 字段；array 形态已被
     *   Shiro 的 MessageConverser 转成 CQ 字符串）
     * @param file 占位符里的文件标签（如 `90A5BC2C….jpg`）
     * @return 图片 url；无图/无 url → null
     */
    fun imageUrlOf(raw: String, file: String): String? {
        val images = Regex("""\[CQ:image,([^\]]*)]""").findAll(raw)
            .map { parseCqParams(it.groupValues[1]) }
            .toList()
        if (images.isEmpty()) return null
        images.firstOrNull { it["file"] == file }?.let { p ->
            p["url"]?.takeIf { it.isNotBlank() && it.startsWith("http") }?.let { return it }
        }
        // 单图兜底：file 标签对不上但整条消息只有一张图
        if (images.size == 1) {
            return images[0]["url"]?.takeIf { it.isNotBlank() && it.startsWith("http") }
        }
        return null
    }

    /** 解析 CQ 段参数；处理 &#44; 等转义 */
    private fun parseCqParams(paramStr: String): Map<String, String> {
        if (paramStr.isBlank()) return emptyMap()
        return paramStr.split(',')
            .mapNotNull { part ->
                val idx = part.indexOf('=')
                if (idx <= 0) return@mapNotNull null
                val key = part.substring(0, idx).trim()
                val value = part.substring(idx + 1)
                    .replace("&amp;", "&")
                    .replace("&#44;", ",")
                    .replace("&quot;", "\"")
                    .replace("&#91;", "[")
                    .replace("&#93;", "]")
                key to value
            }
            .toMap()
    }

    private fun firstNotBlank(vararg values: String?): String? {
        return values
            .mapNotNull { v ->
                v?.trim()?.trim('"')?.takeIf { it.isNotBlank() && it != "\"\"" }
            }
            .firstOrNull()
    }
}
