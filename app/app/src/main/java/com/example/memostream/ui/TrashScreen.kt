package com.example.memostream.ui

import com.example.memostream.data.*
import com.example.memostream.sync.*

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun TrashScreen(
    notes: List<Note>,
    onRestore: (Note) -> Unit,
    onPurge: (Note) -> Unit,
    onEmpty: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmEmpty by remember {
        mutableStateOf(false)
    }

    Column(modifier.fillMaxSize().navigationBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "휴지통의 메모는 30일 뒤 자동으로 지워집니다.",
                Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = memo.fgSoft,
            )
            if (notes.isNotEmpty()) {
                OutlinedButton(onClick = {
                    confirmEmpty = true
                }) {
                    Text("비우기")
                }
            }
        }
        HorizontalDivider(color = memo.border)
        if (notes.isEmpty()) {
            Text(
                "휴지통이 비었습니다",
                Modifier.fillMaxWidth().padding(32.dp),
                color = memo.fgSoft,
            )
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(notes, key = {
                    it.id
                }) { note ->
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(
                            splitAttachments(note.content).text.ifBlank {
                                "(첨부만 있는 메모)"
                            },
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                daysAgoLabel(note.deletedAt ?: 0),
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.labelSmall,
                                color = memo.fgSoft,
                            )
                            TextButton(onClick = {
                                onRestore(note)
                            }) {
                                Text("복원")
                            }
                            TextButton(onClick = {
                                onPurge(note)
                            }) {
                                Text("영구 삭제", color = memo.danger)
                            }
                        }
                    }
                    HorizontalDivider(color = memo.border)
                }
            }
        }
    }

    if (confirmEmpty) {
        AlertDialog(
            containerColor = memo.bg,
            titleContentColor = memo.fg,
            textContentColor = memo.fg,
            onDismissRequest = {
                confirmEmpty = false
            },
            title = {
                Text("휴지통을 비울까요?")
            },
            text = {
                Text("휴지통의 메모가 모두 영구 삭제됩니다. 되돌릴 수 없습니다.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                    onEmpty()
                }) {
                    Text("비우기", color = memo.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmEmpty = false
                }) {
                    Text("취소")
                }
            },
        )
    }
}

fun daysAgoLabel(timestamp: Long): String {
    val days = ((System.currentTimeMillis() - timestamp) / 86_400_000L).toInt()
    return when {
        days <= 0 -> "오늘 삭제"
        days == 1 -> "어제 삭제"
        else -> "${days}일 전 삭제"
    }
}
