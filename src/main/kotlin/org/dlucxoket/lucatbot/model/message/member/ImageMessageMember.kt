package org.dlucxoket.lucatbot.model.message.member

/**
 * 图片消息成员
 *
 * 表示消息中的图片片段，可包含本地路径、网络 URL 或 Base64 编码的图片数据。
 *
 * @param url 图片地址（网络 URL 或本地路径），具体格式取决于 OneBot 实现
 * @param fileName 可辨识文件名（协议 file 字段）；describe 优先展示它，禁止输出 base64
 */
class ImageMessageMember(
    val url: String,
    val fileName: String? = null
) : MessageMember {
}
