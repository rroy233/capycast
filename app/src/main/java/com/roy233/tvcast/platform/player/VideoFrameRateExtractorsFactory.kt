package com.roy233.tvcast.platform.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingExtractor
import androidx.media3.extractor.ForwardingExtractorOutput
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.TrackOutput

/** 为提取器未声明帧率的视频轨补充播放源元数据，其他轨道与样本保持原样。 */
@UnstableApi
internal class VideoFrameRateExtractorsFactory(
    private val delegate: ExtractorsFactory,
    private val frameRate: Float,
) : ExtractorsFactory {
    override fun createExtractors(): Array<Extractor> = wrap(delegate.createExtractors())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> = wrap(delegate.createExtractors(uri, responseHeaders))

    private fun wrap(extractors: Array<Extractor>): Array<Extractor> =
        Array(extractors.size) { index ->
            object : ForwardingExtractor(extractors[index]) {
                override fun init(output: ExtractorOutput) {
                    super.init(
                        object : ForwardingExtractorOutput(output) {
                            override fun track(id: Int, type: Int): TrackOutput {
                                val track = super.track(id, type)
                                if (type != C.TRACK_TYPE_VIDEO) return track
                                return object : ForwardingTrackOutput(track) {
                                    override fun format(format: Format) {
                                        val hasFrameRate =
                                            format.frameRate.isFinite() && format.frameRate > 0f
                                        val canSupplyFrameRate =
                                            frameRate.isFinite() && frameRate > 0f
                                        super.format(
                                            if (!hasFrameRate && canSupplyFrameRate)
                                                format.buildUpon().setFrameRate(frameRate).build()
                                            else format,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
            }
        }
}
