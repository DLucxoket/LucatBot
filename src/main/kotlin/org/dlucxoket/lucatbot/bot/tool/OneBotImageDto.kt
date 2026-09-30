package org.dlucxoket.lucatbot.bot.tool

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mikuac.shiro.enums.ActionPath
import org.dlucxoket.lucatbot.bot.LucatBot
import java.io.File

/**
 * `get_image` OneBot action 的请求路径——Shiro 的 [ActionPathEnum] 未收录该 action，
 * 而 [ActionPath] 是接口，自定义实现即可走 `Bot.customRequest`。
 */
object GetImagePath : ActionPath {
    override fun getPath(): String = "get_image"
}

/** `get_image` 响应体（`{file, url, file_size, file_name}`，多余字段靠 ignoreUnknown） */
@JsonIgnoreProperties(ignoreUnknown = true)
class OneBotImageData {
    /** NapCat 侧已下载好的本地绝对路径 */
    @get:JsonProperty("file")
    var file: String? = null

    /** 新鲜 rkey 直链（本地路径不可读时的兜底） */
    @get:JsonProperty("url")
    var url: String? = null
}

/**
 * NapCat `get_image` 取图——**图片必达链路的协议侧来源**
 *
 * 入参 `file` = 消息里的文件名标签（`[图片 file=<MD5>.jpg]` 的 ref，与
 * [org.dlucxoket.lucatbot.model.message.AssetMarkers] 提取同源）。NapCat 走
 * QQ 协议自己下载（**不依赖直链 rkey 有效期**）→ 落盘 `nt_data/Pic|Emoji\
 * <yyyy-MM>\Ori\<md5>.jpg`，返回本地路径 + 新鲜 URL。实测 HTTP 三路全挂的
 * 8 张图 8/8 救回（取证见 方案记录.md 第一节）。
 *
 * bot 与 NapCat 同机，本地路径可直接读（[org.dlucxoket.lucatbot.bot.llm.ImageResolver]
 * 的 download 已支持非 http 源 = 本地文件）。
 *
 * @return 本地路径（优先）或新鲜 URL；Bot 未连接/失败 → null（调用方降级）
 */
object NapCatImageAction {
    fun localSource(lucatBot: LucatBot, fileTag: String): String? {
        val b = lucatBot.shiroBot ?: return null
        if (fileTag.isBlank()) return null
        return try {
            val data = b.customRequest(GetImagePath, mapOf<String, Any>("file" to fileTag), OneBotImageData::class.java)?.data
            val path = data?.file
            when {
                !path.isNullOrBlank() && File(path).isFile -> path
                !data?.url.isNullOrBlank() -> data.url
                else -> null
            }
        } catch (e: Exception) {
            null // 协议侧取图失败只降级，绝不影响会话链路
        }
    }
}
