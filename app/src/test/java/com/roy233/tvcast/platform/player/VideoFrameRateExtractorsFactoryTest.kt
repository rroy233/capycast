package com.roy233.tvcast.platform.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ForwardingTrackOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoFrameRateExtractorsFactoryTest {
    @Test
    fun `extractor output adds missing video frame rate without changing other metadata`() {
        val fixture = Fixture(59.94f)
        val original = videoFormat()

        fixture.extractor.output.track(1, C.TRACK_TYPE_VIDEO).format(original)

        assertEquals(original.buildUpon().setFrameRate(59.94f).build(), fixture.videoFormat)
    }

    @Test
    fun `extracted frame rate and audio formats retain precedence`() {
        val fixture = Fixture(60f)
        val video = videoFormat().buildUpon().setFrameRate(24f).build()
        val audio = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).build()

        fixture.extractor.output.track(1, C.TRACK_TYPE_VIDEO).format(video)
        fixture.extractor.output.track(2, C.TRACK_TYPE_AUDIO).format(audio)

        assertSame(video, fixture.videoFormat)
        assertSame(audio, fixture.audioFormat)
    }

    @Test
    fun `invalid source rates retain unknown frame rate and samples are forwarded`() {
        listOf(0f, Float.NaN, Float.POSITIVE_INFINITY).forEach { rate ->
            val fixture = Fixture(rate)
            val video = videoFormat()
            val output = fixture.extractor.output.track(1, C.TRACK_TYPE_VIDEO)

            output.format(video)
            output.sampleMetadata(1234L, C.BUFFER_FLAG_KEY_FRAME, 0, 0, null)

            assertSame(video, fixture.videoFormat)
            assertEquals(1234L, fixture.sampleTimeUs)
        }
    }

    @Test
    fun `extractor lifecycle remains delegated`() {
        val fixture = Fixture(30f)
        fixture.wrapped.seek(100, 200)
        fixture.wrapped.release()
        assertEquals(100L to 200L, fixture.extractor.seek)
        assertTrue(fixture.extractor.released)
        assertSame(fixture.extractor, fixture.wrapped.underlyingImplementation)
    }

    private fun videoFormat(): Format =
        Format.Builder()
            .setSampleMimeType(MimeTypes.VIDEO_H264)
            .setCodecs("avc1.640034")
            .setWidth(3840)
            .setHeight(2160)
            .build()

    private class Fixture(frameRate: Float) {
        val extractor = FakeExtractor()
        var videoFormat: Format? = null
        var audioFormat: Format? = null
        var sampleTimeUs: Long? = null
        val wrapped =
            VideoFrameRateExtractorsFactory(ExtractorsFactory { arrayOf(extractor) }, frameRate)
                .createExtractors()
                .single()

        init {
            wrapped.init(
                object : ExtractorOutput {
                    override fun track(id: Int, type: Int): TrackOutput =
                        object : ForwardingTrackOutput(DiscardingTrackOutput()) {
                            override fun format(format: Format) {
                                if (type == C.TRACK_TYPE_VIDEO) videoFormat = format
                                if (type == C.TRACK_TYPE_AUDIO) audioFormat = format
                            }

                            override fun sampleMetadata(
                                timeUs: Long,
                                flags: Int,
                                size: Int,
                                offset: Int,
                                cryptoData: TrackOutput.CryptoData?,
                            ) {
                                sampleTimeUs = timeUs
                            }
                        }

                    override fun endTracks() = Unit

                    override fun seekMap(seekMap: SeekMap) = Unit
                },
            )
        }
    }

    private class FakeExtractor : Extractor {
        lateinit var output: ExtractorOutput
        var seek: Pair<Long, Long>? = null
        var released = false

        override fun sniff(input: ExtractorInput): Boolean = true

        override fun init(output: ExtractorOutput) {
            this.output = output
        }

        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int =
            Extractor.RESULT_END_OF_INPUT

        override fun seek(position: Long, timeUs: Long) {
            seek = position to timeUs
        }

        override fun release() {
            released = true
        }
    }
}
