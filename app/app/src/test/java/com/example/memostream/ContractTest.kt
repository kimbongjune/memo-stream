package com.example.memostream

import com.example.memostream.data.Block
import com.example.memostream.data.blocksOf
import com.example.memostream.data.collectBlobIds
import com.example.memostream.data.gifDurationMs
import com.example.memostream.data.gifIsAnimated
import com.example.memostream.data.sniffMime
import com.example.memostream.data.isAnimated
import com.example.memostream.data.joinAttachments
import com.example.memostream.data.splitAttachments
import com.example.memostream.sync.formatBytes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ContractTest {
    private fun source(name: String): String =
        File("src/main/java/com/example/memostream/$name").readText()

    private fun dataSource(name: String) = source("data/$name")

    private fun syncSource(name: String) = source("sync/$name")

    @Test
    fun `첨부를 못 받은 행은 워터마크를 올리지 않는다`() {
        val body = syncSource("Sync.kt")
        assertTrue(
            "실패한 행이 워터마크를 밀면 다시는 안 받아와 첨부가 사라진다",
            body.contains("if (missedBlob) {") && body.contains("watermark = maxOf(watermark, updatedAt)")
        )
        assertTrue(
            "재시도 표식을 남겨야 다음 pull에서 건너뛰지 않는다",
            body.contains("pullPending = missedBlob")
        )
    }

    @Test
    fun `첨부가 빠진 행은 올리지 않는다`() {
        assertTrue(
            "올리면 원격 blobs 목록을 빈 배열로 덮어써 다른 기기에서도 첨부가 사라진다",
            syncSource("Sync.kt").contains("it.version() > since && !it.pullPending")
        )
    }

    @Test
    fun `워터마크는 테이블마다 따로 간다`() {
        val body = syncSource("Sync.kt")
        listOf("lastPullPurges", "lastPullFolders", "lastPullNotes").forEach {
            assertTrue("$it 워터마크가 있어야 한다", body.contains("conf.$it ="))
        }
        assertFalse("합쳐 쓰던 워터마크는 없어야 한다", body.contains("lastPullAt"))
    }

    @Test
    fun `원격 기본키는 uid다`() {
        val body = syncSource("Supabase.kt")
        assertTrue(body.contains("uid          text primary key") || body.contains("uid        text primary key"))
        assertTrue("upsert 충돌 기준은 uid", body.contains("on_conflict=uid"))
        assertFalse("밀리초를 키로 쓰면 한 배치 중복에서 21000으로 죽는다", body.contains("on_conflict=created_at"))
    }

    @Test
    fun `publication 은 set table 로 등록한다`() {
        val body = syncSource("Supabase.kt")
        assertTrue(body.contains("alter publication supabase_realtime set table"))
        assertFalse(
            "add table 은 이미 들어 있으면 42710으로 죽어 연결 자체가 실패한다",
            body.contains("alter publication supabase_realtime add table")
        )
    }

    @Test
    fun `realtime 은 조인에 성공하면 밀린 것을 당겨온다`() {
        val body = syncSource("Realtime.kt")
        assertTrue("조인 성공 콜백이 있어야 한다", body.contains("onJoined()"))
        assertTrue("변경 알림은 동기화를 부르는 계기일 뿐", body.contains("\"postgres_changes\" -> onChange()"))
        assertTrue("heartbeat 로 죽은 연결을 붙들지 않는다", body.contains("\"heartbeat\""))
        assertTrue("백오프 재연결", body.contains("RT_BACKOFF_MS"))
    }

    @Test
    fun `첨부 분리와 재결합이 왕복한다`() {
        val content = "메모 본문\n\n![](blob:3) [보고서.pdf](blob:7)"
        val split = splitAttachments(content)
        assertEquals("메모 본문", split.text)
        assertEquals(listOf(3L, 7L), split.refs.map {
            it.id
        })
        assertEquals(content.trim(), joinAttachments(split.text, split.refs).trim())
    }

    @Test
    fun `본문 참조를 모두 모은다`() {
        assertEquals(listOf(3L, 7L), collectBlobIds("![](blob:3) 그리고 [x](blob:7) 또 ![](blob:3)"))
    }

    @Test
    fun `블록은 글과 첨부를 순서대로 쪼갠다`() {
        val blocks = blocksOf("앞글\n\n![](blob:1)\n\n뒷글")
        assertEquals(3, blocks.size)
        assertTrue(blocks[0] is Block.Text)
        assertEquals(1L, (blocks[1] as Block.Media).blobId)
        assertTrue(blocks[2] is Block.Text)
    }

    @Test
    fun `움직이는 GIF 를 프레임 수로 가려낸다`() {
        fun gif(frames: Int): ByteArray {
            val out = ArrayList<Byte>()
            "GIF89a".forEach {
                out.add(it.code.toByte())
            }
            repeat(frames) {
                out.addAll(listOf(0x21, 0xF9, 0x04, 0, 0, 0, 0, 0).map {
                    it.toByte()
                })
            }
            return out.toByteArray()
        }
        assertTrue(gifIsAnimated(gif(3)))
        assertFalse(gifIsAnimated(gif(1)))
    }

    @Test
    fun `움직이는 WebP 를 헤더로 가려낸다`() {
        fun webp(animated: Boolean): ByteArray {
            val bytes = ByteArray(64)
            "RIFF".forEachIndexed { i, c ->
                bytes[i] = c.code.toByte()
            }
            "WEBP".forEachIndexed { i, c ->
                bytes[8 + i] = c.code.toByte()
            }
            "VP8X".forEachIndexed { i, c ->
                bytes[12 + i] = c.code.toByte()
            }
            bytes[20] = if (animated) 0x02 else 0x00
            return bytes
        }
        assertTrue(isAnimated(webp(true).inputStream(), "image/webp"))
        assertFalse(isAnimated(webp(false).inputStream(), "image/webp"))
        assertFalse(isAnimated(ByteArray(64).inputStream(), "image/png"))
    }

    @Test
    fun `용량 표시가 익스텐션과 같은 기준이다`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.0 MB", formatBytes(1024L * 1024))
        assertEquals("—", formatBytes(null))
    }

    @Test
    fun `공유로 들어오면 폴더를 고르게 되어 있다`() {
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android.intent.action.SEND\""))
        assertTrue(manifest.contains("android.intent.action.SEND_MULTIPLE"))
        assertTrue(manifest.contains(".ShareActivity"))
        val share = source("ShareActivity.kt")
        assertTrue("폴더 목록에서 골라야 한다", share.contains("어디에 저장할까요?"))
        assertTrue("공유 화면에서도 폴더를 만들 수 있어야 한다", share.contains("repo.createFolder"))
    }

    @Test
    fun `움직이는 이미지는 익스텐션과 같게 mp4 로 옮긴다`() {
        val body = dataSource("Attachments.kt")
        assertTrue(
            "GIF를 원본으로 두면 PC와 다른 형식이 되고 용량도 몇 배가 된다",
            body.contains("GifEncoder.encode(temp, mp4, durationMs)")
        )
        assertTrue("반복 재생 표식을 남긴다", body.contains("\"video/mp4\", \"\$base.mp4\", loop = true"))
        assertTrue(
            "변환이 실패하면 원본이라도 살린다",
            body.contains("return insert(temp, picked.mime, picked.name, loop = true, animated = true)")
        )
        val encoder = dataSource("GifEncoder.kt")
        assertTrue("짧은 변 720", encoder.contains("SHORT_SIDE = 720"))
        assertTrue("코덱 정렬 단위에 맞춘다", encoder.contains("fitToCodec("))
    }

    @Test
    fun `패키지가 나뉘어 있다`() {
        listOf("data", "sync", "ui").forEach { dir ->
            assertTrue("$dir 패키지가 있어야 한다", File("src/main/java/com/example/memostream/$dir").isDirectory)
        }
        assertTrue("Room 엔티티", dataSource("Entities.kt").contains("@Entity"))
        assertTrue("Room DAO", dataSource("Dao.kt").contains("@Dao"))
        assertTrue("Room DB", dataSource("MemoDatabase.kt").contains("@Database"))
        assertFalse(
            "SQLiteOpenHelper 를 직접 쓰지 않는다",
            dataSource("MemoDatabase.kt").contains("SQLiteOpenHelper")
        )
    }

    @Test
    fun `MIME 이 흐릿하면 매직 바이트로 알아낸다`() {
        fun head(vararg parts: Any): ByteArray {
            val out = ArrayList<Byte>()
            parts.forEach { p ->
                when (p) {
                    is String -> p.forEach {
                        out.add(it.code.toByte())
                    }
                    is Int -> out.add(p.toByte())
                    else -> {}
                }
            }
            while (out.size < 32) {
                out.add(0)
            }
            return out.toByteArray()
        }
        assertEquals("image/gif", sniffMime(head("GIF89a")))
        assertEquals("image/gif", sniffMime(head("GIF87a")))
        assertEquals("image/webp", sniffMime(head("RIFF", 0, 0, 0, 0, "WEBP")))
        assertEquals("image/png", sniffMime(head(0x89, 0x50, 0x4E, 0x47)))
        assertEquals("image/jpeg", sniffMime(head(0xFF, 0xD8, 0xFF)))
        assertEquals("video/mp4", sniffMime(head(0, 0, 0, 0x20, "ftypisom")))
        assertEquals("application/pdf", sniffMime(head("%PDF")))
        assertEquals(null, sniffMime(head("hello world")))
    }

    @Test
    fun `공유가 흐릿한 MIME 을 보내도 형식을 되찾는다`() {
        val body = dataSource("Attachments.kt")
        assertTrue(
            "getType 하나만 믿으면 파일 관리자가 보낸 사진이 그냥 파일로 박힌다",
            body.contains("VAGUE_MIMES")
        )
        assertTrue("확장자 폴백", body.contains("getMimeTypeFromExtension"))
        assertTrue("매직 바이트 폴백", body.contains("sniffMime(head.copyOf(read))"))
        assertTrue(
            "공유 인텐트가 선언한 타입도 힌트로 쓴다",
            source("ShareActivity.kt").contains("readPicked(context, uri, declaredType)")
        )
    }

    @Test
    fun `GIF 한 바퀴 길이를 delay 합으로 낸다`() {
        fun gif(vararg delays: Int): ByteArray {
            val out = ArrayList<Byte>()
            "GIF89a".forEach {
                out.add(it.code.toByte())
            }
            delays.forEach { d ->
                out.addAll(listOf(0x21, 0xF9, 0x04, 0x00, d and 0xFF, (d shr 8) and 0xFF, 0, 0).map {
                    it.toByte()
                })
            }
            return out.toByteArray()
        }
        assertEquals(300L, gifDurationMs(gif(10, 10, 10)))
        assertEquals(100L, gifDurationMs(gif(10)))

        assertEquals(200L, gifDurationMs(gif(0, 1)))
        assertEquals(0L, gifDurationMs("GIF89a".toByteArray()))
    }

    @Test
    fun `인코더는 코덱이 받는 크기로 맞춘다`() {
        val body = dataSource("GifEncoder.kt")
        assertTrue(
            "정렬이 안 맞으면 configure 가 아니라 start 에서 CodecException 이 난다",
            body.contains("widthAlignment") && body.contains("heightAlignment")
        )
        assertTrue("최소 비트레이트 바닥", body.contains("MIN_BITRATE"))
        assertTrue("코덱이 받는 크기 범위로 클램프", body.contains("isSizeSupported"))
        assertFalse(
            "codec 입력 Surface 는 소프트웨어 캔버스를 받지 않는다",
            body.contains("surface.lockCanvas(") || body.contains("createInputSurface()")
        )
        assertFalse(
            "AnimationCallback 은 Looper 스레드를 요구한다",
            body.contains("registerAnimationCallback")
        )
        assertTrue(
            "평면 capacity 합은 NV12 에서 중복 계산된다",
            body.contains("encoder.getInputBuffer(index)?.capacity()")
        )
    }

    @Test
    fun `목록은 DB 를 구독한다`() {
        val body = source("ui/AppState.kt")
        assertTrue(
            "공유로 저장한 메모가 바로 보이려면 Flow 를 구독해야 한다",
            body.contains("repo.activeNotes") && body.contains("stateIn(viewModelScope")
        )
        assertTrue("휴지통도 구독", body.contains("repo.trashNotes"))
        assertFalse("수동 갱신에 기대면 다른 화면이 쓴 메모를 놓친다", body.contains("_ui.value ="))
    }

    @Test
    fun `뒤로가기가 안쪽부터 닫힌다`() {
        val body = source("MainActivity.kt")
        assertTrue("서랍 먼저", body.contains("BackHandler(enabled = !imeVisible && drawerEngaged)"))
        assertTrue("그다음 화면", body.contains("screen != Screen.NOTES"))
        assertTrue("그다음 검색", body.contains("search.active"))
        assertTrue("미디어 뷰어도 닫힌다", body.contains("BackHandler {"))
        assertTrue(
            "키보드가 떠 있으면 그것부터 내린다",
            body.contains("BackHandler(enabled = imeVisible)") && body.contains("focusManager.clearFocus()")
        )
        assertEquals(
            "키보드가 보이는 동안에는 나머지 핸들러가 전부 꺼져야 시스템이 키보드를 닫는다",
            4,
            Regex("""BackHandler\(enabled = !imeVisible""").findAll(body).count()
        )
        assertTrue(
            "목록을 누르면 포커스를 놓는다",
            source("ui/NotesScreen.kt").contains("detectTapGestures(onPress = {")
        )
    }

    @Test
    fun `테마 설정이 즉시 반영된다`() {
        assertTrue(
            "remember 로 한 번만 읽으면 설정을 바꿔도 화면이 안 바뀐다",
            source("ui/AppState.kt").contains("val theme: StateFlow<String>")
        )
        assertTrue(
            source("MainActivity.kt").contains("val theme by state.theme.collectAsState()")
        )
        assertFalse(
            source("ui/SettingsScreen.kt").contains("mutableStateOf(state.settings.theme)")
        )
    }

    @Test
    fun `동영상도 720p 로 줄인다`() {
        val body = dataSource("Attachments.kt")
        assertTrue(
            "해상도를 안 주면 원본 크기로 재인코딩만 해서 용량이 안 줄고, 그러면 원본이 그대로 남는다",
            body.contains("Presentation.createForShortSide(VIDEO_SHORT_SIDE)")
        )
        assertTrue("짧은 변 720", body.contains("VIDEO_SHORT_SIDE = 720"))
        assertTrue("비트레이트를 계산해 넘긴다", body.contains("setBitrate(bitrateFor(input))"))
        assertTrue("인코더 설정을 넘긴다", body.contains("setEncoderFactory(encoders)"))
        assertTrue(
            "결과가 원본보다 크면 원본을 쓴다",
            body.contains("compressed.length() in 1 until temp.length()")
        )
    }

    @Test
    fun `토스트가 시스템 바에 가리지 않는다`() {
        assertTrue(
            "Scaffold 가 하단 인셋을 안 주므로 스낵바가 제스처바 뒤로 들어갔다",
            source("MainActivity.kt").contains("SnackbarHost(\n                        snackbar,")
        )
        assertTrue(
            source("MainActivity.kt").contains("WindowInsets.ime.union(WindowInsets.navigationBars)")
        )
    }

    @Test
    fun `다이얼로그가 불투명하다`() {
        val body = source("ui/Dialogs.kt")
        assertTrue("tonalElevation 은 반투명 틴트를 만든다", body.contains("color = memo.bg"))
        assertFalse(body.contains("tonalElevation"))
        assertTrue(body.contains("containerColor = memo.bg"))
    }

    @Test
    fun `첨부를 기기에 저장할 수 있다`() {
        val actions = source("ui/MediaActions.kt")
        assertTrue("다운로드 폴더에 넣는다", actions.contains("MediaStore.Downloads.EXTERNAL_CONTENT_URI"))
        assertTrue("쓰는 동안 IS_PENDING", actions.contains("MediaStore.Downloads.IS_PENDING"))
        assertTrue("구버전은 문서 선택으로", actions.contains("fun writeTo("))

        val dialogs = source("ui/Dialogs.kt")
        assertTrue("첨부 메뉴에 저장", dialogs.contains("\"저장\", onSave"))
        assertTrue("뷰어에도 저장", source("ui/Media.kt").contains("contentDescription = \"저장\""))
        assertTrue(
            "길게 누르면 메뉴가 뜬다",
            source("ui/NoteBubble.kt").contains("onLongClick = {")
        )
    }

    @Test
    fun `일반 파일은 확대 뷰어를 열지 않는다`() {
        val body = source("MainActivity.kt")
        assertTrue(
            "이미지·영상만 뷰어로 간다",
            body.contains("""record.mime.startsWith("image/") || record.mime.startsWith("video/")""")
        )
        assertTrue("그 외에는 메뉴", body.contains("mediaMenu = record"))
        val bubble = source("ui/NoteBubble.kt")
        val fileChip = bubble.substringAfter("else -> FileChip(").substringBefore("}\n                            }")
        assertFalse("파일 조각이 뷰어를 열면 안 된다", fileChip.contains("onOpenMedia"))
        assertTrue("파일 조각은 메뉴를 연다", fileChip.contains("onMediaMenu(record)"))
    }

    @Test
    fun `복사는 종류에 따라 다르게 담는다`() {
        val body = source("ui/MediaActions.kt")
        assertTrue("글은 평문으로", body.contains("ClipData.newPlainText"))
        assertTrue("첨부는 URI 로", body.contains("ClipData.newUri"))
        assertFalse(
            "설명에 text/plain 을 끼우면 받는 앱이 이미지가 아니라 글로 붙여넣는다",
            body.contains("\"text/plain\"")
        )
        assertFalse("MIME 을 직접 지어내지 않는다", body.contains("ClipDescription("))
        assertTrue(
            "글이 있으면 글, 첨부만 있으면 첨부",
            source("MainActivity.kt").contains("if (split.text.isBlank() && media != null)")
        )
        assertTrue("FileProvider 로 내보낸다", body.contains("FileProvider.getUriForFile"))
        assertTrue(
            "webp 는 붙여넣기를 받는 앱이 드물어 클립보드용으로만 png 를 만든다",
            body.contains("PASTEABLE_IMAGES") && body.contains("Bitmap.CompressFormat.PNG")
        )
        assertTrue(
            "영상과 일반 파일은 원본 그대로 올린다",
            body.contains("""if (!record.mime.startsWith("image/") || record.mime in PASTEABLE_IMAGES)""")
        )
        val manifest = File("src/main/AndroidManifest.xml").readText()
        assertTrue("provider 선언", manifest.contains("androidx.core.content.FileProvider"))
        assertTrue("경로 설정", File("src/main/res/xml/file_paths.xml").exists())
    }

    @Test
    fun `키보드가 목록을 가리지 않는다`() {
        val body = source("ui/NotesScreen.kt")
        assertTrue(
            "목록과 작성창이 함께 밀려 올라가야 한다",
            body.contains("WindowInsets.ime.union(WindowInsets.navigationBars)")
        )
        assertTrue(
            "작성창만 올리면 목록 높이가 줄면서 마지막 메모가 가려진다",
            body.contains("WindowInsets.ime.getBottom(LocalDensity.current)") &&
                body.contains("LaunchedEffect(imeBottom)")
        )
        assertFalse(
            "작성창에만 인셋을 주면 아래가 붕 뜬다",
            body.substringAfter("private fun Composer(").substringBefore("@Composable")
                .contains("navigationBarsPadding()")
        )
    }

    @Test
    fun `영상에 컨트롤이 있다`() {
        val body = source("ui/Media.kt")
        assertTrue("재생·일시정지", body.contains("Icons.Default.Pause"))
        assertTrue("탐색 바", body.contains("Slider("))
        assertTrue("경과·전체 시간", body.contains("clock(position)") && body.contains("clock(duration)"))
        assertTrue("시스템 바를 가리면 안 된다", body.contains("safeDrawingPadding()"))
        assertFalse("요청하지 않은 GIF 배지는 넣지 않는다", body.contains("\"GIF\","))
        assertFalse(body.contains("GIF 에서 변환됨"))
    }

    @Test
    fun `색과 모양이 익스텐션 토큰 그대로다`() {
        val theme = source("ui/Theme.kt")

        mapOf(
            "bg" to "0xFFFFFFFF", "bgSoft" to "0xFFF5F6F8", "bgHover" to "0xFFECEEF1",
            "fg" to "0xFF1A1C20", "fgSoft" to "0xFF6B7280", "border" to "0xFFE3E6EA",
            "accent" to "0xFF2563EB", "accentSoft" to "0xFFDBEAFE", "danger" to "0xFFDC2626",
        ).forEach { (name, value) ->
            assertTrue("밝은 테마 $name", theme.contains("$name = Color($value)"))
        }
        mapOf(
            "bg" to "0xFF17181C", "bgSoft" to "0xFF1F2127", "border" to "0xFF2E3138",
            "accent" to "0xFF60A5FA",
        ).forEach { (name, value) ->
            assertTrue("어두운 테마 $name", theme.contains("$name = Color($value)"))
        }
        assertTrue("radius 10px", theme.contains("MemoRadius = 10.dp"))

        val bubble = source("ui/NoteBubble.kt")
        assertTrue("말풍선 배경은 bg-soft", bubble.contains("background(memo.bgSoft)"))
        assertTrue("1px 테두리", bubble.contains("border(1.dp, memo.border, shape)"))
        assertTrue("네 모서리가 같아야 한다", bubble.contains("RoundedCornerShape(MemoRadius)"))
        assertFalse(
            "한 꼭지만 다르게 하지 않는다",
            bubble.contains("topStart =") || bubble.contains("bottomEnd =")
        )
        assertTrue("고정은 왼쪽 3px 선", bubble.contains("3.dp.toPx()"))
    }

    @Test
    fun `시스템 바와 겹치지 않는다`() {
        assertTrue(
            "Scaffold 는 상단만 처리하고 하단은 화면이 직접 맞춘다",
            source("MainActivity.kt").contains("WindowInsets.safeDrawing.only(WindowInsetsSides.Top)")
        )
        assertTrue(
            "키보드와 제스처바 중 큰 쪽만큼 밀어 올린다",
            source("ui/NotesScreen.kt").contains("WindowInsets.ime.union(WindowInsets.navigationBars)")
        )
        listOf("ui/TrashScreen.kt", "ui/SettingsScreen.kt").forEach {
            assertTrue("$it 하단 인셋", source(it).contains("navigationBarsPadding()"))
        }
        listOf("ui/FolderList.kt", "ShareActivity.kt").forEach {
            assertTrue("$it 인셋", source(it).contains("safeDrawingPadding()"))
        }
    }

    @Test
    fun `서랍이 열리는 중에도 뒤로가기가 서랍을 닫는다`() {
        val body = source("MainActivity.kt")
        assertTrue(
            "isOpen 은 애니메이션이 끝난 뒤에만 true 라 중간에 누르면 앱이 꺼졌다",
            body.contains("drawerState.targetValue != DrawerValue.Closed")
        )
        assertTrue("목록에서는 두 번 눌러야 나간다", body.contains("activity?.finish()"))
    }

    @Test
    fun `런처 아이콘이 기본 템플릿이 아니다`() {
        val icon = File("src/main/res/mipmap-anydpi-v26/ic_launcher.xml")
        assertTrue("적응형 아이콘", icon.exists() && icon.readText().contains("adaptive-icon"))
        assertTrue("모노크롬", icon.readText().contains("monochrome"))
        assertTrue(
            "전경이 실제 도형이어야 한다",
            File("src/main/res/drawable/ic_launcher_foreground.xml").readText().contains("pathData")
        )
    }

    private fun singleLineBlock(line: String): Boolean {
        var depth = 0
        var opened = false
        var i = 0
        var inString = false
        while (i < line.length) {
            val c = line[i]
            when {
                inString && c == '\\' -> i++
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> {
                    depth++
                    opened = true
                }
                c == '}' -> {
                    depth--
                    if (opened && depth == 0) {
                        val start = line.indexOf('{')
                        val inner = line.substring(start + 1, i).trim()
                        if (inner.isNotEmpty() && !inner.endsWith("->")) {
                            return true
                        }
                    }
                }
            }
            i++
        }
        return false
    }

    @Test
    fun `중괄호 블록은 한 줄로 끝내지 않는다`() {
        val roots = File("src/main/java/com/example/memostream")
        roots.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                val trimmed = line.trim()
                val interpolationOnly = trimmed.contains("${'$'}{") && !trimmed.contains(" { ")
                if (!interpolationOnly) {
                    assertFalse(
                        "${file.name}:${index + 1} 한 줄 블록 — $trimmed",
                        singleLineBlock(line)
                    )
                }
            }
        }
    }

    @Test
    fun `주석이 없고 if 는 모두 중괄호를 쓴다`() {
        val roots = File("src/main/java/com/example/memostream")
        val files = roots.walkTopDown().filter {
            it.extension == "kt"
        }.toList()
        assertTrue("소스가 있어야 한다", files.size > 15)

        val commentStart = Regex("""^\s*(//|/\*|\*)""")
        val bracelessIf = Regex("""^\s*(if|for|while)\s*\(""")

        files.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                assertFalse(
                    "${file.name}:${index + 1} 주석이 남아 있다 — $line",
                    commentStart.containsMatchIn(line)
                )
                val trimmed = line.trim()
                val opensBlock = trimmed.contains("{")
                val isExpression = trimmed.contains(" else ") || trimmed.endsWith(",") ||
                    trimmed.startsWith("val ") || trimmed.startsWith("var ") ||
                    trimmed.contains("->")
                if (!opensBlock && !isExpression) {
                    assertFalse(
                        "${file.name}:${index + 1} 중괄호 없는 조건문 — $line",
                        bracelessIf.containsMatchIn(line)
                    )
                }
            }
        }
    }

    @Test
    fun `촬영한 사진과 영상은 갤러리에 남지 않는다`() {
        val capture = source("ui/Capture.kt")
        assertTrue("앱 캐시에만 쓴다", capture.contains("File(context.cacheDir, \"capture\")"))
        assertTrue("FileProvider 로 카메라에 넘긴다", capture.contains("FileProvider.getUriForFile"))
        assertFalse("MediaStore 에 넣으면 갤러리에 뜬다", capture.contains("MediaStore"))

        val screen = source("ui/NotesScreen.kt")
        assertTrue("사진 촬영", screen.contains("ActivityResultContracts.TakePicture()"))
        assertTrue("동영상 촬영", screen.contains("ActivityResultContracts.CaptureVideo()"))
        assertTrue(
            "촬영 결과도 같은 첨부 경로를 타야 압축이 동일하다",
            screen.contains("state.attach(listOf(uri))")
        )
        assertTrue("캐시는 시작할 때 비운다", source("ui/AppState.kt").contains("Capture.clear(app)"))
    }

    @Test
    fun `마지막에 고른 폴더를 기억한다`() {
        val settings = dataSource("Settings.kt")
        assertTrue("설정에 담는다", settings.contains("var lastFolderId: Long?"))

        val state = source("ui/AppState.kt")
        assertTrue("고를 때 저장", state.contains("settings.lastFolderId = id"))
        assertTrue("시작할 때 복원", state.contains("val remembered = settings.lastFolderId"))
        assertTrue(
            "지워진 폴더면 전체로 돌아간다",
            state.contains("if (folder != null && folder.deletedAt == null)")
        )
    }

    @Test
    fun `작성창에 이미지를 붙여넣을 수 있다`() {
        val body = source("ui/NotesScreen.kt")
        assertTrue("붙여넣기를 받는다", body.contains(".contentReceiver {"))
        assertTrue(
            "contentReceiver 는 state 기반 BasicTextField 의 붙여넣기 경로에만 연결된다",
            body.contains("state = state.composer")
        )
        assertFalse(
            "value/onValueChange 방식이면 텍스트 전용 경로로 빠져 수신기를 거치지 않는다",
            body.contains("onValueChange = state::setComposerText")
        )
        assertTrue("작성창 상태는 TextFieldState", source("ui/AppState.kt").contains("val composer = TextFieldState()"))
        assertTrue(
            "받은 것은 첨부 경로로 넘겨 압축을 똑같이 태운다",
            body.contains("state.attach(uris)")
        )
        assertTrue("URI 가 없으면 그대로 넘긴다", body.contains("if (uris.isEmpty()) {"))
        assertTrue(
            "같은 첨부를 두 번 붙이면 blob id 가 같아 LazyRow 키가 겹친다",
            body.contains("items(draft, key = {") && body.contains("it.key")
        )
        val state = source("ui/AppState.kt")
        assertTrue("칩마다 고유 키", state.contains("data class DraftRef(val key: Long"))
        assertTrue("키를 증가시켜 발급", state.contains("draftKeys += 1"))
        assertTrue("지울 때도 키로 하나만", state.contains("it.key == ref.key"))
    }

    @Test
    fun `본문 링크를 누르면 열린다`() {
        val body = source("ui/MarkdownText.kt")
        assertTrue("링크 위치를 표시해 둔다", body.contains("addStringAnnotation("))
        assertTrue("탭 위치를 글자 오프셋으로 바꾼다", body.contains("getOffsetForPosition(offset)"))
        assertTrue("그 위치의 링크를 찾는다", body.contains("getStringAnnotations("))
        assertTrue("찾으면 콜백", body.contains("onLinkClick(target.item)"))
        assertTrue(
            "실제로 브라우저를 연다",
            source("MainActivity.kt").contains("Intent(Intent.ACTION_VIEW, url.toUri())")
        )
    }

    @Test
    fun `삭제 표식은 uid 로 남긴다`() {
        val body = syncSource("Sync.kt")
        assertTrue(body.contains("""put("uid", uid)"""))
        assertTrue(body.contains("purged_at"))
    }
}
