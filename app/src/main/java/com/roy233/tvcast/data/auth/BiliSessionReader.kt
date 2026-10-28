package com.roy233.tvcast.data.auth

/** 提供一次请求使用的当前登录 Cookie；空字符串表示访客。 */
fun interface BiliSessionReader {
    fun cookie(): String
}
