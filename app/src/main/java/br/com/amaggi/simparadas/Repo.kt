package br.com.amaggi.simparadas

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Dados do app: guarda tudo no celular (funciona sem sinal) e envia para o Supabase quando há internet.
 * O que é lançado no celular entra numa fila (outbox) e é enviado na ordem em que foi feito.
 */
object Repo {
    private lateinit var ctx: Context
    private lateinit var prefs: SharedPreferences
    private lateinit var dir: File
    private var ready = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val syncLock = Mutex()
    private val fileLock = Any()

    val session = MutableStateFlow<Session?>(null)
    val projects = MutableStateFlow<List<Project>>(emptyList())
    val projectId = MutableStateFlow<String?>(null)
    val acts = MutableStateFlow<List<Act>>(emptyList())
    val orderTitles = MutableStateFlow<Map<String, String>>(emptyMap())
    val executors = MutableStateFlow<Map<String, Executor>>(emptyMap())
    val sectorNames = MutableStateFlow<Map<String, String>>(emptyMap())
    val history = MutableStateFlow<List<Hist>>(emptyList())
    val pending = MutableStateFlow<List<PendingItem>>(emptyList())
    val photos = MutableStateFlow<List<LocalPhoto>>(emptyList())
    val syncing = MutableStateFlow(false)
    val loading = MutableStateFlow(false)
    val lastSync = MutableStateFlow(0L)
    val toast = MutableStateFlow<String?>(null)

    private val rawActs = LinkedHashMap<String, JSONObject>()
    private var wbs = JSONArray()
    private var hist = JSONArray()
    private var settings = JSONObject()
    private var outbox = JSONArray()

    // ------------------------------------------------------------------ início
    fun init(context: Context) {
        if (ready) return
        synchronized(this) {
            if (ready) return
            ctx = context.applicationContext
            prefs = ctx.getSharedPreferences("sim", Context.MODE_PRIVATE)
            dir = File(ctx.filesDir, "dados").apply { mkdirs() }
            val s = prefs.getString("session", null)?.let { runCatching { Session.from(JSONObject(it)) }.getOrNull() }
            Api.session = s
            session.value = s
            Api.onSession = { n -> prefs.edit().putString("session", n?.json()?.toString()).apply(); session.value = n }
            lastSync.value = prefs.getLong("lastSync", 0L)
            read("projetos.json")?.let { projects.value = parseProjects(JSONArray(it)) }
            outbox = read("fila.json")?.let { runCatching { JSONArray(it) }.getOrNull() } ?: JSONArray()
            photos.value = read("fotos.json")?.let { t -> JSONArray(t).let { a -> (0 until a.length()).map { LocalPhoto.from(a.getJSONObject(it)) } } } ?: emptyList()
            read("executantes.json")?.let { executors.value = parseExecutors(JSONArray(it)) }
            sectorMap = read("setores.json")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            publishPending()
            projectId.value = prefs.getString("projectId", null)
            projectId.value?.let { loadLocalProject(it) }
            ready = true
        }
        schedulePeriodic()
    }

    private var sectorMap = JSONObject()

    private fun read(name: String): String? = synchronized(fileLock) { File(dir, name).takeIf { it.exists() }?.readText() }
    private fun write(name: String, text: String) = synchronized(fileLock) {
        val tmp = File(dir, "$name.tmp"); tmp.writeText(text); tmp.renameTo(File(dir, name))
    }

    private fun parseProjects(a: JSONArray) = (0 until a.length()).map { Project.from(a.getJSONObject(it)) }
    private fun parseExecutors(a: JSONArray) = (0 until a.length()).map { a.getJSONObject(it) }
        .map { Executor(it.getString("id"), it.str("name") ?: "", it.str("sector") ?: "") }.associateBy { it.id }

    val userId: String? get() = session.value?.userId

    // ------------------------------------------------------------------ login
    suspend fun login(email: String, password: String) {
        val s = Api.signIn(email, password)
        if (s.mustChange) {
            logout(force = true)
            throw ApiException(403, "Sua senha ainda é a provisória. Entre uma vez no site (sim-paradas-amaggi.netlify.app) para criar sua senha e depois volte aqui.")
        }
        refreshProjects()
    }

