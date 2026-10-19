package com.roy233.tvcast.platform.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.net.toUri
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction
import com.roy233.tvcast.core.diagnostics.NativeLogcat
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.data.bili.BiliApi
import org.json.JSONObject
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.HWDecoderUtil
import org.videolan.libvlc.util.VLCUtil
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale
import kotlin.math.abs

/**
 * 管理 VLC 播放器生命周期和媒体初始化。
 *
 * 播放链路是品牌兼容问题最集中的部分，因此这里会记录 libVLC 运行时能力、媒体
 * 选项、全部播放器事件、实际选中的轨道、解码与丢帧统计、启动/输出/卡顿超时，
 * 并在出错时把 libVLC 原生日志尾部和完整状态写入诊断报告。
 *
 * @param context 用于创建 VLC 运行时的上下文。
 * @param videoLayout 播放视频所使用的布局。
 * @param onEvent VLC 播放器事件回调。
 */
class VlcPlayerEngine(
    context: Context,
    private val videoLayout: VLCVideoLayout,
    private val onEvent: (MediaPlayer.Event) -> Unit,
) {

    /** 应用上下文。 */
    private val appContext = context.applicationContext
    /** 用于执行播放监控任务的主线程处理器。 */
    private val handler = Handler(Looper.getMainLooper())
    /** libVLC 原生日志文件。 */
    private val nativeLogFile = PlaybackDiagnostics.vlcLogFile(appContext)
    /** VLC 原生运行时实例。 */
    private val libVlc = LibVLC(appContext, buildLibVlcOptions())

    /** 对外提供的 VLC 播放器实例。 */
    val player = MediaPlayer(libVlc)

    /** 当前正在播放的媒体，保留引用以便读取统计和轨道信息。 */
    private var currentMedia: Media? = null
    /** 当前是否存在活动的播放监控会话。 */
    @Volatile
    private var sessionActive = false
    /** 最近一次打开媒体的单调时刻。 */
    private var mediaOpenedAt = 0L
    /** 第一次收到 Opening 事件的时刻。 */
    private var openingAt = 0L
    /** 最近一次收到 Playing 事件的时刻。 */
    private var playingAt = 0L
    /** 第一次收到视频输出事件的时刻。 */
    private var voutAt = 0L
    /** 是否已经收到 Opening 事件。 */
    private var openingSeen = false
    /** 是否已经收到 Playing 事件。 */
    private var playingSeen = false
    /** 是否已经收到视频输出事件。 */
    private var voutSeen = false
    /** 是否已经成功绑定视频视图。 */
    private var viewsAttached = false
    /** 第一次 Playing 事件是否已经记录。 */
    private var firstPlayingLogged = false
    /** 最近一次播放位置前进的时刻。 */
    @Volatile
    private var lastProgressAt = 0L
    /** 最近一次已知的播放位置。 */
    @Volatile
    private var lastPositionMs = -1L
    /** 最近一次统计采样的时刻。 */
    private var lastStatsAt = 0L
    /** 最近一次时间线位置日志的时刻。 */
    private var lastTimeLogAt = 0L
    /** 最近一次缓冲进度分桶。 */
    private var lastBufferingBucket = -1
    /** 是否已经提示原生日志为空。 */
    private var vlcLogWarningLogged = false
    /** 是否已经提示统计信息不可用。 */
    private var statsUnavailableLogged = false
    /** 是否已经记录第一次统计基线。 */
    private var statsFirstLogged = false
    /** 上一次统计采样，用于计算增量。 */
    private var previousStats: IMedia.Stats? = null
    /** 最近一次统计采样 JSON。 */
    private var lastStats: JSONObject? = null
    /** 已经提示过一次的异常类型。 */
    private val warnedOnce = mutableSetOf<String>()
    /** 各类异常最近一次提示的时刻。 */
    private val warnedAt = mutableMapOf<String, Long>()
    /** 是否已有原生日志采集线程在运行。 */
    @Volatile
    private var nativeCaptureRunning = false
    /** 已经写入诊断报告的媒体轨道键，避免重复快照。 */
    private val loggedMediaTracks = mutableSetOf<String>()

    /** 周期性播放监控任务。 */
    private val monitor = object : Runnable {
        /** 执行一次监控检查并重新调度。 */
        override fun run() {
            if (!sessionActive) return
            runCatching { monitorTick() }
                .onFailure {
                    LogHub.e(TAG, "monitor tick failed", it, stack = true)
                }
            if (sessionActive) handler.postDelayed(this, MONITOR_INTERVAL_MS)
        }
    }

    /** 将播放器绑定到视频布局并注册事件监听器。 */
    init {
        logRuntime()
        runCatching {
            player.attachViews(videoLayout, null, false, false)
            viewsAttached = true
            LogHub.i(TAG, "video surface attached size=${viewSize()}")
        }.onFailure {
            LogHub.e(TAG, "attachViews failed", it, stack = true)
            PlaybackDiagnostics.runtime("views.attached", "false")
        }
        player.setEventListener { event -> dispatchEvent(event) }
    }

    /**
     * 打开播放信息对应的媒体并开始播放。
     *
     * @param info 已解析的播放信息。
     * @param seekToMs 开始播放前需要跳转的位置，单位为毫秒。
     * @return 是否已经成功发起播放。
     */
    fun open(info: BiliApi.PlayInfo, seekToMs: Long): Boolean {
        val startedAt = SystemClock.elapsedRealtime()
        stopMonitor()
        resetSessionState()
        mediaOpenedAt = startedAt
        var createdMedia: Media? = null
        return try {
            PlaybackDiagnostics.ensureSession("player-open")
            PlaybackDiagnostics.state("opening")
            PlaybackDiagnostics.attribute("player.viewsAttached", viewsAttached.toString())
            PlaybackDiagnostics.attribute("player.seekToMs", seekToMs.coerceAtLeast(0L).toString())
            logOpenSummary(info, seekToMs)

            val url = info.progressiveUrl ?: info.video.url
            require(url.isNotBlank()) { "empty media url" }
            val media = Media(libVlc, url.toUri())
            createdMedia = media
            val hardwareResult = runCatching { media.setHWDecoderEnabled(true, false) }
            if (hardwareResult.isFailure) {
                LogHub.w(
                    TAG,
                    "setHWDecoderEnabled failed: ${LogRedaction.reason(hardwareResult.exceptionOrNull())}",
                )
            }
            if (info.progressiveUrl == null) {
                val audioUrl = info.audio?.url.orEmpty()
                if (audioUrl.isNotBlank()) {
                    media.addOption(":input-slave=$audioUrl")
                    LogHub.i(TAG, "audio slave attached url=${LogRedaction.url(audioUrl)}")
                } else {
                    LogHub.w(TAG, "dash media has no audio track")
                }
            }
            if (seekToMs > 0L) {
                media.addOption(":start-time=${seekToMs / 1000.0}")
                LogHub.i(TAG, "media start-time=${seekToMs}ms")
            }
            media.addOption(":http-referrer=https://www.bilibili.com/")
            media.addOption(":http-user-agent=${BiliApi.UA}")
            media.addOption(":network-caching=2000")
            LogHub.i(
                TAG,
                "media options hwDecoder=mediacodec forcedFlush=false " +
                    "referrer=bilibili ua=${BiliApi.UA.take(32)}… networkCaching=2000",
            )

            val previous = currentMedia
            currentMedia = media
            player.media = media
            previous?.let { runCatching { it.release() } }
            player.play()
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            LogHub.i(TAG, "play invoked elapsed=${elapsed}ms state=${playerStateName()}")
            PlaybackDiagnostics.event("Open", "play invoked elapsed=${elapsed}ms")
            startMonitor()
            true
        } catch (t: Throwable) {
            LogHub.e(TAG, "open failed: ${LogRedaction.reason(t)}", t, stack = true)
            runCatching { player.stop() }
            val assigned = createdMedia != null && currentMedia === createdMedia
            currentMedia = null
            createdMedia?.let { media -> runCatching { media.release() } }
            if (assigned) {
                LogHub.i(TAG, "released failed media after open error")
            }
            PlaybackDiagnostics.fail(
                code = "PLAYER_OPEN_FAILED",
                message = "打开播放器失败",
                detail = "reason=${LogRedaction.reason(t)} ${failureSnapshot()}",
            )
            logNativeLogTail("open-failed", 40)
            stopMonitor()
            false
        }
    }

    /**
     * 暂停或继续播放并记录调用结果。
     *
     * @param paused 是否暂停。
     * @param reason 调用来源。
     */
    fun setPaused(paused: Boolean, reason: String) {
        val beforePlaying = runCatching { player.isPlaying }.getOrDefault(false)
        val beforeState = playerStateName()
        val result = runCatching { if (paused) player.pause() else player.play() }
        val afterPlaying = runCatching { player.isPlaying }.getOrDefault(false)
        LogHub.i(
            TAG,
            "control pause=$paused reason=$reason " +
                "before=$beforeState/$beforePlaying after=${playerStateName()}/$afterPlaying " +
                "call=${result.isSuccess}",
        )
        result.onFailure {
            LogHub.e(TAG, "control pause=$paused failed reason=$reason", it, stack = true)
        }
        PlaybackDiagnostics.event(
            "Control",
            "pause=$paused reason=$reason beforePlaying=$beforePlaying afterPlaying=$afterPlaying",
        )
        lastProgressAt = SystemClock.elapsedRealtime()
    }

    /**
     * 跳转到指定播放位置并记录请求值和实际值。
     *
     * @param positionMs 目标位置，单位为毫秒。
     * @param reason 调用来源。
     * @return 跳转后立即读取到的播放位置。
     */
    fun seekTo(positionMs: Long, reason: String): Long {
        val beforeTime = runCatching { player.time }.getOrDefault(-1L)
        val length = runCatching { player.length }.getOrDefault(-1L)
        val target = positionMs.coerceAtLeast(0L)
        val result = runCatching { player.time = target }
        val afterTime = runCatching { player.time }.getOrDefault(-1L)
        LogHub.i(
            TAG,
            "control seek reason=$reason from=${beforeTime}ms to=${target}ms " +
                "after=${afterTime}ms length=${length}ms call=${result.isSuccess}",
        )
        result.onFailure {
            LogHub.e(TAG, "seek failed reason=$reason target=${target}ms", it, stack = true)
        }
        PlaybackDiagnostics.event(
            "Control",
            "seek reason=$reason target=${target}ms after=${afterTime}ms length=${length}ms",
        )
        lastPositionMs = afterTime
        lastProgressAt = SystemClock.elapsedRealtime()
        return afterTime
    }

    /**
     * 设置播放器音量并记录请求值和实际值。
     *
     * @param volume 目标音量，范围 0 到 100。
     * @param reason 调用来源。
     */
    fun setPlayerVolume(volume: Int, reason: String) {
        val target = volume.coerceIn(0, 100)
        val before = runCatching { player.volume }.getOrDefault(-1)
        val result = runCatching { player.volume = target }
        val after = runCatching { player.volume }.getOrDefault(-1)
        LogHub.i(
            TAG,
            "control volume reason=$reason requested=$target before=$before after=$after " +
                "call=${result.isSuccess}",
        )
        result.onFailure {
            LogHub.e(TAG, "volume failed reason=$reason target=$target", it, stack = true)
        }
        PlaybackDiagnostics.event(
            "Control",
            "volume reason=$reason requested=$target after=$after",
        )
    }

    /**
     * 停止播放并结束当前诊断会话。
     *
     * @param reason 调用来源。
     */
    fun stopPlayback(reason: String) {
        LogHub.i(
            TAG,
            "stop reason=$reason state=${playerStateName()} " +
                "time=${runCatching { player.time }.getOrDefault(-1L)}ms " +
                "playing=${runCatching { player.isPlaying }.getOrDefault(false)}",
        )
        stopMonitor()
        val result = runCatching { player.stop() }
        result.onFailure {
            LogHub.e(TAG, "stop failed reason=$reason", it, stack = true)
        }
        PlaybackDiagnostics.event("Control", "stop reason=$reason success=${result.isSuccess}")
        sampleStats(force = true)
        PlaybackDiagnostics.closeSession("stopped:$reason")
    }

    /**
     * 主动输出一次播放状态、轨道和统计快照。
     *
     * @param reason 触发原因。
     */
    fun refreshSnapshot(reason: String) {
        LogHub.i(TAG, "snapshot reason=$reason ${failureSnapshot()}")
        logTrackSnapshot("manual-$reason")
        sampleStats(force = true)
        logNativeLogTail("manual-$reason", 20)
    }

    /**
     * 返回当前播放器状态的单行快照。
     *
     * @return 包含播放器状态、媒体和统计摘要的文本。
     */
    fun stateSnapshot(): String = failureSnapshot()

    /** 停止播放并释放播放器及原生运行时资源。 */
    fun release() {
        stopMonitor()
        LogHub.i(TAG, "release ${failureSnapshot()}")
        PlaybackDiagnostics.event("Engine", "release")
        PlaybackDiagnostics.closeSession("engine-release")
        runCatching { player.stop() }.onFailure {
            LogHub.e(TAG, "release stop failed", it)
        }
        runCatching { player.detachViews() }.onFailure {
            LogHub.e(TAG, "release detachViews failed", it)
        }
        runCatching { player.release() }.onFailure {
            LogHub.e(TAG, "release player failed", it)
        }
        currentMedia?.let { media ->
            runCatching { media.release() }.onFailure {
                LogHub.e(TAG, "release media failed", it)
            }
        }
        currentMedia = null
        runCatching { libVlc.release() }.onFailure {
            LogHub.e(TAG, "release libvlc failed", it)
        }
        LogHub.i(TAG, "released nativeLogBytes=${PlaybackDiagnostics.vlcLogBytes()}")
    }

    /** 构造 libVLC 启动参数，并启用原生日志文件。 */
    private fun buildLibVlcOptions(): ArrayList<String> = arrayListOf(
        "--avcodec-hw=mediacodec",
        "--no-drop-late-frames",
        "--no-skip-frames",
        "--network-caching=2000",
        "--audio-time-stretch",
        "--android-display-chroma=RV32",
        "--verbose=1",
        "--file-logging",
        "--logfile=${nativeLogFile.absolutePath}",
    )

    /** 记录 libVLC 版本、硬件解码器和显示环境。 */
    private fun logRuntime() {
        val version = runCatching { LibVLC.version() }.getOrElse { "(unavailable)" }
        val changeset = runCatching { LibVLC.changeset() }.getOrElse { "(unavailable)" }
        val compiler = runCatching { LibVLC.compiler() }.getOrElse { "(unavailable)" }
        val decoder = runCatching { HWDecoderUtil.getDecoderFromDevice()?.name }
            .getOrElse { "(unavailable)" }
        val audioOutput = runCatching { HWDecoderUtil.getAudioOutputFromDevice()?.name }
            .getOrElse { "(unavailable)" }
        val compatible = runCatching { VLCUtil.hasCompatibleCPU(appContext) }
            .getOrElse { "(unavailable)" }
        val passthrough = runCatching { player.canDoPassthrough() }.getOrDefault(false)
        val machine = runCatching { VLCUtil.getMachineSpecs() }.getOrNull()
        val metrics = DisplayMetrics()
        val display = runCatching {
            (appContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay
        }.getOrNull()
        if (display != null) {
            runCatching { display.getRealMetrics(metrics) }
        }
        LogHub.i(TAG, "libvlc version=$version changeset=$changeset compiler=$compiler")
        LogHub.i(
            TAG,
            "runtime hwDecoder=$decoder audioOutput=$audioOutput " +
                "compatibleCpu=$compatible passthrough=$passthrough",
        )
        LogHub.i(
            TAG,
            "machine ${machineSummary(machine)} view=${viewSize()} " +
                "display=${metrics.widthPixels}x${metrics.heightPixels}@${display?.refreshRate}",
        )
        LogHub.i(TAG, "native log file=${nativeLogFile.absolutePath} bytes=${nativeLogFile.length()}")
        PlaybackDiagnostics.runtime("vlc.version", version)
        PlaybackDiagnostics.runtime("vlc.changeset", changeset)
        PlaybackDiagnostics.runtime("vlc.compiler", compiler)
        PlaybackDiagnostics.runtime("vlc.hardwareDecoder", decoder.toString())
        PlaybackDiagnostics.runtime("vlc.audioOutput", audioOutput.toString())
        PlaybackDiagnostics.runtime("vlc.compatibleCpu", compatible.toString())
        PlaybackDiagnostics.runtime("vlc.passthrough", passthrough.toString())
        PlaybackDiagnostics.runtime("vlc.machine", machineSummary(machine))
        PlaybackDiagnostics.runtime(
            "display",
            "${metrics.widthPixels}x${metrics.heightPixels}@${display?.refreshRate}",
        )
        PlaybackDiagnostics.runtime("mediaOptions", buildLibVlcOptions().joinToString(" "))
    }

    /** 生成 CPU 能力摘要。 */
    private fun machineSummary(specs: VLCUtil.MachineSpecs?): String {
        if (specs == null) return "unavailable"
        return "cores=${specs.processors} freq=${specs.frequency} 64bit=${specs.is64bits} " +
            "neon=${specs.hasNeon} armv7=${specs.hasArmV7} x86=${specs.hasX86}"
    }

    /** 记录一次打开媒体的完整摘要。 */
    private fun logOpenSummary(info: BiliApi.PlayInfo, seekToMs: Long) {
        val source = if (info.progressiveUrl == null) "dash" else "progressive"
        val video = info.video
        LogHub.i(
            TAG,
            "open source=$source title=${info.title.take(40)} " +
                "qn=${video.id}(${info.qualityLabel}) codec=${codecName(video.codecid)} " +
                "${video.width}x${video.height} bw=${video.bandwidth} " +
                "audio=${info.audio?.id}(${info.audioLabel}) seek=${seekToMs}ms " +
                "videos=${info.videos.size} audios=${info.audios.size} backups=${video.backup.size}",
        )
        LogHub.i(TAG, "open videoUrl=${LogRedaction.url(info.progressiveUrl ?: video.url)}")
        info.audio?.let { audio ->
            LogHub.i(TAG, "open audioUrl=${LogRedaction.url(audio.url)}")
        }
        if (video.backup.isNotEmpty()) {
            LogHub.i(
                TAG,
                "open backupHosts=${video.backup.take(4).joinToString(",") { LogRedaction.host(it) }}",
            )
        }
        PlaybackDiagnostics.attribute("stream.source", source)
        PlaybackDiagnostics.attribute("stream.title", info.title)
        PlaybackDiagnostics.attribute("stream.bvid", info.bvid)
        PlaybackDiagnostics.attribute("stream.aid", info.aid.toString())
        PlaybackDiagnostics.attribute("stream.cid", info.cid.toString())
        PlaybackDiagnostics.attribute("stream.durationSec", info.durationSec.toString())
        PlaybackDiagnostics.attribute("stream.qn", video.id.toString())
        PlaybackDiagnostics.attribute("stream.qnLabel", info.qualityLabel)
        PlaybackDiagnostics.attribute("stream.codec", codecName(video.codecid))
        PlaybackDiagnostics.attribute("stream.size", "${video.width}x${video.height}")
        PlaybackDiagnostics.attribute("stream.bandwidth", video.bandwidth.toString())
        PlaybackDiagnostics.attribute("stream.pcdn", video.pcdn.toString())
        PlaybackDiagnostics.attribute("stream.videoUrl", LogRedaction.url(video.url))
        PlaybackDiagnostics.attribute("stream.audioId", info.audio?.id?.toString().orEmpty())
        PlaybackDiagnostics.attribute(
            "stream.audioUrl",
            LogRedaction.url(info.audio?.url),
        )
        info.videos.take(MAX_TRACKS).forEach { track ->
            PlaybackDiagnostics.track(
                "video",
                mapOf(
                    "source" to "candidate",
                    "id" to track.id.toString(),
                    "codec" to codecName(track.codecid),
                    "size" to "${track.width}x${track.height}",
                    "bandwidth" to track.bandwidth.toString(),
                    "host" to LogRedaction.host(track.url),
                    "pcdn" to track.pcdn.toString(),
                    "chosen" to (track.url == video.url).toString(),
                ),
            )
        }
        info.audios.take(MAX_TRACKS).forEach { track ->
            PlaybackDiagnostics.track(
                "audio",
                mapOf(
                    "source" to "candidate",
                    "id" to track.id.toString(),
                    "codec" to codecName(track.codecid),
                    "bandwidth" to track.bandwidth.toString(),
                    "host" to LogRedaction.host(track.url),
                    "pcdn" to track.pcdn.toString(),
                    "chosen" to (track.url == info.audio?.url).toString(),
                ),
            )
        }
    }

    /** 将播放器事件分发给诊断记录和界面回调。 */
    private fun dispatchEvent(event: MediaPlayer.Event) {
        runCatching { recordEvent(event) }
            .onFailure {
                LogHub.e(TAG, "record event failed type=${event.type}", it, stack = true)
            }
        runCatching { onEvent(event) }
            .onFailure {
                LogHub.e(TAG, "ui event handler failed type=${event.type}", it, stack = true)
            }
    }

    /** 记录单个播放器事件并更新监控状态。 */
    private fun recordEvent(event: MediaPlayer.Event) {
        val now = SystemClock.elapsedRealtime()
        when (event.type) {
            MediaPlayer.Event.TimeChanged -> handleTimeChanged(event, now)
            MediaPlayer.Event.PositionChanged -> Unit
            MediaPlayer.Event.Buffering -> handleBuffering(event)
            MediaPlayer.Event.Opening -> {
                openingSeen = true
                openingAt = now
                PlaybackDiagnostics.state("opening")
                PlaybackDiagnostics.event(
                    "Opening",
                    "afterOpen=${now - mediaOpenedAt}ms state=${playerStateName()}",
                )
                LogHub.i(TAG, "opening afterOpen=${now - mediaOpenedAt}ms")
            }
            MediaPlayer.Event.Playing -> {
                handlePlaying(now)
                PlaybackDiagnostics.event(
                    "Playing",
                    "afterOpen=${now - mediaOpenedAt}ms state=${playerStateName()} " +
                        "length=${runCatching { player.length }.getOrDefault(-1L)}ms",
                )
            }
            MediaPlayer.Event.Paused -> {
                lastProgressAt = now
                PlaybackDiagnostics.state("paused")
                PlaybackDiagnostics.event(
                    "Paused",
                    "position=${runCatching { player.time }.getOrDefault(-1L)}ms",
                )
                LogHub.i(TAG, "paused at=${runCatching { player.time }.getOrDefault(-1L)}ms")
            }
            MediaPlayer.Event.Stopped -> {
                if (isLateTerminalEvent("Stopped")) return
                if (!openingSeen && now - mediaOpenedAt < MEDIA_SWITCH_GRACE_MS) {
                    PlaybackDiagnostics.event(
                        "Stopped",
                        "ignored during media switch elapsed=${now - mediaOpenedAt}ms",
                    )
                    LogHub.d(TAG, "stopped during media switch; ignored")
                    return
                }
                PlaybackDiagnostics.state("stopped")
                PlaybackDiagnostics.event("Stopped", "elapsed=${now - mediaOpenedAt}ms")
                LogHub.i(TAG, "stopped elapsed=${now - mediaOpenedAt}ms")
                sampleStats(force = true)
                stopMonitor()
                PlaybackDiagnostics.closeSession("stream-stopped")
            }
            MediaPlayer.Event.EndReached -> {
                if (isLateTerminalEvent("EndReached")) return
                PlaybackDiagnostics.state("ended")
                PlaybackDiagnostics.event(
                    "EndReached",
                    "elapsed=${now - mediaOpenedAt}ms " +
                        "length=${runCatching { player.length }.getOrDefault(-1L)}ms",
                )
                LogHub.i(
                    TAG,
                    "end reached elapsed=${now - mediaOpenedAt}ms " +
                        "length=${runCatching { player.length }.getOrDefault(-1L)}ms",
                )
                sampleStats(force = true)
                stopMonitor()
                PlaybackDiagnostics.closeSession("end-reached")
            }
            MediaPlayer.Event.EncounteredError -> {
                if (isLateTerminalEvent("EncounteredError")) return
                PlaybackDiagnostics.state("error")
                PlaybackDiagnostics.event(
                    "EncounteredError",
                    "afterOpen=${now - mediaOpenedAt}ms state=${playerStateName()}",
                )
                logFailureContext("encountered-error")
                stopMonitor()
            }
            MediaPlayer.Event.Vout -> handleVout(event, now)
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> {
                val typeName = trackTypeName(event.esChangedType)
                PlaybackDiagnostics.event(
                    eventName(event.type),
                    "trackType=$typeName trackId=${event.esChangedID}",
                )
                LogHub.i(
                    TAG,
                    "${eventName(event.type)} trackType=$typeName trackId=${event.esChangedID}",
                )
            }
            MediaPlayer.Event.LengthChanged -> {
                PlaybackDiagnostics.event(
                    "LengthChanged",
                    "length=${event.lengthChanged}ms",
                )
                LogHub.i(TAG, "length changed to ${event.lengthChanged}ms")
            }
            MediaPlayer.Event.SeekableChanged -> {
                PlaybackDiagnostics.event("SeekableChanged", "seekable=${event.seekable}")
            }
            MediaPlayer.Event.PausableChanged -> {
                PlaybackDiagnostics.event("PausableChanged", "pausable=${event.pausable}")
            }
            MediaPlayer.Event.MediaChanged -> {
                PlaybackDiagnostics.event("MediaChanged", mediaSummary())
                LogHub.i(TAG, "media changed ${mediaSummary()}")
            }
            MediaPlayer.Event.RecordChanged -> {
                PlaybackDiagnostics.event(
                    "RecordChanged",
                    "recording=${event.recording} path=${event.recordPath.orEmpty()}",
                )
            }
            else -> {
                PlaybackDiagnostics.event(eventName(event.type), eventDetail(event))
            }
        }
    }

    /** 判断终止事件是否来自已经结束的会话。 */
    private fun isLateTerminalEvent(name: String): Boolean {
        if (sessionActive) return false
        PlaybackDiagnostics.event(name, "ignored after session end")
        LogHub.d(TAG, "$name after session end; ignored")
        return true
    }

    /** 处理 Playing 事件，记录首帧准备阶段的时间和轨道信息。 */
    private fun handlePlaying(now: Long) {
        playingSeen = true
        playingAt = now
        lastProgressAt = now
        PlaybackDiagnostics.state("playing")
        val first = !firstPlayingLogged
        firstPlayingLogged = true
        LogHub.i(
            TAG,
            "playing afterOpen=${now - mediaOpenedAt}ms first=$first " +
                "state=${playerStateName()} length=${runCatching { player.length }.getOrDefault(-1L)} " +
                "seekable=${runCatching { player.isSeekable }.getOrDefault(false)} " +
                "audioTracks=${runCatching { player.audioTracksCount }.getOrDefault(-1)} " +
                "videoTracks=${runCatching { player.videoTracksCount }.getOrDefault(-1)}",
        )
        if (first) {
            PlaybackDiagnostics.attribute(
                "player.firstPlayingMs",
                (now - mediaOpenedAt).toString(),
            )
            logTrackSnapshot("first-playing")
            sampleStats(force = true)
        }
    }

    /** 处理视频输出事件，记录首个画面的时间。 */
    private fun handleVout(event: MediaPlayer.Event, now: Long) {
        val first = !voutSeen
        if (first) {
            voutSeen = true
            voutAt = now
            val afterPlaying = if (playingAt > 0L) now - playingAt else -1L
            LogHub.i(
                TAG,
                "first vout afterOpen=${now - mediaOpenedAt}ms afterPlaying=${afterPlaying}ms " +
                    "count=${event.voutCount} view=${viewSize()}",
            )
            PlaybackDiagnostics.attribute(
                "player.firstVoutMs",
                (now - mediaOpenedAt).toString(),
            )
            if (videoLayout.width <= 0 || videoLayout.height <= 0) {
                warnOnce("zeroSurface", "video surface has zero size before vout")
            }
            logTrackSnapshot("first-vout")
        }
        PlaybackDiagnostics.event(
            "Vout",
            "count=${event.voutCount} first=$first view=${viewSize()}",
        )
    }

    /** 处理缓冲事件，仅在跨过 10% 分桶时记录。 */
    private fun handleBuffering(event: MediaPlayer.Event) {
        val value = event.buffering
        val bucket = (value / 10f).toInt().coerceIn(0, 10)
        if (value < 100f) {
            PlaybackDiagnostics.state(if (playingSeen) "playing" else "buffering")
        } else if (playingSeen) {
            PlaybackDiagnostics.state("playing")
        }
        if (bucket != lastBufferingBucket) {
            lastBufferingBucket = bucket
            PlaybackDiagnostics.event("Buffering", "value=$value bucket=${bucket * 10}")
            if (value < 100f) {
                LogHub.d(TAG, "buffering ${value}%")
            } else {
                LogHub.i(TAG, "buffering complete")
            }
        }
    }

    /** 处理时间进度事件，限制日志频率并跟踪卡顿。 */
    private fun handleTimeChanged(event: MediaPlayer.Event, now: Long) {
        val time = event.timeChanged
        val delta = time - lastPositionMs
        if (time > 0L && (lastPositionMs < 0L || delta >= 250L)) {
            lastProgressAt = now
        }
        lastPositionMs = time
        val jumped = abs(delta) > 3000L
        if (jumped || now - lastTimeLogAt >= TIME_LOG_INTERVAL_MS) {
            lastTimeLogAt = now
            PlaybackDiagnostics.event(
                "TimeChanged",
                "time=${time}ms delta=${delta}ms${if (jumped) " jump=true" else ""}",
            )
        }
    }

    /** 周期性检查启动、视频输出和进度停滞。 */
    private fun monitorTick() {
        if (!sessionActive) return
        val now = SystemClock.elapsedRealtime()
        val openElapsed = now - mediaOpenedAt
        if (!openingSeen && openElapsed > NO_OPENING_MS) {
            warnOnce(
                "noOpening",
                "no Opening event after ${openElapsed}ms state=${playerStateName()} " +
                    "nativeLogBytes=${PlaybackDiagnostics.vlcLogBytes()}",
            )
        }
        if (!playingSeen && openElapsed > NO_PLAYING_MS) {
            warnOnce(
                "noPlaying",
                "no Playing event after ${openElapsed}ms state=${playerStateName()} " +
                    "nativeLogBytes=${PlaybackDiagnostics.vlcLogBytes()}",
            )
            logTrackSnapshot("watchdog-no-playing")
            logNativeLogTail("watchdog-no-playing", 25)
        }
        if (playingSeen && !voutSeen && now - playingAt > NO_VOUT_MS) {
            warnOnce(
                "noVout",
                "no video output ${now - playingAt}ms after Playing; " +
                    "possible audio-only playback, decoder or surface failure",
            )
            logTrackSnapshot("watchdog-no-vout")
            sampleStats(force = true)
        }
        if (playingSeen &&
            runCatching { player.isPlaying }.getOrDefault(false) &&
            lastProgressAt > 0L &&
            now - lastProgressAt > STALL_MS
        ) {
            warnThrottled(
                "stalled",
                "playback position stalled for ${now - lastProgressAt}ms at " +
                    "${runCatching { player.time }.getOrDefault(-1L)}ms",
            )
            sampleStats(force = true)
            logNativeLogTail("watchdog-stalled", 25)
        }
        if (!vlcLogWarningLogged &&
            openElapsed > NO_NATIVE_LOG_MS &&
            PlaybackDiagnostics.vlcLogBytes() <= 0L
        ) {
            vlcLogWarningLogged = true
            LogHub.w(
                TAG,
                "libvlc native log file still empty after ${openElapsed}ms; " +
                    "playback report will read the VLC logcat instead",
            )
            PlaybackDiagnostics.event(
                "Anomaly",
                "nativeLogEmpty after=${openElapsed}ms",
            )
        }
        sampleStats(force = false)
    }

    /** 采集一次播放统计并检测异常增量。 */
    private fun sampleStats(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastStatsAt < STATS_INTERVAL_MS) return
        lastStatsAt = now
        val media = currentMedia ?: return
        val stats = runCatching { media.stats }.getOrNull()
        if (stats == null) {
            if (!statsUnavailableLogged) {
                statsUnavailableLogged = true
                LogHub.w(TAG, "media stats unavailable; per-stream counters cannot be sampled")
            }
            return
        }
        val sample = JSONObject().apply {
            put("atMs", now - mediaOpenedAt)
            put("positionMs", runCatching { player.time }.getOrDefault(-1L))
            put("readBytes", stats.readBytes)
            put("inputBitrate", stats.inputBitrate.toDouble())
            put("demuxReadBytes", stats.demuxReadBytes)
            put("demuxBitrate", stats.demuxBitrate.toDouble())
            put("demuxCorrupted", stats.demuxCorrupted)
            put("demuxDiscontinuity", stats.demuxDiscontinuity)
            put("decodedVideo", stats.decodedVideo)
            put("decodedAudio", stats.decodedAudio)
            put("displayedPictures", stats.displayedPictures)
            put("lostPictures", stats.lostPictures)
            put("playedAbuffers", stats.playedAbuffers)
            put("lostAbuffers", stats.lostAbuffers)
        }
        lastStats = sample
        PlaybackDiagnostics.stats(sample)
        if (!statsFirstLogged) {
            statsFirstLogged = true
            LogHub.i(
                TAG,
                "stats baseline position=${sample.optLong("positionMs")}ms " +
                    "readBytes=${stats.readBytes} inputBitrate=${stats.inputBitrate} " +
                    "decodedVideo=${stats.decodedVideo} decodedAudio=${stats.decodedAudio} " +
                    "displayed=${stats.displayedPictures} lost=${stats.lostPictures} " +
                    "demuxCorrupted=${stats.demuxCorrupted} " +
                    "demuxDiscontinuity=${stats.demuxDiscontinuity}",
            )
        }
        checkStatAnomalies(stats, now)
        previousStats = stats
    }

    /** 对比相邻统计采样，记录丢帧、损坏和停读等异常。 */
    private fun checkStatAnomalies(stats: IMedia.Stats, now: Long) {
        val previous = previousStats ?: return
        val playing = runCatching { player.isPlaying }.getOrDefault(false)
        val position = runCatching { player.time }.getOrDefault(-1L)
        val positionMoved = position > 0L
        val lostDelta = stats.lostPictures - previous.lostPictures
        if (lostDelta > 0) {
            warnThrottled(
                "lostPictures",
                "dropped video frames +$lostDelta (total=${stats.lostPictures})",
            )
        }
        val corruptedDelta = stats.demuxCorrupted - previous.demuxCorrupted
        if (corruptedDelta > 0) {
            warnThrottled(
                "demuxCorrupted",
                "demux corrupted blocks +$corruptedDelta (total=${stats.demuxCorrupted})",
            )
        }
        val readDelta = stats.readBytes - previous.readBytes
        if (playing && positionMoved && readDelta <= 0) {
            warnThrottled(
                "noRead",
                "no network read progress while playing (readBytes=${stats.readBytes})",
            )
        }
        val videoDelta = stats.decodedVideo - previous.decodedVideo
        val displayDelta = stats.displayedPictures - previous.displayedPictures
        if (playing && positionMoved && videoDelta <= 0) {
            warnThrottled(
                "noDecodedVideo",
                "no decoded video frames while position advances " +
                    "(decoded=${stats.decodedVideo} displayed=${stats.displayedPictures})",
            )
        }
        if (videoDelta > 0 && displayDelta <= 0) {
            warnThrottled(
                "notDisplayed",
                "decoded $videoDelta video frame(s) but none displayed " +
                    "(displayed=${stats.displayedPictures} lost=${stats.lostPictures})",
            )
        }
        if (playing &&
            positionMoved &&
            stats.playedAbuffers == 0 &&
            runCatching { player.audioTracksCount }.getOrDefault(0) > 0
        ) {
            warnThrottled(
                "noAudio",
                "no audio buffers played yet (audioTracks=" +
                    "${runCatching { player.audioTracksCount }.getOrDefault(0)})",
            )
        }
        if (now - mediaOpenedAt > 30_000L && stats.decodedVideo == 0 && stats.displayedPictures == 0) {
            warnThrottled(
                "noVideoStats",
                "no video frames decoded after ${now - mediaOpenedAt}ms " +
                    "(audioOnly=${stats.decodedAudio > 0})",
            )
        }
    }

    /** 记录所有媒体轨道和播放器轨道选择。 */
    private fun logTrackSnapshot(stage: String) {
        val playerVideo = runCatching { player.videoTracksCount }.getOrDefault(-1)
        val playerAudio = runCatching { player.audioTracksCount }.getOrDefault(-1)
        val playerSpu = runCatching { player.spuTracksCount }.getOrDefault(-1)
        val videoTracks = runCatching {
            player.getVideoTracks()?.joinToString(",") { "${it.id}:${it.name}" }.orEmpty()
        }.getOrDefault("")
        val audioTracks = runCatching {
            player.getAudioTracks()?.joinToString(",") { "${it.id}:${it.name}" }.orEmpty()
        }.getOrDefault("")
        val media = currentMedia
        val parsed = runCatching { media?.isParsed }.getOrNull()
        val trackCount = runCatching { media?.trackCount }.getOrNull() ?: -1
        LogHub.i(
            TAG,
            "tracks stage=$stage playerVideo=$playerVideo playerAudio=$playerAudio " +
                "playerSpu=$playerSpu mediaParsed=$parsed mediaTracks=$trackCount",
        )
        if (videoTracks.isNotBlank()) LogHub.i(TAG, "  playerVideoTracks=$videoTracks")
        if (audioTracks.isNotBlank()) LogHub.i(TAG, "  playerAudioTracks=$audioTracks")
        if (media != null && trackCount > 0) {
            val count = trackCount.coerceAtMost(MAX_TRACKS)
            for (index in 0 until count) {
                val track = runCatching { media.getTrack(index) }.getOrNull() ?: continue
                val fields = trackFields(track)
                LogHub.i(
                    TAG,
                    "  mediaTrack[$index] " +
                        fields.entries.joinToString(" ") { "${it.key}=${it.value}" },
                )
                val trackKey = listOf(
                    track.type,
                    track.id,
                    track.codec.orEmpty(),
                    track.widthOrZero(),
                    track.heightOrZero(),
                    track.channelsOrZero(),
                    track.rateOrZero(),
                ).joinToString("|")
                if (loggedMediaTracks.add(trackKey)) {
                    PlaybackDiagnostics.track(trackTypeName(track.type), fields)
                }
            }
            if (trackCount > count) {
                LogHub.i(TAG, "  mediaTracks truncated at $count of $trackCount")
            }
        }
    }

    /** 把一条媒体轨道转换为诊断字段。 */
    private fun trackFields(track: IMedia.Track): Map<String, String> {
        val fields = linkedMapOf(
            "source" to "media",
            "type" to trackTypeName(track.type),
            "codec" to track.codec.orEmpty(),
            "originalCodec" to track.originalCodec.orEmpty(),
            "id" to track.id.toString(),
            "profile" to track.profile.toString(),
            "level" to track.level.toString(),
            "bitrate" to track.bitrate.toString(),
            "language" to track.language.orEmpty(),
            "description" to track.description.orEmpty(),
        )
        when (track) {
            is IMedia.VideoTrack -> {
                fields["width"] = track.width.toString()
                fields["height"] = track.height.toString()
                fields["sar"] = "${track.sarNum}:${track.sarDen}"
                fields["fps"] = "${track.frameRateNum}/${track.frameRateDen}"
                fields["orientation"] = track.orientation.toString()
                fields["projection"] = track.projection.toString()
            }
            is IMedia.AudioTrack -> {
                fields["channels"] = track.channels.toString()
                fields["sampleRate"] = track.rate.toString()
            }
        }
        return fields
    }

    /** 读取视频轨道宽度，非视频轨道返回 0。 */
    private fun IMedia.Track.widthOrZero(): Int =
        (this as? IMedia.VideoTrack)?.width ?: 0

    /** 读取视频轨道高度，非视频轨道返回 0。 */
    private fun IMedia.Track.heightOrZero(): Int =
        (this as? IMedia.VideoTrack)?.height ?: 0

    /** 读取音频轨道声道数，非音频轨道返回 0。 */
    private fun IMedia.Track.channelsOrZero(): Int =
        (this as? IMedia.AudioTrack)?.channels ?: 0

    /** 读取音频轨道采样率，非音频轨道返回 0。 */
    private fun IMedia.Track.rateOrZero(): Int =
        (this as? IMedia.AudioTrack)?.rate ?: 0

    /** 记录失败时的完整状态并写入失败诊断。 */
    private fun logFailureContext(stage: String) {
        val snapshot = failureSnapshot()
        LogHub.e(TAG, "failure stage=$stage $snapshot")
        PlaybackDiagnostics.event("FailureContext", "stage=$stage")
        logTrackSnapshot("failure-$stage")
        sampleStats(force = true)
        logNativeLogTail("failure-$stage", 40)
        PlaybackDiagnostics.fail(
            code = "VLC_ENCOUNTERED_ERROR",
            message = "libVLC 播放过程中报错",
            detail = "stage=$stage $snapshot",
        )
    }

    /** 输出 libVLC 原生日志尾部。 */
    private fun logNativeLogTail(stage: String, maxLines: Int) {
        val tail = PlaybackDiagnostics.vlcLogTail(maxLines)
        if (tail.isNotBlank()) {
            LogHub.i(TAG, "native file log tail stage=$stage lines=${tail.lineSequence().count()}")
            tail.lineSequence().forEach { line -> LogHub.i("VlcNative", line) }
            return
        }
        LogHub.w(
            TAG,
            "native file log unavailable stage=$stage " +
                "bytes=${PlaybackDiagnostics.vlcLogBytes()}; reading logcat",
        )
        captureNativeLogAsync(stage, maxLines)
    }

    /** 在后台读取 logcat 中的 libVLC 原生日志，避免阻塞播放线程。 */
    private fun captureNativeLogAsync(stage: String, maxLines: Int) {
        if (nativeCaptureRunning) return
        nativeCaptureRunning = true
        Thread {
            try {
                val tail = NativeLogcat.tail(maxLines, force = true)
                if (tail.isBlank()) {
                    LogHub.w(TAG, "native logcat unavailable or empty stage=$stage")
                    return@Thread
                }
                LogHub.i(
                    TAG,
                    "native logcat tail stage=$stage lines=${tail.lineSequence().count()}",
                )
                PlaybackDiagnostics.recordNativeLog(stage, tail)
                tail.lineSequence().forEach { line -> LogHub.i("VlcNative", line) }
            } finally {
                nativeCaptureRunning = false
            }
        }.apply {
            name = "vlc-native-log"
            isDaemon = true
        }.start()
    }

    /** 生成当前播放器和媒体的单行状态快照。 */
    private fun failureSnapshot(): String = buildString {
        append("session=").append(if (sessionActive) "active" else "idle")
        append(" ").append(mediaSummary())
        append(" playerState=").append(playerStateName())
        append(" playing=").append(runCatching { player.isPlaying }.getOrDefault(false))
        append(" time=").append(runCatching { player.time }.getOrDefault(-1L))
        append(" length=").append(runCatching { player.length }.getOrDefault(-1L))
        append(" seekable=").append(runCatching { player.isSeekable }.getOrDefault(false))
        append(" volume=").append(runCatching { player.volume }.getOrDefault(-1))
        append(" videoTracks=").append(runCatching { player.videoTracksCount }.getOrDefault(-1))
        append(" audioTracks=").append(runCatching { player.audioTracksCount }.getOrDefault(-1))
        append(" spuTracks=").append(runCatching { player.spuTracksCount }.getOrDefault(-1))
        append(" viewsAttached=").append(viewsAttached)
        append(" view=").append(viewSize())
        append(" openingSeen=").append(openingSeen)
        append(" playingSeen=").append(playingSeen)
        append(" voutSeen=").append(voutSeen)
        append(" openingMs=").append(if (openingAt > 0L) openingAt - mediaOpenedAt else -1L)
        append(" playingMs=").append(if (playingAt > 0L) playingAt - mediaOpenedAt else -1L)
        append(" voutMs=").append(if (voutAt > 0L) voutAt - mediaOpenedAt else -1L)
        append(" lastStats=").append(lastStats?.toString() ?: "(none)")
    }

    /** 生成当前媒体的单行摘要。 */
    private fun mediaSummary(): String {
        val media = currentMedia ?: return "media=(none)"
        val uri = runCatching { media.uri?.toString() }.getOrNull()
        return "media=(uri=${LogRedaction.url(uri)} " +
            "parsed=${runCatching { media.isParsed }.getOrElse { "?" }} " +
            "state=${runCatching { media.state }.getOrElse { -1 }} " +
            "tracks=${runCatching { media.trackCount }.getOrElse { -1 }} " +
            "duration=${runCatching { media.duration }.getOrElse { -1L }}ms)"
    }

    /** 返回视频布局尺寸文本。 */
    private fun viewSize(): String = "${videoLayout.width}x${videoLayout.height}"

    /** 返回当前播放器状态名称。 */
    private fun playerStateName(): String = runCatching {
        when (player.playerState) {
            0 -> "NothingSpecial"
            1 -> "Opening"
            2 -> "Buffering"
            3 -> "Playing"
            4 -> "Paused"
            5 -> "Stopped"
            6 -> "Ended"
            7 -> "Error"
            else -> "Unknown(${player.playerState})"
        }
    }.getOrElse { "Unavailable(${LogRedaction.reason(it)})" }

    /** 返回事件名称。 */
    private fun eventName(type: Int): String = when (type) {
        MediaPlayer.Event.MediaChanged -> "MediaChanged"
        MediaPlayer.Event.Opening -> "Opening"
        MediaPlayer.Event.Buffering -> "Buffering"
        MediaPlayer.Event.Playing -> "Playing"
        MediaPlayer.Event.Paused -> "Paused"
        MediaPlayer.Event.Stopped -> "Stopped"
        MediaPlayer.Event.EndReached -> "EndReached"
        MediaPlayer.Event.EncounteredError -> "EncounteredError"
        MediaPlayer.Event.TimeChanged -> "TimeChanged"
        MediaPlayer.Event.PositionChanged -> "PositionChanged"
        MediaPlayer.Event.SeekableChanged -> "SeekableChanged"
        MediaPlayer.Event.PausableChanged -> "PausableChanged"
        MediaPlayer.Event.LengthChanged -> "LengthChanged"
        MediaPlayer.Event.Vout -> "Vout"
        MediaPlayer.Event.ESAdded -> "ESAdded"
        MediaPlayer.Event.ESDeleted -> "ESDeleted"
        MediaPlayer.Event.ESSelected -> "ESSelected"
        MediaPlayer.Event.RecordChanged -> "RecordChanged"
        else -> "Event$type"
    }

    /** 返回事件详情。 */
    private fun eventDetail(event: MediaPlayer.Event): String = runCatching {
        when (event.type) {
            MediaPlayer.Event.TimeChanged -> "time=${event.timeChanged}ms"
            MediaPlayer.Event.PositionChanged ->
                "position=${String.format(Locale.US, "%.4f", event.positionChanged)}"
            MediaPlayer.Event.LengthChanged -> "length=${event.lengthChanged}ms"
            MediaPlayer.Event.SeekableChanged -> "seekable=${event.seekable}"
            MediaPlayer.Event.PausableChanged -> "pausable=${event.pausable}"
            MediaPlayer.Event.Vout -> "count=${event.voutCount}"
            MediaPlayer.Event.ESAdded,
            MediaPlayer.Event.ESDeleted,
            MediaPlayer.Event.ESSelected -> "trackType=${trackTypeName(event.esChangedType)} trackId=${event.esChangedID}"
            MediaPlayer.Event.RecordChanged ->
                "recording=${event.recording} path=${event.recordPath.orEmpty()}"
            else -> ""
        }
    }.getOrElse { "detail unavailable: ${LogRedaction.reason(it)}" }

    /** 将轨道类型转换为可读名称。 */
    private fun trackTypeName(type: Int): String = when (type) {
        IMedia.Track.Type.Video -> "video"
        IMedia.Track.Type.Audio -> "audio"
        IMedia.Track.Type.Text -> "text"
        else -> "unknown($type)"
    }

    /** 将编码标识转换为可读名称。 */
    private fun codecName(codecid: Int): String = when (codecid) {
        7 -> "H.264"
        12 -> "HEVC"
        13 -> "AV1"
        else -> "codec$codecid"
    }

    /** 重置一次新播放所需的监控状态。 */
    private fun resetSessionState() {
        loggedMediaTracks.clear()
        openingSeen = false
        playingSeen = false
        voutSeen = false
        firstPlayingLogged = false
        openingAt = 0L
        playingAt = 0L
        voutAt = 0L
        lastProgressAt = SystemClock.elapsedRealtime()
        lastPositionMs = -1L
        lastStatsAt = 0L
        lastTimeLogAt = 0L
        lastBufferingBucket = -1
        vlcLogWarningLogged = false
        statsUnavailableLogged = false
        statsFirstLogged = false
        previousStats = null
        lastStats = null
        warnedOnce.clear()
        warnedAt.clear()
    }

    /** 启动周期监控。 */
    private fun startMonitor() {
        handler.removeCallbacks(monitor)
        sessionActive = true
        handler.postDelayed(monitor, MONITOR_INTERVAL_MS)
    }

    /** 停止周期监控。 */
    private fun stopMonitor() {
        sessionActive = false
        handler.removeCallbacks(monitor)
    }

    /** 提示一次同类异常。 */
    private fun warnOnce(key: String, message: String) {
        if (!warnedOnce.add(key)) return
        LogHub.w(TAG, message)
        PlaybackDiagnostics.event("Anomaly", "$key $message")
    }

    /** 限频提示同类异常。 */
    private fun warnThrottled(key: String, message: String) {
        val now = SystemClock.elapsedRealtime()
        val last = warnedAt[key] ?: 0L
        if (now - last < ANOMALY_INTERVAL_MS) return
        warnedAt[key] = now
        LogHub.w(TAG, message)
        PlaybackDiagnostics.event("Anomaly", "$key $message")
    }

    private companion object {
        /** 日志标签。 */
        const val TAG = "VlcPlayer"
        /** 周期监控间隔。 */
        const val MONITOR_INTERVAL_MS = 1_000L
        /** 播放统计采样间隔。 */
        const val STATS_INTERVAL_MS = 5_000L
        /** 播放位置时间线日志间隔。 */
        const val TIME_LOG_INTERVAL_MS = 30_000L
        /** 同类异常提示的最小间隔。 */
        const val ANOMALY_INTERVAL_MS = 15_000L
        /** 未收到 Opening 事件的告警阈值。 */
        const val NO_OPENING_MS = 6_000L
        /** 未收到 Playing 事件的告警阈值。 */
        const val NO_PLAYING_MS = 20_000L
        /** 未收到视频输出事件的告警阈值。 */
        const val NO_VOUT_MS = 15_000L
        /** 播放进度停滞的告警阈值。 */
        const val STALL_MS = 12_000L
        /** 原生日志为空的检查阈值。 */
        const val NO_NATIVE_LOG_MS = 15_000L
        /** 切换媒体时忽略旧媒体 Stopped 事件的保护窗口。 */
        const val MEDIA_SWITCH_GRACE_MS = 1_500L
        /** 单次轨道快照最多读取的媒体轨道数。 */
        const val MAX_TRACKS = 16
    }
}
