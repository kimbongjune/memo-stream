package com.example.memostream.ui

import com.example.memostream.data.*
import com.example.memostream.sync.*

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun FolderList(
    folders: List<Folder>,
    selected: Long?,
    counts: Map<Long, Int>,
    onSelect: (Long?) -> Unit,
    onCreate: (String) -> Unit,
    onRename: (Folder, String) -> Unit,
    onTogglePin: (Folder) -> Unit,
    onDelete: (Folder, Boolean) -> Unit,
    onSettings: () -> Unit,
    onTrash: () -> Unit,
) {
    var creating by remember {
        mutableStateOf(false)
    }
    var renaming by remember {
        mutableStateOf<Folder?>(null)
    }
    var deleting by remember {
        mutableStateOf<Folder?>(null)
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(memo.bg)
            .safeDrawingPadding()
            .padding(vertical = 8.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("폴더", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = {
                creating = true
            }) {
                Icon(Icons.Default.CreateNewFolder, contentDescription = "폴더 추가")
            }
        }

        NavRow(Icons.Default.Inbox, "전체", counts.values.sum(), selected == null) {
            onSelect(null)
        }
        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = memo.border)

        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(folders, key = {
                it.id
            }) { folder ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onSelect(folder.id)
                        }
                        .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (folder.pinned) Icons.Default.PushPin else Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = if (selected == folder.id) {
                            memo.accent
                        } else {
                            memo.fgSoft
                        },
                    )
                    Text(
                        folder.name,
                        modifier = Modifier.weight(1f).padding(start = 10.dp),
                        color = if (selected == folder.id) {
                            memo.accent
                        } else {
                            memo.fg
                        },
                    )
                    Text(
                        (counts[folder.id] ?: 0).toString(),
                        style = MaterialTheme.typography.labelSmall,
                        color = memo.fgSoft,
                    )
                    IconButton(onClick = {
                        renaming = folder
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, contentDescription = "이름 바꾸기", Modifier.size(16.dp))
                    }
                    IconButton(onClick = {
                        deleting = folder
                    }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "삭제", Modifier.size(16.dp))
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = memo.border)
        NavRow(Icons.Default.Delete, "휴지통", null, false, onTrash)
        NavRow(Icons.Default.Settings, "설정", null, false, onSettings)
    }

    if (creating) {
        TextPrompt("새 폴더", "폴더 이름", "") { name ->
            creating = false
            if (name != null && name.isNotBlank()) {
                onCreate(name)
            }
        }
    }
    renaming?.let { folder ->
        TextPrompt("이름 바꾸기", "폴더 이름", folder.name) { name ->
            renaming = null
            if (name != null && name.isNotBlank()) {
                onRename(folder, name)
            }
        }
    }
    deleting?.let { folder ->
        AlertDialog(
            containerColor = memo.bg,
            titleContentColor = memo.fg,
            textContentColor = memo.fg,
            onDismissRequest = {
                deleting = null
            },
            title = {
                Text("\"${folder.name}\" 폴더를 삭제할까요?")
            },
            text = {
                Text("안에 있는 메모를 어떻게 할지 선택해 주세요.")
            },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(folder, true)
                    deleting = null
                }) {
                    Text("메모는 Inbox로")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        deleting = null
                    }) {
                        Text("취소")
                    }
                    TextButton(onClick = {
                        onDelete(folder, false)
                        deleting = null
                    }) {
                        Text("메모도 삭제", color = memo.danger)
                    }
                }
            },
        )
    }
}

@Composable
private fun NavRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    count: Int?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(18.dp),
            tint = if (selected) memo.accent else memo.fgSoft,
        )
        Text(
            label,
            modifier = Modifier.weight(1f),
            color = if (selected) memo.accent else memo.fg,
        )
        count?.let {
            Text(it.toString(), style = MaterialTheme.typography.labelSmall,
                color = memo.fgSoft)
        }
    }
}

@Composable
fun TextPrompt(title: String, label: String, initial: String, onDone: (String?) -> Unit) {
    var value by remember {
        mutableStateOf(initial)
    }
    AlertDialog(
        containerColor = memo.bg,
        titleContentColor = memo.fg,
        textContentColor = memo.fg,
        onDismissRequest = {
            onDone(null)
        },
        title = {
            Text(title)
        },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = {
                    value = it
                },
                label = {
                    Text(label)
                },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                onDone(value)
            }) {
                Text("확인")
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDone(null)
            }) {
                Text("취소")
            }
        },
    )
}
