package com.example.memostream.sync

import com.example.memostream.data.*

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

const val SB_BUCKET = "memo"
private const val SB_MGMT = "https://api.supabase.com/v1"
private const val SB_PROJECT_NAME = "memo-stream"
private const val SB_REGION = "ap-northeast-2"

val SB_SCHEMA = """
create table if not exists folders (
  uid        text primary key,
  name       text not null,
  "order"    int  default 0,
  pinned     boolean default false,
  created_at bigint not null,
  deleted_at bigint,
  updated_at bigint not null
);

create table if not exists notes (
  uid          text primary key,
  folder_uid   text,
  content      text  default '',
  blobs        jsonb default '[]'::jsonb,
  source_url   text,
  source_title text,
  pinned       boolean default false,
  created_at   bigint not null,
  edited_at    bigint,
  deleted_at   bigint,
  updated_at   bigint not null
);

create table if not exists purges (
  uid       text primary key,
  purged_at bigint not null
);

create index if not exists purges_purged_at_idx  on purges  (purged_at);
create index if not exists notes_updated_at_idx   on notes   (updated_at);
create index if not exists folders_updated_at_idx on folders (updated_at);

insert into storage.buckets (id, name, public)
values ('$SB_BUCKET', '$SB_BUCKET', false) on conflict (id) do nothing;

create or replace function public.usage_stats()
returns json language sql security definer set search_path = public, storage as ${
    '$'
}fn${
    '$'
}
  select json_build_object(
    'db_bytes',      pg_database_size(current_database()),
    'notes_bytes',   (select coalesce(sum(octet_length(content)), 0)
                        from public.notes where deleted_at is null),
    'storage_bytes', (select coalesce(sum((metadata->>'size')::bigint), 0)
                        from storage.objects where bucket_id = '$SB_BUCKET'),
    'storage_count', (select count(*) from storage.objects where bucket_id = '$SB_BUCKET'),
    'note_count',    (select count(*) from public.notes where deleted_at is null)
  );
${
    '$'
}fn${
    '$'
};

revoke all on function public.usage_stats() from public, anon, authenticated;
grant execute on function public.usage_stats() to service_role;

alter table folders enable row level security;
alter table notes   enable row level security;
alter table purges  enable row level security;

alter publication supabase_realtime set table folders, notes, purges;

notify pgrst, 'reload schema';
"""

data class Plan(val label: String, val db: Long, val storage: Long)

val SB_PLANS = linkedMapOf(
    "free" to Plan("무료", 500L * 1024 * 1024, 1024L * 1024 * 1024),
    "pro" to Plan("Pro", 8L * 1024 * 1024 * 1024, 100L * 1024 * 1024 * 1024),
)

fun planOf(conf: SyncConf?): Plan = SB_PLANS[conf?.plan ?: "free"] ?: SB_PLANS.getValue("free")

class SupabaseError(message: String) : Exception(message)

private val JSON_MEDIA = "application/json".toMediaType()

