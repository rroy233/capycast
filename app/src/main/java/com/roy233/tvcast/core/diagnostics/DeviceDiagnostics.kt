package com.roy233.tvcast.core.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build
import android.util.DisplayMetrics
import android.util.Range
import android.view.Display
import android.view.WindowManager
import com.roy233.tvcast.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.util.HWDecoderUtil
import org.videolan.libvlc.util.VLCUtil
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread

/**
 * 采集并缓存电视设备的播放能力。
 *
 * 品牌设备无法播放时，最常见的原因是缺少对应编码的硬件解码器、显示能力不足或
 * libVLC 运行时选择策略不合适。这里一次性采集设备、显示、音频、libVLC 和
 * MediaCodec 解码器信息，供 `/playback` 诊断报告和日志使用。
 */
object DeviceDiagnostics {

    /** 日志标签。 */
    private const val TAG = "DeviceCaps"
    /** 每种编码最多保留的解码器明细数量，避免报告过大。 */
    private const val MAX_DECODERS_PER_MIME = 4
    /** 需要重点检查的视频编码类型。 */
    private val videoMimes = listOf(
        "video/avc",
        "video/hevc",
        "video/dolby-vision",
        "video/av01",
        "video/x-vnd.on2.vp9",
        "video/mp4v-es",
    )
    /** 需要重点检查的音频编码类型。 */
    private val audioMimes = listOf(
        "audio/mp4a-latm",
        "audio/ac3",
        "audio/eac3",
        "audio/eac3-joc",
        "audio/opus",
        "audio/flac",
    )

    /** 已缓存的设备能力信息。 */
    @Volatile
    private var cached: JSONObject? = null
    /** 是否已经有后台采集任务在运行。 */
    @Volatile
    private var collecting = false

