package org.dlucxoket.lucatbot.model.user

/**
 * 用户实体
 *
 * 一个 [User] 实例对应一个 QQ 号，描述用户的基本资料信息。
 * 数据来源：OneBot 的 get_stranger_info / get_group_member_info 等接口。
 *
 * @param qqNumber   QQ 号（唯一标识）
 * @param nickname   QQ 昵称（全局，一个 QQ 号一份）
 * @param sex        性别
 * @param level      QQ 等级
 * @param signature  个性签名
 * @param card       群名片（群内昵称，按群而变；私聊场景为 null）
 */
class User(
    val qqNumber: Long,
    val nickname: String,
    val sex: org.dlucxoket.lucatbot.model.user.Sex,
    val level: Int,
    val signature: String,
    val card: String? = null
) {
    /**
     * 展示名——**全项目取名的统一规则**：群聊优先群名片，否则 QQ 昵称
     * （与聊天软件的显示习惯一致）。
     *
     * 关键：实时入档与爬楼入档必须都用它，否则同一人会因取名来源不同
     * （一个取到 QQ 昵称、一个取到群名片）在聊天记录里"看起来像两个人"，
     * 复盘智能体就会误写「先后使用两个昵称」类记忆。
     */
    val displayName: String
        get() = card?.takeIf { it.isNotBlank() } ?: nickname
}