    fun logout(force: Boolean = false): Boolean {
        if (!force && outbox.length() > 0) return false
        Api.session = null
        prefs.edit().clear().apply()
        synchronized(fileLock) { dir.listFiles()?.forEach { it.deleteRecursively() } }
        File(ctx.filesDir, "fotos").deleteRecursively()
        outbox = JSONArray(); rawActs.clear(); wbs = JSONArray(); hist = JSONArray(); settings = JSONObject()
        session.value = null; projects.value = emptyList(); projectId.value = null; acts.value = emptyList()
        photos.value = emptyList(); publishPending()
        return true
    }

    // ------------------------------------------------------------------ paradas (projetos)
    suspend fun refreshProjects() {
        loading.value = true
        try {
            val a = Api.select("projects", listOf(
                "select" to "id,code,name,status,planned_start,planned_finish,start_at,finish_at,progress",
                "status" to "neq.cancelled", "order" to "created_at.desc"))
            write("projetos.json", a.toString())
            projects.value = parseProjects(a)
        } finally { loading.value = false }
    }

    fun selectProject(id: String?) {
        projectId.value = id
        prefs.edit().putString("projectId", id).apply()
        rawActs.clear(); acts.value = emptyList(); history.value = emptyList()
        if (id == null) return
        loadLocalProject(id)
        scope.launch { runCatching { refreshProject() }.onFailure { if (rawActs.isEmpty()) toast.value = "Sem internet: não há dados desta parada no celular ainda." } }
    }

    private fun loadLocalProject(id: String) {
        val t = read("parada_$id.json") ?: return
        runCatching {
            val j = JSONObject(t)
            val a = j.optJSONArray("acts") ?: JSONArray()
            rawActs.clear()
            for (i in 0 until a.length()) a.getJSONObject(i).let { rawActs[it.getString("id")] = it }
            wbs = j.optJSONArray("wbs") ?: JSONArray()
            hist = j.optJSONArray("hist") ?: JSONArray()
            settings = j.optJSONObject("settings") ?: JSONObject()
            publish()
        }
    }

    private fun saveLocalProject() {
        val id = projectId.value ?: return
        val a = JSONArray(); rawActs.values.forEach { a.put(it) }
        write("parada_$id.json", JSONObject().put("acts", a).put("wbs", wbs).put("hist", hist).put("settings", settings).toString())
    }

    /** Baixa a parada inteira do servidor e guarda no celular. */
    suspend fun refreshProject() {
        val id = projectId.value ?: return
        loading.value = true
        try {
            val a = Api.select("activities", listOf(
                "select" to "id,code,name,planned_start,planned_finish,actual_start,actual_finish,percent_complete,planned_hours,actual_hours,is_milestone,meta,programmed_on,executor_ids,scope_status,status,responsible",
                "project_id" to "eq.$id", "order" to "sort_order.asc"))
            val w = Api.select("wbs", listOf("select" to "code,name", "project_id" to "eq.$id"))
            val h = Api.select("activity_progress", listOf("select" to "activity_id,progress_date,percent_complete", "project_id" to "eq.$id", "order" to "progress_date.asc"))
            val st = Api.select("org_settings", listOf("select" to "key,value", "key" to "in.(setores_padrao,\"setores_projeto:$id\")"))
            val ex = Api.select("executors", listOf("select" to "id,name,sector", "active" to "eq.true"))
            val sn = Api.select("sector_names", listOf("select" to "sap_code,name"))
            if (projectId.value != id) return
            synchronized(this) {
                rawActs.clear()
                for (i in 0 until a.length()) a.getJSONObject(i).let { rawActs[it.getString("id")] = it }
                // o que ainda está na fila continua valendo por cima do que veio do servidor
                for (i in 0 until outbox.length()) {
                    val op = outbox.getJSONObject(i)
                    if (op.optString("type") == "progress" && op.optString("projectId") == id) applyPatch(op.getString("activityId"), op.getJSONObject("patch"))
                }
                wbs = w; hist = h
                settings = JSONObject().also { s -> for (i in 0 until st.length()) st.getJSONObject(i).let { s.put(it.getString("key"), it.opt("value")) } }
                saveLocalProject()
            }
            write("executantes.json", ex.toString()); executors.value = parseExecutors(ex)
            sectorMap = JSONObject().also { m -> for (i in 0 until sn.length()) sn.getJSONObject(i).let { r -> r.str("sap_code")?.let { c -> m.put(c, r.str("name") ?: c) } } }
            write("setores.json", sectorMap.toString())
            publish()
        } finally { loading.value = false }
    }

