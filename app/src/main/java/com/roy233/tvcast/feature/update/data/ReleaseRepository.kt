package com.roy233.tvcast.feature.update.data

import android.os.Build
import com.roy233.tvcast.BuildConfig
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.feature.update.AppUpdateInfo
import com.roy233.tvcast.feature.update.model.UpdateChannel
import com.roy233.tvcast.feature.update.UpdateManifest
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 通过版本服务查询公开发布信息和更新清单。
 *
 * 版本服务按通道返回最新的发布数据。APK 与更新清单仍从发布附件地址下载，
 * 直连失败时再切换到备用代理。
 */
class ReleaseRepository(
    private val client: OkHttpClient = OkHttpClient(),
) {

    /**
     * 查询指定通道最新的可用更新。
     *
     * Beta 通道同时比较预发布版与正式版的 versionCode，返回其中较新的一个。
     *
     * @param channel 要查询的发布通道。
     * @return 更新信息；相关通道均无公开发布时返回 `null`。
     */
    suspend fun fetchLatest(channel: UpdateChannel): AppUpdateInfo? = withContext(Dispatchers.IO) {
        LogHub.i(TAG, "check started channel=${channel.apiValue}")
        val candidates = when (channel) {
            UpdateChannel.STABLE -> listOfNotNull(fetchUpdate(UpdateChannel.STABLE))
            UpdateChannel.BETA -> fetchBetaCandidates()
        }
        val newest = candidates.maxWithOrNull(
            compareBy<AppUpdateInfo> { it.versionCode }
                .thenBy { if (it.channel == UpdateChannel.STABLE) 1 else 0 },
        ) ?: run {
            LogHub.i(TAG, "no release found channel=${channel.apiValue}")
            return@withContext null
        }
        LogHub.i(
            TAG,
            "check selected version=${newest.versionName} code=${newest.versionCode} " +
                "channel=${newest.channel.apiValue} requested=${channel.apiValue}",
        )
        newest
    }

    /** 查询单个通道并组装更新信息。 */
    private fun fetchUpdate(channel: UpdateChannel): AppUpdateInfo? =
        fetchRelease(channel)?.let { release -> buildUpdate(channel, release) }

    /**
     * Beta 通道同时查询 beta 与 release，选取 versionCode 更大的发布。
     *
     * 正式版发布后 Beta 用户也能升级到正式版；之后出现更高 versionCode 的
     * 预发布版时会再次覆盖。
     */
    private fun fetchBetaCandidates(): List<AppUpdateInfo> {
        val beta = runCatching { fetchRelease(UpdateChannel.BETA) }
        val stable = runCatching { fetchRelease(UpdateChannel.STABLE) }
        beta.exceptionOrNull()?.let { LogHub.e(TAG, "beta channel query failed", it) }
        stable.exceptionOrNull()?.let { LogHub.e(TAG, "release channel query failed", it) }

        val betaRelease = beta.getOrNull()
        val stableRelease = stable.getOrNull()
        val betaId = betaRelease?.stringOrEmpty("id")
        val candidates = mutableListOf<AppUpdateInfo>()
        var lastFailure: Throwable? = null

        if (betaRelease != null) {
            runCatching { buildUpdate(UpdateChannel.BETA, betaRelease) }
                .onSuccess { candidates += it }
                .onFailure {
                    lastFailure = it
                    LogHub.e(TAG, "beta release rejected", it)
                }
        }
        if (stableRelease != null &&
            (betaId.isNullOrBlank() || stableRelease.stringOrEmpty("id") != betaId)
        ) {
            runCatching { buildUpdate(UpdateChannel.STABLE, stableRelease) }
                .onSuccess { candidates += it }
                .onFailure {
                    lastFailure = it
                    LogHub.e(TAG, "release rejected", it)
                }
        }
        if (candidates.isEmpty()) {
            val error = lastFailure ?: beta.exceptionOrNull() ?: stable.exceptionOrNull()
            if (error != null) throw error
        }
        return candidates
    }

    /** 将一次发布转换为更新信息。 */
    private fun buildUpdate(requested: UpdateChannel, release: JsonObject): AppUpdateInfo {
        val assets = release.array("assets")
            .mapNotNull { element -> element.takeIf { it.isJsonObject }?.asJsonObject }
        val manifestAsset = assets.firstOrNull { asset -> asset.stringOrEmpty("name") == MANIFEST_NAME }
            ?: throw IOException("发布缺少 $MANIFEST_NAME 清单文件")
        val manifestUrl = manifestAsset.string("browser_download_url")
        LogHub.i(TAG, "manifest asset found name=$MANIFEST_NAME")
        val manifest = parseManifest(fetchText(manifestUrl, "manifest"))
        val apkAsset = assets.firstOrNull { asset -> asset.stringOrEmpty("name") == manifest.apkName }
            ?: throw IOException("发布缺少 ${manifest.apkName} 附件")
        val actualChannel = validateManifest(requested, release, manifest, apkAsset)

        val apkUrl = apkAsset.string("browser_download_url")
        LogHub.i(
            TAG,
            "release validated version=${manifest.versionName} code=${manifest.versionCode} " +
                "size=${manifest.sizeBytes} channel=${actualChannel.apiValue} " +
                "requested=${requested.apiValue}",
        )
        return AppUpdateInfo(
            channel = actualChannel,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            apkName = manifest.apkName,
            downloadUrl = apkUrl,
            fallbackDownloadUrl = proxyUrl(apkUrl),
            sha256 = manifest.sha256.lowercase(),
            sizeBytes = manifest.sizeBytes,
            releaseNotes = release.stringOrEmpty("body"),
            releaseUrl = release.string("html_url"),
        )
    }

    /** 向版本服务查询指定通道的最新发布。 */
    private fun fetchRelease(channel: UpdateChannel): JsonObject? {
        val url = "${BuildConfig.UPDATE_API_BASE_URL}/version/release" +
            "?app_id=${BuildConfig.UPDATE_APP_ID}&channel=${channel.apiValue}"
        LogHub.i(TAG, "request source=version-api channel=${channel.apiValue}")
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .build()
        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            LogHub.e(TAG, "request error source=version-api channel=${channel.apiValue}", error)
            throw IOException("无法连接版本服务，请检查网络后重试。", error)
        }
        return response.use { result ->
            val body = result.body?.string().orEmpty()
            when {
                // 通道尚无公开发布时，版本服务以 404 表示没有可用版本。
                result.code == HTTP_NOT_FOUND -> {
                    LogHub.i(TAG, "response source=version-api http=404 channel=${channel.apiValue}")
                    null
                }

                !result.isSuccessful -> {
                    val detail = apiFailureDetail(result.code, body)
                    LogHub.e(TAG, "request failed source=version-api $detail channel=${channel.apiValue}")
                    throw IOException("版本服务请求失败（$detail）")
                }

                else -> {
                    LogHub.i(TAG, "response source=version-api http=${result.code} channel=${channel.apiValue}")
                    parseObject(body)
                }
            }
        }
    }

    /** 提取版本服务错误摘要，不记录原始响应正文。 */
    private fun apiFailureDetail(code: Int, body: String): String {
        val message = runCatching {
            val json = JsonParser.parseString(body).asJsonObject
            json.getAsJsonObject("error")
                ?.get("message")?.takeUnless { it.isJsonNull }?.asString
                ?: json.get("message")?.takeUnless { it.isJsonNull }?.asString
        }.getOrNull()
            ?.replace(IP_ADDRESS_PATTERN, "<ip>")
            ?.take(180)
        return if (message.isNullOrBlank()) "HTTP $code" else "HTTP $code: $message"
    }

    /** 下载公开发布附件的文本内容。 */
    private fun fetchText(url: String, purpose: String): String {
        require(url.startsWith("https://")) { "更新附件地址必须使用 HTTPS" }
        val candidates = buildList {
            add(url to "official")
            proxyUrl(url)?.let { proxy -> add(proxy to "proxy") }
        }
        var lastFailure: IOException? = null
        candidates.forEachIndexed { index, (candidate, source) ->
            LogHub.i(TAG, "fetch $purpose source=$source")
            try {
                client.newCall(
                    Request.Builder()
                        .url(candidate)
                        .header("User-Agent", USER_AGENT)
                        .build(),
                ).execute().use { response ->
                    if (response.isSuccessful) {
                        LogHub.i(TAG, "fetch $purpose success source=$source http=${response.code}")
                        return response.body?.string()
                            ?: throw IOException("更新清单内容为空")
                    }
                    lastFailure = IOException("更新清单下载失败（HTTP ${response.code}）")
                    LogHub.e(TAG, "fetch $purpose failed source=$source http=${response.code}")
                }
            } catch (error: IOException) {
                LogHub.e(TAG, "fetch $purpose error source=$source", error)
                lastFailure = error
            }
            if (index == 0 && candidates.size > 1) LogHub.i(TAG, "retrying $purpose through proxy")
        }
        throw lastFailure ?: IOException("更新清单下载失败")
    }

    /** 解析并验证更新清单的字段类型。 */
    private fun parseManifest(content: String): UpdateManifest {
        val json = parseObject(content)
        return UpdateManifest(
            schemaVersion = json.int("schemaVersion"),
            channel = json.string("channel"),
            versionName = json.string("versionName"),
            versionCode = json.long("versionCode"),
            apkName = json.string("apkName"),
            sha256 = json.string("sha256"),
            sizeBytes = json.long("sizeBytes"),
            minSdk = json.int("minSdk"),
        )
    }

    /** 校验清单与发布附件之间的一致性，并返回清单声明的实际通道。 */
    private fun validateManifest(
        requested: UpdateChannel,
        release: JsonObject,
        manifest: UpdateManifest,
        apkAsset: JsonObject,
    ): UpdateChannel {
        require(manifest.schemaVersion == MANIFEST_SCHEMA_VERSION) { "不支持的更新清单格式" }
        val actualChannel = when (manifest.channel) {
            "stable" -> UpdateChannel.STABLE
            "beta" -> UpdateChannel.BETA
            else -> throw IOException("更新清单通道无效")
        }
        require(manifest.channel in requested.manifestValues) { "更新清单通道不匹配" }
        require(manifest.versionName == release.string("tag_name").removePrefix("v")) {
            "更新清单版本号与发布 Tag 不一致"
        }
        require(manifest.versionCode > 0L) { "更新清单版本号无效" }
        require(manifest.sizeBytes > 0L) { "更新清单文件大小无效" }
        require(manifest.minSdk <= Build.VERSION.SDK_INT) { "此更新不支持当前 Android 版本" }
        require(SHA_256_PATTERN.matches(manifest.sha256)) { "更新清单 SHA-256 格式无效" }

        val assetDigest = apkAsset.stringOrEmpty("digest").removePrefix("sha256:")
        if (assetDigest.isNotBlank()) {
            require(assetDigest.equals(manifest.sha256, ignoreCase = true)) {
                "更新清单与发布附件校验值不一致"
            }
        }
        return actualChannel
    }

    /** 将文本解析为 JSON 对象。 */
    private fun parseObject(content: String): JsonObject = try {
        JsonParser.parseString(content).asJsonObject
    } catch (exception: Exception) {
        throw IOException("版本服务返回了无法识别的发布数据", exception)
    }

    private companion object {
        /** 更新流程日志标签。 */
        const val TAG = "UpdateRepository"
        /** GitHub 访问代理前缀，仅用于发布附件下载。 */
        const val PROXY_PREFIX = "https://v4.gh-proxy.org/"
        /** 更新清单文件名。 */
        const val MANIFEST_NAME = "capycast-update.json"
        /** 当前支持的更新清单格式版本。 */
        const val MANIFEST_SCHEMA_VERSION = 1
        /** 版本服务表示通道暂无发布的状态码。 */
        const val HTTP_NOT_FOUND = 404
        /** 更新器请求使用的 User-Agent。 */
        const val USER_AGENT = "CapyCast-Android-Updater"
        /** SHA-256 十六进制值格式。 */
        val SHA_256_PATTERN = Regex("^[A-Fa-f0-9]{64}$")
        /** 避免将错误正文中的客户端公网 IPv4 地址写入局域网诊断日志。 */
        val IP_ADDRESS_PATTERN = Regex("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b")
    }

    /** 为 GitHub 发布附件地址生成备用代理地址；其他地址不代理。 */
    private fun proxyUrl(url: String): String? = when {
        url.startsWith(PROXY_PREFIX) || !url.startsWith(GITHUB_PREFIX) -> null
        else -> "$PROXY_PREFIX$url"
    }
}

/** GitHub 发布附件的地址前缀。 */
private const val GITHUB_PREFIX = "https://github.com/"

/** 读取 JSON 对象中的必填字符串字段。 */
private fun JsonObject.string(name: String): String =
    get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        ?: throw IOException("发布数据缺少 $name")

/** 读取 JSON 对象中的可选字符串字段。 */
private fun JsonObject.stringOrEmpty(name: String): String =
    get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

/** 读取 JSON 对象中的必填长整数字段。 */
private fun JsonObject.long(name: String): Long = try {
    get(name)?.takeUnless { it.isJsonNull }?.asLong ?: throw NumberFormatException()
} catch (exception: Exception) {
    throw IOException("发布数据中的 $name 无效", exception)
}

/** 读取 JSON 对象中的必填整数字段。 */
private fun JsonObject.int(name: String): Int = try {
    get(name)?.takeUnless { it.isJsonNull }?.asInt ?: throw NumberFormatException()
} catch (exception: Exception) {
    throw IOException("发布数据中的 $name 无效", exception)
}

/** 读取 JSON 对象中的数组字段，字段不存在时返回空数组。 */
private fun JsonObject.array(name: String): JsonArray =
    getAsJsonArray(name) ?: JsonArray()
