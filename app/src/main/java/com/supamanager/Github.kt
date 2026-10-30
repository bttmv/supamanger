package com.supamanager
import android.util.Base64
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

class Gh(private val token: String) {
    private val H get() = mapOf("Authorization" to "Bearer $token", "Accept" to "application/vnd.github+json", "X-GitHub-Api-Version" to "2022-11-28")
    private val J = "application/json".toMediaType()
    private fun req(m: String, path: String, body: JSONObject? = null) = Api.call("https://api.github.com$path", m, H, body?.toString()?.toRequestBody(J))
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)

    fun user(): String = JSONObject(req("GET", "/user").second).optString("login").ifBlank { error("التوكن غير صحيح") }

    fun ensureRepo(owner: String, repo: String): String {
        if (req("GET", "/repos/$owner/$repo").first == 200) return "المستودع موجود"
        val r = req("POST", "/user/repos", JSONObject().put("name", repo).put("private", true).put("auto_init", true))
        return if (r.first == 201) "تم إنشاء مستودع خاص" else error("فشل إنشاء المستودع: ${r.second}")
    }

    fun putSecret(owner: String, repo: String, name: String, value: String): String {
        val pk = JSONObject(req("GET", "/repos/$owner/$repo/actions/secrets/public-key").second)
        val key = Base64.decode(pk.getString("key"), Base64.NO_WRAP)
        val msg = value.toByteArray()
        val out = ByteArray(msg.size + 48)
        LazySodiumAndroid(SodiumAndroid()).cryptoBoxSeal(out, msg, msg.size.toLong(), key)
        val r = req("PUT", "/repos/$owner/$repo/actions/secrets/$name", JSONObject().put("encrypted_value", b64(out)).put("key_id", pk.getString("key_id")))
        return if (r.first in 200..204) "تم حفظ السر $name" else error("فشل حفظ السر: ${r.second}")
    }

    fun putFile(owner: String, repo: String, path: String, content: ByteArray): String {
        val ex = req("GET", "/repos/$owner/$repo/contents/$path")
        val body = JSONObject().put("message", "Update $path").put("content", b64(content))
        if (ex.first == 200) body.put("sha", JSONObject(ex.second).getString("sha"))
        val r = req("PUT", "/repos/$owner/$repo/contents/$path", body)
        return if (r.first in 200..201) "تم رفع $path" else error("فشل رفع الملف (تأكد من صلاحية workflow): ${r.second}")
    }

    fun dispatch(owner: String, repo: String): String {
        val r = req("POST", "/repos/$owner/$repo/actions/workflows/keepalive.yml/dispatches", JSONObject().put("ref", "main"))
        return if (r.first == 204) "بدأ التشغيل" else "تعذر التشغيل الآن: ${r.second}"
    }

    fun runs(owner: String, repo: String): String {
        val a = JSONObject(req("GET", "/repos/$owner/$repo/actions/runs?per_page=5").second).optJSONArray("workflow_runs") ?: JSONArray()
        if (a.length() == 0) return "لا توجد تشغيلات بعد"
        return (0 until a.length()).joinToString("\n") { a.getJSONObject(it).let { o -> "${o.optString("created_at")}  ${o.optString("status")}  ${o.optString("conclusion")}" } }
    }

    fun setup(owner: String, repo: String, projects: List<Project>, yml: ByteArray): String {
        val l = mutableListOf(ensureRepo(owner, repo))
        val arr = JSONArray(); projects.forEach { arr.put(JSONObject().put("name", it.name).put("url", it.url).put("key", it.key)) }
        l += putSecret(owner, repo, "SUPABASE_PROJECTS", arr.toString())
        l += putFile(owner, repo, ".github/workflows/keepalive.yml", yml)
        Thread.sleep(4000); l += dispatch(owner, repo)
        return l.joinToString("\n") + "\n\nيعمل الإنعاش الآن يوميًا تلقائيًا."
    }
}
