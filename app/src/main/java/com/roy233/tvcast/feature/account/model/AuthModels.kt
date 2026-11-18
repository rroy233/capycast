package com.roy233.tvcast.feature.account.model

/** 账号及其会话所属的平台。 */
enum class AccountPlatform {
    DOMESTIC,
    INTERNATIONAL,
}

enum class CredentialKind {
    TV,
    WEB,
}

/**
 * 保存账号认证信息。
 *
 * @property accessKey 电视端访问令牌；网页账号没有该令牌。
 * @property mid 账号标识。
 * @property cookie 登录后获得的 Cookie 集合。
 * @property refreshToken 刷新令牌。
 * @property expiresIn 令牌有效期。
 */
data class Token(
    val accessKey: String?,
    val mid: Long,
    val cookie: String,
    val refreshToken: String,
    val expiresIn: Long,
    val platform: AccountPlatform = AccountPlatform.DOMESTIC,
    val credentialKind: CredentialKind =
        if (accessKey == null) CredentialKind.WEB else CredentialKind.TV,
)

/**
 * 保存二维码地址及其轮询所需的会话信息。
 *
 * @property url 二维码内容地址。
 * @property authCode 二维码认证码。
 * @property sessionId 登录会话标识。
 */
data class Qr(val url: String, val authCode: String, val sessionId: String)

/** 表示二维码登录轮询结果。 */
sealed class Poll {
    /** 表示用户尚未完成扫码或确认。 */
    data class Pending(val code: Int, val message: String) : Poll()

    /** 表示登录成功并返回认证令牌。 */
    data class Success(val token: Token) : Poll()

    /** 服务端明确拒绝或认证结果无效。 */
    data class Failure(val code: Int, val message: String) : Poll()
}
