package com.roy233.tvcast.platform.player

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.HWDecoderUtil
import org.videolan.libvlc.util.VLCUtil
import org.videolan.libvlc.util.VLCVideoLayout
import java.io.File

/**
 * 负责 libVLC 启动参数、运行时能力和显示环境探测。
 *
 * 这些信息在每次应用启动时记录一次，用于区分“设备硬件/系统能力不足”和
 * “单个视频码流有问题”两类故障。
 */
internal object VlcRuntimeLogger {

    /**
     * 构造 libVLC 启动参数，并启用原生日志文件。
     *
     * @param nativeLogFile libVLC 原生日志文件。
     * @return libVLC 启动参数。
     */
    fun options(nativeLogFile: File): ArrayList<String> = arrayListOf(
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

    /**
     * 记录 libVLC 版本、硬件解码器、CPU 和显示环境。
     *
     * @param context 应用上下文。
     * @param player 播放器实例，用于读取直通能力。
     * @param view 视频布局，用于记录视图尺寸。
     * @param nativeLogFile libVLC 原生日志文件。
     * @param options 实际传给 libVLC 的启动参数。
     */
    fun logStartup(
        context: Context,
        player: MediaPlayer,
        view: VLCVideoLayout,
        nativeLogFile: File,
        options: List<String>,
    ) {
        val version = runCatching { LibVLC.version() }.getOrNull() ?: "(unavailable)"
        val changeset = runCatching { LibVLC.changeset() }.getOrNull() ?: "(unavailable)"
        val compiler = runCatching { LibVLC.compiler() }.getOrNull() ?: "(unavailable)"
        val decoder = runCatching { HWDecoderUtil.getDecoderFromDevice()?.name }
            .getOrNull() ?: "(unavailable)"
        val audioOutput = runCatching { HWDecoderUtil.getAudioOutputFromDevice()?.name }
            .getOrNull() ?: "(unavailable)"
        val compatible = runCatching { VLCUtil.hasCompatibleCPU(context) }
            .getOrNull()?.toString() ?: "(unavailable)"
        val passthrough = runCatching { player.canDoPassthrough() }.getOrDefault(false)
        val machine = runCatching { VLCUtil.getMachineSpecs() }.getOrNull()
        val metrics = DisplayMetrics()
        val display = runCatching {
            (context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager)
                ?.defaultDisplay
        }.getOrNull()
        if (display != null) {
            runCatching { display.getRealMetrics(metrics) }
        }
        LogHub.i(
            VLC_PLAYER_TAG,
            "libvlc version=$version changeset=$changeset compiler=$compiler",
        )
        LogHub.i(
            VLC_PLAYER_TAG,
            "runtime hwDecoder=$decoder audioOutput=$audioOutput " +
                "compatibleCpu=$compatible passthrough=$passthrough",
        )
        LogHub.i(
            VLC_PLAYER_TAG,
            "machine ${machineSummary(machine)} view=${view.width}x${view.height} " +
                "display=${metrics.widthPixels}x${metrics.heightPixels}@${display?.refreshRate}",
        )
        LogHub.i(
            VLC_PLAYER_TAG,
            "native log file=${nativeLogFile.absolutePath} bytes=${nativeLogFile.length()}",
        )
        PlaybackDiagnostics.runtime("vlc.version", version)
        PlaybackDiagnostics.runtime("vlc.changeset", changeset)
        PlaybackDiagnostics.runtime("vlc.compiler", compiler)
        PlaybackDiagnostics.runtime("vlc.hardwareDecoder", decoder)
        PlaybackDiagnostics.runtime("vlc.audioOutput", audioOutput)
        PlaybackDiagnostics.runtime("vlc.compatibleCpu", compatible)
        PlaybackDiagnostics.runtime("vlc.passthrough", passthrough.toString())
        PlaybackDiagnostics.runtime("vlc.machine", machineSummary(machine))
        PlaybackDiagnostics.runtime(
            "display",
            "${metrics.widthPixels}x${metrics.heightPixels}@${display?.refreshRate}",
        )
        PlaybackDiagnostics.runtime("mediaOptions", options.joinToString(" "))
    }

    /**
     * 生成 CPU 能力摘要。
     *
     * @param specs libVLC 读取到的机器信息。
     * @return 单行 CPU 能力摘要。
     */
    private fun machineSummary(specs: VLCUtil.MachineSpecs?): String {
        if (specs == null) return "unavailable"
        return "cores=${specs.processors} freq=${specs.frequency} 64bit=${specs.is64bits} " +
            "neon=${specs.hasNeon} armv7=${specs.hasArmV7} x86=${specs.hasX86}"
    }
}
