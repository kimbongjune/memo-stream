package com.example.memostream.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import android.media.MediaCodecInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.Effects
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val MAX_ATTACH_BYTES = 200L * 1024 * 1024
private const val MAX_IMAGE_DIM = 1600
private const val THUMB_DIM = 300
private const val VIDEO_SHORT_SIDE = 720
private const val VIDEO_BITS_PER_PIXEL = 0.12f
private const val VIDEO_MIN_BITRATE = 400_000
private const val VIDEO_MAX_BITRATE = 8_000_000

data class PickedFile(val uri: Uri, val name: String, val mime: String, val size: Long)

private val VAGUE_MIMES = setOf("", "application/octet-stream", "*/*", "application/unknown")

fun sniffMime(head: ByteArray): String? {
    fun ascii(offset: Int, text: String): Boolean {
        if (head.size < offset + text.length) {
            return false
        }
        return text.indices.all {
            head[offset + it] == text[it].code.toByte()
        }
    }
    fun bytes(offset: Int, vararg expected: Int): Boolean {
        if (head.size < offset + expected.size) {
            return false
        }
        return expected.indices.all {
            (head[offset + it].toInt() and 0xFF) == expected[it]
        }
    }
    return when {
        ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
        ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
        bytes(0, 0x89, 0x50, 0x4E, 0x47) -> "image/png"
        bytes(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
        bytes(0, 0x42, 0x4D) -> "image/bmp"
        ascii(4, "ftypheic") || ascii(4, "ftypheix") || ascii(4, "ftypmif1") -> "image/heic"
        ascii(4, "ftypavif") -> "image/avif"
        ascii(4, "ftyp") -> "video/mp4"
        bytes(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
        bytes(0, 0x25, 0x50, 0x44, 0x46) -> "application/pdf"
        else -> null
    }
}

fun readPicked(context: Context, uri: Uri, declared: String? = null): PickedFile {
    val resolver = context.contentResolver
    var name = ""
    var size = 0L
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex)
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex)
                }
            }
        }
    }
    if (name.isEmpty()) {
        name = uri.lastPathSegment?.substringAfterLast('/').orEmpty()
    }
    if (size == 0L) {
        runCatching {
            resolver.openAssetFileDescriptor(uri, "r")?.use {
                size = it.length.coerceAtLeast(0)
            }
        }
    }

    var mime = resolver.getType(uri).orEmpty()
    if (mime in VAGUE_MIMES) {
        mime = declared.orEmpty()
    }
    if (mime in VAGUE_MIMES) {
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext).orEmpty()
        }
    }
    if (mime in VAGUE_MIMES) {
        val head = ByteArray(64)
        runCatching {
            resolver.openInputStream(uri)?.use { stream ->
                val read = stream.read(head)
                if (read > 0) {
                    mime = sniffMime(head.copyOf(read)).orEmpty()
                }
            }
        }
    }
    if (mime.isEmpty()) {
        mime = "application/octet-stream"
    }

    return PickedFile(uri, name.ifEmpty {
        "file"
    }, mime, size)
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { stream ->
        val buffer = ByteArray(1 shl 16)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) {
                break
            }
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") {
        "%02x".format(it)
    }
}

private fun copyToTemp(context: Context, uri: Uri, suffix: String): File {
    val temp = File.createTempFile("attach", suffix, context.cacheDir)
    context.contentResolver.openInputStream(uri)?.use { input ->
        temp.outputStream().use { output ->
            input.copyTo(output)
        }
    } ?: throw IllegalStateException("첨부를 열 수 없습니다")
    return temp
}

fun isAnimated(stream: InputStream, mime: String): Boolean {
    val head = ByteArray(64)
    val read = stream.read(head)
    if (read <= 0) {
        return false
    }
    return when {
        mime == "image/gif" -> true
        mime == "image/webp" && read >= 21 -> {
            val chunk = String(head, 12, 4, Charsets.US_ASCII)
            chunk == "VP8X" && (head[20].toInt() and 0x02) != 0
        }
        else -> false
    }
}

fun gifDurationMs(bytes: ByteArray): Long {
    var total = 0L
    var frames = 0
    var i = 0
    while (i + 5 < bytes.size) {
        if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte() && bytes[i + 2] == 0x04.toByte()) {
            val delay = (bytes[i + 4].toInt() and 0xFF) or ((bytes[i + 5].toInt() and 0xFF) shl 8)
            total += (if (delay <= 1) 10 else delay) * 10L
            frames++
            i += 6
            continue
        }
        i++
    }
    return if (frames == 0) 0 else total
}

