package com.roy233.tvcast.feature.feedback

import android.graphics.Bitmap
import com.roy233.tvcast.core.diagnostics.SupportLinks

/** 用户选择的反馈渠道。 */
enum class FeedbackChannel(
    val label: String,
    val prompt: String,
    val qrPayload: String,
) {
    GITHUB(
        label = "GitHub",
        prompt = "遇到问题或有改进建议，欢迎在 GitHub 提交 Issue。",
        qrPayload = SupportLinks.GITHUB_ISSUES_URL,
    ),
    EMAIL(
        label = "Email",
        prompt = "遇到问题或有改进建议，欢迎通过 Email 联系开发者。",
        qrPayload = "mailto:${SupportLinks.FEEDBACK_EMAIL}",
    ),
}

/** 反馈功能在界面上的瞬时投影；二维码仅在当前会话内保留。 */
data class FeedbackUiState(
    val visible: Boolean = false,
    val channel: FeedbackChannel = FeedbackChannel.GITHUB,
    val qrCodes: Map<FeedbackChannel, Bitmap> = emptyMap(),
    val isGenerating: Boolean = false,
    val errorMessage: String? = null,
    val versionLabel: String = "",
) {
    /** 当前渠道的二维码位图；若未生成成功则为 null。 */
    val qrCode: Bitmap?
        get() = qrCodes[channel]
}

/** 生成反馈时需要的当前设备信息。 */
data class FeedbackMetadata(
    val versionLabel: String,
)

/** 为反馈会话读取即时设备信息的窄端口。 */
fun interface FeedbackMetadataProvider {
    fun snapshot(): FeedbackMetadata
}

/** 将字符串编码为临时二维码位图的窄端口。 */
fun interface FeedbackQrCodeEncoder {
    fun generate(payload: String): Bitmap?
}
