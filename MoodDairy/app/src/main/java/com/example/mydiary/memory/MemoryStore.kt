package com.example.mydiary.memory

import android.content.Context
import android.net.Uri
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import com.example.mydiary.data.local.SecurePreferences
import com.example.mydiary.data.local.SessionManager
import com.example.mydiary.data.network.ApiEndpoints
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.*
import java.io.File
import java.util.UUID

internal class MemorySessionExpired : IllegalStateException("登录状态已失效，请重新登录。手机草稿仍保留。")

internal class MemoryStore(val context: Context, val session: SessionManager) {
    val user = session.getUserId() ?: error("请先登录")
    private val prefs = SecurePreferences.create(context, "memory_drafts_secure")
    private val root = File(context.noBackupFilesDir, "memory/$user").apply { mkdirs() }
    private val client = MemoryHttp.client()
    private val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private fun encrypted(file: File) = EncryptedFile.Builder(context, file, key, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB).build()
    fun draft(): JSONObject? = prefs.getString("draft_$user", null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun drafts(): List<JSONObject> = prefs.all.filterKeys { it.startsWith("saved_${user}_") }.values.mapNotNull { runCatching {JSONObject(it as String)}.getOrNull() }
    fun save(value: JSONObject) {
        val edit=prefs.edit(); val server=value.optString("server_id")
        if(server.isNotBlank()) for(old in drafts().filter{it.optString("server_id")==server&&it.optString("request_id")!=value.getString("request_id")}) edit.remove("saved_${user}_${old.getString("request_id")}")
        edit.putString("draft_$user", value.toString()).putString("saved_${user}_${value.getString("request_id")}",value.toString()).apply()
    }
    fun discard(value:JSONObject) {
        for(file in (value.optJSONArray("files")?:JSONArray()).objects()) deleteMedia(file.getString("name"))
        prefs.edit().remove("saved_${user}_${value.getString("request_id")}").apply()
        if(draft()?.optString("request_id")==value.getString("request_id")) prefs.edit().remove("draft_$user").apply()
    }
    fun clear(serverId: String) {
        for(value in drafts().filter {it.optString("server_id")==serverId}) {
            discard(value)
        }
        if(draft()?.optString("server_id")==serverId) prefs.edit().remove("draft_$user").apply()
    }
    fun writeMedia(bytes: ByteArray): String {
        require(bytes.size <= 25 * 1024 * 1024) { "单份素材不得超过25MB" }
        val name = UUID.randomUUID().toString()
        encrypted(File(root, name)).openFileOutput().use { it.write(bytes) }
        return name
    }
    fun readMedia(name: String): ByteArray {
        require(name.matches(Regex("[a-f0-9-]{36}")))
        return encrypted(File(root, name)).openFileInput().use { it.readBytes() }
    }
    fun deleteMedia(name: String) { if (name.matches(Regex("[a-f0-9-]{36}"))) File(root, name).delete() }
    suspend fun importMedia(uri: Uri): String = withContext(Dispatchers.IO) {
        val bytes = context.contentResolver.openInputStream(uri)!!.use { input ->
            val output = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
            while (true) { val count = input.read(buffer); if (count < 0) break; output.write(buffer,0,count); require(output.size() <= 25*1024*1024) { "单份素材不得超过25MB" } }
            output.toByteArray()
        }
        writeMedia(bytes)
    }
    private fun request(path: String): Request.Builder {
        check(session.getUserId() == user) { "账号已切换，请重新进入" }
        return Request.Builder().url(ApiEndpoints.apiBaseUrl + path)
            .header("Authorization", "Bearer ${session.getAccessToken()}")
            .header("Cache-Control", "no-store")
    }
    suspend fun api(path: String, method: String = "GET", value: JSONObject? = null): Any = withContext(Dispatchers.IO) {
        val body = if (method in listOf("GET", "DELETE")) null else (value ?: JSONObject()).toString().toRequestBody("application/json".toMediaType())
        client.newCall(request(path).method(method, body).build()).execute().use { response ->
            if(response.code==401) throw MemorySessionExpired()
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) error(runCatching { JSONObject(text).getString("detail") }.getOrDefault("暂时无法连接，请稍后重试"))
            JSONTokener(text).nextValue()
        }
    }
    suspend fun upload(id: String, version: Int, name: String, kind: String): JSONObject = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).addFormDataPart("version", version.toString())
            .addFormDataPart("request_id",name)
            .addFormDataPart("kind", kind).addFormDataPart("file", "material", readMedia(name).toRequestBody("application/octet-stream".toMediaType())).build()
        client.newCall(request("future-letters/$id/assets").post(body).build()).execute().use { response ->
            if(response.code==401) throw MemorySessionExpired()
            val text = response.body!!.string(); if (!response.isSuccessful) error(JSONObject(text).optString("detail", "上传失败"))
            JSONObject(text)
        }
    }
    suspend fun bytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        client.newCall(request(path).build()).execute().use { response ->
            if(response.code==401) throw MemorySessionExpired()
            if (!response.isSuccessful) error("内容暂不可用或尚未到开启时间")
            response.body!!.bytes()
        }
    }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
