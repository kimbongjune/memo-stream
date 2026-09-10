package com.example.memostream.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import java.io.File

class MemoRepository(context: Context) {
    private val db = MemoDatabase.get(context)
    val files = BlobFiles(context)

    private val noteDao = db.notes()
    private val folderDao = db.folders()
    private val blobDao = db.blobs()

    var onNotePurged: (suspend (Note) -> Unit)? = null

    val activeNotes: Flow<List<Note>> = noteDao.activeFlow()
    val activeFolders: Flow<List<Folder>> = folderDao.activeFlow()
    val trashNotes: Flow<List<Note>> = noteDao.trashFlow()
    val allBlobs: Flow<List<BlobRecord>> = blobDao.allFlow()

    private fun now() = System.currentTimeMillis()

    suspend fun folders(): List<Folder> = folderDao.all()

    suspend fun folders(active: Boolean): List<Folder> =
        if (active) {
            folderDao.active()
        } else {
            folderDao.all()
        }

    suspend fun folder(id: Long?): Folder? = id?.let {
        folderDao.byId(it)
    }

    suspend fun folderByUid(uid: String): Folder? = folderDao.byUid(uid)

    suspend fun ensureInbox(): Long =
        folderDao.active().firstOrNull()?.id ?: createFolder("Inbox", pinned = true)

    suspend fun createFolder(name: String, pinned: Boolean = false): Long {
        val stamp = now()
        return folderDao.insert(
            Folder(
                name = name.trim(),
                order = folderDao.maxOrder() + 1,
                pinned = pinned,
                createdAt = stamp,
                updatedAt = stamp,
            )
        )
    }

    suspend fun insertFolder(folder: Folder): Long = folderDao.insert(folder)

    suspend fun updateFolder(folder: Folder) = folderDao.update(folder)

    suspend fun renameFolder(id: Long, name: String) = folderDao.rename(id, name.trim(), now())

    suspend fun setFolderPinned(id: Long, pinned: Boolean) = folderDao.setPinned(id, pinned, now())

    suspend fun softDeleteFolder(id: Long) = folderDao.softDelete(id, now())

    suspend fun allNotes(): List<Note> = noteDao.all()

    suspend fun notes(folderId: Long?): List<Note> {
        val rows = noteDao.active()
        return if (folderId == null) rows else rows.filter {
            it.folderId == folderId
        }
    }

    suspend fun note(id: Long): Note? = noteDao.byId(id)

    suspend fun noteByUid(uid: String): Note? = noteDao.byUid(uid)

    suspend fun insertNote(note: Note): Long = noteDao.insert(note)

    suspend fun updateNote(note: Note) = noteDao.update(note)

    suspend fun createNote(
        folderId: Long?,
        content: String,
        blobIds: List<Long> = emptyList(),
        sourceUrl: String? = null,
        sourceTitle: String? = null,
    ): Long {
        val stamp = now()
        return noteDao.insert(
            Note(
                folderId = folderId,
                content = content,
                blobIds = blobIds,
                sourceUrl = sourceUrl,
                sourceTitle = sourceTitle,
                createdAt = stamp,
                updatedAt = stamp,
            )
        )
    }

    suspend fun editNote(id: Long, content: String, blobIds: List<Long>) =
        noteDao.edit(id, content, blobIds, now())

    suspend fun setNotePinned(id: Long, pinned: Boolean) = noteDao.setPinned(id, pinned, now())

    suspend fun moveNote(id: Long, folderId: Long) = noteDao.move(id, folderId, now())

    suspend fun softDeleteNote(id: Long) = noteDao.softDelete(id, now())

    suspend fun restoreNote(id: Long) = noteDao.restore(id, now())

    suspend fun trash(): List<Note> = noteDao.trash()

    suspend fun deleteNoteRow(id: Long) = noteDao.deleteRow(id)

    suspend fun purgeNote(id: Long) {
        val note = noteDao.byId(id) ?: return
        noteDao.deleteRow(id)
        onNotePurged?.invoke(note)
        note.blobIds.forEach {
            decrementRef(it)
        }
    }

    suspend fun cleanupOldTrash(days: Int = 30): Int {
        val stale = noteDao.staleTrash(now() - days * 24L * 60 * 60 * 1000)
        stale.forEach {
            purgeNote(it.id)
        }
        return stale.size
    }

    suspend fun emptyTrash(): Int {
        val rows = noteDao.trash()
        rows.forEach {
            purgeNote(it.id)
        }
        return rows.size
    }

    suspend fun blobs(): List<BlobRecord> = blobDao.all()

    suspend fun blob(id: Long): BlobRecord? = blobDao.byId(id)

    suspend fun blobBySha(sha256: String): BlobRecord? = blobDao.bySha(sha256)

    suspend fun insertBlob(record: BlobRecord): Long = blobDao.insert(record)

    suspend fun setRefCount(id: Long, count: Int) = blobDao.setRefCount(id, count)

    suspend fun setThumbSize(id: Long, size: Long) = blobDao.setThumbSize(id, size)

    suspend fun setDimensions(id: Long, width: Int, height: Int) {
        if (width > 0 && height > 0) {
            blobDao.setDimensions(id, width, height)
        }
    }

    suspend fun incrementRef(id: Long) {
        val record = blobDao.byId(id) ?: return
        blobDao.setRefCount(id, record.refCount + 1)
    }

    suspend fun decrementRef(id: Long) {
        val record = blobDao.byId(id) ?: return
        if (record.refCount - 1 <= 0) {
            deleteBlob(record)
        } else {
            blobDao.setRefCount(id, record.refCount - 1)
        }
    }

    suspend fun deleteBlob(record: BlobRecord) {
        blobDao.delete(record.id)
        files.delete(record.sha256)
    }

    fun blobFile(record: BlobRecord): File = files.blob(record.sha256)

    fun thumbFile(record: BlobRecord): File = files.thumb(record.sha256)

    suspend fun search(query: String, folderId: Long?): List<Note> {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) {
            return emptyList()
        }
        return notes(folderId).filter { note ->
            note.content.lowercase().contains(needle) ||
                (note.sourceTitle ?: "").lowercase().contains(needle) ||
                (note.sourceUrl ?: "").lowercase().contains(needle)
        }
    }

    suspend fun filterByMedia(notes: List<Note>, kind: String?): List<Note> {
        if (kind.isNullOrEmpty()) {
            return notes
        }
        val mimes = blobDao.all().associate {
            it.id to it.mime
        }
        fun matches(id: Long): Boolean {
            val mime = mimes[id] ?: return false
            return when (kind) {
                "image" -> mime.startsWith("image/")
                "video" -> mime.startsWith("video/")
                else -> !mime.startsWith("image/") && !mime.startsWith("video/")
            }
        }
        return notes.filter { note ->
            note.blobIds.any(::matches)
        }
    }
}
