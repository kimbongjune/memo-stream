package com.example.memostream.ui

import com.example.memostream.data.*
import com.example.memostream.sync.*

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val LONG_NOTE_CHARS = 500

private fun mediaRatio(record: BlobRecord): Float {
    if (record.width <= 0 || record.height <= 0) {
        return 4f / 3f
    }
    return (record.width.toFloat() / record.height).coerceIn(0.5f, 2.2f)
}

fun timeLabel(timestamp: Long): String =
    SimpleDateFormat("a h:mm", Locale.KOREAN).format(Date(timestamp))

fun dateLabel(key: String): String {
    val today = localDateKey(System.currentTimeMillis())
    val yesterday = localDateKey(System.currentTimeMillis() - 86_400_000)
    return when (key) {
        today -> "오늘"
        yesterday -> "어제"
        else -> key
    }
}

fun domainOf(url: String): String = runCatching {
    java.net.URI(url).host?.removePrefix("www.") ?: url
}.getOrDefault(url)

@Composable
fun NoteBubble(
    note: Note,
    folderName: String?,
    highlight: String,
    blobs: Map<Long, BlobRecord>,
    blobFile: (BlobRecord) -> File,
    thumbFile: (BlobRecord) -> File,
    onOpenMedia: (BlobRecord) -> Unit,
    onLinkClick: (String) -> Unit,
    onMenu: () -> Unit,
) {
    var expanded by remember(note.id) {
        mutableStateOf(note.content.length <= LONG_NOTE_CHARS)
    }
    val accent = memo.accent
    val blocks = remember(note.content) {
        blocksOf(note.content)
    }

    val shape = RoundedCornerShape(MemoRadius)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(shape)
            .background(memo.bgSoft)
            .border(1.dp, memo.border, shape)
            .drawBehind {
                if (note.pinned) {
                    drawRect(
                        color = accent,
                        size = androidx.compose.ui.geometry.Size(3.dp.toPx(), size.height),
                    )
                }
            }
            .padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(end = 4.dp)) {
                blocks.forEach { block ->
                    when (block) {
                        is Block.Text -> MarkdownBody(
                            markdown = if (expanded) {
                                block.markdown
                            } else {
                                block.markdown.take(LONG_NOTE_CHARS)
                            },
                            highlight = highlight,
                            modifier = Modifier.padding(bottom = 4.dp),
                            onLinkClick = onLinkClick,
                        )

                        is Block.Media -> {
                            val record = blobs[block.blobId]
                            if (record != null) {
                                val file = blobFile(record)
                                val mediaShape = RoundedCornerShape(12.dp)
                                when {
                                    record.mime.startsWith("video/") ->
                                        VideoThumb(
                                            thumbFile(record),
                                            null,
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(mediaShape)
                                        ) {
                                            onOpenMedia(record)
                                        }

                                    record.mime.startsWith("image/") ->
                                        BlobImage(
                                            file,
                                            Modifier
                                                .fillMaxWidth()
                                                .clip(mediaShape)
                                                .clickable {
                                                    onOpenMedia(record)
                                                },
                                            ContentScale.FillWidth,
                                        )

                                    else -> FileChip(
                                        record.name.ifEmpty {
                                            "첨부"
                                        },
                                        record.size,
                                        Modifier.fillMaxWidth(),
                                    ) {
                                        onOpenMedia(record)
                                    }
                                }
                            }
                        }
                    }
                }
            }
            IconButton(onClick = onMenu, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.MoreVert,
                    contentDescription = "메모 메뉴",
                    tint = memo.fgSoft,
                )
            }
        }

        if (note.content.length > LONG_NOTE_CHARS) {
            TextButton(onClick = {
                expanded = !expanded
            }) {
                Text(if (expanded) "접기" else "더보기", style = MaterialTheme.typography.labelMedium)
            }
        }

        note.sourceUrl?.let { url ->
            Text(
                domainOf(url),
                style = MaterialTheme.typography.labelSmall,
                color = memo.accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .clickable {
                        onLinkClick(url)
                    },
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(top = 2.dp, end = 8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (note.pinned) {
                Icon(
                    Icons.Default.PushPin,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = memo.fgSoft,
                )
            }
            folderName?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = memo.fgSoft,
                    modifier = Modifier.padding(start = 4.dp, end = 6.dp),
                )
            }
            Text(
                timeLabel(note.createdAt),
                style = MaterialTheme.typography.labelSmall,
                color = memo.fgSoft,
            )
        }
    }
}
