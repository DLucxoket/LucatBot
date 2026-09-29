package org.dlucxoket.lucatbot.model.message

/**
 * describe 富占位符的**纯文本提取**（图片通道的文本侧）
 *
 * 与 [SendContentParser]（send 方向的逆变换）互补：这里只**读**占位符——
 * 从聊天记录文本里按序取出图片/表情包的 `file=`/`url=` 标签，
 * 供调用方与入档时收集的地址列表（`imageUrls`）按下标对应。
 *
 * 标签与地址一一对应的依据：[ChatMessage.describe] 按成员顺序渲染占位符，
 * 入档的地址列表也按成员顺序收集，同一记录内两序列天然对齐。
 */
object AssetMarkers {

    /** `[图片 file=a.jpg]` / `[表情包 url=b.png]` / `[图片]`（无参数） */
    private val markerRegex = Regex("""\[图片([^\]]*)]|\[表情包([^\]]*)]""")

    /**
     * 按出现顺序提取图片/表情包的标识
     *
     * @return `file=` 或 `url=` 后的标签（无参数占位符返回空串——调用方
     *   用地址自身的文件名兜底）
     */
    fun refs(content: String): List<String> =
        markerRegex.findAll(content).map { m ->
            val params = m.groupValues[1].ifEmpty { m.groupValues[2] }
            fileParam(params) ?: urlParam(params) ?: ""
        }.toList()

    private fun fileParam(params: String): String? =
        Regex("""file=([^\s]+)""").find(params)?.groupValues?.get(1)

    private fun urlParam(params: String): String? =
        Regex("""url=([^\s]+)""").find(params)?.groupValues?.get(1)
}