    /**
     * 返回设备能力快照，尚未采集时同步采集一次。
     *
     * @param context 用于读取系统服务和应用信息的上下文。
     * @return 设备能力 JSON。
     */
    fun snapshot(context: Context): JSONObject {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val data = runCatching { collect(context.applicationContext) }
                .getOrElse { error ->
                    JSONObject().put("error", LogRedaction.reason(error))
                }
            cached = data
            return data
        }
    }

    /**
     * 在后台线程采集设备能力，避免阻塞界面启动。
     *
     * @param context 用于读取系统服务和应用信息的上下文。
     */
    fun collectAsync(context: Context) {
        if (cached != null || collecting) return
        collecting = true
        val appContext = context.applicationContext
        thread(name = "device-diagnostics", isDaemon = true) {
            runCatching { collect(appContext) }
                .onSuccess { data ->
                    cached = data
                    LogHub.i(TAG, "caps ${compactSummaryOf(data)}")
                }
                .onFailure { error ->
                    LogHub.e(
                        TAG,
                        "collect failed: ${LogRedaction.reason(error)}",
                        error,
                        stack = true,
                    )
                }
            collecting = false
        }
    }

    /**
     * 返回已缓存能力的单行摘要，用于日志和状态页。
     *
     * @return 单行设备能力摘要。
     */
    fun compactSummary(): String = cached?.let(::compactSummaryOf) ?: "(not collected)"

    /** 采集当前设备的完整播放能力。 */
    private fun collect(context: Context): JSONObject = JSONObject().apply {
        put(
            "collectedAt",
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
        )
        put("app", safeSection("app") { appInfo() })
        put("device", safeSection("device") { deviceInfo(context) })
        put("display", safeSection("display") { displayInfo(context) })
        put("audio", safeSection("audio") { audioInfo(context) })
        put("vlc", safeSection("vlc") { vlcInfo(context) })
        put("codecs", safeSection("codecs") { codecInfo() })
    }

    /** 将单个能力分区转换为失败时仍可继续的 JSON。 */
    private fun safeSection(name: String, block: () -> JSONObject): JSONObject =
        runCatching(block).getOrElse { error ->
            JSONObject().put("error", "$name: ${LogRedaction.reason(error)}")
        }

    /** 读取应用版本信息。 */
    private fun appInfo(): JSONObject = JSONObject().apply {
        put("versionName", BuildConfig.VERSION_NAME)
        put("versionCode", BuildConfig.VERSION_CODE)
        put("applicationId", BuildConfig.APPLICATION_ID)
        put("buildType", BuildConfig.BUILD_TYPE)
    }

    /** 读取设备型号、系统版本和内存信息。 */
    private fun deviceInfo(context: Context): JSONObject = JSONObject().apply {
        put("manufacturer", Build.MANUFACTURER)
        put("brand", Build.BRAND)
        put("model", Build.MODEL)
        put("device", Build.DEVICE)
        put("product", Build.PRODUCT)
        put("board", Build.BOARD)
        put("hardware", Build.HARDWARE)
        put("fingerprint", Build.FINGERPRINT)
        put("abis", jsonArray(Build.SUPPORTED_ABIS.toList()))
        put("androidRelease", Build.VERSION.RELEASE)
        put("sdkInt", Build.VERSION.SDK_INT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            put("securityPatch", Build.VERSION.SECURITY_PATCH)
        }
        put("cpuCores", Runtime.getRuntime().availableProcessors())
        put("isTv", isTv(context))
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (activityManager != null) {
            put("lowRam", activityManager.isLowRamDevice)
            put("memoryClassMb", activityManager.memoryClass)
            val memoryInfo = ActivityManager.MemoryInfo()
            runCatching { activityManager.getMemoryInfo(memoryInfo) }
                .onSuccess {
                    put("totalMemMb", memoryInfo.totalMem / (1024L * 1024L))
                    put("availMemMb", memoryInfo.availMem / (1024L * 1024L))
                }
        }
    }

    /** 读取屏幕尺寸、刷新率和 HDR 支持情况。 */
    private fun displayInfo(context: Context): JSONObject = JSONObject().apply {
        val metrics = DisplayMetrics()
        val display = runCatching {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)?.defaultDisplay
        }.getOrNull()
        if (display != null) {
            runCatching { display.getRealMetrics(metrics) }
            put("refreshRate", display.refreshRate)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                put(
                    "mode",
                    runCatching { display.mode }.getOrNull()?.let {
                        "${it.physicalWidth}x${it.physicalHeight}@" +
                            String.format(Locale.US, "%.3f", it.refreshRate)
                    } ?: "(unknown)",
                )
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val hdr = runCatching { display.hdrCapabilities }.getOrNull()
                put(
                    "hdrTypes",
                    jsonArray(hdr?.supportedHdrTypes?.map(::hdrTypeName).orEmpty()),
                )
            }
        }
        put("widthPx", metrics.widthPixels)
        put("heightPx", metrics.heightPixels)
        put("density", metrics.density)
        put("densityDpi", metrics.densityDpi)
        val configuration = context.resources.configuration
        put("screenWidthDp", configuration.screenWidthDp)
        put("screenHeightDp", configuration.screenHeightDp)
        put("smallestWidthDp", configuration.smallestScreenWidthDp)
        put("orientation", configuration.orientation)
        put("uiMode", configuration.uiMode)
    }

    /** 读取音频输出能力和输出设备列表。 */
    private fun audioInfo(context: Context): JSONObject = JSONObject().apply {
        val audioManager =
            context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            put("available", false)
            return@apply
        }
        put("available", true)
        put("sampleRate", audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE))
        put("framesPerBuffer", audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = runCatching {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            }.getOrDefault(emptyArray())
            put(
                "outputDevices",
                jsonArray(
                    devices.take(8).map { device ->
                        val encodings = device.encodings.joinToString(",") { encoding ->
                            audioEncodingName(encoding)
                        }
                        "${audioDeviceTypeName(device.type)}:${device.productName}" +
                            " encodings=$encodings"
                    },
                ),
            )
        }
    }

    /** 读取 libVLC 运行时版本和硬件解码策略。 */
    private fun vlcInfo(context: Context): JSONObject = JSONObject().apply {
        runCatching { LibVLC.loadLibraries() }
        put("version", runCatching { LibVLC.version() }.getOrNull() ?: "(unavailable)")
        put("changeset", runCatching { LibVLC.changeset() }.getOrNull() ?: "(unavailable)")
        put("compiler", runCatching { LibVLC.compiler() }.getOrNull() ?: "(unavailable)")
        put(
            "hardwareDecoder",
            runCatching { HWDecoderUtil.getDecoderFromDevice()?.name }
                .getOrNull() ?: "(unavailable)",
        )
        put(
            "audioOutput",
            runCatching { HWDecoderUtil.getAudioOutputFromDevice()?.name }
                .getOrNull() ?: "(unavailable)",
        )
        put(
            "compatibleCpu",
            runCatching { VLCUtil.hasCompatibleCPU(context) }
                .getOrNull()?.toString() ?: "(unavailable)",
        )
        val vlcError = runCatching { VLCUtil.getErrorMsg() }.getOrNull().orEmpty()
        if (vlcError.isNotBlank()) put("error", vlcError)
        runCatching { VLCUtil.getMachineSpecs() }.getOrNull()?.let { specs ->
            put(
                "machine",
                JSONObject().apply {
                    put("processors", specs.processors)
                    put("frequency", specs.frequency)
                    put("is64bits", specs.is64bits)
                    put("hasNeon", specs.hasNeon)
                    put("hasArmV7", specs.hasArmV7)
                    put("hasX86", specs.hasX86)
                    put("bogoMips", specs.bogoMIPS)
                },
            )
        }
    }

    /** 枚举系统提供的硬件和软件解码器。 */
    private fun codecInfo(): JSONObject {
        val infos = runCatching {
            MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
        }.getOrNull() ?: return JSONObject().put("error", "codec list unavailable")
        val result = JSONObject()
        result.put("total", infos.size)
        (videoMimes + audioMimes).forEach { mime ->
            val decoders = infos.filter { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals(mime, ignoreCase = true) }
            }
            result.put(
                mime,
                JSONObject().apply {
                    put("count", decoders.size)
                    put(
                        "decoders",
                        jsonObjects(decoders.take(MAX_DECODERS_PER_MIME)) { info ->
                            describeCodec(info, mime)
                        },
                    )
                },
            )
        }
        return result
    }

    /** 生成单个解码器的能力描述。 */
    private fun describeCodec(info: MediaCodecInfo, mime: String): JSONObject =
        JSONObject().apply {
            put("name", info.name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put("alias", info.isAlias)
                put("hardware", info.isHardwareAccelerated)
                put("softwareOnly", info.isSoftwareOnly)
                put("vendor", info.isVendor)
            } else {
                put("hardwareGuess", !isSoftwareCodecName(info.name))
            }
            val capabilities = runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
                ?: return@apply
            put(
                "secure",
                runCatching {
                    capabilities.isFeatureSupported(
                        MediaCodecInfo.CodecCapabilities.FEATURE_SecurePlayback,
                    )
                }.getOrDefault(false),
            )
            capabilities.videoCapabilities?.let { video ->
                put(
                    "sizeRange",
                    "${rangeText(video.supportedWidths)}x${rangeText(video.supportedHeights)}",
                )
                put("maxFrameRate", runCatching { video.supportedFrameRates.upper }.getOrNull())
                put(
                    "supports4k",
                    runCatching { video.isSizeSupported(3840, 2160) }.getOrDefault(false),
                )
                put(
                    "supports1080p",
                    runCatching { video.isSizeSupported(1920, 1080) }.getOrDefault(false),
                )
            }
            capabilities.audioCapabilities?.let { audio ->
                put("channels", runCatching { audio.maxInputChannelCount }.getOrNull())
                put(
                    "sampleRates",
                    runCatching {
                        audio.supportedSampleRates.take(8).joinToString(",")
                    }.getOrElse { "(unknown)" },
                )
            }
        }

    /** 将区间转换为简短文本。 */
    private fun rangeText(range: Range<Int>?): String =
        if (range == null) "?" else "${range.lower}..${range.upper}"

    /** 判断解码器名称是否符合软件解码器特征。 */
    private fun isSoftwareCodecName(name: String): Boolean =
        name.startsWith("OMX.google.", ignoreCase = true) ||
            name.startsWith("c2.android.", ignoreCase = true) ||
            name.contains("software", ignoreCase = true)

    /** 判断当前设备是否属于电视形态。 */
    private fun isTv(context: Context): Boolean = runCatching {
        val packageManager = context.packageManager
        packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
            packageManager.hasSystemFeature(PackageManager.FEATURE_TELEVISION)
    }.getOrDefault(false)

    /** 将 HDR 类型转换为可读名称。 */
    private fun hdrTypeName(type: Int): String = when (type) {
        Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION -> "dolbyVision"
        Display.HdrCapabilities.HDR_TYPE_HDR10 -> "hdr10"
        Display.HdrCapabilities.HDR_TYPE_HLG -> "hlg"
        Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS -> "hdr10Plus"
        else -> "type$type"
    }

    /** 将音频输出设备类型转换为可读名称。 */
    private fun audioDeviceTypeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "speaker"
        AudioDeviceInfo.TYPE_HDMI -> "hdmi"
        AudioDeviceInfo.TYPE_HDMI_ARC -> "hdmiArc"
        AudioDeviceInfo.TYPE_HDMI_EARC -> "hdmiEarc"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "btA2dp"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "usb"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "usbHeadset"
        AudioDeviceInfo.TYPE_LINE_ANALOG -> "lineAnalog"
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> "lineDigital"
        else -> "type$type"
    }

    /** 将可直通的 Android 音频编码转换为可读文本。 */
    private fun audioEncodingName(encoding: Int): String = when (encoding) {
        android.media.AudioFormat.ENCODING_AC3 -> "ac3"
        android.media.AudioFormat.ENCODING_E_AC3 -> "eac3"
        android.media.AudioFormat.ENCODING_E_AC3_JOC -> "eac3-joc"
        else -> encoding.toString()
    }

    /** 构造 JSON 数组。 */
    private fun jsonArray(values: List<*>): JSONArray = JSONArray().apply {
        values.forEach { put(it) }
    }

    /** 将列表逐项转换为 JSON 数组。 */
    private fun <T> jsonObjects(values: List<T>, transform: (T) -> JSONObject): JSONArray =
        JSONArray().apply {
            values.forEach { put(transform(it)) }
        }

    /** 生成单行能力摘要。 */
    private fun compactSummaryOf(data: JSONObject): String {
        val codecs = data.optJSONObject("codecs") ?: return "(no codec data)"
        val parts = mutableListOf<String>()
        val keys = codecs.keys()
        while (keys.hasNext()) {
            val mime = keys.next()
            if (mime == "total") continue
            val group = codecs.optJSONObject(mime) ?: continue
            val count = group.optInt("count", 0)
            val first = group.optJSONArray("decoders")?.optJSONObject(0)
            val hardware = when {
                first == null -> "?"
                first.has("hardware") -> if (first.optBoolean("hardware")) "hw" else "sw"
                first.optBoolean("hardwareGuess") -> "hw?"
                else -> "sw?"
            }
            val size = first?.optString("sizeRange").orEmpty()
            parts += "$mime=$count/$hardware" + if (size.isBlank()) "" else "($size)"
        }
        val decoder = data.optJSONObject("vlc")?.optString("hardwareDecoder").orEmpty()
        val display = data.optJSONObject("display")
        val resolution = if (display != null) {
            "${display.optInt("widthPx")}x${display.optInt("heightPx")}@" +
                display.optDouble("refreshRate", 0.0)
        } else {
            "?"
        }
        return buildString {
            append("display=").append(resolution)
            append(" vlcDecoder=").append(decoder)
            append(" codecs=").append(parts.joinToString(" "))
        }
    }
}
