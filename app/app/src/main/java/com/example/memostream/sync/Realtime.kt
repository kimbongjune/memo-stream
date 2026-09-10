package com.example.memostream.sync

import com.example.memostream.data.*

import android.util.Log
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.Timer
import java.util.TimerTask

private const val RT_TOPIC = "realtime:memo-stream"
private const val RT_HEARTBEAT_MS = 30_000L
private val RT_TABLES = listOf("folders", "notes", "purges")
private val RT_BACKOFF_MS = longArrayOf(1000, 2000, 5000, 10000, 30000)

class Realtime(
    private val confProvider: () -> SyncConf?,
    private val onChange: () -> Unit,
    private val onJoined: () -> Unit,
) {
    private var socket: WebSocket? = null
    private var heartbeat: Timer? = null
    private var retry: Timer? = null
    private var attempt = 0
    private var ref = 0

    val alive: Boolean get() = socket != null

    @Synchronized
    fun connect() {
        val conf = confProvider()
        if (conf == null) {
            stop()
            return
        }
        if (socket != null) {
            return
        }

        val base = conf.url.replace(Regex("^http"), "ws")
        val url = "$base/realtime/v1/websocket?apikey=${conf.key}&vsn=1.0.0"
        val request = Request.Builder().url(url).build()
        socket = Sb.client.newWebSocket(request, Listener(conf))
    }

    @Synchronized
    fun stop() {
        clearTimers()
        socket?.close(1000, null)
        socket = null
    }

    private fun clearTimers() {
        heartbeat?.cancel()
        heartbeat = null
        retry?.cancel()
        retry = null
    }

    private fun nextRef(): String = (++ref).toString()

    private fun send(target: WebSocket, message: JSONObject) {
        runCatching {
            target.send(message.toString())
        }
    }

    private fun join(target: WebSocket, conf: SyncConf) {
        val id = nextRef()
        val changes = JSONArray()
        RT_TABLES.forEach { table ->
            changes.put(
                JSONObject().apply {
                    put("event", "*")
                    put("schema", "public")
                    put("table", table)
                }
            )
        }
        send(
            target,
            JSONObject().apply {
                put("topic", RT_TOPIC)
                put("event", "phx_join")
                put("ref", id)
                put("join_ref", id)
                put(
                    "payload",
                    JSONObject().apply {
                        put("access_token", conf.key)
                        put(
                            "config",
                            JSONObject().apply {
                                put("broadcast", JSONObject().put("self", false))
                                put("presence", JSONObject().put("key", ""))
                                put("postgres_changes", changes)
                            }
                        )
                    }
                )
            }
        )
    }

    @Synchronized
    private fun retryLater() {
        val delay = RT_BACKOFF_MS[minOf(attempt, RT_BACKOFF_MS.size - 1)]
        attempt++
        retry?.cancel()
        retry = Timer(true).also { timer ->
            timer.schedule(
                object : TimerTask() {
                    override fun run() = connect()
                },
                delay
            )
        }
    }

    @Synchronized
    private fun drop(source: WebSocket) {
        if (socket !== source) {
            return
        }
        socket = null
        heartbeat?.cancel()
        heartbeat = null
        retryLater()
    }

    private inner class Listener(private val conf: SyncConf) : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempt = 0
            join(webSocket, conf)
            heartbeat?.cancel()
            heartbeat = Timer(true).also { timer ->
                timer.scheduleAtFixedRate(
                    object : TimerTask() {
                        override fun run() {
                            send(
                                webSocket,
                                JSONObject().apply {
                                    put("topic", "phoenix")
                                    put("event", "heartbeat")
                                    put("payload", JSONObject())
                                    put("ref", nextRef())
                                }
                            )
                        }
                    },
                    RT_HEARTBEAT_MS, RT_HEARTBEAT_MS
                )
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val message = runCatching {
                JSONObject(text)
            }.getOrNull() ?: return
            when (message.optString("event")) {
                "postgres_changes" -> onChange()
                "phx_reply" -> {
                    if (message.optString("topic") != RT_TOPIC) {
                        return
                    }
                    val status = message.optJSONObject("payload")?.optString("status")
                    if (status == "ok") {
                        onJoined()
                    } else {
                        Log.w("realtime", "join rejected: ${message.optJSONObject("payload")}")
                    }
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.d("realtime", "socket failed", t)
            drop(webSocket)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = drop(webSocket)
    }
}
