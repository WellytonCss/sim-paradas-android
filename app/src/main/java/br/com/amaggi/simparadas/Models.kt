package br.com.amaggi.simparadas

import org.json.JSONArray
import org.json.JSONObject

data class Project(
    val id: String, val code: String, val name: String, val status: String,
    val start: String?, val finish: String?, val progress: Double,
) {
    companion object {
        fun from(j: JSONObject) = Project(
            j.getString("id"), j.str("code") ?: "", j.str("name") ?: "", j.str("status") ?: "",
            (j.str("start_at") ?: j.str("planned_start"))?.take(10), (j.str("finish_at") ?: j.str("planned_finish"))?.take(10), j.num("progress"),
        )
    }
}

/** Operação (atividade) do cronograma, no formato que o app usa. */
data class Act(
    val id: String, val code: String, val name: String, val order: String, val op: String,
    val sector: String, val sectorText: String, val techObj: String, val orderStatus: String, val priority: String,
    val plannedStart: String?, val plannedFinish: String?, val plannedHours: Double,
    val percent: Double, val actualHours: Double, val actualStart: String?, val actualFinish: String?,
    val programmedOn: String?, val milestone: Boolean, val scope: String?, val executorIds: List<String>, val capacities: Int,
) {
    val done: Boolean get() = percent >= 100.0
    /** Ordem ainda aberta no SAP (não liberada). */
    val orderOpen: Boolean get() = Regex("aberto|criad", RegexOption.IGNORE_CASE).containsMatchIn(orderStatus)
    val label: String get() = if (order.isNotBlank()) "$order / $op" else code
    val weight: Double get() = maxOf(0.1, plannedHours)

    companion object {
        fun from(j: JSONObject): Act {
            val m = j.optJSONObject("meta") ?: JSONObject()
            val ex = j.optJSONArray("executor_ids")
            val exl = if (ex == null) emptyList() else (0 until ex.length()).mapNotNull { ex.optString(it).takeIf { s -> s.isNotBlank() && s != "null" } }
            return Act(
                id = j.getString("id"), code = j.str("code") ?: "", name = j.str("name") ?: "",
                order = m.str("order") ?: "", op = m.str("op") ?: "",
                sector = m.str("wcOp") ?: m.str("wcMain") ?: j.str("responsible") ?: "SEM-SETOR",
                sectorText = m.str("wcOpText") ?: m.str("wcMainText") ?: "",
                techObj = m.str("techObjDesc") ?: "", orderStatus = m.str("orderStatus") ?: "", priority = m.str("priorityText") ?: "",
                plannedStart = j.str("planned_start"), plannedFinish = j.str("planned_finish"), plannedHours = j.num("planned_hours"),
                percent = j.num("percent_complete"), actualHours = j.num("actual_hours"),
                actualStart = j.str("actual_start"), actualFinish = j.str("actual_finish"),
                programmedOn = j.str("programmed_on"), milestone = j.optBoolean("is_milestone", false), scope = j.str("scope_status"),
                executorIds = exl, capacities = m.optInt("capacities", 0),
            )
        }
    }
}

data class Executor(val id: String, val name: String, val sector: String)
data class Hist(val activityId: String, val date: String, val pct: Double)
data class Crew(val role: String, val count: Int)
data class Equip(val name: String, val count: Int, val status: String)

/** RDO (diário de obra) de um dia. */
data class Report(
    val id: String, val date: String,
    val weatherMorning: String = "bom", val weatherAfternoon: String = "bom", val workable: Boolean = true,
    val crew: List<Crew> = emptyList(), val equipment: List<Equip> = emptyList(),
    val occurrences: String = "", val safety: String = "", val notes: String = "", val status: String = "draft",
) {
    fun row(projectId: String, userId: String?): JSONObject = JSONObject()
        .put("id", id).put("project_id", projectId).put("report_date", date)
        .put("weather_morning", weatherMorning).put("weather_afternoon", weatherAfternoon).put("workable", workable)
        .put("crew", JSONArray().also { a -> crew.filter { it.role.isNotBlank() }.forEach { a.put(JSONObject().put("role", it.role).put("count", it.count)) } })
        .put("equipment", JSONArray().also { a -> equipment.filter { it.name.isNotBlank() }.forEach { a.put(JSONObject().put("name", it.name).put("count", it.count).put("status", it.status)) } })
        .put("occurrences", occurrences.ifBlank { null }.orNull()).put("safety_notes", safety.ifBlank { null }.orNull())
        .put("general_notes", notes.ifBlank { null }.orNull()).put("status", status)
        .put("created_by", userId.orNull()).put("updated_at", java.time.Instant.now().toString())

    companion object {
        fun from(j: JSONObject): Report {
            val c = j.optJSONArray("crew") ?: JSONArray()
            val e = j.optJSONArray("equipment") ?: JSONArray()
            return Report(
                id = j.getString("id"), date = j.getString("report_date"),
                weatherMorning = j.str("weather_morning") ?: "bom", weatherAfternoon = j.str("weather_afternoon") ?: "bom",
                workable = j.optBoolean("workable", true),
                crew = (0 until c.length()).map { c.getJSONObject(it) }.map { Crew(it.str("role") ?: "", it.optInt("count", 0)) },
                equipment = (0 until e.length()).map { e.getJSONObject(it) }.map { Equip(it.str("name") ?: "", it.optInt("count", 0), it.str("status") ?: "") },
                occurrences = j.str("occurrences") ?: "", safety = j.str("safety_notes") ?: "", notes = j.str("general_notes") ?: "",
                status = j.str("status") ?: "draft",
            )
        }
    }
}

data class LocalPhoto(val id: String, val projectId: String, val date: String, val file: String, val caption: String, val uploaded: Boolean) {
    fun json(): JSONObject = JSONObject().put("id", id).put("projectId", projectId).put("date", date).put("file", file).put("caption", caption).put("uploaded", uploaded)

    companion object {
        fun from(j: JSONObject) = LocalPhoto(j.getString("id"), j.getString("projectId"), j.getString("date"), j.getString("file"), j.optString("caption"), j.optBoolean("uploaded"))
    }
}

data class RemotePhoto(val id: String, val path: String, val caption: String)

/** Item da fila de envio (o que foi feito no celular e ainda não chegou no servidor). */
data class PendingItem(val id: String, val type: String, val title: String, val error: String?)
