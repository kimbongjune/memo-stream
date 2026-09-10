package com.example.memostream.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

private val INVALID_NAME = Regex("""[\\/:*?"<>|\n\r]""")

fun sanitizeFileName(name: String): String =
    INVALID_NAME.replace(name, "_").trim().ifEmpty {
        "untitled"
    }

fun localDateKey(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(timestamp))

private fun extOf(mime: String): String = when (mime) {
    "image/png" -> "png"
    "image/jpeg" -> "jpg"
    "image/gif" -> "gif"
    "image/webp" -> "webp"
    "video/mp4" -> "mp4"
    "video/webm" -> "webm"
    else -> "bin"
}

data class ImportResult(val folders: Int, val notes: Int, val blobs: Int, val reusedBlobs: Int)

class Backup(private val repo: MemoRepository) {
    fun exportName(): String = "memo-export-${localDateKey(System.currentTimeMillis())}.zip"

    suspend fun exportTo(output: OutputStream) {
        val folders = repo.folders(true)
        val notes = repo.allNotes().filter {
            it.deletedAt == null
        }
        val blobs = repo.blobs()

        val prefixCount = HashMap<String, Int>()
        blobs.forEach { record ->
            val base = record.sha256.take(8)
            prefixCount[base] = (prefixCount[base] ?: 0) + 1
        }
        val used = HashSet<String>()
        val assetNames = HashMap<Long, String>()
        blobs.forEach { record ->
            var base = record.sha256.take(8)
            if ((prefixCount[base] ?: 0) > 1) {
                base = record.sha256.take(16)
            }
            val ext = extOf(record.mime)
            var name = "$base.$ext"
            var counter = 2
            while (used.contains(name)) {
                name = "$base-$counter.$ext"
                counter++
            }
            used.add(name)
            assetNames[record.id] = name
        }

        ZipOutputStream(output).use { zip ->
            blobs.forEach { record ->
                val file = repo.files.blob(record.sha256)
                if (!file.exists()) {
                    return@forEach
                }
                zip.putNextEntry(ZipEntry("assets/${assetNames[record.id]}"))
                file.inputStream().use {
                    it.copyTo(zip)
                }
                zip.closeEntry()
            }

            val byFolder = notes.groupBy {
                it.folderId
            }
            folders.forEach { folder ->
                val folderNotes = byFolder[folder.id].orEmpty()
                if (folderNotes.isEmpty()) {
                    return@forEach
                }
                val dir = sanitizeFileName(folder.name)
                folderNotes.groupBy {
                    localDateKey(it.createdAt)
                }.forEach { (day, dayNotes) ->
                    val markdown = StringBuilder()
                    dayNotes.sortedBy {
                        it.createdAt
                    }.forEach { note ->
                        var content = note.content
                        Regex("""blob:(\d+)""").findAll(note.content).forEach { match ->
                            val id = match.groupValues[1].toLongOrNull() ?: return@forEach
                            val asset = assetNames[id] ?: return@forEach
                            content = content.replace("blob:$id", "../assets/$asset")
                        }
                        if (note.sourceUrl != null) {
                            val source = listOfNotNull(note.sourceTitle, note.sourceUrl)
                                .joinToString(" ").trim()
                            content += "\n\n> 출처: $source\n"
                        }
                        markdown.append(content.trim()).append("\n\n---\n\n")
                    }
                    zip.putNextEntry(ZipEntry("$dir/$day.md"))
                    zip.write(markdown.toString().toByteArray())
                    zip.closeEntry()
                }
            }

            val payload = JSONObject().apply {
                put("app", "memo-stream")
                put("version", 2)
                put("exportedAt", System.currentTimeMillis())
                put("folders", JSONArray().apply {
                    folders.forEach { folder ->
                        put(JSONObject().apply {
                            put("id", folder.id)
                            put("uid", folder.uid)
                            put("name", folder.name)
                            put("order", folder.order)
                            put("pinned", folder.pinned)
                            put("createdAt", folder.createdAt)
                        })
                    }
                })
                put("notes", JSONArray().apply {
                    notes.forEach { note ->
                        put(JSONObject().apply {
                            put("id", note.id)
                            put("folderId", note.folderId ?: JSONObject.NULL)
                            put("content", note.content)
                            put("blobIds", JSONArray(note.blobIds))
                            put("sourceUrl", note.sourceUrl ?: JSONObject.NULL)
                            put("sourceTitle", note.sourceTitle ?: JSONObject.NULL)
                            put("pinned", note.pinned)
                            put("createdAt", note.createdAt)
                            put("editedAt", note.editedAt ?: JSONObject.NULL)
                        })
                    }
                })
                put("blobs", JSONArray().apply {
                    blobs.forEach { record ->
                        put(JSONObject().apply {
                            put("id", record.id)
                            put("sha256", record.sha256)
                            put("mime", record.mime)
                            put("name", record.name)
                            put("size", record.size)
                            put("width", record.width)
                            put("height", record.height)
                            put("loop", record.loop)
                        })
                    }
                })
            }
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(payload.toString().toByteArray())
            zip.closeEntry()
        }
    }

