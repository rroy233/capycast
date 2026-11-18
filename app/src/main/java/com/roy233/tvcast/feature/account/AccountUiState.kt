package com.roy233.tvcast.feature.account

import android.graphics.Bitmap
import com.roy233.tvcast.feature.account.model.AccountPlatform

enum class LoginStatus {
    NONE,
    FETCHING,
    REQUEST_FAILED,
    GENERATE_FAILED,
    WAITING,

    /** 已扫码，等待手机端确认。 */
    SCANNED,
    EXPIRED,
    NETWORK_ERROR,

    /** 服务端明确拒绝本次登录。 */
    REJECTED,

    /** 扫码成功的账号与当前选中平台不一致。 */
    PLATFORM_MISMATCH,
}

/** 账号登录功能独占的界面状态切片。 */
data class AccountUiState(
    val platform: AccountPlatform = AccountPlatform.DOMESTIC,
    val platformLoginStates: Map<AccountPlatform, Boolean> = emptyMap(),
    val loggedIn: Boolean = false,
    val accountName: String = "",
    val mid: Long = 0L,
    val vip: Boolean = false,
    val loginQr: Bitmap? = null,
    val loginStatus: LoginStatus = LoginStatus.NONE,
    val loginSkipped: Boolean = false,
)
