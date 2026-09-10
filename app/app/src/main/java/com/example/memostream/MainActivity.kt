package com.example.memostream

import com.example.memostream.data.*
import com.example.memostream.sync.*
import com.example.memostream.ui.*

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MemoApp()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MemoApp() {
    val state: AppState = viewModel()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val screen by state.screen.collectAsState()
    val folderId by state.folderId.collectAsState()
    val search by state.search.collectAsState()
    val ui by state.ui.collectAsState()
    val trash by state.trash.collectAsState()
    val draft by state.draft.collectAsState()
    val composerText by state.composerText.collectAsState()
    val status by state.status.collectAsState()
    val toast by state.toast.collectAsState()
    val syncing by state.syncing.collectAsState()

    val theme by state.theme.collectAsState()
    var viewing by remember {
        mutableStateOf<BlobRecord?>(null)
    }
    var mediaMenu by remember {
        mutableStateOf<BlobRecord?>(null)
    }
    var savingRecord by remember {
        mutableStateOf<BlobRecord?>(null)
    }
    var menuNote by remember {
        mutableStateOf<Note?>(null)
    }
    var editingNote by remember {
        mutableStateOf<Note?>(null)
    }
    var movingNote by remember {
        mutableStateOf<Note?>(null)
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbar = remember {
        SnackbarHostState()
    }

    val drawerEngaged = drawerState.currentValue != DrawerValue.Closed ||
        drawerState.targetValue != DrawerValue.Closed

    val imeVisible = WindowInsets.isImeVisible
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = imeVisible) {
        focusManager.clearFocus()
    }

    BackHandler(enabled = !imeVisible && drawerEngaged) {
        scope.launch {
            drawerState.close()
        }
    }
    BackHandler(enabled = !imeVisible && !drawerEngaged && screen != Screen.NOTES) {
        state.setScreen(Screen.NOTES)
    }
    BackHandler(enabled = !imeVisible && !drawerEngaged && screen == Screen.NOTES && search.active) {
        state.toggleSearch()
    }

    val activity = context as? android.app.Activity
    var exitArmed by remember {
        mutableStateOf(false)
    }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            kotlinx.coroutines.delay(2000)
            exitArmed = false
        }
    }
    BackHandler(enabled = !imeVisible && !drawerEngaged && screen == Screen.NOTES && !search.active) {
        if (exitArmed) {
            activity?.finish()
        } else {
            exitArmed = true
            state.showToast("한 번 더 누르면 닫힙니다")
        }
    }

    LaunchedEffect(toast) {
        toast?.let {
            snackbar.showSnackbar(it)
            state.clearToast()
        }
    }

    val blobs by state.blobs.collectAsState()
    val folderCounts by state.folderCounts.collectAsState()

    MemoTheme(theme) {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    modifier = Modifier.width(300.dp),
                    drawerContainerColor = memo.bg,
                    drawerContentColor = memo.fg,
                    drawerShape = RoundedCornerShape(topEnd = 12.dp, bottomEnd = 12.dp),
                ) {
                    FolderList(
                        folders = ui.folders,
                        selected = folderId,
                        counts = folderCounts,
                        onSelect = {
                            state.selectFolder(it)
                            scope.launch {
                                drawerState.close()
                            }
                        },
                        onCreate = state::createFolder,
                        onRename = state::renameFolder,
                        onTogglePin = state::toggleFolderPin,
                        onDelete = state::deleteFolder,
                        onSettings = {
                            state.setScreen(Screen.SETTINGS)
                            scope.launch {
                                drawerState.close()
                            }
                        },
                        onTrash = {
                            state.setScreen(Screen.TRASH)
                            scope.launch {
                                drawerState.close()
                            }
                        },
                    )
                }
            },
        ) {
            Scaffold(
                containerColor = memo.bg,
                contentWindowInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
                snackbarHost = {
                    SnackbarHost(
                        snackbar,
                        modifier = Modifier
                            .windowInsetsPadding(
                                WindowInsets.ime.union(WindowInsets.navigationBars)
                            )
                            .padding(bottom = 72.dp),
                    )
                },
                topBar = {
                    TopAppBar(
                        title = {
                            Text(
                                when (screen) {
                                    Screen.SETTINGS -> "설정"
                                    Screen.TRASH -> "휴지통"
                                    Screen.NOTES -> ui.folders.firstOrNull {
                                        it.id == folderId
                                    }?.name ?: "전체"
                                }
                            )
                        },
                        navigationIcon = {
                            if (screen == Screen.NOTES) {
                                IconButton(onClick = {
                                    scope.launch {
                                        drawerState.open()
                                    }
                                }) {
                                    Icon(Icons.Default.Menu, contentDescription = "폴더")
                                }
                            } else {
                                IconButton(onClick = {
                                    state.setScreen(Screen.NOTES)
                                }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "뒤로")
                                }
                            }
                        },
                        actions = {
                            if (screen == Screen.NOTES) {
                                if (syncing) {
                                    CircularProgressIndicator(Modifier.padding(12.dp).size(18.dp), strokeWidth = 2.dp)
                                } else if (state.sync.conf() != null) {
                                    IconButton(onClick = {
                                        scope.launch {
                                            state.runSync()
                                        }
                                    }) {
                                        Icon(Icons.Default.Sync, contentDescription = "동기화")
                                    }
                                }
                                IconButton(onClick = state::toggleSearch) {
                                    Icon(Icons.Default.Search, contentDescription = "검색")
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (screen) {
                        Screen.NOTES -> NotesScreen(
                            state = state,
                            ui = ui,
                            search = search,
                            blobs = blobs,
                            status = status,
                            draft = draft,
                            composerText = composerText,
                            onOpenMedia = { record ->
                                if (record.mime.startsWith("image/") || record.mime.startsWith("video/")) {
                                    viewing = record
                                } else {
                                    mediaMenu = record
                                }
                            },
                            onMediaMenu = { record ->
                                mediaMenu = record
                            },
                            onLinkClick = { url ->
                                runCatching {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
                                }
                            },
                            onNoteMenu = {
                                menuNote = it
                            },
                        )

                        Screen.SETTINGS -> SettingsScreen(state)

                        Screen.TRASH -> TrashScreen(
                            notes = trash,
                            onRestore = state::restoreNote,
                            onPurge = state::purgeNote,
                            onEmpty = state::emptyTrash,
                        )
                    }
                }
            }
        }

        val saver = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("*/*")
        ) { target ->
            val record = savingRecord
            savingRecord = null
            if (target != null && record != null) {
                val ok = writeTo(context, state.blobFile(record), target)
                state.showToast(if (ok) "저장했습니다" else "저장에 실패했습니다")
            }
        }

        fun save(record: BlobRecord, file: java.io.File) {
            if (saveToDownloads(context, record, file)) {
                state.showToast("다운로드에 저장했습니다")
            } else {
                savingRecord = record
                saver.launch(suggestedName(record))
            }
        }

        mediaMenu?.let { record ->
            val file = state.blobFile(record)
            MediaMenu(
                record = record,
                onDismiss = {
                    mediaMenu = null
                },
                onSave = {
                    save(record, file)
                    mediaMenu = null
                },
                onCopy = {
                    if (!copyMedia(context, record, file)) {
                        state.showToast("복사에 실패했습니다")
                    }
                    mediaMenu = null
                },
                onShare = {
                    shareMedia(context, record, file, null)
                    mediaMenu = null
                },
                onOpen = {
                    openExternally(context, record, file)
                    mediaMenu = null
                },
            )
        }

        viewing?.let { record ->
            val file = state.blobFile(record)
            BackHandler {
                viewing = null
            }
            MediaViewer(
                record = record,
                file = file,
                onClose = {
                    viewing = null
                },
                onSave = {
                    save(record, file)
                },
                onCopy = {
                    if (!copyMedia(context, record, file)) {
                        state.showToast("복사에 실패했습니다")
                    }
                },
                onShare = {
                    shareMedia(context, record, file, null)
                },
                onOpen = {
                    openExternally(context, record, file)
                },
            )
        }

        menuNote?.let { note ->
            NoteMenu(
                note = note,
                onDismiss = {
                    menuNote = null
                },
                onEdit = {
                    editingNote = note
                    menuNote = null
                },
                onPin = {
                    state.togglePin(note)
                    menuNote = null
                },
                onMove = {
                    movingNote = note
                    menuNote = null
                },
                onCopy = {
                    val split = splitAttachments(note.content)
                    val media = split.refs.firstNotNullOfOrNull {
                        blobs[it.id]
                    }
                    val ok = when {
                        media != null && split.text.isBlank() ->
                            copyMedia(context, media, state.blobFile(media))
                        else -> {
                            context.getSystemService(android.content.ClipboardManager::class.java)
                                .setPrimaryClip(
                                    android.content.ClipData.newPlainText("memo", split.text)
                                )
                            true
                        }
                    }
                    if (!ok) {
                        state.showToast("복사에 실패했습니다")
                    }
                    menuNote = null
                },
                onShare = {
                    val split = splitAttachments(note.content)
                    val media = split.refs.firstNotNullOfOrNull {
                        blobs[it.id]
                    }
                    if (media != null) {
                        shareMedia(context, media, state.blobFile(media), split.text.ifBlank {
                            null
                        })
                    } else {
                        context.startActivity(
                            Intent.createChooser(
                                Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, split.text)
                                },
                                "공유"
                            )
                        )
                    }
                    menuNote = null
                },
                onDelete = {
                    state.deleteNote(note)
                    menuNote = null
                },
            )
        }

        editingNote?.let { note ->
            EditNoteDialog(
                note = note,
                onDismiss = {
                    editingNote = null
                },
                onSave = { text, refs ->
                    state.saveEdit(note, text, refs)
                    editingNote = null
                },
            )
        }

        movingNote?.let { note ->
            FolderPickerDialog(
                folders = ui.folders,
                title = "메모 옮기기",
                onDismiss = {
                    movingNote = null
                },
                onPick = { folder ->
                    state.moveNote(note, folder.id)
                    movingNote = null
                },
            )
        }
    }
}