    private fun applyPatch(actId: String, patch: JSONObject) {
        val raw = rawActs[actId] ?: return
        patch.keys().forEach { k -> raw.put(k, patch.get(k)) }
    }

    /** Monta a lista visível (mesma regra do site para setores fora do padrão e desativados). */
    private fun publish() {
        val pid = projectId.value
        val std = settings.optJSONObject("setores_padrao")
        val stdCodes = std?.keys()?.asSequence()?.filter { std.str(it) != null }?.toSet() ?: emptySet()
        val toggles = settings.optJSONObject("setores_projeto:$pid")
        val list = rawActs.values.map { Act.from(it) }.filter { a ->
            if (a.milestone || a.scope == "rejeitada") return@filter false
            if (toggles != null && toggles.has(a.sector) && !toggles.isNull(a.sector)) return@filter toggles.optBoolean(a.sector, true)
            !(stdCodes.isNotEmpty() && a.sector !in stdCodes && a.scope != "aprovada")
        }
        acts.value = list
        orderTitles.value = (0 until wbs.length()).map { wbs.getJSONObject(it) }.mapNotNull { r -> r.str("code")?.let { it to (r.str("name") ?: "") } }.toMap()
        val names = HashMap<String, String>()
        list.forEach { a ->
            names[a.sector] = sectorMap.str(a.sector) ?: std?.str(a.sector) ?: a.sectorText.ifBlank { a.sector }
        }
        sectorNames.value = names
        history.value = (0 until hist.length()).map { hist.getJSONObject(it) }.map { Hist(it.getString("activity_id"), it.getString("progress_date"), it.num("percent_complete")) }
    }

    fun sectorName(code: String): String = sectorNames.value[code] ?: code

    // ------------------------------------------------------------------ apontamento
    suspend fun apontar(a: Act, pct: Double, hours: Double, note: String, day: String) = withContext(Dispatchers.IO) {
        val pid = projectId.value ?: return@withContext
        val p = pct.coerceIn(0.0, 100.0)
        val start = a.actualStart?.takeIf { it <= day } ?: if (p > 0) day else null
        val finish = if (p >= 100) day else null
        val status = when { p >= 100 -> "completed"; p > 0 -> "in_progress"; else -> "not_started" }
        val total = a.actualHours + maxOf(0.0, hours)
        val patch = JSONObject().put("percent_complete", p).put("status", status).put("actual_start", start.orNull())
            .put("actual_finish", finish.orNull()).put("actual_hours", total).put("remaining_days", JSONObject.NULL)
        val prog = JSONObject().put("id", UUID.randomUUID().toString()).put("project_id", pid).put("activity_id", a.id)
            .put("progress_date", day).put("percent_complete", p).put("qty_done", 0).put("actual_start", start.orNull())
            .put("actual_finish", finish.orNull()).put("actual_hours", maxOf(0.0, hours)).put("note", note.trim().ifBlank { null }.orNull())
            .put("recorded_by", userId.orNull()).put("recorded_at", Instant.now().toString())
        synchronized(this@Repo) {
            applyPatch(a.id, patch)
            hist.put(JSONObject().put("activity_id", a.id).put("progress_date", day).put("percent_complete", p))
            saveLocalProject()
        }
        enqueue(JSONObject().put("type", "progress").put("projectId", pid).put("activityId", a.id)
            .put("title", "Avanço ${a.label}: ${fmtNum(p, 0)}%").put("prog", prog).put("patch", patch))
        publish()
        toast.value = "Apontamento salvo${if (online()) "" else " no celular (envia quando tiver sinal)"}."
    }

