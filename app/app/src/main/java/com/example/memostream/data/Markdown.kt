package com.example.memostream.data

private val ATTACH_RE = Regex("""!?\[[^\]]*]\(blob:(\d+)\)[ \t]*""")
private val BLOB_RE = Regex("""blob:(\d+)""")

data class Attachment(val id: Long, val markdown: String)

data class SplitContent(val text: String, val refs: List<Attachment>)

fun collectBlobIds(content: String): List<Long> =
    BLOB_RE.findAll(content).mapNotNull {
        it.groupValues[1].toLongOrNull()
    }.distinct().toList()

fun splitAttachments(content: String): SplitContent {
    val refs = ArrayList<Attachment>()
    val stripped = ATTACH_RE.replace(content) { match ->
        val id = match.groupValues[1].toLongOrNull()
        if (id != null) {
            refs.add(Attachment(id, match.value.trim()))
        }
        ""
    }
    val text = stripped
        .lines().joinToString("\n") {
            it.trimEnd()
        }
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()
    return SplitContent(text, refs)
}

fun joinAttachments(text: String, refs: List<Attachment>): String {
    val body = text.trim()
    val tail = refs.joinToString(" ") {
        it.markdown
    }
    return when {
        tail.isEmpty() -> body
        body.isEmpty() -> tail
        else -> "$body\n\n$tail"
    }
}

sealed interface Block {
    data class Text(val markdown: String) : Block
    data class Media(val blobId: Long) : Block
}

fun blocksOf(content: String): List<Block> {
    val blocks = ArrayList<Block>()
    var cursor = 0
    for (match in ATTACH_RE.findAll(content)) {
        val id = match.groupValues[1].toLongOrNull() ?: continue
        if (match.range.first > cursor) {
            val chunk = content.substring(cursor, match.range.first).trim()
            if (chunk.isNotEmpty()) {
                blocks.add(Block.Text(chunk))
            }
        }
        blocks.add(Block.Media(id))
        cursor = match.range.last + 1
    }
    if (cursor < content.length) {
        val chunk = content.substring(cursor).trim()
        if (chunk.isNotEmpty()) {
            blocks.add(Block.Text(chunk))
        }
    }
    return blocks
}
