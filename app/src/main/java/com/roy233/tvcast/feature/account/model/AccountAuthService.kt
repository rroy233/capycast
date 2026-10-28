package com.roy233.tvcast.feature.account.model

import android.content.Context

/** 电视端二维码登录服务端口。 */
interface AccountAuthService {
    /** 请求一组新的二维码登录参数。 */
    fun requestQr(context: Context): Qr

    /** 轮询二维码登录状态并返回当前登录结果。 */
    fun poll(context: Context, authCode: String, sessionId: String): Poll
}
