package com.example.memostream.ui

import com.example.memostream.data.*
import com.example.memostream.sync.formatBytes

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import java.io.File
import java.util.Locale
import kotlinx.coroutines.delay

@Composable
fun BlobImage(
    file: File,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.FillWidth,
) {
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(file).build(),
        contentDescription = null,
        modifier = modifier,
        contentScale = contentScale,
    )
}

private fun clock(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
}

@Composable
private fun rememberPlayer(file: File, loop: Boolean, autoPlay: Boolean): ExoPlayer {
    val context = LocalContext.current
    val player = remember(file.path) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(file.toURI().toString()))
            repeatMode = if (loop) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF
            if (loop) {
                volume = 0f
            }
            prepare()
            playWhenReady = autoPlay
        }
    }
    DisposableEffect(player) {
        onDispose {
            player.release()
        }
    }
    return player
}

@Composable
fun VideoThumb(thumb: File?, duration: String?, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        if (thumb != null && thumb.exists()) {
            BlobImage(thumb, Modifier.fillMaxWidth())
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(memo.bgSoft)
            )
        }
        Box(
            Modifier
                .size(50.dp)
                .clip(RoundedCornerShape(25.dp))
                .background(Color.Black.copy(alpha = 0.5f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = "재생", tint = Color.White)
        }
        duration?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
fun FileChip(name: String, size: Long, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(memo.bgSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Description,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = memo.fgSoft,
        )
        Column(Modifier.padding(start = 10.dp)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                formatBytes(size),
                style = MaterialTheme.typography.labelSmall,
                color = memo.fgSoft,
            )
        }
    }
}

@Composable
fun MediaViewer(
    record: BlobRecord,
    file: File,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onCopy: () -> Unit,
    onShare: () -> Unit,
    onOpen: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .safeDrawingPadding()
    ) {
        if (record.mime.startsWith("video/")) {
            VideoPlayer(file, Modifier.align(Alignment.Center))
        } else {
            BlobImage(file, Modifier.fillMaxSize(), ContentScale.Fit)
        }

        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = onSave) {
                Icon(Icons.Default.Download, contentDescription = "저장", tint = Color.White)
            }
            IconButton(onClick = onCopy) {
                Icon(Icons.Default.ContentCopy, contentDescription = "복사", tint = Color.White)
            }
            IconButton(onClick = onShare) {
                Icon(Icons.Default.Share, contentDescription = "공유", tint = Color.White)
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Default.OpenInNew, contentDescription = "다른 앱으로 열기", tint = Color.White)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "닫기", tint = Color.White)
            }
        }
    }
}

@Composable
fun VideoPlayer(file: File, modifier: Modifier = Modifier) {
    val player = rememberPlayer(file, loop = false, autoPlay = true)
    var playing by remember {
        mutableStateOf(true)
    }
    var position by remember {
        mutableLongStateOf(0L)
    }
    var duration by remember {
        mutableLongStateOf(0L)
    }
    var scrubbing by remember {
        mutableStateOf(false)
    }
    var scrubValue by remember {
        mutableFloatStateOf(0f)
    }

    LaunchedEffect(player) {
        while (true) {
            playing = player.isPlaying
            if (!scrubbing) {
                position = player.currentPosition.coerceAtLeast(0)
            }
            duration = player.duration.takeIf {
                it > 0
            } ?: 0
            delay(200)
        }
    }

    Box(modifier.fillMaxWidth()) {
        PlayerSurface(player = player, modifier = Modifier.fillMaxWidth())

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    if (player.isPlaying) {
                        player.pause()
                    } else {
                        player.play()
                    }
                }) {
                    Icon(
                        if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (playing) "일시정지" else "재생",
                        tint = Color.White,
                    )
                }
                Text(clock(position), color = Color.White, style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = if (scrubbing) scrubValue else {
                        if (duration > 0) position.toFloat() / duration else 0f
                    },
                    onValueChange = {
                        scrubbing = true
                        scrubValue = it
                    },
                    onValueChangeFinished = {
                        if (duration > 0) {
                            player.seekTo((scrubValue * duration).toLong())
                        }
                        scrubbing = false
                    },
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color.White,
                        inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                    ),
                )
                Text(clock(duration), color = Color.White, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
