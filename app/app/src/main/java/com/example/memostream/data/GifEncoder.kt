package com.example.memostream.data

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File

private const val FRAME_RATE = 20
private const val SHORT_SIDE = 720
private const val BITRATE_PER_PIXEL = 0.15f
private const val MIN_BITRATE = 200_000
private const val MAX_DURATION_US = 60_000_000L
private const val DEFAULT_DURATION_US = 5_000_000L

class GifEncodeUnsupported(message: String, cause: Throwable? = null) : Exception(message, cause)

object GifEncoder {
    fun encode(input: File, output: File, durationMs: Long = 0): File {
        if (Build.VERSION.SDK_INT < 28) {
            throw GifEncodeUnsupported("Android 9 이상이 필요합니다")
        }

        val drawable = try {
            ImageDecoder.decodeDrawable(ImageDecoder.createSource(input)) { decoder, _, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } catch (err: Exception) {
            throw GifEncodeUnsupported("이미지를 열 수 없습니다", err)
        }
        if (drawable !is AnimatedImageDrawable) {
            throw GifEncodeUnsupported("움직이는 이미지가 아닙니다")
        }

        val srcWidth = drawable.intrinsicWidth
        val srcHeight = drawable.intrinsicHeight
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw GifEncodeUnsupported("크기를 읽을 수 없습니다")
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val caps = encoder.codecInfo
            .getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            .videoCapabilities ?: throw GifEncodeUnsupported("인코더 정보를 읽을 수 없습니다")

        val scale = minOf(1f, SHORT_SIDE.toFloat() / minOf(srcWidth, srcHeight))
        val (width, height) = fitToCodec(
            caps,
            (srcWidth * scale).toInt().coerceAtLeast(1),
            (srcHeight * scale).toInt().coerceAtLeast(1),
        )

        val bitrate = maxOf(
            MIN_BITRATE,
            (width * height * FRAME_RATE * BITRATE_PER_PIXEL).toInt()
        ).let {
            caps.bitrateRange.clamp(it)
        }

        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val muxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val pixels = IntArray(width * height)
        val info = MediaCodec.BufferInfo()

        var trackIndex = -1
        var muxerStarted = false
        var finished = false

        fun drain(endOfStream: Boolean) {
            while (true) {
                val status = encoder.dequeueOutputBuffer(info, if (endOfStream) 20_000 else 0)
                if (status == MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (!endOfStream) {
                        return
                    }
                    continue
                }
                if (status == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    trackIndex = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true
                    continue
                }
                if (status < 0) {
                    continue
                }
                val buffer = encoder.getOutputBuffer(status)
                if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                    info.size = 0
                }
                if (info.size > 0 && muxerStarted && buffer != null) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    muxer.writeSampleData(trackIndex, buffer, info)
                }
                encoder.releaseOutputBuffer(status, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    finished = true
                    return
                }
            }
        }

        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val drawScale = minOf(width.toFloat() / srcWidth, height.toFloat() / srcHeight)
            val drawWidth = (srcWidth * drawScale).toInt().coerceAtLeast(1)
            val drawHeight = (srcHeight * drawScale).toInt().coerceAtLeast(1)
            val offsetX = (width - drawWidth) / 2
            val offsetY = (height - drawHeight) / 2
            drawable.setBounds(offsetX, offsetY, offsetX + drawWidth, offsetY + drawHeight)
            drawable.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
            drawable.start()
            val targetUs = (if (durationMs > 0) durationMs * 1000 else DEFAULT_DURATION_US)
                .coerceAtMost(MAX_DURATION_US)

            val frameIntervalUs = 1_000_000L / FRAME_RATE
            var ptsUs = 0L
            var wroteAny = false