    // ------------------------------------------------------------------ RDO
    private fun localReports(pid: String): JSONObject = read("rdo_$pid.json")?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    /** RDO do dia: o que está no celular; se tiver internet e nada pendente, atualiza pelo servidor. */
    suspend fun loadReport(day: String): Report? {
        val pid = projectId.value ?: return null
        val local = localReports(pid).optJSONObject(day)?.let { Report.from(it) }
        val pendingHere = (0 until outbox.length()).any { outbox.getJSONObject(it).let { o -> o.optString("type") == "report" && o.optString("projectId") == pid && o.optString("date") == day } }
        if (pendingHere) return local
        return runCatching {
            val a = Api.select("daily_reports", listOf("select" to "*", "project_id" to "eq.$pid", "report_date" to "eq.$day"))
            if (a.length() > 0) {
                val row = a.getJSONObject(0)
                val all = localReports(pid); all.put(day, row); write("rdo_$pid.json", all.toString())
                Report.from(row)
            } else local
        }.getOrElse { local }
    }

    suspend fun saveReport(r: Report) = withContext(Dispatchers.IO) {
        val pid = projectId.value ?: return@withContext
        val row = r.row(pid, userId)
        val all = localReports(pid); all.put(r.date, row); write("rdo_$pid.json", all.toString())
        // substitui um envio pendente do mesmo dia (manda só a versão mais nova)
        synchronized(this@Repo) {
            val keep = JSONArray()
            for (i in 0 until outbox.length()) outbox.getJSONObject(i).let { o -> if (!(o.optString("type") == "report" && o.optString("projectId") == pid && o.optString("date") == r.date && !o.optBoolean("failed"))) keep.put(o) }
            outbox = keep
        }
        enqueue(JSONObject().put("type", "report").put("projectId", pid).put("date", r.date)
            .put("title", "RDO ${fmtDate(r.date)}${if (r.status == "submitted") " (enviado)" else " (rascunho)"}").put("row", row))
        toast.value = if (r.status == "submitted") "RDO enviado${if (online()) "" else " para a fila (sem sinal)"}." else "Rascunho do RDO salvo."
    }

    // ------------------------------------------------------------------ fotos
    suspend fun addPhoto(uri: Uri, day: String, caption: String) = withContext(Dispatchers.IO) {
        val pid = projectId.value ?: return@withContext
        val id = UUID.randomUUID().toString()
        val f = File(File(ctx.filesDir, "fotos"), "$id.jpg")
        compressPhoto(ctx, uri, f)
        val p = LocalPhoto(id, pid, day, f.absolutePath, caption, false)
        photos.value = photos.value + p
        savePhotos()
        enqueue(JSONObject().put("type", "photo").put("projectId", pid).put("photoId", id).put("date", day).put("title", "Foto do RDO ${fmtDate(day)}"))
    }

    private fun savePhotos() { write("fotos.json", JSONArray().also { a -> photos.value.forEach { a.put(it.json()) } }.toString()) }

    suspend fun remotePhotos(day: String): List<RemotePhoto> {
        val pid = projectId.value ?: return emptyList()
        val a = Api.select("attachments", listOf("select" to "id,storage_path,caption", "project_id" to "eq.$pid", "taken_on" to "eq.$day", "order" to "created_at.asc"))
        val mine = photos.value.map { it.id }.toSet()
        return (0 until a.length()).map { a.getJSONObject(it) }.filter { it.getString("id") !in mine }
            .map { RemotePhoto(it.getString("id"), it.getString("storage_path"), it.str("caption") ?: "") }
    }

