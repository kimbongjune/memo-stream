package com.example.memostream.ui

import com.example.memostream.data.*
import com.example.memostream.sync.*

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

enum class Screen {
    NOTES, SETTINGS, TRASH
}

data class SearchState(
    val active: Boolean = false,
    val query: String = "",
    val global: Boolean = true,
    val kind: String = "",
)

data class DraftRef(val id: Long, val markdown: String)

data class NotesUi(
    val folders: List<Folder> = emptyList(),
    val notes: List<Note> = emptyList(),
    val folderNames: Map<Long, String> = emptyMap(),
    val hiddenCount: Int = 0,
)

const val NOTE_PAGE_SIZE = 200
private const val SYNC_DEBOUNCE_MS = 2000L
private const val TRASH_KEEP_DAYS = 30

class AppState(app: Application) : AndroidViewModel(app) {
    val repo = MemoRepository(app)
    val settings = Settings(app)
    val attachments = Attachments(app, repo)
    val stats = Stats(repo)
    val backup = Backup(repo)
    val sync = Sync(repo, settings)

    private val realtime = Realtime(
        confProvider = {
            sync.conf()
        },
        onChange = {
            syncSoon(300)
        },
        onJoined = {
            viewModelScope.launch {
                runSync()
            }
        },
    )

    private val _theme = MutableStateFlow(settings.theme)
    val theme: StateFlow<String> = _theme.asStateFlow()

    private val _screen = MutableStateFlow(Screen.NOTES)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    private val _folderId = MutableStateFlow<Long?>(null)
    val folderId: StateFlow<Long?> = _folderId.asStateFlow()

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    private val _pageSize = MutableStateFlow(NOTE_PAGE_SIZE)

