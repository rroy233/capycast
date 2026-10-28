package com.roy233.tvcast.feature.account.model

/** 登录后用于显示账号身份的稳定结果。 */
data class AccountProfile(val name: String, val avatar: String, val vip: Boolean, val mid: Long)

fun interface AccountNavigator {
    fun currentProfile(): AccountProfile
}
