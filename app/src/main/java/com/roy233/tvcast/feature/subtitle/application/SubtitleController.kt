package com.roy233.tvcast.feature.subtitle.application

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.PlaybackDiagnostics
import com.roy233.tvcast.feature.subtitle.domain.SubtitleContent
import com.roy233.tvcast.feature.subtitle.domain.SubtitleCue
import com.roy233.tvcast.feature.subtitle.domain.SubtitleCueLookup
import com.roy233.tvcast.feature.subtitle.domain.SubtitleOrigin
import com.roy233.tvcast.feature.subtitle.domain.SubtitleRenderer
import com.roy233.tvcast.feature.subtitle.domain.SubtitleRepository
import com.roy233.tvcast.feature.subtitle.domain.SubtitleSelection
import com.roy233.tvcast.feature.subtitle.domain.SubtitleSettings
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrack
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrackKey
import com.roy233.tvcast.feature.subtitle.domain.SubtitleTrackOrder
import com.roy233.tvcast.feature.subtitle.domain.SubtitleUnavailableException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 字幕会话控制器。
 *
 * 数据归属由内容身份与会话代次决定：只有当前会话能把轨道或正文提交给渲染器，取消作业只用于 节省资源。所有公开方法都应在主线程调用。
 */
class SubtitleController(
    private val scope: CoroutineScope,
    private val renderer: SubtitleRenderer,
    private val repository: SubtitleRepository,
    private val host: SubtitleSessionHost,
) {

    /** 一次点播字幕会话，持有其代次、内容身份与加载作业。 */
    private data class Session(
        val generation: Long,
        val content: SubtitleContent,
        var tracksJob: Job? = null,
        var cuesJob: Job? = null,
    )

    private var activeSession: Session? = null
    private var lastGeneration = 0L
    private var settings = SubtitleSettings()
    private var tracks: List<SubtitleTrack> = emptyList()
    private var selected: SubtitleTrack? = null
    private var availability = SubtitleAvailability.EMPTY
    private var cues: List<SubtitleCue> = emptyList()
    private var lines: List<String> = emptyList()
    private var positionMs = 0L

    /** 当前生效的字幕设置。 */
    fun currentSettings(): SubtitleSettings = settings

    /**
     * 开启或关闭字幕；开启时沿用默认轨道选择规则。
     *
     * @param enabled 目标开关状态。
     */
    fun setEnabled(enabled: Boolean) {
        if (settings.enabled == enabled) return
        applySettings(settings.copy(enabled = enabled))
        host.persistSubtitleSettings(settings)
    }

    /** 应用设置；关闭字幕时立即清屏。 */
    fun applySettings(newSettings: SubtitleSettings) {
        val previous = settings
        settings = newSettings.sanitized()
        if (
            previous.enabled != settings.enabled || previous.autoAiEnabled != settings.autoAiEnabled
        ) {
            if (!settings.enabled) {
                activeSession?.let { session ->
                    session.cuesJob?.cancel()
                    session.cuesJob = null
                }
                selected = null
                cues = emptyList()
                clearLines()
            } else if (selected == null) {
                activeSession?.let { session -> defaultTrack()?.let { selectTrack(session, it) } }
            }
        }
        resolve()
        publish()
    }

    /**
     * 替换为新的字幕会话：取消旧会话的作业，并重新拉取轨道列表。
     *
     * @param content 数据身份。
     */
    fun open(content: SubtitleContent) {
        retireSession()
        val session = Session(generation = ++lastGeneration, content = content)
        activeSession = session
        tracks = emptyList()
        selected = null
        cues = emptyList()
        lines = emptyList()
        positionMs = 0L
        availability = SubtitleAvailability.LOADING
        renderer.clear()
        LogHub.i(TAG, "session open ${label(session)}")
        attributeState()
        publish()
        loadTracks(session)
    }

    /** 关闭会话并清空字幕。 */
    fun close() {
        val session = activeSession
        retireSession()
        tracks = emptyList()
        selected = null
        cues = emptyList()
        clearLines()
        availability = SubtitleAvailability.EMPTY
        attributeState()
        if (session != null) {
            LogHub.i(TAG, "session close ${label(session)}")
            host.publishSubtitleSession(
                SubtitleSessionState(
                    content = session.content,
                    tracks = emptyList(),
                    selected = null,
                    availability = SubtitleAvailability.EMPTY,
                    enabled = settings.enabled,
                ),
            )
        }
    }

    /** 播放器位置采样；由字幕同步驱动器调用。 */
    fun onPlayerTime(positionMs: Long) {
        this.positionMs = positionMs.coerceAtLeast(0L)
        resolve()
    }

    /**
     * 选择字幕轨道。
     *
     * @param key 目标轨道；null 表示关闭字幕。
     */
    fun selectTrack(key: SubtitleTrackKey?) {
        val session = activeSession ?: return
        if (key == null) {
            setEnabled(enabled = false)
            selectTrack(session, null)
            return
        }
        val track = tracks.firstOrNull { it.key == key }
        if (track == null) {
            LogHub.w(TAG, "select unknown track ${label(session)} accepted=false")
            return
        }
        if (track.locked) {
            LogHub.i(TAG, "select locked track ${label(session)} lan=${track.lan} accepted=false")
            return
        }
        setEnabled(enabled = true)
        if (selected?.key == track.key && cues.isNotEmpty()) return
        selectTrack(session, track)
    }

    /** 释放会话资源；Activity 销毁时调用。 */
    fun release() {
        close()
    }

    /** 取消当前会话的作业并撤销会话归属。 */
    private fun retireSession() {
        val session = activeSession ?: return
        activeSession = null
        session.tracksJob?.cancel()
        session.cuesJob?.cancel()
        session.tracksJob = null
        session.cuesJob = null
        LogHub.i(TAG, "session retire ${label(session)}")
    }

    private fun loadTracks(session: Session) {
        session.tracksJob?.cancel()
        val content = session.content
        session.tracksJob = scope.launch {
            val loaded =
                try {
                    repository.tracks(content)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    if (activeSession === session) {
                        availability = SubtitleAvailability.UNAVAILABLE
                        attributeState()
                        PlaybackDiagnostics.attribute("subtitle.error", errorCode(failure))
                        LogHub.w(
                            TAG,
                            "tracks failed ${label(session)} accepted=false " +
                                "code=${errorCode(failure)} detail=${failure.message}",
                        )
                        publish()
                    }
                    return@launch
                }
            if (activeSession !== session) {
                LogHub.i(TAG, "tracks discarded ${label(session)} accepted=false")
                return@launch
            }
            tracks = SubtitleTrackOrder.order(loaded)
            availability =
                if (tracks.isEmpty()) SubtitleAvailability.EMPTY else SubtitleAvailability.READY
            attributeState()
            PlaybackDiagnostics.attribute(
                "subtitle.manual",
                tracks.count { it.origin == SubtitleOrigin.MANUAL }.toString(),
            )
            PlaybackDiagnostics.attribute(
                "subtitle.ai",
                tracks.count { it.origin.isAi }.toString(),
            )
            PlaybackDiagnostics.attribute("subtitle.error", "")
            LogHub.i(
                TAG,
                "tracks accepted ${label(session)} total=${tracks.size} " +
                    "manual=${tracks.count { it.origin == SubtitleOrigin.MANUAL }} " +
                    "ai=${tracks.count { it.origin.isAi }}",
            )
            publish()
            val default = defaultTrack()
            if (default == null) {
                LogHub.i(TAG, "no default track ${label(session)} enabled=${settings.enabled}")
                return@launch
            }
            selectTrack(session, default)
        }
    }

    private fun defaultTrack(): SubtitleTrack? = SubtitleSelection.defaultTrack(tracks, settings)

    private fun selectTrack(session: Session, track: SubtitleTrack?) {
        session.cuesJob?.cancel()
        session.cuesJob = null
        selected = track
        cues = emptyList()
        clearLines()
        attributeState()
        publish()
        if (track == null) return
        val content = session.content
        session.cuesJob = scope.launch {
            val document =
                try {
                    repository.document(content, track)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    LogHub.w(
                        TAG,
                        "body failed ${label(session)} lan=${track.lan} " +
                            "code=${errorCode(failure)} accepted=false detail=${failure.message}",
                    )
                    PlaybackDiagnostics.attribute("subtitle.error", errorCode(failure))
                    return@launch
                }
            if (activeSession !== session || selected?.key != track.key) {
                LogHub.i(
                    TAG,
                    "body discarded ${label(session)} lan=${track.lan} accepted=false",
                )
                return@launch
            }
            cues = document.cues
            PlaybackDiagnostics.attribute("subtitle.cues", cues.size.toString())
            PlaybackDiagnostics.attribute("subtitle.error", "")
            LogHub.i(
                TAG,
                "body accepted ${label(session)} lan=${track.lan} " +
                    "origin=${track.origin.name} cues=${cues.size}",
            )
            resolve()
        }
    }

    /** 解析当前位置的字幕行，只在内容变化时更新渲染器与界面。 */
    private fun resolve() {
        val next =
            if (!settings.enabled || selected == null) {
                emptyList()
            } else {
                SubtitleCueLookup.resolve(cues, (positionMs - settings.offsetMs).coerceAtLeast(0L))
            }
        if (next == lines) return
        lines = next
        if (next.isEmpty()) renderer.clear() else renderer.show(next)
        PlaybackDiagnostics.attribute("subtitle.lines", next.size.toString())
        LogHub.d(
            TAG,
            "lines changed generation=${activeSession?.generation ?: 0} count=${next.size} " +
                "position=${positionMs}ms",
        )
    }

    private fun clearLines() {
        if (lines.isEmpty()) return
        lines = emptyList()
        renderer.clear()
    }

    private fun publish() {
        val session = activeSession ?: return
        host.publishSubtitleSession(
            SubtitleSessionState(
                content = session.content,
                tracks = tracks,
                selected = selected,
                availability = availability,
                enabled = settings.enabled,
            ),
        )
    }

    private fun attributeState() {
        PlaybackDiagnostics.attribute("subtitle.state", availability.name)
        PlaybackDiagnostics.attribute("subtitle.tracks", tracks.size.toString())
        PlaybackDiagnostics.attribute(
            "subtitle.selected",
            selected?.let { "${it.lan}:${it.origin.name}" }.orEmpty(),
        )
        PlaybackDiagnostics.attribute("subtitle.offsetMs", settings.offsetMs.toString())
    }

    private fun errorCode(failure: Exception): String =
        (failure as? SubtitleUnavailableException)?.code ?: "SUBTITLE_UNKNOWN"

    /** 诊断前缀：会话代次与内容身份。 */
    private fun label(session: Session): String =
        "generation=${session.generation} aid=${session.content.aid} " +
            "cid=${session.content.cid} ep=${session.content.epId}"

    private companion object {
        const val TAG = "SubtitleController"
    }
}
