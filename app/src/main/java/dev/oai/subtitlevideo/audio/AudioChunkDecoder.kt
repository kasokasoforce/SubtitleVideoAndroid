package dev.oai.subtitlevideo.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import dev.oai.subtitlevideo.service.ProcessingGuardService
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Decodes the source audio and emits 16 kHz mono float PCM in bounded chunks. */
class AudioChunkDecoder(private val context: Context) {
    companion object {
        const val TARGET_SAMPLE_RATE = 16_000
        private const val DEFAULT_CHUNK_SECONDS = 300
    }

    fun decode(
        uri: Uri,
        chunkSeconds: Int = DEFAULT_CHUNK_SECONDS,
        onProgress: (Int) -> Unit = {},
        onChunk: (samples: FloatArray, chunkStartMs: Long) -> Unit,
    ) {
        ProcessingGuardService.start(context, "音声解析・Whisper文字起こしを実行中")
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val audioTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: error("動画に音声トラックがありません")

            val sourceFormat = extractor.getTrackFormat(audioTrack)
            val mime = sourceFormat.getString(MediaFormat.KEY_MIME) ?: error("音声MIMEを取得できません")
            val durationUs = if (sourceFormat.containsKey(MediaFormat.KEY_DURATION)) {
                sourceFormat.getLong(MediaFormat.KEY_DURATION)
            } else 0L
            extractor.selectTrack(audioTrack)

            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(sourceFormat, null, null, 0)
            codec.start()

            var outputFormat = sourceFormat
            var inputEnded = false
            var outputEnded = false
            val info = MediaCodec.BufferInfo()
            var resampler: StreamingMonoResampler? = null
            val sink = ChunkSink(chunkSeconds * TARGET_SAMPLE_RATE, onChunk)

            while (!outputEnded) {
                if (!inputEnded) {
                    val inputIndex = codec.dequeueInputBuffer(10_000)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: error("音声入力バッファを取得できません")
                        inputBuffer.clear()
                        val size = extractor.readSampleData(inputBuffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(
                                inputIndex,
                                0,
                                0,
                                0,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputEnded = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0)
                            codec.queueInputBuffer(inputIndex, 0, size, pts, 0)
                            if (durationUs > 0) {
                                onProgress(((pts * 100L) / durationUs).toInt().coerceIn(0, 99))
                            }
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = codec.outputFormat
                        resampler = StreamingMonoResampler(
                            sourceRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            targetRate = TARGET_SAMPLE_RATE,
                            sink = sink,
                        )
                    }
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    else -> if (outputIndex >= 0) {
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(outputIndex) ?: error("音声出力バッファを取得できません")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val sampleRate = outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            val channels = outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                            val pcmEncoding = if (outputFormat.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                outputFormat.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            if (resampler == null) {
                                resampler = StreamingMonoResampler(sampleRate, TARGET_SAMPLE_RATE, sink)
                            }
                            decodeBuffer(buffer.slice().order(ByteOrder.LITTLE_ENDIAN), channels, pcmEncoding, resampler!!)
                        }
                        outputEnded = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        codec.releaseOutputBuffer(outputIndex, false)
                        sink.drain()
                    }
                }
            }
            resampler?.finish()
            sink.finish()
            sink.drain()
            onProgress(100)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
            ProcessingGuardService.stop(context)
        }
    }

    private fun decodeBuffer(
        buffer: ByteBuffer,
        channels: Int,
        pcmEncoding: Int,
        resampler: StreamingMonoResampler,
    ) {
        require(channels > 0) { "不正なチャンネル数: $channels" }
        when (pcmEncoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val frames = buffer.remaining() / 4 / channels
                repeat(frames) {
                    var sum = 0f
                    repeat(channels) { sum += buffer.float }
                    resampler.push(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_16BIT -> {
                val frames = buffer.remaining() / 2 / channels
                repeat(frames) {
                    var sum = 0f
                    repeat(channels) { sum += buffer.short / 32768f }
                    resampler.push(sum / channels)
                }
            }
            AudioFormat.ENCODING_PCM_8BIT -> {
                val frames = buffer.remaining() / channels
                repeat(frames) {
                    var sum = 0f
                    repeat(channels) { sum += ((buffer.get().toInt() and 0xFF) - 128) / 128f }
                    resampler.push(sum / channels)
                }
            }
            else -> error("未対応のPCM形式です: $pcmEncoding")
        }
    }

    private class StreamingMonoResampler(
        sourceRate: Int,
        targetRate: Int,
        private val sink: ChunkSink,
    ) {
        private val step = sourceRate.toDouble() / targetRate.toDouble()
        private var previous = 0f
        private var sourceIndex = 0L
        private var nextOutputPosition = 0.0
        private var initialized = false

        fun push(sample: Float) {
            if (!initialized) {
                previous = sample
                initialized = true
                sink.append(sample)
                nextOutputPosition = step
                return
            }
            val currentIndex = sourceIndex + 1
            while (nextOutputPosition <= currentIndex.toDouble()) {
                val fraction = (nextOutputPosition - sourceIndex.toDouble()).toFloat().coerceIn(0f, 1f)
                sink.append(previous + (sample - previous) * fraction)
                nextOutputPosition += step
            }
            previous = sample
            sourceIndex = currentIndex
        }

        fun finish() = Unit
    }

    private class ChunkSink(
        private val chunkSize: Int,
        private val callback: (FloatArray, Long) -> Unit,
    ) {
        private data class PendingChunk(val samples: FloatArray, val startMs: Long)

        private var buffer = FloatArray(chunkSize)
        private var size = 0
        private var totalSamples = 0L
        private val pending = ArrayDeque<PendingChunk>()

        fun append(value: Float) {
            buffer[size++] = value
            if (size == chunkSize) enqueue()
        }

        fun finish() {
            if (size > 0) enqueue()
        }

        fun drain() {
            while (pending.isNotEmpty()) {
                val chunk = pending.removeFirst()
                callback(chunk.samples, chunk.startMs)
            }
        }

        private fun enqueue() {
            val startMs = totalSamples * 1000L / TARGET_SAMPLE_RATE
            val out = if (size == buffer.size) buffer else buffer.copyOf(size)
            pending.addLast(PendingChunk(out, startMs))
            totalSamples += size
            buffer = FloatArray(chunkSize)
            size = 0
        }
    }
}
