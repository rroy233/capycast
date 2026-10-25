package com.roy233.tvcast.feature.account

import android.graphics.Bitmap

/** 账号登录功能独占的界面状态切片。 */
data class AccountUiState(
    val loggedIn: Boolean = false,
    val accountName: String = "未登录",
    val mid: Long = 0L,
    val vip: Boolean = false,
    val loginQr: Bitmap? = null,
    val loginStatus: String = "正在获取二维码…",
    val loginSkipped: Boolean = false,
)