    suspend fun importFrom(input: InputStream): ImportResult {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) {
                    entries[entry.name] = zip.readBytes()
                }
                zip.closeEntry()
            }
        }
        val manifest = entries["backup.json"]
        return if (manifest != null) {
            importBackup(JSONObject(String(manifest)), entries)
        } else {
            importMarkdown(entries)
        }
    }

    private fun findAsset(entries: Map<String, ByteArray>, sha256: String): ByteArray? {
        val short = sha256.take(8)
        val long = sha256.take(16)
        return entries.entries.firstOrNull { (key, _) ->
            if (!key.startsWith("assets/")) {
                return@firstOrNull false
            }
            val base = key.removePrefix("assets/").substringBefore('.')
            base == short || base == long || base.startsWith("$short-")
        }?.value
    }

    private suspend fun insertBytes(bytes: ByteArray, mime: String, name: String, loop: Boolean): Pair<Long, Boolean> {
        val sha = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        repo.blobBySha(sha)?.let {
            repo.incrementRef(it.id)
            return it.id to true
        }
        repo.files.blob(sha).writeBytes(bytes)
        val id = repo.insertBlob(
            BlobRecord(
                sha256 = sha,
                mime = mime,
                name = name,
                size = bytes.size.toLong(),
                loop = loop,
                refCount = 1,
                createdAt = System.currentTimeMillis(),
            )
        )
        return id to false
    }

    private suspend fun importBackup(payload: JSONObject, entries: Map<String, ByteArray>): ImportResult {
        var imported = 0
        var reused = 0
        val blobIdMap = HashMap<Long, Long>()

        val blobs = payload.optJSONArray("blobs") ?: JSONArray()
        for (i in 0 until blobs.length()) {
            val row = blobs.getJSONObject(i)
            val bytes = findAsset(entries, row.optString("sha256")) ?: continue
            val (id, wasReused) = insertBytes(
                bytes,
                row.optString("mime").ifEmpty {
                    "application/octet-stream"
                },
                row.optString("name"),
                row.optBoolean("loop"),
            )
            blobIdMap[row.optLong("id")] = id
            if (wasReused) {
                reused++
            } else {
                imported++
            }
        }

        val existing = repo.folders(true).toMutableList()
        val folderIdMap = HashMap<Long, Long>()
        var foldersCreated = 0
        val folders = payload.optJSONArray("folders") ?: JSONArray()
        for (i in 0 until folders.length()) {
            val row = folders.getJSONObject(i)
            val name = row.optString("name")
            val found = existing.firstOrNull {
                it.name == name
            }
            if (found != null) {
                folderIdMap[row.optLong("id")] = found.id
                continue
            }
            val id = repo.createFolder(name)
            repo.folder(id)?.let {
                existing.add(it)
            }
            folderIdMap[row.optLong("id")] = id
            foldersCreated++
        }

        var notesCreated = 0
        val notes = payload.optJSONArray("notes") ?: JSONArray()
        for (i in 0 until notes.length()) {
            val row = notes.getJSONObject(i)
            val folderId = folderIdMap[row.optLong("folderId")] ?: repo.ensureInbox()
            val oldIds = row.optJSONArray("blobIds") ?: JSONArray()
            val newIds = (0 until oldIds.length()).mapNotNull {
                blobIdMap[oldIds.optLong(it)]
            }
            var content = row.optString("content")
            blobIdMap.forEach {
                (old, new) -> content = content.replace("blob:$old)", "blob:$new)")
            }
            repo.insertNote(
                Note(
                    uid = newUid(),
                    folderId = folderId,
                    content = content,
                    blobIds = newIds,
                    sourceUrl = row.optString("sourceUrl").takeIf {
                        !row.isNull("sourceUrl")
                    },
                    sourceTitle = row.optString("sourceTitle").takeIf {
                        !row.isNull("sourceTitle")
                    },
                    pinned = row.optBoolean("pinned"),
                    createdAt = row.optLong("createdAt").takeIf {
                        it > 0
                    } ?: System.currentTimeMillis(),
                    editedAt = row.optLong("editedAt").takeIf {
                        !row.isNull("editedAt") && it > 0
                    },
                    updatedAt = System.currentTimeMillis(),
                )
            )
            newIds.forEach {
                repo.incrementRef(it)
            }
            notesCreated++
        }

        blobIdMap.values.forEach {
            repo.decrementRef(it)
        }
        return ImportResult(foldersCreated, notesCreated, imported, reused)
    }

    private suspend fun importMarkdown(entries: Map<String, ByteArray>): ImportResult {
        val mimeByExt = mapOf(
            "png" to "image/png", "jpg" to "image/jpeg", "jpeg" to "image/jpeg",
            "gif" to "image/gif", "webp" to "image/webp",
        )
        val imageMap = HashMap<String, Long>()
        var imported = 0
        var reused = 0
        entries.forEach { (key, bytes) ->
            val fileName = key.substringAfterLast('/').lowercase()
            val ext = fileName.substringAfterLast('.', "")
            val mime = mimeByExt[ext] ?: return@forEach
            if (imageMap.containsKey(fileName)) {
                return@forEach
            }
            val loop = mime == "image/gif" && gifIsAnimated(bytes)
            val (id, wasReused) = insertBytes(bytes, mime, fileName, loop)
            imageMap[fileName] = id
            if (wasReused) {
                reused++
            } else {
                imported++
            }
        }

        var foldersCreated = 0
        var notesCreated = 0
        val folderCache = HashMap<String, Long>()

        suspend fun folderFor(dir: String): Long {
            if (dir.isEmpty() || dir == "assets") {
                return repo.ensureInbox()
            }
            folderCache[dir]?.let {
                return it
            }
            val found = repo.folders(true).firstOrNull {
                it.name == dir
            }
            val id = if (found != null) found.id else {
                foldersCreated++
                repo.createFolder(dir)
            }
            folderCache[dir] = id
            return id
        }

        entries.forEach { (key, bytes) ->
            if (!key.endsWith(".md", ignoreCase = true) || key.startsWith("assets/")) {
                return@forEach
            }
            val parts = key.split('/')
            val folderId = folderFor(if (parts.size > 1) parts[0] else "")
            val usedIds = LinkedHashSet<Long>()
            val content = Regex("""!\[[^\]]*]\(([^)]+)\)""").replace(String(bytes)) { match ->
                val fileName = match.groupValues[1].trim().substringAfterLast('/').lowercase()
                val id = imageMap[fileName] ?: return@replace match.value
                usedIds.add(id)
                "![](blob:$id)"
            }
            val now = System.currentTimeMillis()
            repo.insertNote(
                Note(
                    uid = newUid(),
                    folderId = folderId,
                    content = content,
                    blobIds = usedIds.toList(),
                    createdAt = now,
                    updatedAt = now,
                )
            )
            usedIds.forEach {
                repo.incrementRef(it)
            }
            notesCreated++
        }

        imageMap.values.forEach {
            repo.decrementRef(it)
        }
        return ImportResult(foldersCreated, notesCreated, imported, reused)
    }
}
