package com.example.memostream.ui

import com.example.memostream.data.*

import android.content.ClipData
import android.content.ContentValues
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File

private fun shareableUri(context: Context, record: BlobRecord, source: File): Uri {
    val dir = File(context.cacheDir, "shared").apply {
        mkdirs()
    }
    val name = record.name.ifEmpty {
        "memo-${record.id}"
    }
        .replace(Regex("""[\\/:*?"<>|]"""), "_")
    val target = File(dir, name)
    if (!target.exists() || target.length() != source.length()) {
        source.copyTo(target, overwrite = true)
    }
    return FileProvider.getUriForFile(context, "${context.packageName}.files", target)
}

fun copyMedia(context: Context, record: BlobRecord, file: File): Boolean {
    if (!file.exists()) {
        return false
    }
    return runCatching {
        val uri = shareableUri(context, record, file)
        val label = record.name.ifEmpty {
            "첨부"
        }

        val clip = ClipData(
            ClipDescription(label, arrayOf(record.mime, "text/plain")),
            ClipData.Item(uri),
        )
        context.grantUriPermission(
            "android",
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        )
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
        true
    }.onFailure {
        android.util.Log.w("memostream", "복사 실패", it)
    }.getOrDefault(false)
}

fun shareMedia(context: Context, record: BlobRecord, file: File, text: String?) {
    if (!file.exists()) {
        return
    }
    runCatching {
        val uri = shareableUri(context, record, file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = record.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            if (!text.isNullOrBlank()) {
                putExtra(Intent.EXTRA_TEXT, text)
            }
            clipData = ClipData.newUri(context.contentResolver, record.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "공유"))
    }
}

fun saveToDownloads(context: Context, record: BlobRecord, file: File): Boolean {
    if (!file.exists()) {
        return false
    }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
        return false
    }
    return runCatching {
        val name = record.name.ifEmpty {
            "memo-${record.id}.${record.mime.substringAfterLast('/', "bin")}"
        }.replace(Regex("""[\\/:*?"<>|]"""), "_")
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, record.mime)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val target = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(target)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(target, values, null, null)
        true
    }.onFailure { error ->
        android.util.Log.w("memostream", "저장 실패", error)
    }.getOrDefault(false)
}

fun writeTo(context: Context, file: File, target: Uri): Boolean {
    return runCatching {
        context.contentResolver.openOutputStream(target)?.use { output ->
            file.inputStream().use { input ->
                input.copyTo(output)
            }
        }
        true
    }.getOrDefault(false)
}

fun suggestedName(record: BlobRecord): String {
    return record.name.ifEmpty {
        "memo-${record.id}.${record.mime.substringAfterLast('/', "bin")}"
    }
}

fun openExternally(context: Context, record: BlobRecord, file: File) {
    if (!file.exists()) {
        return
    }
    runCatching {
        val uri = shareableUri(context, record, file)
        context.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, record.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        )
    }
}
