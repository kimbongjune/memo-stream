package com.example.memostream

import com.example.memostream.data.*
import com.example.memostream.sync.*
import com.example.memostream.ui.*

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ShareActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        val declaredType = intent.type
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))

            Intent.ACTION_SEND_MULTIPLE ->
                intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

            else -> emptyList()
        }

        setContent {
            val settings = remember {
                Settings(this)
            }
            MemoTheme(settings.theme) {
                ShareScreen(
                    text = text,
                    subject = subject,
                    uris = uris,
                    declaredType = declaredType,
                    onDone = {
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
private fun ShareScreen(
    text: String?,
    subject: String?,
    uris: List<Uri>,
    declaredType: String?,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember {
        MemoRepository(context)
    }
    val attachments = remember {
        Attachments(context, repo)
    }

    var folders by remember {
        mutableStateOf<List<Folder>>(emptyList())
    }
    var note by remember {
        mutableStateOf(text.orEmpty())
    }
    var busy by remember {
        mutableStateOf(false)
    }
    var progress by remember {
        mutableStateOf("")
    }
    var creating by remember {
        mutableStateOf(false)
    }

    LaunchedEffect(Unit) {
        folders = withContext(Dispatchers.IO) {
            repo.ensureInbox()
            repo.folders(true)
        }
    }

    fun save(folder: Folder) {
        if (busy) {
            return
        }
        busy = true
        scope.launch {
            runCatching {
                val ids = ArrayList<Long>()
                val marks = ArrayList<String>()
                uris.forEachIndexed { index, uri ->
                    progress = "첨부 저장 중... (${index + 1}/${uris.size})"
                    val picked = withContext(Dispatchers.IO) {
                        readPicked(context, uri, declaredType)
                    }
                    if (picked.size > MAX_ATTACH_BYTES) {
                        return@forEachIndexed
                    }
                    val id = attachments.store(picked) {
                        progress = it
                    }
                    val record = withContext(Dispatchers.IO) {
                        repo.blob(id)
                    }
                    ids.add(id)
                    marks.add(
                        when {
                            record == null -> "[첨부](blob:$id)"
                            record.mime.startsWith("image/") -> "![](blob:$id)"
                            record.mime.startsWith("video/") -> "![${picked.name}](blob:$id)"
                            else -> "[${picked.name.replace(Regex("[\\[\\]]"), "")}](blob:$id)"
                        }
                    )
                }
                progress = "저장 중..."
                withContext(Dispatchers.IO) {
                    val body = joinAttachments(
                        note.trim(),
                        marks.mapIndexed { index, mark ->
                            Attachment(ids[index], mark)
                        }
                    )
                    val sourceUrl = text?.trim()?.takeIf {
                        it.startsWith("http://") || it.startsWith("https://")
                    }
                    repo.createNote(
                        folderId = folder.id,
                        content = body,
                        blobIds = collectBlobIds(body),
                        sourceUrl = sourceUrl,
                        sourceTitle = subject,
                    )
                }
            }.onSuccess {
                Toast.makeText(context, "${folder.name}에 저장했습니다", Toast.LENGTH_SHORT).show()
                onDone()
            }.onFailure {
                Toast.makeText(context, "저장에 실패했습니다", Toast.LENGTH_SHORT).show()
                busy = false
            }
        }
    }

    Scaffold { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .safeDrawingPadding()
        ) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("어디에 저장할까요?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    creating = true
                }, enabled = !busy) {
                    Icon(Icons.Default.CreateNewFolder, contentDescription = "폴더 추가")
                }
            }

            if (uris.isNotEmpty()) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(uris) { uri ->
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(10.dp))
                        ) {
                            coil3.compose.AsyncImage(
                                model = uri,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = note,
                onValueChange = {
                    note = it
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(110.dp),
                placeholder = {
                    Text("메모를 덧붙이세요 (선택)")
                },
                enabled = !busy,
            )

            HorizontalDivider(color = memo.border)

            if (busy) {
                Row(
                    Modifier.fillMaxWidth().padding(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(progress.ifEmpty {
                        "저장 중..."
                    })
                }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(folders, key = {
                        it.id
                    }) { folder ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    save(folder)
                                }
                                .padding(horizontal = 20.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (folder.pinned) Icons.Default.PushPin else Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = memo.accent,
                            )
                            Text(
                                folder.name,
                                Modifier.padding(start = 14.dp).weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        HorizontalDivider(color = memo.border)
                    }
                }
            }
        }
    }

    if (creating) {
        TextPrompt("새 폴더", "폴더 이름", "") { name ->
            creating = false
            if (!name.isNullOrBlank()) {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        repo.createFolder(name)
                    }
                    folders = withContext(Dispatchers.IO) {
                        repo.folders(true)
                    }
                }
            }
        }
    }
}