fun gifIsAnimated(bytes: ByteArray): Boolean {
    var frames = 0
    var i = 0
    while (i + 3 < bytes.size) {
        if (bytes[i] == 0x21.toByte() && bytes[i + 1] == 0xF9.toByte() && bytes[i + 2] == 0x04.toByte()) {
            frames++
            if (frames > 1) {
                return true
            }
        }
        i++
    }
    return false
}

class Attachments(private val context: Context, private val repo: MemoRepository) {
    suspend fun store(picked: PickedFile, onProgress: (String) -> Unit): Long =
        withContext(Dispatchers.IO) {
            val mime = picked.mime
            when {
                mime.startsWith("image/") -> storeImage(picked, onProgress)
                mime.startsWith("video/") -> storeVideo(picked, onProgress)
                else -> storeRaw(picked, mime, loop = false)
            }
        }

    private suspend fun storeImage(picked: PickedFile, onProgress: (String) -> Unit): Long {
        val temp = copyToTemp(context, picked.uri, ".img")
        try {
            val animated = temp.inputStream().use {
                isAnimated(it, picked.mime)
            } &&
                (picked.mime != "image/gif" || gifIsAnimated(temp.readBytes()))
            if (animated) {
                onProgress("움직이는 이미지 변환 중...")
                val mp4 = File.createTempFile("anim", ".mp4", context.cacheDir)
                val durationMs = if (picked.mime == "image/gif") gifDurationMs(temp.readBytes()) else 0L
                val converted = runCatching {
                    GifEncoder.encode(temp, mp4, durationMs)
                }
                    .onFailure {
                        android.util.Log.w("memostream", "gif->mp4 실패", it)
                    }
                    .getOrNull()
                if (converted != null && converted.length() in 1 until temp.length()) {
                    val base = picked.name.substringBeforeLast('.', picked.name)
                    val id = insert(converted, "video/mp4", "$base.mp4", loop = true, animated = false)
                    writeVideoThumb(id, converted)
                    converted.delete()
                    return id
                }
                mp4.delete()
                return insert(temp, picked.mime, picked.name, loop = true, animated = true)
            }
            val bitmap = BitmapFactory.decodeFile(temp.absolutePath)
                ?: return insert(temp, picked.mime, picked.name, loop = false, animated = false)
            val scaled = scale(bitmap, MAX_IMAGE_DIM)
            val webp = File.createTempFile("img", ".webp", context.cacheDir)
            webp.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, out)
            }
            val id = insert(
                webp, "image/webp",
                picked.name.substringBeforeLast('.', picked.name) + ".webp",
                loop = false, animated = false,
                width = scaled.width, height = scaled.height,
            )
            writeThumb(id, scaled)
            if (scaled !== bitmap) {
                scaled.recycle()
            }
            bitmap.recycle()
            return id
        } finally {
            temp.delete()
        }
    }

    private suspend fun storeVideo(picked: PickedFile, onProgress: (String) -> Unit): Long {
        val temp = copyToTemp(context, picked.uri, ".video")
        try {
            var source = temp
            if (Settings(context).compressVideo) {
                onProgress("영상 압축 중...")
                val compressed = runCatching {
                    transcode(temp)
                }.getOrNull()
                if (compressed != null && compressed.length() in 1 until temp.length()) {
                    source = compressed
                } else {
                    compressed?.delete()
                }
            }
            val id = insert(source, "video/mp4", picked.name, loop = false, animated = false)
            writeVideoThumb(id, source)
            if (source !== temp) {
                source.delete()
            }
            return id
        } finally {
            temp.delete()
        }
    }

    private suspend fun storeRaw(picked: PickedFile, mime: String, loop: Boolean): Long {
        val temp = copyToTemp(context, picked.uri, ".bin")
        return try {
            insert(temp, mime, picked.name, loop, animated = false)
        } finally {
            temp.delete()
        }
    }

    private suspend fun insert(
        file: File,
        mime: String,
        name: String,
        loop: Boolean,
        animated: Boolean,
        width: Int = 0,
        height: Int = 0,
    ): Long {
        val sha = sha256(file)
        repo.blobBySha(sha)?.let {
            repo.incrementRef(it.id)
            return it.id
        }
        val target = repo.files.blob(sha)
        file.copyTo(target, overwrite = true)
        var w = width
        var h = height
        if (w == 0 && mime.startsWith("image/")) {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(target.absolutePath, options)
            w = options.outWidth.coerceAtLeast(0)
            h = options.outHeight.coerceAtLeast(0)
        }
        val id = repo.insertBlob(
            BlobRecord(
                sha256 = sha,
                mime = mime,
                name = name,
                size = target.length(),
                width = w,
                height = h,
                loop = loop || animated,
                refCount = 1,
                createdAt = System.currentTimeMillis(),
            )
        )
        if (animated) {
            writeThumbFromFile(id, target)
        }
        return id
    }

    private fun scale(bitmap: Bitmap, maxDim: Int): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= maxDim) {
            return bitmap
        }
        val ratio = maxDim.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width * ratio).toInt().coerceAtLeast(1),
            (bitmap.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

    private suspend fun writeThumb(blobId: Long, source: Bitmap) {
        val record = repo.blob(blobId) ?: return
        val thumb = scale(source, THUMB_DIM)
        val file = repo.files.thumb(record.sha256)
        file.outputStream().use { out ->
            thumb.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, out)
        }
        if (thumb !== source) {
            thumb.recycle()
        }
        repo.setThumbSize(blobId, file.length())
    }

    private suspend fun writeThumbFromFile(blobId: Long, file: File) {
        val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
        writeThumb(blobId, bitmap)
        bitmap.recycle()
    }

    private suspend fun writeVideoThumb(blobId: Long, file: File) {
        val record = repo.blob(blobId) ?: return
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val frame = retriever.frameAtTime ?: return
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            repo.setDimensions(blobId, width, height)
            val thumb = scale(frame, THUMB_DIM)
            repo.files.thumb(record.sha256).outputStream().use { out ->
                thumb.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, out)
            }
            repo.setThumbSize(blobId, repo.files.thumb(record.sha256).length())
            if (thumb !== frame) {
                thumb.recycle()
            }
            frame.recycle()
        } catch (err: Exception) {
            return
        } finally {
            runCatching {
                retriever.release()
            }
        }
    }

    private fun bitrateFor(input: File): Int {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(input.absolutePath)
            fun meta(key: Int) =
                retriever.extractMetadata(key)?.toIntOrNull() ?: 0
            val width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            val height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            val fps = meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)
                .takeIf {
                    it in 1..120
                } ?: 30
            if (width <= 0 || height <= 0) {
                return VIDEO_MIN_BITRATE
            }
            val shortSide = minOf(width, height)
            val scale = minOf(1f, VIDEO_SHORT_SIDE.toFloat() / shortSide)
            val outPixels = (width * scale) * (height * scale)
            (outPixels * fps * VIDEO_BITS_PER_PIXEL).toInt()
                .coerceIn(VIDEO_MIN_BITRATE, VIDEO_MAX_BITRATE)
        } catch (err: Exception) {
            VIDEO_MIN_BITRATE
        } finally {
            runCatching {
                retriever.release()
            }
        }
    }

    private suspend fun transcode(input: File): File = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            val output = File.createTempFile("mp4", ".mp4", context.cacheDir)

            val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(input)))
                .setEffects(
                    Effects(
                        emptyList(),

                        listOf(Presentation.createForShortSide(VIDEO_SHORT_SIDE)),
                    )
                )
                .build()

            val encoders = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder()
                        .setBitrate(bitrateFor(input))
                        .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR)
                        .build()
                )
                .build()

            val transformer = Transformer.Builder(context)
                .setVideoMimeType(MimeTypes.VIDEO_H264)
                .setAudioMimeType(MimeTypes.AUDIO_AAC)
                .setEncoderFactory(encoders)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, result: ExportResult) {
                        if (cont.isActive) {
                            cont.resume(output)
                        }
                    }

                    override fun onError(
                        composition: Composition,
                        result: ExportResult,
                        exception: ExportException,
                    ) {
                        output.delete()
                        if (cont.isActive) {
                            cont.resumeWithException(exception)
                        }
                    }
                })
                .build()
            transformer.start(item, output.absolutePath)
            cont.invokeOnCancellation {
                runCatching {
                    transformer.cancel()
                }
            }
        }
    }
}
