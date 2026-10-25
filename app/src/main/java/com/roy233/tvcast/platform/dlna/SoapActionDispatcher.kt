package com.roy233.tvcast.platform.dlna

import com.roy233.tvcast.core.diagnostics.LogHub
import com.roy233.tvcast.core.diagnostics.LogRedaction

/**
 * 分发 DLNA SOAP 动作并构造响应。
 *
 * @param state 当前传输状态。
 * @param listener 接收播放控制事件的渲染器回调。
 */
internal class SoapActionDispatcher(
    private val state: DlnaTransportState,
    private val listener: DlnaRenderer.Listener,
) {

    /** 当前传输状态名称。 */
    fun transportState(): String = state.transport

    /** 获取当前播放位置并限制为非负值。 */
    fun positionMs(): Long {
        val pos = runCatching { listener.positionMs() }.getOrDefault(0L)
        return pos.coerceAtLeast(0L)
    }

    /** 获取播放器时长或 DLNA 状态中的时长。 */
    fun durationMs(): Long {
        val fromPlayer = runCatching { listener.durationMs() }.getOrDefault(0L)
        return if (fromPlayer > 0) fromPlayer else state.durationMs
    }

    /** 获取并校验当前音量值。 */
    fun volume(): Int {
        val v = runCatching { listener.volume() }.getOrDefault(state.volume)
        return if (v in 0..100) v else state.volume
    }

    /** 分发 SOAP 动作并构造对应响应。 */
    fun dispatch(req: SoapRequest): ByteArray {
        val s = state
        return when (req.action) {
            "SetAVTransportURI" -> {
                val uri = req.args["CurrentURI"].orEmpty()
                val meta = req.args["CurrentURIMetaData"].orEmpty()
                val title = DlnaXml.extractTitle(meta)
                val longDesc = DlnaXml.extractLongDescription(meta)
                s.uri = uri
                s.metadata = meta
                s.title = title
                s.durationMs = DlnaTime.parseDidlDuration(meta)
                s.transport = "TRANSITIONING"
                LogHub.i(
                    TAG,
                    "SetAVTransportURI title=${title.take(80)} " +
                        "uri=${LogRedaction.url(uri)} metadata=${meta.length}B " +
                        "longDescription=${longDesc.length}B duration=${s.durationMs}ms",
                )
                if (longDesc.isBlank()) {
                    LogHub.w(TAG, "SetAVTransportURI has no longDescription; cast identity missing")
                }
                runCatching { listener.onCast(uri, meta, title, longDesc) }
                    .onFailure { LogHub.e(TAG, "onCast failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "Play" -> {
                s.transport = "PLAYING"
                LogHub.i(TAG, "Play command transport=${s.transport} title=${s.title.take(60)}")
                runCatching { listener.onPlay() }
                    .onFailure { LogHub.e(TAG, "onPlay failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "Pause" -> {
                s.transport = "PAUSED_PLAYBACK"
                LogHub.i(TAG, "Pause command transport=${s.transport}")
                runCatching { listener.onPause() }
                    .onFailure { LogHub.e(TAG, "onPause failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "Stop" -> {
                s.transport = "STOPPED"
                LogHub.i(TAG, "Stop command transport=${s.transport}")
                runCatching { listener.onStop() }
                    .onFailure { LogHub.e(TAG, "onStop failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "Seek" -> {
                if (!runCatching { listener.isSeekable() }.getOrDefault(false)) {
                    LogHub.w(TAG, "Seek rejected: current media is not seekable")
                    return fault(req.service, 710, "Seek mode is not supported")
                }
                val target = req.args["Target"].orEmpty()
                val ms = DlnaTime.parse(target)
                LogHub.i(TAG, "Seek $target -> ${ms}ms")
                runCatching { listener.onSeek(ms) }
                    .onFailure { LogHub.e(TAG, "onSeek failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "GetTransportInfo" -> soapOk(
                req.service, req.action,
                listOf(
                    "CurrentTransportState" to transportState(),
                    "CurrentTransportStatus" to "OK",
                    "CurrentSpeed" to "1",
                )
            )
            "GetPositionInfo" -> {
                val pos = positionMs()
                val dur = durationMs()
                soapOk(
                    req.service, req.action,
                    listOf(
                        "Track" to "1",
                        "TrackDuration" to DlnaTime.format(dur),
                        "TrackMetaData" to s.metadata,
                        "TrackURI" to s.uri,
                        "RelTime" to DlnaTime.format(pos),
                        "AbsTime" to DlnaTime.format(pos),
                        "RelCount" to "2147483647",
                        "AbsCount" to "2147483647",
                    )
                )
            }
            "GetMediaInfo" -> soapOk(
                req.service, req.action,
                listOf(
                    "NrTracks" to if (s.uri.isEmpty()) "0" else "1",
                    "MediaDuration" to DlnaTime.format(durationMs()),
                    "CurrentURI" to s.uri,
                    "CurrentURIMetaData" to s.metadata,
                    "NextURI" to "",
                    "NextURIMetaData" to "",
                    "PlayMedium" to "NETWORK",
                    "RecordMedium" to "NOT_IMPLEMENTED",
                    "WriteStatus" to "NOT_IMPLEMENTED",
                )
            )
            "GetTransportSettings" -> soapOk(
                req.service, req.action,
                listOf("PlayMode" to "NORMAL", "RecQualityMode" to "NOT_IMPLEMENTED")
            )
            "GetDeviceCapabilities" -> soapOk(
                req.service, req.action,
                listOf(
                    "PlayMedia" to "NETWORK",
                    "RecMedia" to "NOT_IMPLEMENTED",
                    "RecQualityModes" to "NOT_IMPLEMENTED",
                )
            )
            "GetCurrentTransportActions" -> soapOk(
                req.service,
                req.action,
                listOf(
                    "Actions" to if (runCatching { listener.isSeekable() }.getOrDefault(false)) {
                        "Play,Pause,Stop,Seek"
                    } else {
                        "Play,Pause,Stop"
                    },
                ),
            )
            "SetPlayMode", "Next", "Previous" -> soapOk(req.service, req.action)
            "GetVolume" -> soapOk(
                req.service, req.action,
                listOf("CurrentVolume" to volume().toString())
            )
            "SetVolume" -> {
                val v = req.args["DesiredVolume"]?.toIntOrNull()?.coerceIn(0, 100) ?: 50
                s.volume = v
                LogHub.i(TAG, "SetVolume command value=$v")
                runCatching { listener.onSetVolume(v) }
                    .onFailure { LogHub.e(TAG, "onSetVolume failed", it, stack = true) }
                soapOk(req.service, req.action)
            }
            "GetMute" -> soapOk(
                req.service, req.action,
                listOf("CurrentMute" to if (s.mute) "1" else "0")
            )
            "SetMute" -> {
                s.mute = req.args["DesiredMute"] in listOf("1", "true", "True")
                soapOk(req.service, req.action)
            }
            "GetVolumeDB" -> soapOk(req.service, req.action, listOf("CurrentVolume" to "0"))
            "GetVolumeDBRange" -> soapOk(
                req.service, req.action,
                listOf("MinValue" to "-32767", "MaxValue" to "32767")
            )
            "ListPresets" -> soapOk(
                req.service, req.action,
                listOf("CurrentPresetNameList" to "FactoryDefaults")
            )
            "SelectPreset" -> soapOk(req.service, req.action)
            "GetProtocolInfo" -> soapOk(
                req.service, req.action,
                listOf(
                    "Source" to "",
                    "Sink" to "http-get:*:video/mp4:*,http-get:*:video/x-matroska:*," +
                        "http-get:*:application/vnd.apple.mpegurl:*," +
                        "http-get:*:application/dash+xml:*," +
                        "http-get:*:audio/mp4:*,http-get:*:audio/mpeg:*",
                )
            )
            "GetCurrentConnectionIDs" -> soapOk(
                req.service, req.action, listOf("ConnectionIDs" to "0")
            )
            "GetCurrentConnectionInfo" -> soapOk(
                req.service, req.action,
                listOf(
                    "RcsID" to "0",
                    "AVTransportID" to "0",
                    "ProtocolInfo" to "",
                    "PeerConnectionManager" to "",
                    "PeerConnectionID" to "-1",
                    "Direction" to "Input",
                    "Status" to "OK",
                )
            )
            else -> {
                LogHub.i(TAG, "unhandled SOAP action=${req.action}; replying empty")
                soapOk(req.service, req.action)
            }
        }
    }

    /** 根据 SOAP 动作选择对应的 UPnP 服务。 */
    fun serviceFor(action: String): String = when (action) {
        "GetVolume", "SetVolume", "GetMute", "SetMute", "GetVolumeDB",
        "GetVolumeDBRange", "ListPresets", "SelectPreset" -> RCS
        "GetProtocolInfo", "GetCurrentConnectionIDs", "GetCurrentConnectionInfo" -> CMS
        else -> AVT
    }

    /** 构造成功的 SOAP 动作响应。 */
    fun soapOk(
        service: String,
        action: String,
        args: List<Pair<String, String>> = emptyList(),
    ): ByteArray {
        val body = args.joinToString("") { (k, v) ->
            "<$k>${DlnaXml.escape(v)}</$k>"
        }
        val inner = "<u:${action}Response xmlns:u=\"${DlnaXml.escape(service)}\">$body</u:${action}Response>"
        return soapEnvelope(inner)
    }

    /** 构造 SOAP 错误响应。 */
    fun fault(service: String, code: Int, description: String): ByteArray {
        val inner = "<s:Fault><faultcode>s:Client</faultcode>" +
            "<faultstring>UPnPError</faultstring><detail>" +
            "<UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\">" +
            "<errorCode>$code</errorCode>" +
            "<errorDescription>${DlnaXml.escape(description)}</errorDescription>" +
            "</UPnPError></detail></s:Fault>"
        return soapEnvelope(inner)
    }

    /** 将 SOAP 内容包装为完整 SOAP 信封。 */
    fun soapEnvelope(inner: String): ByteArray {
        val xml = "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<s:Envelope xmlns:s=\"$SOAP_ENV\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">" +
            "<s:Body>$inner</s:Body></s:Envelope>"
        return xml.toByteArray(Charsets.UTF_8)
    }
    private companion object {
        /** SOAP 1.1 命名空间。 */
        const val SOAP_ENV = "http://schemas.xmlsoap.org/soap/envelope/"
        /** AVTransport 服务类型。 */
        const val AVT = "urn:schemas-upnp-org:service:AVTransport:1"
        /** RenderingControl 服务类型。 */
        const val RCS = "urn:schemas-upnp-org:service:RenderingControl:1"
        /** ConnectionManager 服务类型。 */
        const val CMS = "urn:schemas-upnp-org:service:ConnectionManager:1"
        /** 日志标签。 */
        const val TAG = "DLNA"
    }
}