    val ui: StateFlow<NotesUi> = combine(
        repo.activeNotes,
        repo.activeFolders,
        repo.allBlobs,
        _search,
        combine(_folderId, _pageSize) { folder, page ->
            folder to page
        },
    ) { notes, folders, blobRows, search, scope ->
        val (folderId, pageSize) = scope
        val needle = search.query.trim().lowercase()
        val searching = search.active && (needle.isNotEmpty() || search.kind.isNotEmpty())
        val target = if (searching && search.global) null else folderId

        var rows = if (target == null) notes else notes.filter {
            it.folderId == target
        }
        if (needle.isNotEmpty()) {
            rows = rows.filter { note ->
                note.content.lowercase().contains(needle) ||
                    (note.sourceTitle ?: "").lowercase().contains(needle) ||
                    (note.sourceUrl ?: "").lowercase().contains(needle)
            }
        }
        if (searching && search.kind.isNotEmpty()) {
            val mimes = blobRows.associate {
                it.id to it.mime
            }
            rows = rows.filter { note ->
                note.blobIds.any { id ->
                    val mime = mimes[id]
                    when {
                        mime == null -> false
                        search.kind == "image" -> mime.startsWith("image/")
                        search.kind == "video" -> mime.startsWith("video/")
                        else -> !mime.startsWith("image/") && !mime.startsWith("video/")
                    }
                }
            }
        }

        val pinned = rows.filter {
            it.pinned
        }
        val rest = rows.filter {
            !it.pinned
        }
        val hidden = (rest.size - pageSize).coerceAtLeast(0)
        NotesUi(
            folders = folders,
            notes = pinned + rest.drop(hidden),
            folderNames = folders.associate {
                it.id to it.name
            },
            hiddenCount = hidden,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, NotesUi())

    val blobs: StateFlow<Map<Long, BlobRecord>> = repo.allBlobs
        .map { rows ->
            rows.associateBy {
                it.id
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val folderCounts: StateFlow<Map<Long, Int>> = repo.activeNotes
        .map { rows ->
            rows.groupingBy {
                it.folderId ?: -1L
            }.eachCount()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val trash: StateFlow<List<Note>> = repo.trashNotes
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _draft = MutableStateFlow<List<DraftRef>>(emptyList())
    val draft: StateFlow<List<DraftRef>> = _draft.asStateFlow()

    private val _composerText = MutableStateFlow("")
    val composerText: StateFlow<String> = _composerText.asStateFlow()

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private var syncJob: Job? = null
    private var editingNoteId: Long? = null

    init {
        repo.onNotePurged = { note ->
            sync.queuePurge(note)
        }
        sync.onPulled = {
            viewModelScope.launch {
                refresh()
            }
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.ensureInbox()
                if (settings.defaultFolderId == null) {
                    settings.defaultFolderId = repo.ensureInbox()
                }
                repo.cleanupOldTrash(TRASH_KEEP_DAYS)
                Capture.clear(app)
            }
            refresh()
            runSync()
            realtime.connect()
        }
    }

    override fun onCleared() {
        realtime.stop()
        super.onCleared()
    }

    fun onResume() {
        if (!realtime.alive) {
            realtime.connect()
        }
        val conf = settings.sb ?: return
        if (System.currentTimeMillis() - conf.lastSyncedAt > 60_000) {
            viewModelScope.launch {
                runSync()
            }
        }
    }

    fun setScreen(screen: Screen) {
        _screen.value = screen
    }

    fun selectFolder(id: Long?) {
        _folderId.value = id
        _pageSize.value = NOTE_PAGE_SIZE
        _screen.value = Screen.NOTES
        closeSearch()
        viewModelScope.launch {
            refresh()
        }
    }

    fun toggleSearch() {
        if (_search.value.active) {
            closeSearch()
        } else {
            _search.value = SearchState(active = true)
        }
        viewModelScope.launch {
            refresh()
        }
    }

    fun closeSearch() {
        _search.value = SearchState()
    }

    fun setQuery(query: String) {
        _search.value = _search.value.copy(query = query)
        _pageSize.value = NOTE_PAGE_SIZE
        viewModelScope.launch {
            refresh()
        }
    }

    fun setGlobal(global: Boolean) {
        _search.value = _search.value.copy(global = global)
        viewModelScope.launch {
            refresh()
        }
    }

    fun setKind(kind: String) {
        _search.value = _search.value.copy(kind = kind)
        _pageSize.value = NOTE_PAGE_SIZE
        viewModelScope.launch {
            refresh()
        }
    }

    fun loadMore() {
        _pageSize.value += NOTE_PAGE_SIZE
    }

    fun setComposerText(text: String) {
        _composerText.value = text
    }

    suspend fun refresh() = Unit

    suspend fun defaultFolderId(): Long {
        val current = settings.defaultFolderId
        val folder = current?.let {
            repo.folder(it)
        }
        if (folder != null && folder.deletedAt == null) {
            return current
        }
        val fallback = repo.ensureInbox()
        settings.defaultFolderId = fallback
        return fallback
    }

    fun send() {
        val text = _composerText.value
        val refs = _draft.value
        if (text.isBlank() && refs.isEmpty()) {
            return
        }
        _composerText.value = ""
        _draft.value = emptyList()
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val content = joinAttachments(text, refs.map {
                    Attachment(it.id, it.markdown)
                })
                val folder = _folderId.value ?: defaultFolderId()
                repo.createNote(folder, content, collectBlobIds(content))
            }
            refresh()
            syncSoon()
        }
    }

    fun attach(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }
        viewModelScope.launch {
            var done = 0
            var skipped = 0
            var problem: String? = null
            for ((index, uri) in uris.withIndex()) {
                _status.value = "첨부 저장 중... (${index + 1}/${uris.size})"
                try {
                    val picked = withContext(Dispatchers.IO) {
                        readPicked(getApplication(), uri)
                    }
                    if (picked.size > MAX_ATTACH_BYTES) {
                        problem = "${picked.name}: ${formatBytes(MAX_ATTACH_BYTES)}를 넘어 건너뜁니다"
                        skipped++
                        continue
                    }
                    val id = attachments.store(picked) {
                        _status.value = it
                    }
                    val record = withContext(Dispatchers.IO) {
                        repo.blob(id)
                    }
                    val markdown = when {
                        record == null -> "[첨부](blob:$id)"
                        record.mime.startsWith("image/") -> "![](blob:$id)"
                        record.mime.startsWith("video/") -> "![${picked.name}](blob:$id)"
                        else -> "[${picked.name.replace(Regex("[\\[\\]]"), "")}](blob:$id)"
                    }
                    _draft.value = _draft.value + DraftRef(id, markdown)
                    done++
                } catch (err: Exception) {
                    problem = "첨부 저장에 실패했습니다"
                    skipped++
                }
            }
            _status.value = problem ?: "첨부 ${done}개 저장됨"
            delay(if (skipped > 0) 2500 else 1500)
            _status.value = null
        }
    }

