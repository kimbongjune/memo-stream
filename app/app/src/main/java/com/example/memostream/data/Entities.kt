package com.example.memostream.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

fun newUid(): String = UUID.randomUUID().toString()

@Entity(tableName = "folders", indices = [Index(value = ["uid"], unique = true)])
data class Folder(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = newUid(),
    val name: String,
    @ColumnInfo(name = "ord") val order: Int = 0,
    val pinned: Boolean = false,
    val createdAt: Long,
    val deletedAt: Long? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "notes",
    indices = [
        Index(value = ["uid"], unique = true),
        Index(value = ["folderId"]),
        Index(value = ["deletedAt"]),
        Index(value = ["updatedAt"]),
    ]
)
data class Note(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uid: String = newUid(),
    val folderId: Long?,
    val content: String = "",
    val blobIds: List<Long> = emptyList(),
    val sourceUrl: String? = null,
    val sourceTitle: String? = null,
    val pinned: Boolean = false,
    val createdAt: Long,
    val editedAt: Long? = null,
    val deletedAt: Long? = null,
    val updatedAt: Long,
    val pullPending: Boolean = false,
)

@Entity(tableName = "blobs", indices = [Index(value = ["sha256"], unique = true)])
data class BlobRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sha256: String,
    val mime: String,
    val name: String = "",
    val size: Long = 0,
    val thumbSize: Long = 0,
    val width: Int = 0,
    val height: Int = 0,
    val loop: Boolean = false,
    val refCount: Int = 1,
    val createdAt: Long,
)

fun Note.version(): Long = if (updatedAt != 0L) updatedAt else editedAt ?: createdAt

fun Folder.version(): Long = if (updatedAt != 0L) updatedAt else createdAt
