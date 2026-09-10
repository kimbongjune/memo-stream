package com.example.memostream.sync

import com.example.memostream.data.*

import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

private const val PURGE_CHUNK = 200
private const val REMOTE_LIST_LIMIT = 10000

data class SyncResult(val pulled: Int, val pushed: Int)

class Sync(
    private val repo: MemoRepository,
    private val settings: Settings,
) {
    private val lock = Mutex()
    private val uploadedShas = HashSet<String>()

    var onPulled: (() -> Unit)? = null

    fun conf(): SyncConf? = settings.sb?.takeIf {
        it.url.isNotEmpty() && it.key.isNotEmpty() && it.enabled
    }

    fun queuePurge(note: Note) {
        if (note.uid.isEmpty()) {
            return
        }
        val queue = settings.purgeQueue.toMutableList()
        if (!queue.contains(note.uid)) {
            queue.add(note.uid)
            settings.purgeQueue = queue
        }
    }

    suspend fun run(onProgress: (String) -> Unit = {}): SyncResult? {
        val conf = conf() ?: return null
        if (lock.isLocked) {
            return null
        }
        return lock.withLock {
            try {
                purges(conf, onProgress)
                val pulled = pull(conf, onProgress)
                val pushed = push(conf, onProgress)
                conf.lastSyncedAt = System.currentTimeMillis()
                conf.lastError = null
                settings.saveSb(conf)
                if (pulled > 0) {
                    onPulled?.invoke()
                }
                SyncResult(pulled, pushed)
            } catch (err: Exception) {
                Log.e("sync", "failed", err)
                conf.lastError = err.message ?: err.toString()
                settings.saveSb(conf)
                throw err
            }
        }
    }

    private suspend fun purges(conf: SyncConf, onProgress: (String) -> Unit) {
        val queue = settings.purgeQueue
        if (queue.isEmpty()) {
            return
        }
        onProgress("삭제 반영 중...")
        val purgedAt = System.currentTimeMillis()
        queue.chunked(PURGE_CHUNK).forEach { chunk ->
            val list = chunk.joinToString(",") {
                "\"$it\""
            }
            Sb.restDelete(conf, "/notes?uid=in.($list)")
            val rows = JSONArray()
            chunk.forEach { uid ->
                rows.put(JSONObject().put("uid", uid).put("purged_at", purgedAt))
            }
            Sb.upsert(conf, "purges", rows)
        }
        settings.purgeQueue = emptyList()
    }

    private suspend fun pull(conf: SyncConf, onProgress: (String) -> Unit): Int {
        onProgress("받는 중...")
        val sinceFolders = conf.lastPullFolders
        val sinceNotes = conf.lastPullNotes
        val sincePurges = conf.lastPullPurges

        val remoteFolders = Sb.restGet(conf, "/folders?updated_at=gt.$sinceFolders&order=updated_at.asc")
        val remoteNotes = Sb.restGet(conf, "/notes?updated_at=gt.$sinceNotes&order=updated_at.asc")

        val tombs = pullTombstones(conf, sincePurges)
        val folders = repo.folders().toMutableList()
        val folderResult = pullFolders(remoteFolders, folders, sinceFolders)
        val noteResult = pullNotes(conf, remoteNotes, folders, sinceNotes, onProgress)

        conf.lastPullPurges = tombs.first
        conf.lastPullFolders = folderResult.first
        conf.lastPullNotes = noteResult.first
        return tombs.second + folderResult.second + noteResult.second
    }

    private suspend fun pullTombstones(conf: SyncConf, since: Long): Pair<Long, Int> {
        val rows = runCatching {
            Sb.restGet(conf, "/purges?purged_at=gt.$since&order=purged_at.asc")
        }.getOrNull() ?: return since to 0
        var watermark = since
        var changed = 0
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            watermark = maxOf(watermark, row.optLong("purged_at"))
            val local = repo.noteByUid(row.optString("uid")) ?: continue
            repo.deleteNoteRow(local.id)
            local.blobIds.forEach {
                repo.decrementRef(it)
            }
            changed++
        }
        return watermark to changed
    }

    private suspend fun pullFolders(
        remote: JSONArray,
        folders: MutableList<Folder>,
        since: Long,
    ): Pair<Long, Int> {
        var watermark = since
        var changed = 0
        val claimed = HashSet<Long>()
        val remoteUids = (0 until remote.length()).map {
            remote.getJSONObject(it).optString("uid")
        }.toSet()

        for (i in 0 until remote.length()) {
            val row = remote.getJSONObject(i)
            watermark = maxOf(watermark, row.optLong("updated_at"))
            val uid = row.optString("uid")
            var local = folders.firstOrNull {
                it.uid == uid
            }

            if (local == null && since == 0L) {
                local = folders.firstOrNull {
                    it.name == row.optString("name") && !claimed.contains(it.id) && !remoteUids.contains(it.uid)
                }
            }
            if (local != null) {
                claimed.add(local.id)
            }
            if (local != null && local.uid == uid && local.version() >= row.optLong("updated_at")) {
                continue
            }

            val patch = Folder(
                id = local?.id ?: 0,
                uid = uid,
                name = row.optString("name"),
                order = row.optInt("order"),
                pinned = row.optBoolean("pinned"),
                createdAt = row.optLong("created_at"),
                deletedAt = row.optLong("deleted_at").takeIf {
                    !row.isNull("deleted_at") && it != 0L
                },
                updatedAt = row.optLong("updated_at"),
            )
            if (local != null) {
                repo.updateFolder(patch)
                folders[folders.indexOfFirst {
                    it.id == local.id
                }] = patch
            } else {
                val id = repo.insertFolder(patch)
                folders.add(patch.copy(id = id))
            }
            changed++
        }
        return watermark to changed
    }

    private suspend fun pullNotes(
        conf: SyncConf,
        remote: JSONArray,
        folders: List<Folder>,
        since: Long,
        onProgress: (String) -> Unit,
    ): Pair<Long, Int> {
        var watermark = since
        var changed = 0
        var stalled = 0

        for (i in 0 until remote.length()) {
            val row = remote.getJSONObject(i)
            val uid = row.optString("uid")
            val updatedAt = row.optLong("updated_at")
            val local = repo.noteByUid(uid)
            if (local != null && !local.pullPending && local.version() >= updatedAt) {
                watermark = maxOf(watermark, updatedAt)
                continue
            }

            var content = row.optString("content")
            val blobIds = ArrayList<Long>()
            var missedBlob = false
            val metas = row.optJSONArray("blobs") ?: JSONArray()
            for (j in 0 until metas.length()) {
                val meta = metas.getJSONObject(j)
                onProgress("첨부 받는 중...")
                val id = ensureLocalBlob(conf, meta)
                if (id == null) {
                    missedBlob = true
                    continue
                }
                blobIds.add(id)
                content = content.replace("blob:${meta.optString("sha")})", "blob:$id)")
            }

            if (missedBlob) {
                stalled++
            } else {
                watermark = maxOf(watermark, updatedAt)
            }

            val folderUid = row.optString("folder_uid")
            val folder = folders.firstOrNull {
                it.uid == folderUid
            }
            val patch = Note(
                id = local?.id ?: 0,
                uid = uid,
                folderId = folder?.id ?: repo.ensureInbox(),
                content = content,
                blobIds = blobIds,
                sourceUrl = row.optString("source_url").takeIf {
                    !row.isNull("source_url")
                },
                sourceTitle = row.optString("source_title").takeIf {
                    !row.isNull("source_title")
                },
                pinned = row.optBoolean("pinned"),
                createdAt = row.optLong("created_at"),
                editedAt = row.optLong("edited_at").takeIf {
                    !row.isNull("edited_at") && it != 0L
                },
                deletedAt = row.optLong("deleted_at").takeIf {
                    !row.isNull("deleted_at") && it != 0L
                },
                updatedAt = updatedAt,
                pullPending = missedBlob,
            )
            if (local != null) {
                repo.updateNote(patch)
                local.blobIds.forEach {
                    repo.decrementRef(it)
                }
            } else {
                repo.insertNote(patch)
            }
            changed++
        }
        if (stalled > 0) {
            Log.w("sync", "$stalled rows kept pending for attachments")
        }
        return watermark to changed
    }

    private suspend fun ensureLocalBlob(conf: SyncConf, meta: JSONObject): Long? {
        val sha = meta.optString("sha")
        repo.blobBySha(sha)?.let {
            repo.incrementRef(it.id)
            return it.id
        }
        return try {
            val target = repo.files.blob(sha)
            Sb.download(conf, "blobs/$sha", target)
            repo.insertBlob(
                BlobRecord(
                    sha256 = sha,
                    mime = meta.optString("mime").ifEmpty {
                        "application/octet-stream"
                    },
                    name = meta.optString("name"),
                    size = if (meta.optLong("size") > 0) meta.optLong("size") else target.length(),
                    width = meta.optInt("width"),
                    height = meta.optInt("height"),
                    loop = meta.optBoolean("loop"),
                    refCount = 1,
                    createdAt = System.currentTimeMillis(),
                )
            )
        } catch (err: Exception) {
            Log.w("sync", "attachment download failed $sha", err)
            null
        }
    }

    private suspend fun push(conf: SyncConf, onProgress: (String) -> Unit): Int {
        val since = conf.lastPushAt
        val startedAt = System.currentTimeMillis()

        val folders = repo.folders().filter {
            it.version() > since
        }
        val notes = repo.allNotes().filter {
            it.version() > since && !it.pullPending
        }
        if (folders.isEmpty() && notes.isEmpty()) {
            conf.lastPushAt = startedAt
            return 0
        }

        onProgress("보내는 중...")
        val folderRows = JSONArray()
        folders.forEach { folder ->
            folderRows.put(
                JSONObject().apply {
                    put("uid", folder.uid)
                    put("name", folder.name)
                    put("order", folder.order)
                    put("pinned", folder.pinned)
                    put("created_at", folder.createdAt)
                    put("deleted_at", folder.deletedAt ?: JSONObject.NULL)
                    put("updated_at", folder.version())
                }
            )
        }
        Sb.upsert(conf, "folders", folderRows)

        val noteRows = JSONArray()
        for (note in notes) {
            val folder = note.folderId?.let {
                repo.folder(it)
            }
            val (content, metas) = pushBlobs(conf, note, onProgress)
            noteRows.put(
                JSONObject().apply {
                    put("uid", note.uid)
                    put("folder_uid", folder?.uid ?: JSONObject.NULL)
                    put("content", content)
                    put("blobs", metas)
                    put("source_url", note.sourceUrl ?: JSONObject.NULL)
                    put("source_title", note.sourceTitle ?: JSONObject.NULL)
                    put("pinned", note.pinned)
                    put("created_at", note.createdAt)
                    put("edited_at", note.editedAt ?: JSONObject.NULL)
                    put("deleted_at", note.deletedAt ?: JSONObject.NULL)
                    put("updated_at", note.version())
                }
            )
        }
        Sb.upsert(conf, "notes", noteRows)
        conf.lastPushAt = startedAt
        return noteRows.length() + folderRows.length()
    }

    private suspend fun pushBlobs(
        conf: SyncConf,
        note: Note,
        onProgress: (String) -> Unit,
    ): Pair<String, JSONArray> {
        var content = note.content
        val metas = JSONArray()

        for (id in note.blobIds) {
            val record = repo.blob(id)
            val file = record?.let {
                repo.files.blob(it.sha256)
            }
            if (record == null || file == null || !file.exists()) {
                content = Regex("""!?\[[^\]]*]\(blob:$id\)[ \t]*""").replace(content, "")
                continue
            }
            if (!uploadedShas.contains(record.sha256)) {
                if (!Sb.objectExists(conf, "blobs/${record.sha256}")) {
                    onProgress("첨부 올리는 중... ${formatBytes(record.size)}")
                    Sb.upload(conf, "blobs/${record.sha256}", file, record.mime)
                }
                uploadedShas.add(record.sha256)
            }
            metas.put(
                JSONObject().apply {
                    put("sha", record.sha256)
                    put("mime", record.mime)
                    put("name", record.name)
                    put("size", record.size)
                    put("width", record.width)
                    put("height", record.height)
                    put("loop", record.loop)
                }
            )
            content = content.replace("blob:$id)", "blob:${record.sha256})")
        }
        return content to metas
    }

    suspend fun cleanupRemoteBlobs(onProgress: (String) -> Unit = {}): Int {
        val conf = conf() ?: return 0
        val objects = Sb.listObjects(conf, "blobs/", REMOTE_LIST_LIMIT)
        if (objects.length() >= REMOTE_LIST_LIMIT) {
            Log.w("sync", "remote attachments exceed $REMOTE_LIST_LIMIT, only the first page is checked")
        }

        onProgress("참조 확인 중...")
        val rows = Sb.restGet(conf, "/notes?select=blobs")
        val referenced = HashSet<String>()
        for (i in 0 until rows.length()) {
            val metas = rows.getJSONObject(i).optJSONArray("blobs") ?: continue
            for (j in 0 until metas.length()) {
                metas.optJSONObject(j)?.optString("sha")?.takeIf {
                    it.isNotEmpty()
                }?.let(referenced::add)
            }
        }

        var removed = 0
        for (i in 0 until objects.length()) {
            val sha = objects.getJSONObject(i).optString("name")
            if (sha.isEmpty() || referenced.contains(sha)) {
                continue
            }
            onProgress("원격 첨부 정리 중... ($removed)")
            Sb.deleteObject(conf, "blobs/$sha")
            uploadedShas.remove(sha)
            removed++
        }
        return removed
    }
}

fun formatBytes(bytes: Long?): String {
    if (bytes == null) {
        return "—"
    }
    if (bytes < 1024) {
        return "$bytes B"
    }
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    do {
        value /= 1024
        index++
    } while (value >= 1024 && index < units.size - 1)
    val rounded = if (value >= 100) value.toLong().toString() else String.format("%.1f", value)
    return "$rounded ${units[index]}"
}