    fun removeDraft(ref: DraftRef) {
        _draft.value = _draft.value.filterNot {
            it.id == ref.id
        }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.decrementRef(ref.id)
            }
        }
    }

    fun draftIds(): List<Long> = _draft.value.map {
        it.id
    }

    fun setEditing(id: Long?) {
        editingNoteId = id
    }

    fun saveEdit(note: Note, text: String, refs: List<Attachment>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val content = joinAttachments(text, refs)
                val blobIds = collectBlobIds(content)
                repo.editNote(note.id, content, blobIds)
                splitAttachments(note.content).refs
                    .filterNot {
                        blobIds.contains(it.id)
                    }
                    .forEach {
                        repo.decrementRef(it.id)
                    }
            }
            setEditing(null)
            refresh()
            syncSoon()
            _toast.value = "저장했습니다"
        }
    }

    fun togglePin(note: Note) = mutate {
        repo.setNotePinned(note.id, !note.pinned)
    }

    fun deleteNote(note: Note) = mutate("휴지통으로 옮겼습니다") {
        repo.softDeleteNote(note.id)
    }

    fun moveNote(note: Note, folderId: Long) = mutate("옮겼습니다") {
        repo.moveNote(note.id, folderId)
    }

    fun restoreNote(note: Note) = mutate("되살렸습니다") {
        repo.restoreNote(note.id)
    }

    fun purgeNote(note: Note) = mutate("영구 삭제했습니다") {
        repo.purgeNote(note.id)
    }

    fun emptyTrash() = mutate("휴지통을 비웠습니다") {
        repo.emptyTrash()
    }

    fun createFolder(name: String) = mutate {
        repo.createFolder(name)
    }

    fun renameFolder(folder: Folder, name: String) = mutate {
        repo.renameFolder(folder.id, name)
    }

    fun toggleFolderPin(folder: Folder) = mutate {
        repo.setFolderPinned(folder.id, !folder.pinned)
    }

    fun deleteFolder(folder: Folder, keepNotes: Boolean) = mutate("폴더를 삭제했습니다") {
        val notes: List<Note> = repo.notes(folder.id)
        if (keepNotes) {
            val fallback = repo.folders(true).firstOrNull {
                it.id != folder.id
            }?.id
                ?: repo.createFolder("Inbox")
            notes.forEach {
                repo.moveNote(it.id, fallback)
            }
            if (settings.defaultFolderId == folder.id) {
                settings.defaultFolderId = fallback
            }
        } else {
            notes.forEach {
                repo.softDeleteNote(it.id)
            }
            if (settings.defaultFolderId == folder.id) {
                settings.defaultFolderId = repo.folders(true).firstOrNull {
                    it.id != folder.id
                }?.id
            }
        }
        repo.softDeleteFolder(folder.id)
        if (_folderId.value == folder.id) {
            _folderId.value = null
        }
    }

    private fun mutate(message: String? = null, block: suspend () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                block()
            }
            refresh()
            message?.let {
                _toast.value = it
            }
            syncSoon()
        }
    }

    fun syncSoon(delayMs: Long = SYNC_DEBOUNCE_MS) {
        if (sync.conf() == null) {
            return
        }
        syncJob?.cancel()
        syncJob = viewModelScope.launch {
            delay(delayMs)
            runSync()
        }
    }

    suspend fun runSync(onProgress: (String) -> Unit = {}): SyncResult? {
        if (sync.conf() == null) {
            return null
        }
        _syncing.value = true
        return try {
            val result = sync.run(onProgress)
            refresh()
            result
        } catch (err: Exception) {
            null
        } finally {
            _syncing.value = false
        }
    }

    fun connectRealtime() = realtime.connect()

    fun stopRealtime() = realtime.stop()

    fun setTheme(value: String) {
        settings.theme = value
        _theme.value = value
    }

    fun showToast(message: String) {
        _toast.value = message
    }

    fun clearToast() {
        _toast.value = null
    }

    fun blobFile(record: BlobRecord): File = repo.blobFile(record)

    fun thumbFile(record: BlobRecord): File = repo.thumbFile(record)
}
