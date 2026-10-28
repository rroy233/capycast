package com.roy233.tvcast.feature.account.model

/** 持久化电视端登录令牌的存储端口。 */
interface AccountSessionStore {
    /** 保存登录令牌及其关联的账号信息。 */
    fun save(token: Token)

    /** 读取已保存的登录令牌；无可用令牌时返回 null。 */
    fun load(): Token?

    /** 清除已保存的登录令牌。 */
    fun clear()
}
