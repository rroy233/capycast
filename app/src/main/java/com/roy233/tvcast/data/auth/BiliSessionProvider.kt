package com.roy233.tvcast.data.auth

/** 提供当前 B 站请求使用的会话 Cookie。 */
object BiliSessionProvider {

    /** 当前 Cookie 字符串；为空表示访客请求。 */
    @Volatile
    var cookie: String = ""
}
