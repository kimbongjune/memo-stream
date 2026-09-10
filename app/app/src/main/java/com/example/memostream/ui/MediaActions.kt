package com.example.memostream.ui

import com.example.memostream.data.*

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
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
