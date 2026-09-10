package com.example.memostream.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.RoomDatabase
import java.io.File

class Converters {
    @TypeConverter
    fun fromIds(ids: List<Long>): String = ids.joinToString(",")

    @TypeConverter
    fun toIds(raw: String): List<Long> =
        raw.split(',').mapNotNull {
            it.trim().toLongOrNull()
        }
}

@Database(entities = [Folder::class, Note::class, BlobRecord::class], version = 1, exportSchema = false)
@TypeConverters(Converters::class)
abstract class MemoDatabase : RoomDatabase() {
    abstract fun notes(): NoteDao
    abstract fun folders(): FolderDao
    abstract fun blobs(): BlobDao

    companion object {
        @Volatile
        private var instance: MemoDatabase? = null

        fun get(context: Context): MemoDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MemoDatabase::class.java,
                "memostream.db"
            ).build().also {
                instance = it
            }
        }
    }
}

class BlobFiles(context: Context) {
    private val blobDir = File(context.filesDir, "blobs").apply {
        mkdirs()
    }
    private val thumbDir = File(context.filesDir, "thumbs").apply {
        mkdirs()
    }

    val root: File get() = blobDir

    fun blob(sha256: String): File = File(blobDir, sha256)

    fun thumb(sha256: String): File = File(thumbDir, sha256)

    fun delete(sha256: String) {
        blob(sha256).delete()
        thumb(sha256).delete()
    }
}
