package com.example.memostream.ui

import com.example.memostream.data.*

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File

object Capture {

    private fun dir(context: Context): File {
        val target = File(context.cacheDir, "capture")
        if (!target.exists()) {
            target.mkdirs()
        }
        return target
    }

    fun newFile(context: Context, extension: String): File {
        return File(dir(context), "capture-${System.currentTimeMillis()}.$extension")
    }

    fun uriFor(context: Context, file: File): Uri {
        return FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }

    fun clear(context: Context) {
        dir(context).listFiles()?.forEach { file ->
            file.delete()
        }
    }
}
