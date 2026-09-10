package com.example.memostream.ui

import com.example.memostream.data.*
import com.example.memostream.sync.*

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsScreen(state: AppState, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var usage by remember {
        mutableStateOf(UsageStats())
    }
    var remote by remember {
        mutableStateOf<String?>(null)
    }
    var syncStatus by remember {
        mutableStateOf<String?>(null)
    }
    var token by remember {
        mutableStateOf("")
    }
    var busy by remember {
        mutableStateOf(false)
    }
    var conf by remember {
        mutableStateOf(state.settings.sb)
    }
    var compress by remember {
        mutableStateOf(state.settings.compressVideo)
    }
    var dbPassword by remember {
        mutableStateOf<String?>(null)
    }
    var confirmCleanup by remember {
        mutableStateOf(false)
    }

    suspend fun reloadUsage() {
        usage = withContext(Dispatchers.IO) {
            state.stats.usage(state.draftIds())
        }
        val current = state.settings.sb
        remote = if (current == null) null else withContext(Dispatchers.IO) {
            Sb.usage(current)?.let { stats ->
                val plan = planOf(current)
                "DB ${formatBytes(stats.optLong("db_bytes"))} / ${formatBytes(plan.db)} · " +
                    "첨부 ${formatBytes(stats.optLong("storage_bytes"))} / ${formatBytes(plan.storage)}"
            }
        }
    }

    LaunchedEffect(conf) {
        reloadUsage()
    }

    val exporter = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use {
                        state.backup.exportTo(it)
                    }
                }
            }.onSuccess {
                state.settings.lastExportAt = System.currentTimeMillis()
                state.showToast("내보내기를 마쳤습니다")
            }.onFailure {
                state.showToast("내보내기에 실패했습니다")
            }
        }
    }

    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) {
            return@rememberLauncherForActivityResult
        }
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)!!.use {
                        state.backup.importFrom(it)
                    }
                }
            }.onSuccess { result ->
                state.showToast("가져오기 완료: 메모 ${result.notes}개, 첨부 ${result.blobs}개 (병합 ${result.reusedBlobs}개)")
                state.refresh()
                state.syncSoon()
                reloadUsage()
            }.onFailure {
                state.showToast("가져오기에 실패했습니다")
            }
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(16.dp)
    ) {
        Section("저장 공간")
        UsageRow("메모", "${usage.noteCount}개 · ${formatBytes(usage.textBytes)}")
        UsageRow("사진", "${usage.imageCount}개 · ${formatBytes(usage.imageBytes)}")
        UsageRow("동영상", "${usage.videoCount}개 · ${formatBytes(usage.videoBytes)}")
        UsageRow("파일", "${usage.fileCount}개 · ${formatBytes(usage.fileBytes)}")
        UsageRow("썸네일", formatBytes(usage.thumbBytes))
        UsageRow("휴지통", "${usage.trashNoteCount}개 · ${formatBytes(usage.trashBytes)}")
        UsageRow("중복 제거로 아낀 용량", formatBytes(usage.savedBytes))
        UsageRow("기기 사용 / 여유", "${formatBytes(usage.usage)} / ${formatBytes(usage.quota)}")
        remote?.let {
            UsageRow("서버", it)
        }

        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                exporter.launch(state.backup.exportName())
            }) {
                Text("내보내기")
            }
            OutlinedButton(onClick = {
                importer.launch(arrayOf("application/zip"))
            }) {
                Text("가져오기")
            }
            OutlinedButton(onClick = {
                confirmCleanup = true
            }) {
                Text("정리")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = memo.border)
        Section("일반")
        val theme by state.theme.collectAsState()
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("테마", Modifier.weight(1f))
            listOf("system" to "시스템", "light" to "밝게", "dark" to "어둡게").forEach { (value, label) ->
                TextButton(onClick = {
                    state.setTheme(value)
                }) {
                    Text(
                        label,
                        color = if (theme == value) memo.accent
                        else {
                            memo.fgSoft
                        }
                    )
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("영상 압축", Modifier.weight(1f))
            Switch(checked = compress, onCheckedChange = {
                compress = it
                state.settings.compressVideo = it
            })
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = memo.border)
        Section("동기화")

        val current = conf
        if (current == null || current.url.isEmpty()) {
            Text(
                "Supabase 액세스 토큰을 넣으면 프로젝트와 테이블을 자동으로 준비합니다. 다른 기기에서 같은 토큰을 넣으면 같은 곳에 연결됩니다.",
                style = MaterialTheme.typography.bodySmall,
                color = memo.fgSoft,
            )
            OutlinedTextField(
                value = token,
                onValueChange = {
                    token = it
                },
                label = {
                    Text("sbp_ 로 시작하는 토큰")
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Button(
                enabled = !busy && token.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching {
                            Sb.provision(token.trim()) {
                                syncStatus = it
                            }
                        }
                            .onSuccess { (provisioned, pass) ->
                                state.settings.saveSb(provisioned)
                                conf = provisioned
                                dbPassword = pass
                                syncStatus = "연결됐습니다. 첫 동기화 중..."
                                state.runSync {
                                    syncStatus = it
                                }
                                state.connectRealtime()
                                syncStatus = null
                                reloadUsage()
                            }
                            .onFailure {
                                syncStatus = it.message
                            }
                        busy = false
                    }
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text("연결")
            }
        } else {
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("동기화", Modifier.weight(1f))
                Switch(checked = current.enabled, onCheckedChange = { on ->
                    current.enabled = on
                    state.settings.saveSb(current)
                    conf = current.copy()
                    if (on) {
                        state.connectRealtime()
                    } else {
                        state.stopRealtime()
                    }
                })
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("요금제", Modifier.weight(1f))
                SB_PLANS.forEach { (value, plan) ->
                    TextButton(onClick = {
                        current.plan = value
                        state.settings.saveSb(current)
                        conf = current.copy()
                        scope.launch {
                            reloadUsage()
                        }
                    }) {
                        Text(
                            plan.label,
                            color = if (current.plan == value) memo.accent
                            else {
                                memo.fgSoft
                            }
                        )
                    }
                }
            }
            UsageRow(
                "마지막 동기화",
                if (current.lastSyncedAt > 0) {
                    timeLabel(current.lastSyncedAt)
                } else {
                    "아직 없음"
                },
            )
            current.lastError?.let {
                Text(it, color = memo.danger, style = MaterialTheme.typography.bodySmall)
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val result = state.runSync {
                            syncStatus = it
                        }
                        syncStatus = result?.let {
                            "받음 ${it.pulled}건, 보냄 ${it.pushed}건"
                        }
                            ?: state.settings.sb?.lastError
                        conf = state.settings.sb
                        reloadUsage()
                        busy = false
                    }
                }) {
                    Text("지금 동기화")
                }
                OutlinedButton(onClick = {
                    state.stopRealtime()
                    state.settings.saveSb(null)
                    conf = null
                }) {
                    Text("연결 해제")
                }
            }
        }
        syncStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }

    if (confirmCleanup) {
        AlertDialog(
            containerColor = memo.bg,
            titleContentColor = memo.fg,
            textContentColor = memo.fg,
            onDismissRequest = {
                confirmCleanup = false
            },
            title = {
                Text("정리")
            },
            text = {
                Text("참조가 끊긴 첨부 삭제와 휴지통 비우기를 실행합니다. 동기화 중이면 서버의 첨부도 함께 정리합니다. 되돌릴 수 없습니다.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmCleanup = false
                    scope.launch {
                        val summary = withContext(Dispatchers.IO) {
                            state.stats.runFullCleanup(state.draftIds())
                        }
                        var message = "정리 완료 (끊긴 첨부 ${summary.orphans}, 휴지통 ${summary.purged})"
                        if (state.sync.conf() != null) {
                            state.runSync()
                            val removed = runCatching {
                                state.sync.cleanupRemoteBlobs()
                            }.getOrDefault(0)
                            message += ", 서버 첨부 $removed"
                        }
                        state.showToast(message)
                        state.refresh()
                        reloadUsage()
                    }
                }) {
                    Text("정리하기", color = memo.danger)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmCleanup = false
                }) {
                    Text("취소")
                }
            },
        )
    }

    dbPassword?.let { pass ->
        AlertDialog(
            containerColor = memo.bg,
            titleContentColor = memo.fg,
            textContentColor = memo.fg,
            onDismissRequest = {
                dbPassword = null
            },
            title = {
                Text("데이터베이스 비밀번호")
            },
            text = {
                Column {
                    Text("방금 만든 Supabase 프로젝트의 Postgres 비밀번호입니다. 지금 한 번만 보여주고 어디에도 저장하지 않습니다.")
                    OutlinedTextField(
                        value = pass,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    dbPassword = null
                }) {
                    Text("확인")
                }
            },
        )
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun UsageRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = memo.fgSoft,
        )
    }
}
