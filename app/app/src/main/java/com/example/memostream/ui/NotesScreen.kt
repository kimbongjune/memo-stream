package com.example.memostream.ui

import com.example.memostream.data.*
import com.example.memostream.sync.*

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.content.contentReceiver
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import java.io.File

private data class Row(val key: String)

@Composable
fun NotesScreen(
    state: AppState,
    ui: NotesUi,
    search: SearchState,
    blobs: Map<Long, BlobRecord>,
    status: String?,
    draft: List<DraftRef>,
    composerText: String,
    onOpenMedia: (BlobRecord) -> Unit,
    onMediaMenu: (BlobRecord) -> Unit,
    onLinkClick: (String) -> Unit,
    onNoteMenu: (Note) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val grouped = remember(ui.notes, ui.folderNames, search) {
        buildRows(ui, search, state.folderId.value)
    }

    LaunchedEffect(ui.notes.lastOrNull()?.id, ui.notes.size) {
        if (grouped.isNotEmpty()) {
            listState.scrollToItem(grouped.size - 1)
        }
    }

    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    LaunchedEffect(imeBottom) {
        if (grouped.isNotEmpty()) {
            listState.scrollToItem(grouped.size - 1)
        }
    }

    Box(modifier.fillMaxSize().background(memo.bgSoft)) {
    Column(
        Modifier
            .fillMaxSize()
            .background(memo.bg)
            .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
    ) {
        if (search.active) {
            SearchBar(search, state)
        }
        val focusManager = LocalFocusManager.current
        Box(
            Modifier
                .weight(1f)
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        focusManager.clearFocus()
                    })
                }
        ) {
            if (grouped.isEmpty()) {
                Text(
                    emptyText(search),
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                    color = memo.fgSoft,
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                ) {
                    if (ui.hiddenCount > 0) {
                        item("more") {
                            TextButton(
                                onClick = state::loadMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("이전 메모 ${ui.hiddenCount}개 더 보기")
                            }
                        }
                    }
                    items(grouped, key = {
                        it.key
                    }) { row ->
                        when (row) {
                            is ListRow.Separator -> Separator(row.label)
                            is ListRow.Bubble -> NoteBubble(
                                note = row.note,
                                folderName = row.folderName,
                                highlight = if (search.active) search.query.trim() else "",
                                blobs = blobs,
                                blobFile = state::blobFile,
                                thumbFile = state::thumbFile,
                                onOpenMedia = onOpenMedia,
                                onMediaMenu = onMediaMenu,
                                onLinkClick = onLinkClick,
                                onMenu = {
                                    onNoteMenu(row.note)
                                },
                            )
                        }
                    }
                }
            }
        }
        Composer(state, draft, composerText, status)
    }
    }
}

private fun emptyText(search: SearchState): String = when {
    !search.active -> "아직 메모가 없습니다. 아래에 첫 메모를 적어보세요."
    search.kind.isNotEmpty() && search.query.isBlank() ->
        "${filterLabel(search.kind)}이(가) 있는 메모가 없습니다"
    else -> "검색 결과가 없습니다"
}

fun filterLabel(kind: String): String = when (kind) {
    "image" -> "사진"
    "video" -> "동영상"
    else -> "파일"
}

sealed interface ListRow {
    val key: String

    data class Separator(val label: String, override val key: String) : ListRow
    data class Bubble(val note: Note, val folderName: String?, override val key: String) : ListRow
}

private fun buildRows(ui: NotesUi, search: SearchState, folderId: Long?): List<ListRow> {
    val rows = ArrayList<ListRow>()
    val pinned = ui.notes.filter {
        it.pinned
    }
    val rest = ui.notes.filter {
        !it.pinned
    }
    val showFolderTag = folderId == null

    if (pinned.isNotEmpty()) {
        rows.add(ListRow.Separator("고정됨", "sep-pinned"))
        pinned.forEach { note ->
            rows.add(ListRow.Bubble(note, if (showFolderTag) ui.folderNames[note.folderId] else null, "n${note.id}p"))
        }
    }

    var lastDay: String? = null
    rest.forEach { note ->
        val day = localDateKey(note.createdAt)
        if (day != lastDay) {
            rows.add(ListRow.Separator(dateLabel(day), "sep-$day"))
            lastDay = day
        }
        rows.add(ListRow.Bubble(note, if (showFolderTag) ui.folderNames[note.folderId] else null, "n${note.id}"))
    }
    return rows
}

