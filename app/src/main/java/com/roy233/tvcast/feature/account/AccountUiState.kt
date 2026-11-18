package com.roy233.tvcast.feature.account

import android.graphics.Bitmap

enum class LoginStatus {
    NONE,
    FETCHING,
    REQUEST_FAILED,
    GENERATE_FAILED,
    WAITING,
    EXPIRED,
    NETWORK_ERROR,
}

/** 账号登录功能独占的界面状态切片。 */
data class AccountUiState(
    val loggedIn: Boolean = false,
    val accountName: String = "",
    val mid: Long = 0L,
    val vip: Boolean = false,
    val loginQr: Bitmap? = null,
    val loginStatus: LoginStatus = LoginStatus.NONE,
    val loginSkipped: Boolean = false,
)
