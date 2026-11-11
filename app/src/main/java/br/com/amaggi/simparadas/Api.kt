package br.com.amaggi.simparadas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiException(val code: Int, message: String) : Exception(message)

data class Session(
    val access: String, val refresh: String, val expiresAt: Long,
    val userId: String, val email: String, val name: String, val mustChange: Boolean,
) {
    fun json(): JSONObject = JSONObject().put("access", access).put("refresh", refresh).put("expiresAt", expiresAt)
        .put("userId", userId).put("email", email).put("name", name).put("mustChange", mustChange)

    companion object {
        fun from(j: JSONObject) = Session(j.getString("access"), j.getString("refresh"), j.getLong("expiresAt"),
            j.getString("userId"), j.optString("email"), j.optString("name"), j.optBoolean("mustChange"))
    }
}

/** Acesso direto ao Supabase (login, tabelas e fotos) pela internet. */
object Api {
    private val JSON_TYPE = "application/json; charset=utf-8".toMediaType()
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS).readTimeout(90, TimeUnit.SECONDS).writeTimeout(120, TimeUnit.SECONDS).build()

    @Volatile var session: Session? = null
    var onSession: (Session?) -> Unit = {}

    private fun parseSession(j: JSONObject): Session {
        val u = j.getJSONObject("user")
        val meta = u.optJSONObject("app_metadata")
        val um = u.optJSONObject("user_metadata")
        return Session(
            j.getString("access_token"), j.getString("refresh_token"),
            System.currentTimeMillis() / 1000 + j.optLong("expires_in", 3600),
            u.getString("id"), u.optString("email"), um?.str("full_name") ?: "", meta?.optBoolean("must_change_password", false) ?: false,
        )
    }

    private fun authCall(grant: String, body: JSONObject): Session {
        val req = Request.Builder().url("${Config.SUPABASE_URL}/auth/v1/token?grant_type=$grant")
            .header("apikey", Config.ANON_KEY).post(body.toString().toRequestBody(JSON_TYPE)).build()
        http.newCall(req).execute().use { r ->
            val txt = r.body?.string().orEmpty()
            if (!r.isSuccessful) {
                val msg = runCatching { JSONObject(txt).let { it.str("error_description") ?: it.str("msg") ?: it.str("message") } }.getOrNull() ?: ""
                if (grant == "password" && (r.code == 400 || msg.contains("invalid", true))) throw ApiException(r.code, "E-mail ou senha incorretos.")
                throw ApiException(if (grant == "refresh_token") 401 else r.code, msg.ifBlank { "Erro ${r.code} no login" })
            }
            return parseSession(JSONObject(txt))
        }
    }

    suspend fun signIn(email: String, password: String): Session = withContext(Dispatchers.IO) {
        val s = authCall("password", JSONObject().put("email", email.trim().lowercase()).put("password", password))
        session = s; onSession(s); s
    }

    private fun refreshBlocking(): Session {
        val s = session ?: throw ApiException(401, "Sessão expirada. Entre de novo.")
        val n = authCall("refresh_token", JSONObject().put("refresh_token", s.refresh))
        session = n; onSession(n); return n
    }

    private fun token(): String {
        val s = session ?: throw ApiException(401, "Sessão expirada. Entre de novo.")
        return if (s.expiresAt - System.currentTimeMillis() / 1000 < 90) refreshBlocking().access else s.access
    }

    private fun handle(r: Response): String {
        val txt = r.body?.string().orEmpty()
        if (!r.isSuccessful) {
            val msg = runCatching { JSONObject(txt).let { it.str("message") ?: it.str("error") } }.getOrNull() ?: ""
            throw ApiException(r.code, msg.ifBlank { "Erro ${r.code} no servidor" })
        }
        return txt
    }

    /** Executa com o token do usuário; se expirou, renova uma vez e tenta de novo. */
    private fun exec(build: (String) -> Request): String {
        http.newCall(build(token())).execute().use { r -> if (r.code != 401) return handle(r) }
        http.newCall(build(refreshBlocking().access)).execute().use { r -> return handle(r) }
    }

    private fun Request.Builder.auth(t: String): Request.Builder = header("apikey", Config.ANON_KEY).header("Authorization", "Bearer $t")

    private fun restUrl(table: String, params: List<Pair<String, String>>): HttpUrl {
        val b = "${Config.SUPABASE_URL}/rest/v1/$table".toHttpUrl().newBuilder()
        params.forEach { (k, v) -> b.addQueryParameter(k, v) }
        return b.build()
    }

    /** Lê uma tabela inteira em páginas de 1000 linhas. */
    suspend fun select(table: String, params: List<Pair<String, String>>, pageSize: Int = 1000): JSONArray = withContext(Dispatchers.IO) {
        val out = JSONArray()
        val url = restUrl(table, params)
        var from = 0
        while (true) {
            val f = from
            val txt = exec { t -> Request.Builder().url(url).auth(t).header("Range-Unit", "items").header("Range", "$f-${f + pageSize - 1}").get().build() }
            val arr = JSONArray(txt)
            for (i in 0 until arr.length()) out.put(arr.get(i))
            if (arr.length() < pageSize) break
            from += pageSize
        }
        out
    }

    suspend fun upsert(table: String, rows: JSONArray, onConflict: String, returnRows: Boolean = false): String = withContext(Dispatchers.IO) {
        val url = restUrl(table, listOf("on_conflict" to onConflict))
        val prefer = "resolution=merge-duplicates," + if (returnRows) "return=representation" else "return=minimal"
        exec { t -> Request.Builder().url(url).auth(t).header("Prefer", prefer).post(rows.toString().toRequestBody(JSON_TYPE)).build() }
    }

    suspend fun patch(table: String, id: String, body: JSONObject) {
        withContext(Dispatchers.IO) {
            val url = restUrl(table, listOf("id" to "eq.$id"))
            exec { t -> Request.Builder().url(url).auth(t).header("Prefer", "return=minimal").patch(body.toString().toRequestBody(JSON_TYPE)).build() }
        }
    }

    suspend fun upload(path: String, bytes: ByteArray, type: String) {
        withContext(Dispatchers.IO) {
            val url = "${Config.SUPABASE_URL}/storage/v1/object/${Config.BUCKET}/$path"
            exec { t -> Request.Builder().url(url).auth(t).header("x-upsert", "true").post(bytes.toRequestBody(type.toMediaType())).build() }
        }
    }

    suspend fun download(path: String): ByteArray = withContext(Dispatchers.IO) {
        val url = "${Config.SUPABASE_URL}/storage/v1/object/authenticated/${Config.BUCKET}/$path"
        http.newCall(Request.Builder().url(url).auth(token()).get().build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "Erro ${r.code} ao baixar a foto")
            r.body?.bytes() ?: ByteArray(0)
        }
    }
}
