package com.example.memostream.data

import android.os.Environment
import android.os.StatFs

data class UsageStats(
    val usage: Long = 0,
    val quota: Long = 0,
    val noteCount: Int = 0,
    val trashNoteCount: Int = 0,
    val blobCount: Int = 0,
    val textBytes: Long = 0,
    val imageBytes: Long = 0,
    val videoBytes: Long = 0,
    val fileBytes: Long = 0,
    val imageCount: Int = 0,
    val videoCount: Int = 0,
    val fileCount: Int = 0,
    val thumbBytes: Long = 0,
    val trashBytes: Long = 0,
    val savedBytes: Long = 0,
)

data class CleanupResult(val orphans: Int, val purged: Int)

class Stats(private val repo: MemoRepository) {
    suspend fun usage(draftRefs: List<Long>): UsageStats {
        val blobs = repo.blobs()
        val notes = repo.allNotes()
        val active = notes.filter {
            it.deletedAt == null
        }
        val trashed = notes.filter {
            it.deletedAt != null
        }

        var imageBytes = 0L
        var videoBytes = 0L
        var fileBytes = 0L
        var imageCount = 0
        var videoCount = 0
        var fileCount = 0
        var thumbBytes = 0L
        blobs.forEach { record ->
            when {
                record.mime.startsWith("image/") -> {
                    imageBytes += record.size; imageCount++
                }
                record.mime.startsWith("video/") -> {
                    videoBytes += record.size; videoCount++
                }
                else -> {
                    fileBytes += record.size; fileCount++
                }
            }
            thumbBytes += record.thumbSize
        }

        val activeRefs = HashSet<Long>()
        active.forEach {
            activeRefs.addAll(it.blobIds)
        }
        val trashRefs = HashSet<Long>()
        trashed.forEach {
            trashRefs.addAll(it.blobIds)
        }
        var trashBlobBytes = 0L
        blobs.forEach { record ->
            if (!activeRefs.contains(record.id) && trashRefs.contains(record.id)) {
                trashBlobBytes += record.size + record.thumbSize
            }
        }

        var savedBytes = 0L
        blobs.forEach { record ->
            if (record.refCount > 1) {
                savedBytes += record.size * (record.refCount - 1)
            }
        }

        val stat = StatFs(repo.files.root.absolutePath)
        val free = stat.availableBytes
        val used = blobs.sumOf {
            it.size + it.thumbSize
        }

        return UsageStats(
            usage = used,
            quota = free + used,
            noteCount = active.size,
            trashNoteCount = trashed.size,
            blobCount = blobs.size,
            textBytes = active.sumOf {
                it.content.toByteArray().size.toLong()
            },
            imageBytes = imageBytes,
            videoBytes = videoBytes,
            fileBytes = fileBytes,
            imageCount = imageCount,
            videoCount = videoCount,
            fileCount = fileCount,
            thumbBytes = thumbBytes,
            trashBytes = trashed.sumOf {
                it.content.toByteArray().size.toLong()
            } + trashBlobBytes,
            savedBytes = savedBytes,
        )
    }

    suspend fun cleanupOrphans(draftRefs: List<Long>): Int {
        val counts = HashMap<Long, Int>()
        fun hold(id: Long) {
            counts[id] = (counts[id] ?: 0) + 1
        }
        repo.allNotes().forEach { note ->
            val ids = LinkedHashSet(note.blobIds)
            ids.addAll(collectBlobIds(note.content))
            ids.forEach(::hold)
        }
        draftRefs.forEach(::hold)

        var removed = 0
        repo.blobs().forEach { record ->
            val actual = counts[record.id] ?: 0
            if (actual == 0) {
                repo.deleteBlob(record)
                removed++
            } else if (actual != record.refCount) {
                repo.setRefCount(record.id, actual)
            }
        }
        return removed
    }

    suspend fun runFullCleanup(draftRefs: List<Long>): CleanupResult {
        val orphans = cleanupOrphans(draftRefs)
        val purged = repo.emptyTrash()
        return CleanupResult(orphans, purged)
    }
}
