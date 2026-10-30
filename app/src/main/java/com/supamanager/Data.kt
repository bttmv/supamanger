package com.supamanager
import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class Project(val name: String, val url: String, val key: String, val pat: String = "") {
    val ref get() = url.removePrefix("https://").substringBefore('.')
}

object Store {
    fun p(c: Context) = EncryptedSharedPreferences.create(c, "secure", MasterKey.Builder(c).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV, EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    fun load(c: Context): List<Project> {
        val a = JSONArray(p(c).getString("projects", "[]"))
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Project(o.getString("n"), o.getString("u"), o.getString("k"), o.optString("t")) } }
    }
    fun save(c: Context, l: List<Project>) {
        val a = JSONArray(); l.forEach { a.put(JSONObject().put("n", it.name).put("u", it.url).put("k", it.key).put("t", it.pat)) }
        p(c).edit().putString("projects", a.toString()).apply()
    }
    fun log(c: Context, name: String, msg: String) = p(c).edit().putString("log_$name", msg).apply()
    fun lastLog(c: Context, name: String) = p(c).getString("log_$name", "لم يتم بعد") ?: ""
}

object Api {
    private val http = OkHttpClient()
    private val J = "application/json".toMediaType()
    fun call(url: String, method: String, headers: Map<String, String>, body: RequestBody? = null): Pair<Int, String> {
        val b = Request.Builder().url(url); headers.forEach { (k, v) -> b.header(k, v) }
        b.method(method, body ?: if (method == "GET") null else "".toRequestBody(J))
        http.newCall(b.build()).execute().use { return it.code to (it.body?.string() ?: "") }
    }
    private fun h(p: Project) = mapOf("apikey" to p.key, "Authorization" to "Bearer ${p.key}", "Prefer" to "return=representation")
    fun ping(p: Project) = call("${p.url}/rest/v1/", "GET", h(p)).first
    fun tables(p: Project): List<String> {
        val d = JSONObject(call("${p.url}/rest/v1/", "GET", h(p)).second).optJSONObject("definitions") ?: return emptyList()
        return d.keys().asSequence().toList().sorted()
    }
    fun rows(p: Project, t: String) = call("${p.url}/rest/v1/$t?limit=50", "GET", h(p)).second
    fun insert(p: Project, t: String, json: String) = call("${p.url}/rest/v1/$t", "POST", h(p) + ("Content-Type" to "application/json"), json.toRequestBody(J)).second
    fun delete(p: Project, t: String, filter: String) = call("${p.url}/rest/v1/$t?$filter", "DELETE", h(p)).second
    fun buckets(p: Project) = call("${p.url}/storage/v1/bucket", "GET", h(p)).second
    fun objects(p: Project, b: String) = call("${p.url}/storage/v1/object/list/$b", "POST", h(p) + ("Content-Type" to "application/json"),
        """{"prefix":"","limit":100}""".toRequestBody(J)).second
    fun upload(p: Project, b: String, name: String, bytes: ByteArray, mime: String) =
        call("${p.url}/storage/v1/object/$b/$name", "POST", h(p) + ("Content-Type" to mime), bytes.toRequestBody(mime.toMediaType())).second
    fun users(p: Project) = call("${p.url}/auth/v1/admin/users?per_page=50", "GET", h(p)).second
    private fun mg(p: Project) = mapOf("Authorization" to "Bearer ${p.pat}", "Content-Type" to "application/json")
    fun sql(p: Project, q: String) = if (p.pat.isBlank()) "أضف Personal Access Token للمشروع لتشغيل SQL"
        else call("https://api.supabase.com/v1/projects/${p.ref}/database/query", "POST", mg(p), JSONObject().put("query", q).toString().toRequestBody(J)).second
    /** ينعش المشروع، وإن كان متوقفاً ومعه PAT يحاول استعادته */
    fun keepAlive(p: Project): String {
        if (p.pat.isNotBlank()) runCatching {
            val st = JSONObject(call("https://api.supabase.com/v1/projects/${p.ref}", "GET", mg(p)).second).optString("status")
            if (st == "INACTIVE" || st == "PAUSED") { call("https://api.supabase.com/v1/projects/${p.ref}/restore", "POST", mg(p)); return "كان متوقفاً — أُرسل طلب استعادة" }
        }
        return "HTTP ${ping(p)}"
    }
}