object Sb {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private fun body(response: Response, label: String): String {
        val text = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            when (response.code) {
                401 -> throw SupabaseError("키가 올바르지 않습니다. 다시 확인해 주세요.")
                403 -> throw SupabaseError("토큰 권한이 부족합니다. full access(legacy) 토큰으로 다시 발급해 주세요.")
            }
            throw SupabaseError("$label ${response.code}: ${text.take(300)}")
        }
        return text
    }

    private suspend fun call(request: Request, label: String): String = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use {
            body(it, label)
        }
    }

    suspend fun mgmt(token: String, path: String, payload: JSONObject? = null): String {
        val builder = Request.Builder()
            .url(SB_MGMT + path)
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
        if (payload != null) {
            builder.post(payload.toString().toRequestBody(JSON_MEDIA))
        }
        return call(builder.build(), "Supabase $path")
    }

    private fun restBuilder(conf: SyncConf, path: String) = Request.Builder()
        .url("${conf.url}/rest/v1$path")
        .header("apikey", conf.key)
        .header("Authorization", "Bearer ${conf.key}")
        .header("Content-Type", "application/json")

    suspend fun restGet(conf: SyncConf, path: String): JSONArray {
        val text = call(restBuilder(conf, path).build(), "REST $path")
        return if (text.isBlank()) JSONArray() else JSONArray(text)
    }

    suspend fun restPost(conf: SyncConf, path: String, payload: String, prefer: String?): String {
        val builder = restBuilder(conf, path).post(payload.toRequestBody(JSON_MEDIA))
        if (prefer != null) {
            builder.header("Prefer", prefer)
        }
        return call(builder.build(), "REST $path")
    }

    suspend fun restDelete(conf: SyncConf, path: String) {
        call(restBuilder(conf, path).delete().header("Prefer", "return=minimal").build(), "REST $path")
    }

    suspend fun upsert(conf: SyncConf, table: String, rows: JSONArray) {
        if (rows.length() == 0) {
            return
        }
        restPost(conf, "/$table?on_conflict=uid", rows.toString(), "resolution=merge-duplicates,return=minimal")
    }

    private fun storageUrl(conf: SyncConf, path: String) = "${conf.url}/storage/v1/object/$SB_BUCKET/$path"

    suspend fun objectExists(conf: SyncConf, path: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(storageUrl(conf, path))
            .head()
            .header("apikey", conf.key)
            .header("Authorization", "Bearer ${conf.key}")
            .build()
        client.newCall(request).execute().use {
            it.isSuccessful
        }
    }

    suspend fun upload(conf: SyncConf, path: String, file: File, mime: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(storageUrl(conf, path))
            .post(file.asRequestBody(mime.toMediaType()))
            .header("apikey", conf.key)
            .header("Authorization", "Bearer ${conf.key}")
            .header("x-upsert", "true")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful && response.code != 409) {
                throw SupabaseError("upload $path ${response.code}")
            }
        }
    }

    suspend fun download(conf: SyncConf, path: String, target: File) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(storageUrl(conf, path))
            .header("apikey", conf.key)
            .header("Authorization", "Bearer ${conf.key}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SupabaseError("download $path ${response.code}")
            }
            target.outputStream().use { out ->
                response.body!!.byteStream().copyTo(out)
            }
        }
    }

    suspend fun deleteObject(conf: SyncConf, path: String) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(storageUrl(conf, path))
            .delete()
            .header("apikey", conf.key)
            .header("Authorization", "Bearer ${conf.key}")
            .build()
        client.newCall(request).execute().close()
    }

    suspend fun listObjects(conf: SyncConf, prefix: String, limit: Int): JSONArray = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("prefix", prefix)
            put("limit", limit)
            put("offset", 0)
        }
        val request = Request.Builder().url("${conf.url}/storage/v1/object/list/$SB_BUCKET")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .header("apikey", conf.key)
            .header("Authorization", "Bearer ${conf.key}")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw SupabaseError("storage list ${response.code}")
            }
            JSONArray(response.body?.string().orEmpty())
        }
    }

    suspend fun usage(conf: SyncConf): JSONObject? = runCatching {
        val text = restPost(conf, "/rpc/usage_stats", "{}", null)
        if (text.isBlank()) null else JSONObject(text)
    }.getOrNull()

    suspend fun provision(token: String, onProgress: (String) -> Unit): Pair<SyncConf, String?> {
        onProgress("키 확인 중...")
        val orgs = JSONArray(mgmt(token, "/organizations"))
        if (orgs.length() == 0) {
            throw SupabaseError("조직이 없습니다. Supabase에서 조직을 먼저 만들어 주세요.")
        }

        onProgress("프로젝트 확인 중...")
        val projects = JSONArray(mgmt(token, "/projects"))
        var project: JSONObject? = null
        for (i in 0 until projects.length()) {
            val row = projects.getJSONObject(i)
            if (row.optString("name") == SB_PROJECT_NAME) {
                project = row
                break
            }
        }

        var dbPass: String? = null
        if (project == null) {
            dbPass = "${newUid()}aA1!"
            onProgress("프로젝트 만드는 중... (1~2분 걸립니다)")
            val payload = JSONObject().apply {
                put("name", SB_PROJECT_NAME)
                put("organization_id", orgs.getJSONObject(0).optString("id"))
                put("region", SB_REGION)
                put("db_pass", dbPass)
            }
            project = JSONObject(mgmt(token, "/projects", payload))
        }

        val ref = project.optString("id")
        var status = project.optString("status")
        var attempt = 0
        while (attempt < 90 && status != "ACTIVE_HEALTHY") {
            delay(5000)
            status = JSONObject(mgmt(token, "/projects/$ref")).optString("status")
            onProgress("프로젝트 준비 중... ($status)")
            attempt++
        }
        if (status != "ACTIVE_HEALTHY") {
            throw SupabaseError("프로젝트가 아직 준비되지 않았습니다 ($status). 잠시 후 다시 연결해 주세요.")
        }

        onProgress("테이블 확인 중...")
        mgmt(token, "/projects/$ref/database/query", JSONObject().put("query", SB_SCHEMA))

        onProgress("키 받는 중...")
        val keys = JSONArray(mgmt(token, "/projects/$ref/api-keys?reveal=true"))
        var serviceKey: String? = null
        for (i in 0 until keys.length()) {
            val row = keys.getJSONObject(i)
            if (row.optString("name") == "service_role") {
                serviceKey = row.optString("api_key")
                break
            }
        }
        if (serviceKey.isNullOrEmpty()) {
            throw SupabaseError("service_role 키를 받지 못했습니다.")
        }

        val conf = SyncConf(url = "https://$ref.supabase.co", key = serviceKey)

        onProgress("테이블 반영 기다리는 중...")
        var ready = false
        var tries = 0
        while (tries < 20 && !ready) {
            ready = runCatching {
                restGet(conf, "/notes?limit=0")
            }.isSuccess
            if (!ready) {
                delay(1000)
            }
            tries++
        }
        if (!ready) {
            throw SupabaseError("테이블이 아직 반영되지 않았습니다. 잠시 후 다시 연결해 주세요.")
        }

        return conf to dbPass
    }
}
