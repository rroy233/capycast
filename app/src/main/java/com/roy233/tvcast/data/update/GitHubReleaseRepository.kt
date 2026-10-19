package com.roy233.tvcast.data.update

import android.os.Build
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roy233.tvcast.BuildConfig
import com.roy233.tvcast.feature.update.AppUpdateInfo
import com.roy233.tvcast.feature.update.UpdateChannel
import com.roy233.tvcast.feature.update.UpdateManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * 从 GitHub Releases 查询公开发布信息和更新清单。
 *
 * 发布清单提供 APK 文件名、版本号和 SHA-256，用于将 Release 元数据与下载文件关联。
 */
class GitHubReleaseRepository(
    private val client: OkHttpClient = OkHttpClient(),
) {

    /**
     * 查询指定通道最新的可用更新。
     *
     * @param channel 要查询的发布通道。
     * @return 更新信息；通道尚无公开发布时返回 `null`。
     */
    suspend fun fetchLatest(channel: UpdateChannel): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val release = when (channel) {
            UpdateChannel.STABLE -> fetchStableRelease()
            UpdateChannel.BETA -> fetchLatestBetaRelease()
        } ?: return@withContext null

        val assets = buildList {
            val iterator = release.array("assets").iterator()
            while (iterator.hasNext()) add(iterator.next().asJsonObject)
        }
        val manifestAsset = assets.firstOrNull { asset -> asset.string("name") == MANIFEST_NAME }
            ?: throw IOException("发布缺少 $MANIFEST_NAME 清单文件")
        val manifestUrl = manifestAsset.string("browser_download_url")
        val manifest = parseManifest(fetchText(manifestUrl))
        val apkAsset = assets.first { asset -> asset.string("name") == manifest.apkName }
        validateManifest(channel, release, assets, manifest, apkAsset)

        AppUpdateInfo(
            channel = channel,
            versionName = manifest.versionName,
            versionCode = manifest.versionCode,
            apkName = manifest.apkName,
            downloadUrl = apkAsset.string("browser_download_url"),
            sha256 = manifest.sha256.lowercase(),
            sizeBytes = manifest.sizeBytes,
            releaseNotes = release.stringOrEmpty("body"),
            releaseUrl = release.string("html_url"),
        )
    }

    /** 查询最新正式发布。 */
    private fun fetchStableRelease(): JsonObject? {
        val response = fetchResponse("/repos/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")
        if (response.code == HTTP_NOT_FOUND) return null
        return parseObject(response.body)
    }

    /** 查询公开 Beta 列表中的最新预发布版本。 */
    private fun fetchLatestBetaRelease(): JsonObject? {
        val response = fetchResponse("/repos/${BuildConfig.UPDATE_REPOSITORY}/releases?per_page=30")
        val releases = JsonParser.parseString(response.body).asJsonArray
        return releases
            .map { it.asJsonObject }
            .firstOrNull { it.boolean("prerelease") && !it.boolean("draft") }
    }

    /** 下载并返回一个 GitHub API 响应。 */
    private fun fetchResponse(path: String): ApiResponse {
        val request = Request.Builder()
            .url("${BuildConfig.UPDATE_API_BASE_URL}$path")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "CapyCast-Android-Updater")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful && response.code != HTTP_NOT_FOUND) {
                throw IOException("GitHub 请求失败（HTTP ${response.code}）")
            }
            return ApiResponse(response.code, body)
        }
    }

    /** 下载公开发布附件的文本内容。 */
    private fun fetchText(url: String): String {
        require(url.startsWith("https://")) { "更新附件地址必须使用 HTTPS" }
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "CapyCast-Android-Updater")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("更新清单下载失败（HTTP ${response.code}）")
            }
            return response.body?.string() ?: throw IOException("更新清单内容为空")
        }
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

    /** 校验清单与 GitHub Release 附件之间的一致性。 */
    private fun validateManifest(
        channel: UpdateChannel,
        release: JsonObject,
        assets: List<JsonObject>,
        manifest: UpdateManifest,
        apkAsset: JsonObject,
    ) {
        require(manifest.schemaVersion == MANIFEST_SCHEMA_VERSION) { "不支持的更新清单格式" }
        require(manifest.channel == channel.name.lowercase()) { "更新清单通道不匹配" }
        require(manifest.versionName == release.string("tag_name").removePrefix("v")) {
            "更新清单版本号与 GitHub Tag 不一致"
        }
        require(manifest.versionCode > 0L) { "更新清单版本号无效" }
        require(manifest.sizeBytes > 0L) { "更新清单文件大小无效" }
        require(manifest.minSdk <= Build.VERSION.SDK_INT) { "此更新不支持当前 Android 版本" }
        require(SHA_256_PATTERN.matches(manifest.sha256)) { "更新清单 SHA-256 格式无效" }
        require(assets.any { asset -> asset.string("name") == manifest.apkName }) { "发布缺少 APK 附件" }

        val assetDigest = apkAsset.stringOrEmpty("digest").removePrefix("sha256:")
        if (assetDigest.isNotBlank()) {
            require(assetDigest.equals(manifest.sha256, ignoreCase = true)) {
                "更新清单与 GitHub 附件校验值不一致"
            }
        }
    }

    /** 将文本解析为 JSON 对象。 */
    private fun parseObject(content: String): JsonObject = try {
        JsonParser.parseString(content).asJsonObject
    } catch (exception: Exception) {
        throw IOException("GitHub 返回了无法识别的发布数据", exception)
    }

    /** GitHub API 的状态码和响应文本。 */
    private data class ApiResponse(val code: Int, val body: String)

    private companion object {
        /** 更新清单文件名。 */
        const val MANIFEST_NAME = "capycast-update.json"
        /** 当前支持的更新清单格式版本。 */
        const val MANIFEST_SCHEMA_VERSION = 1
        /** GitHub 未找到资源的状态码。 */
        const val HTTP_NOT_FOUND = 404
        /** SHA-256 十六进制值格式。 */
        val SHA_256_PATTERN = Regex("^[A-Fa-f0-9]{64}$")
    }
}

/** 读取 JSON 对象中的必填字符串字段。 */
private fun JsonObject.string(name: String): String =
    get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }
        ?: throw IOException("GitHub 发布数据缺少 $name")

/** 读取 JSON 对象中的可选字符串字段。 */
private fun JsonObject.stringOrEmpty(name: String): String =
    get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

/** 读取 JSON 对象中的必填长整数字段。 */
private fun JsonObject.long(name: String): Long = try {
    get(name)?.takeUnless { it.isJsonNull }?.asLong ?: throw NumberFormatException()
} catch (exception: Exception) {
    throw IOException("GitHub 发布数据中的 $name 无效", exception)
}

/** 读取 JSON 对象中的必填整数字段。 */
private fun JsonObject.int(name: String): Int = try {
    get(name)?.takeUnless { it.isJsonNull }?.asInt ?: throw NumberFormatException()
} catch (exception: Exception) {
    throw IOException("GitHub 发布数据中的 $name 无效", exception)
}

/** 读取 JSON 对象中的布尔字段，字段不存在时返回 `false`。 */
private fun JsonObject.boolean(name: String): Boolean =
    get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: false

/** 读取 JSON 对象中的数组字段，字段不存在时返回空数组。 */
private fun JsonObject.array(name: String): JsonArray =
    getAsJsonArray(name) ?: JsonArray()