    private val thumbCache = android.util.LruCache<String, android.graphics.Bitmap>(60)
    suspend fun thumb(path: String, remote: Boolean): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        thumbCache.get(path)?.let { return@withContext it }
        val bytes = runCatching { if (remote) Api.download(path) else File(path).readBytes() }.getOrNull() ?: return@withContext null
        thumbOf(bytes)?.also { thumbCache.put(path, it) }
    }

    // ------------------------------------------------------------------ fila de envio
    private fun enqueue(op: JSONObject) {
        synchronized(this) {
            op.put("id", UUID.randomUUID().toString()).put("createdAt", Instant.now().toString())
            outbox.put(op); write("fila.json", outbox.toString())
        }
        publishPending(); kick()
    }

    private fun publishPending() {
        pending.value = (0 until outbox.length()).map { outbox.getJSONObject(it) }
            .map { PendingItem(it.optString("id"), it.optString("type"), it.optString("title"), if (it.optBoolean("failed")) it.optString("error") else null) }
    }

    fun discard(id: String) {
        synchronized(this) {
            val keep = JSONArray()
            for (i in 0 until outbox.length()) outbox.getJSONObject(i).let { if (it.optString("id") != id) keep.put(it) }
            outbox = keep; write("fila.json", outbox.toString())
        }
        publishPending()
    }

    fun retryFailed() {
        synchronized(this) {
            for (i in 0 until outbox.length()) outbox.getJSONObject(i).let { it.remove("failed"); it.remove("error") }
            write("fila.json", outbox.toString())
        }
        publishPending(); kick()
    }

    fun online(): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Envia a fila para o servidor. Devolve true se não sobrou nada para tentar de novo. */
    suspend fun syncNow(): Boolean = syncLock.withLock {
        if (session.value == null) return true
        syncing.value = true
        try {
            while (true) {
                val op = synchronized(this) { (0 until outbox.length()).map { outbox.getJSONObject(it) }.firstOrNull { !it.optBoolean("failed") } } ?: break
                try {
                    process(op)
                    discard(op.getString("id"))
                } catch (e: ApiException) {
                    if (e.code == 401) { toast.value = "Sessão expirada: saia e entre de novo para enviar."; return false }
                    if (e.code in 400..499 && e.code != 408 && e.code != 429) {
                        synchronized(this) { op.put("failed", true).put("error", e.message ?: "Erro ${e.code}"); write("fila.json", outbox.toString()) }
                        publishPending(); continue
                    }
                    return false
                } catch (e: IOException) {
                    return false
                }
            }
            lastSync.value = System.currentTimeMillis(); prefs.edit().putLong("lastSync", lastSync.value).apply()
            true
        } finally { syncing.value = false }
    }

    private suspend fun process(op: JSONObject) {
        when (op.optString("type")) {
            "progress" -> {
                Api.upsert("activity_progress", JSONArray().put(op.getJSONObject("prog")), "id")
                Api.patch("activities", op.getString("activityId"), op.getJSONObject("patch"))
            }
            "report" -> {
                val row = op.getJSONObject("row")
                val txt = Api.upsert("daily_reports", JSONArray().put(row), "project_id,report_date", returnRows = true)
                val saved = runCatching { JSONArray(txt).getJSONObject(0) }.getOrNull()
                if (saved != null) {
                    val pid = op.getString("projectId")
                    val all = localReports(pid); all.put(op.getString("date"), saved); write("rdo_$pid.json", all.toString())
                }
            }
            "photo" -> {
                val photo = photos.value.firstOrNull { it.id == op.getString("photoId") } ?: return
                val f = File(photo.file)
                if (!f.exists()) return
                val bytes = f.readBytes()
                val path = "${photo.projectId}/attachments/${photo.id}/foto-${photo.date}.jpg"
                Api.upload(path, bytes, "image/jpeg")
                val rep = Api.select("daily_reports", listOf("select" to "id", "project_id" to "eq.${photo.projectId}", "report_date" to "eq.${photo.date}"))
                val reportId = if (rep.length() > 0) rep.getJSONObject(0).getString("id") else null
                val row = JSONObject().put("id", photo.id).put("project_id", photo.projectId).put("entity_type", "daily_report")
                    .put("entity_id", reportId.orNull()).put("file_name", "foto-${photo.date}.jpg").put("mime_type", "image/jpeg")
                    .put("size_bytes", bytes.size).put("storage_path", path).put("caption", photo.caption.ifBlank { null }.orNull())
                    .put("taken_on", photo.date).put("created_by", userId.orNull())
                Api.upsert("attachments", JSONArray().put(row), "id")
                photos.value = photos.value.map { if (it.id == photo.id) it.copy(uploaded = true) else it }
                savePhotos()
            }
        }
    }

    private val netOk = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun kick() {
        if (!::ctx.isInitialized) return
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(netOk)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("sim-envio", ExistingWorkPolicy.APPEND_OR_REPLACE, req)
    }

    private fun schedulePeriodic() {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(netOk).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sim-envio-periodico", ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /** Botão "Sincronizar": envia a fila e baixa a parada atualizada. */
    suspend fun syncAndRefresh(): String {
        val ok = syncNow()
        return try {
            refreshProjects(); refreshProject()
            if (ok) "Tudo sincronizado." else "Dados atualizados; alguns lançamentos ainda não foram enviados."
        } catch (e: Exception) {
            if (e is ApiException) e.message ?: "Erro" else "Sem conexão com o servidor. Os lançamentos ficam guardados no celular."
        }
    }
}
