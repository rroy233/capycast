package com.roy233.tvcast.feature.feedback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 协调反馈弹窗的临时二维码会话。
 *
 * 二维码在用户打开弹窗或切换渠道时按需生成并在当前会话内缓存；关闭、替换或销毁时都会取消任务并移除位图引用。
 */
class FeedbackCoordinator(
    private val scope: CoroutineScope,
    private val qrCodeEncoder: FeedbackQrCodeEncoder,
    private val metadataProvider: FeedbackMetadataProvider,
    private val readState: () -> FeedbackUiState,
    private val writeState: (FeedbackUiState) -> Unit,
    private val generationDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private var generationJob: Job? = null
    private var generationRevision = 0L

    /** 打开反馈会话，默认展示 GitHub Issue 二维码。 */
    fun open() = showChannel(FeedbackChannel.GITHUB)

    /** 切换渠道并在已有缓存时即时呈现二维码。 */
    fun selectChannel(channel: FeedbackChannel) {
        val current = readState()
        if (!current.visible || current.channel == channel) return
        if (current.qrCodes.containsKey(channel)) {
            generationJob?.cancel()
            generationJob = null
            writeState(
                current.copy(
                    channel = channel,
                    isGenerating = false,
                    errorMessage = null,
                ),
            )
            return
        }
        showChannel(channel)
    }

    /** 关闭反馈会话并丢弃当前二维码缓存。 */
    fun close() {
        generationRevision++
        generationJob?.cancel()
        generationJob = null
        writeState(FeedbackUiState())
    }

    /** 释放当前异步生成任务；由 Activity 销毁路径调用。 */
    fun release() = close()

    private fun showChannel(channel: FeedbackChannel) {
        generationJob?.cancel()
        val revision = ++generationRevision
        val current = readState()
        val metadata = if (current.visible) null else metadataProvider.snapshot()
        writeState(
            current.copy(
                visible = true,
                channel = channel,
                isGenerating = true,
                errorMessage = null,
                versionLabel = metadata?.versionLabel ?: current.versionLabel,
            ),
        )
        generationJob = scope.launch {
            val bitmap =
                withContext(generationDispatcher) {
                    qrCodeEncoder.generate(channel.qrPayload)
                }
            if (!isActive || revision != generationRevision) return@launch
            val latest = readState()
            if (!latest.visible || latest.channel != channel) return@launch
            writeState(
                latest.copy(
                    qrCodes =
                        if (bitmap != null) latest.qrCodes + (channel to bitmap)
                        else latest.qrCodes,
                    isGenerating = false,
                    errorMessage = if (bitmap == null) QR_GENERATION_FAILED_MESSAGE else null,
                ),
            )
        }
    }

    private companion object {
        const val QR_GENERATION_FAILED_MESSAGE = "二维码生成失败，请重试。"
    }
}