@Composable
private fun Separator(label: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = memo.fgSoft,
            modifier = Modifier
                .clip(RoundedCornerShape(MemoRadius))
                .background(memo.bgHover)
                .padding(horizontal = 10.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun SearchBar(search: SearchState, state: AppState) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        OutlinedTextField(
            value = search.query,
            onValueChange = state::setQuery,
            placeholder = {
                Text("메모 검색...")
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                IconButton(onClick = state::toggleSearch) {
                    Icon(Icons.Default.Close, contentDescription = "검색 닫기")
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = search.global, onCheckedChange = state::setGlobal)
            Text("전체 폴더", style = MaterialTheme.typography.labelMedium)
            listOf("image", "video", "file").forEach { kind ->
                AssistChip(
                    onClick = {
                        state.setKind(if (search.kind == kind) "" else kind)
                    },
                    label = {
                        Text(filterLabel(kind), style = MaterialTheme.typography.labelSmall)
                    },
                    modifier = Modifier.padding(start = 6.dp),
                    colors = if (search.kind == kind) {
                        AssistChipDefaults.assistChipColors(
                            containerColor = memo.accentSoft
                        )
                    } else AssistChipDefaults.assistChipColors(),
                )
            }
        }
    }
    HorizontalDivider(color = memo.border)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Composer(
    state: AppState,
    draft: List<DraftRef>,
    text: String,
    status: String?,
) {
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        state.attach(uris)
    }

    var pending by remember {
        mutableStateOf<Uri?>(null)
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pending
        pending = null
        if (ok && uri != null) {
            state.attach(listOf(uri))
        }
    }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok ->
        val uri = pending
        pending = null
        if (ok && uri != null) {
            state.attach(listOf(uri))
        }
    }

    var sheet by remember {
        mutableStateOf(false)
    }
    var focused by remember {
        mutableStateOf(false)
    }

    Column(Modifier.fillMaxWidth().background(memo.bg)) {
        HorizontalDivider(color = memo.border)
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = memo.fgSoft,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        if (draft.isNotEmpty()) {
            LazyRow(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(draft, key = {
                    it.id
                }) { ref ->
                    DraftChip(state, ref)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    sheet = true
                },
                modifier = Modifier.size(42.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "첨부", tint = memo.fgSoft)
            }
            BasicTextField(
                value = text,
                onValueChange = state::setComposerText,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .heightIn(min = 42.dp, max = 160.dp)
                    .clip(RoundedCornerShape(MemoRadius))
                    .background(if (focused) memo.bg else memo.bgSoft)
                    .border(
                        1.dp,
                        if (focused) memo.accent else memo.border,
                        RoundedCornerShape(MemoRadius),
                    )
                    .onFocusChanged {
                        focused = it.isFocused
                    }
                    .contentReceiver { received ->
                        val data = received.clipEntry.clipData
                        val uris = (0 until data.itemCount).mapNotNull { index ->
                            data.getItemAt(index).uri
                        }
                        if (uris.isEmpty()) {
                            received
                        } else {
                            state.attach(uris)
                            null
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = memo.fg),
                cursorBrush = SolidColor(memo.accent),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            "메모를 적으세요",
                            style = MaterialTheme.typography.bodyMedium,
                            color = memo.fgSoft,
                        )
                    }
                    inner()
                },
            )
            val canSend = text.isNotBlank() || draft.isNotEmpty()
            Box(
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(MemoRadius))
                    .background(if (canSend) memo.accent else memo.bgHover)
                    .clickable(enabled = canSend, onClick = state::send),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "보내기",
                    tint = if (canSend) Color.White else memo.fgSoft,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
    }

    if (sheet) {
        AttachSheet(
            onDismiss = {
                sheet = false
            },
            onPhoto = {
                sheet = false
                val file = Capture.newFile(context, "jpg")
                val uri = Capture.uriFor(context, file)
                pending = uri
                photo.launch(uri)
            },
            onVideo = {
                sheet = false
                val file = Capture.newFile(context, "mp4")
                val uri = Capture.uriFor(context, file)
                pending = uri
                video.launch(uri)
            },
            onFile = {
                sheet = false
                picker.launch(arrayOf("*/*"))
            },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AttachSheet(
    onDismiss: () -> Unit,
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onFile: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = memo.bg,
        contentColor = memo.fg,
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 12.dp)) {
            AttachOption(Icons.Default.PhotoCamera, "사진 찍기", onPhoto)
            AttachOption(Icons.Default.Videocam, "동영상 찍기", onVideo)
            AttachOption(Icons.Default.AttachFile, "파일 선택", onFile)
        }
    }
}

@Composable
private fun AttachOption(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(22.dp), tint = memo.accent)
        Text(label, Modifier.padding(start = 18.dp), color = memo.fg)
    }
}

@Composable
private fun DraftChip(state: AppState, ref: DraftRef) {
    val blobs by state.blobs.collectAsState()
    val record = blobs[ref.id]
    Box {
        if (record != null && record.mime.startsWith("image/")) {
            val thumb = state.thumbFile(record).takeIf {
                it.exists()
            } ?: state.blobFile(record)
            BlobImage(
                thumb,
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp)),
                ContentScale.Crop,
            )
        } else {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(memo.bgSoft),
                contentAlignment = Alignment.Center,
            ) {
                Text("파일", style = MaterialTheme.typography.labelSmall)
            }
        }
        IconButton(
            onClick = {
                state.removeDraft(ref)
            },
            modifier = Modifier.size(20.dp).align(Alignment.TopEnd),
        ) {
            Icon(Icons.Default.Close, contentDescription = "첨부 제거", modifier = Modifier.size(14.dp))
        }
    }
}
