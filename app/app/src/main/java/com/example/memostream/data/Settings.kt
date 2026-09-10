package com.example.memostream.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class SyncConf(
    var url: String,
    var key: String,
    var enabled: Boolean = true,
    var plan: String = "free",
    var lastSyncedAt: Long = 0,
    var lastError: String? = null,
    var lastPullNotes: Long = 0,
    var lastPullFolders: Long = 0,
    var lastPullPurges: Long = 0,
    var lastPushAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("url", url)
        put("key", key)
        put("enabled", enabled)
        put("plan", plan)
        put("lastSyncedAt", lastSyncedAt)
        put("lastError", lastError ?: JSONObject.NULL)
        put("lastPullNotes", lastPullNotes)
        put("lastPullFolders", lastPullFolders)
        put("lastPullPurges", lastPullPurges)
        put("lastPushAt", lastPushAt)
    }

    companion object {
        fun fromJson(json: JSONObject): SyncConf = SyncConf(
            url = json.optString("url"),
            key = json.optString("key"),
            enabled = json.optBoolean("enabled", true),
            plan = json.optString("plan", "free"),
            lastSyncedAt = json.optLong("lastSyncedAt"),
            lastError = json.optString("lastError").takeIf {
                it.isNotEmpty() && it != "null"
            },
            lastPullNotes = json.optLong("lastPullNotes"),
            lastPullFolders = json.optLong("lastPullFolders"),
            lastPullPurges = json.optLong("lastPullPurges"),
            lastPushAt = json.optLong("lastPushAt"),
        )
    }
}

class Settings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("memostream", Context.MODE_PRIVATE)

    var defaultFolderId: Long?
        get() = prefs.getLong("defaultFolderId", -1L).takeIf {
            it >= 0
        }
        set(value) = prefs.edit().apply {
            if (value == null) {
                remove("defaultFolderId")
            } else {
                putLong("defaultFolderId", value)
            }
        }.apply()

    var theme: String
        get() = prefs.getString("theme", "system") ?: "system"
        set(value) = prefs.edit().putString("theme", value).apply()

    var enterBehavior: String
        get() = prefs.getString("enterBehavior", "newline") ?: "newline"
        set(value) = prefs.edit().putString("enterBehavior", value).apply()

    var compressVideo: Boolean
        get() = prefs.getBoolean("compressVideo", true)
        set(value) = prefs.edit().putBoolean("compressVideo", value).apply()

    var onboarded: Boolean
        get() = prefs.getBoolean("onboarded", false)
        set(value) = prefs.edit().putBoolean("onboarded", value).apply()

    var lastExportAt: Long
        get() = prefs.getLong("lastExportAt", 0)
        set(value) = prefs.edit().putLong("lastExportAt", value).apply()

    var sb: SyncConf?
        get() = prefs.getString("sb", null)?.let {
            runCatching {
                SyncConf.fromJson(JSONObject(it))
            }.getOrNull()
        }
        set(value) = prefs.edit().apply {
            if (value == null) {
                remove("sb")
            } else {
                putString("sb", value.toJson().toString())
            }
        }.apply()

    var purgeQueue: List<String>
        get() = prefs.getString("purgeQueue", null)?.let { raw ->
            runCatching {
                val array = JSONArray(raw)
                (0 until array.length()).map {
                    array.getString(it)
                }
            }.getOrDefault(emptyList())
        } ?: emptyList()
        set(value) = prefs.edit().putString("purgeQueue", JSONArray(value).toString()).apply()

    fun saveSb(conf: SyncConf?) {
        sb = conf
    }
}
