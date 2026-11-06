package com.roy233.tvcast

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.roy233.tvcast.core.playback.DEFAULT_PLAYBACK_USER_AGENT
import okhttp3.OkHttpClient

/**
 * 应用级进程入口，负责配置全局单例的 Coil [ImageLoader]。
 *
 * B 站图片 CDN（hdslb.com）要求请求携带 `Referer`，否则返回 403；Coil 默认的
 * OkHttp 客户端不会附带该头，导致最近投屏封面与头像无法加载。这里统一为 B 站
 * 域名的图片请求补上 Referer 与浏览器 UA，避免每个 [coil.compose.AsyncImage]
 * 调用点各自处理。
 */
class CapyCastApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val host = request.url.host
                val isBiliImage = host.endsWith("hdslb.com") ||
                    host.endsWith("bilibili.com") ||
                    host.endsWith("bilivideo.com")
                val builder = request.newBuilder()
                if (isBiliImage) {
                    builder.header("Referer", "https://www.bilibili.com/")
                    builder.header("User-Agent", DEFAULT_PLAYBACK_USER_AGENT)
                }
                chain.proceed(builder.build())
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }
}