            while (ptsUs < targetUs) {
                canvas.drawColor(Color.BLACK)
                drawable.draw(canvas)
                frame.getPixels(pixels, 0, width, 0, 0, width, height)

                val index = encoder.dequeueInputBuffer(20_000)
                if (index >= 0) {
                    val image = encoder.getInputImage(index)
                    if (image == null) {
                        encoder.queueInputBuffer(index, 0, 0, ptsUs, 0)
                    } else {
                        fillYuv(image, pixels, width, height)
                        val size = encoder.getInputBuffer(index)?.capacity() ?: (width * height * 3 / 2)
                        encoder.queueInputBuffer(index, 0, size, ptsUs, 0)
                    }
                    wroteAny = true
                    ptsUs += frameIntervalUs
                }
                drain(false)
                Thread.sleep(frameIntervalUs / 1000)
            }

            if (!wroteAny) {
                throw GifEncodeUnsupported("프레임을 읽지 못했습니다")
            }

            val endIndex = encoder.dequeueInputBuffer(200_000)
            if (endIndex >= 0) {
                encoder.queueInputBuffer(endIndex, 0, 0, ptsUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            }
            var guard = 0
            while (!finished && guard < 200) {
                drain(true)
                guard++
            }
        } catch (err: GifEncodeUnsupported) {
            throw err
        } catch (err: Exception) {
            throw GifEncodeUnsupported("인코딩에 실패했습니다: ${err.message}", err)
        } finally {
            runCatching {
                drawable.stop()
            }
            runCatching {
                encoder.stop()
            }
            runCatching {
                encoder.release()
            }
            if (muxerStarted) {
                runCatching {
                    muxer.stop()
                }
            }
            runCatching {
                muxer.release()
            }
            frame.recycle()
        }

        if (!output.exists() || output.length() == 0L) {
            throw GifEncodeUnsupported("변환 결과가 비었습니다")
        }
        return output
    }

    private fun fitToCodec(
        caps: MediaCodecInfo.VideoCapabilities,
        requestedWidth: Int,
        requestedHeight: Int,
    ): Pair<Int, Int> {
        val widthAlign = caps.widthAlignment.coerceAtLeast(2)
        val heightAlign = caps.heightAlignment.coerceAtLeast(2)

        fun align(value: Int, unit: Int) = ((value + unit - 1) / unit) * unit

        var width = caps.supportedWidths.clamp(align(requestedWidth, widthAlign))
        width = align(width, widthAlign).coerceAtMost(caps.supportedWidths.upper)
        var height = caps.getSupportedHeightsFor(width).clamp(align(requestedHeight, heightAlign))
        height = align(height, heightAlign).coerceAtMost(caps.getSupportedHeightsFor(width).upper)

        if (!caps.isSizeSupported(width, height)) {
            width = caps.supportedWidths.lower.coerceAtLeast(align(requestedWidth, widthAlign))
            height = caps.getSupportedHeightsFor(width).lower
        }
        return width to height
    }

    private fun fillYuv(image: android.media.Image, argb: IntArray, width: Int, height: Int) {
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        yBuffer.clear(); uBuffer.clear(); vBuffer.clear()

        val yRowStride = yPlane.rowStride
        val yPixelStride = yPlane.pixelStride
        val uRowStride = uPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride

        for (y in 0 until height) {
            val rowBase = y * width
            val yRow = y * yRowStride
            for (x in 0 until width) {
                val color = argb[rowBase + x]
                val r = (color shr 16) and 0xFF
                val g = (color shr 8) and 0xFF
                val b = color and 0xFF
                val luma = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                yBuffer.put(yRow + x * yPixelStride, luma.coerceIn(16, 235).toByte())

                if (y and 1 == 0 && x and 1 == 0) {
                    val cb = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val cr = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    val half = (y / 2)
                    val uIndex = half * uRowStride + (x / 2) * uPixelStride
                    val vIndex = half * vRowStride + (x / 2) * vPixelStride
                    if (uIndex < uBuffer.capacity()) {
                        uBuffer.put(uIndex, cb.coerceIn(16, 240).toByte())
                    }
                    if (vIndex < vBuffer.capacity()) {
                        vBuffer.put(vIndex, cr.coerceIn(16, 240).toByte())
                    }
                }
            }
        }
    }
}
