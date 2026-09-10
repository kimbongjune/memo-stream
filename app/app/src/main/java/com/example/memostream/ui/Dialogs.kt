package com.example.memostream.ui

import com.example.memostream.data.*
import com.example.memostream.sync.*

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Share
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteMenu(
    note: Note,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    BasicAlertDialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(MemoRadius),
            color = memo.bg,
            contentColor = memo.fg,
            border = BorderStroke(1.dp, memo.border),
        ) {
            Column(Modifier.padding(vertical = 8.dp)) {
                MenuItem(Icons.Default.Edit, "편집", onEdit)
                MenuItem(Icons.Default.PushPin, if (note.pinned) "고정 해제" else "고정", onPin)
                MenuItem(Icons.Default.DriveFileMove, "폴더 옮기기", onMove)
                MenuItem(Icons.Default.ContentCopy, "복사", onCopy)
                MenuItem(Icons.Default.Share, "공유", onShare)
                MenuItem(Icons.Default.Delete, "삭제", onDelete, danger = true)
            }
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, label: String, onClick: () -> Unit, danger: Boolean = false) {
    val tint = if (danger) memo.danger else memo.fg
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(18.dp), tint = tint)
        Text(label, Modifier.padding(start = 14.dp), color = tint)
    }
}

@Composable
fun EditNoteDialog(
    note: Note,
    onDismiss: () -> Unit,
    onSave: (String, List<Attachment>) -> Unit,
) {
    val split = remember(note.id) {
        splitAttachments(note.content)
    }
    var text by remember(note.id) {
        mutableStateOf(split.text)
    }
    var refs by remember(note.id) {
        mutableStateOf(split.refs)
    }

    AlertDialog(
        containerColor = memo.bg,
        titleContentColor = memo.fg,
        textContentColor = memo.fg,
        onDismissRequest = onDismiss,
        title = {
            Text("메모 편집")
        },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                )
                if (refs.isNotEmpty()) {
                    Text(
                        "첨부 ${refs.size}개",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    refs.forEach { ref ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ref.markdown, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = {
                                refs = refs.filterNot {
                                    it.id == ref.id
                                }
                            }) {
                                Text("제거")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(text, refs)
            }) {
                Text("저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}

@Composable
fun FolderPickerDialog(
    folders: List<Folder>,
    title: String,
    onDismiss: () -> Unit,
    onPick: (Folder) -> Unit,
) {
    AlertDialog(
        containerColor = memo.bg,
        titleContentColor = memo.fg,
        textContentColor = memo.fg,
        onDismissRequest = onDismiss,
        title = {
            Text(title)
        },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                items(folders, key = {
                    it.id
                }) { folder ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onPick(folder)
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, Modifier.size(18.dp))
                        Text(folder.name, Modifier.padding(start = 12.dp))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소")
            }
        },
    )
}
