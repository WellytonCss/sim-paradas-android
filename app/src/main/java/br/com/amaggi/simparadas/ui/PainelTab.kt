@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.amaggi.simparadas.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import br.com.amaggi.simparadas.Act
import br.com.amaggi.simparadas.Hist
import br.com.amaggi.simparadas.Repo
import br.com.amaggi.simparadas.fmtDate
import br.com.amaggi.simparadas.fmtNum
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Date

private fun planFrac(a: Act, d: LocalDate): Double {
    val s = a.plannedStart?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: return 0.0
    val f = a.plannedFinish?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() } ?: s
    if (d.isBefore(s)) return 0.0
    if (!d.isBefore(f)) return 1.0
    val tot = ChronoUnit.DAYS.between(s, f) + 1
    return (ChronoUnit.DAYS.between(s, d) + 1).toDouble() / tot
}

private data class Curve(val dates: List<LocalDate>, val plan: List<Double>, val real: List<Double?>)

private fun buildCurve(acts: List<Act>, hist: List<Hist>, actualNow: Double): Curve? {
    val starts = acts.mapNotNull { it.plannedStart?.let { s -> runCatching { LocalDate.parse(s.take(10)) }.getOrNull() } }
    val ends = acts.mapNotNull { it.plannedFinish?.let { s -> runCatching { LocalDate.parse(s.take(10)) }.getOrNull() } }
    if (starts.isEmpty() || ends.isEmpty()) return null
    val a0 = starts.minBy { it.toEpochDay() }
    val endMax = ends.maxBy { it.toEpochDay() }
    val nowCap = LocalDate.ofEpochDay(minOf(LocalDate.now().toEpochDay(), endMax.plusDays(60).toEpochDay()))
    val a1 = if (nowCap.isAfter(endMax)) nowCap else endMax
    val totalW = acts.sumOf { it.weight }
    if (totalW <= 0) return null
    val steps = ChronoUnit.DAYS.between(a0, a1).coerceAtLeast(1)
    val stride = maxOf(1L, steps / 40)
    val dates = generateSequence(a0) { it.plusDays(stride) }.takeWhile { !it.isAfter(a1) }.toMutableList()
    if (dates.last() != a1) dates.add(a1)
    val w = acts.associate { it.id to it.weight }
    val sorted = hist.filter { w.containsKey(it.activityId) }.sortedBy { it.date }
    val last = HashMap<String, Double>()
    var idx = 0
    val today = LocalDate.now()
    val plan = dates.map { d -> acts.sumOf { it.weight * planFrac(it, d) } / totalW * 100 }
    val real = dates.map { d ->
        if (d.isAfter(today)) null else {
            while (idx < sorted.size && !LocalDate.parse(sorted[idx].date.take(10)).isAfter(d)) { last[sorted[idx].activityId] = sorted[idx].pct; idx++ }
            last.entries.sumOf { (id, p) -> (w[id] ?: 0.0) * p } / totalW
        }
    }.toMutableList()
    // o último ponto usa o avanço atual (inclui o que veio das confirmações do SAP)
    val li = real.indexOfLast { it != null }
    if (li >= 0) real[li] = actualNow
    return Curve(dates, plan, real)
}

/** Painel: avanço, previsto × real, Curva S, setores e situação do envio. */
@Composable
fun PainelTab() {
    val acts by Repo.acts.collectAsState()
    val hist by Repo.history.collectAsState()
    val names by Repo.sectorNames.collectAsState()
    val pending by Repo.pending.collectAsState()
    val last by Repo.lastSync.collectAsState()
    val today = LocalDate.now()
    val totalW = acts.sumOf { it.weight }.takeIf { it > 0 } ?: 1.0
    val real = acts.sumOf { it.weight * it.percent } / totalW
    val plan = acts.sumOf { it.weight * planFrac(it, today) } / totalW * 100
    val done = acts.count { it.done }
    val hhPrev = acts.sumOf { it.plannedHours }; val hhReal = acts.sumOf { it.actualHours }
    val openOrders = acts.filter { it.orderOpen && !it.done }.map { it.order }.distinct().size
    val curve = remember(acts, hist) { buildCurve(acts, hist, real) }
    val bySector = acts.groupBy { it.sector }.map { (s, l) ->
        val tw = l.sumOf { it.weight }.takeIf { it > 0 } ?: 1.0
        Triple(names[s] ?: s, l, l.sumOf { it.weight * it.percent } / tw)
    }.sortedBy { it.first }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        if (acts.isEmpty()) { EmptyState("Sem dados desta parada no celular.", "Toque em sincronizar (no alto) com internet para baixar."); return@Column }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("Avanço real", "${fmtNum(real)}%", "previsto ${fmtNum(plan)}%", if (real + 2 >= plan) Green else Red, Modifier.weight(1f))
            Kpi("Operações", "$done/${acts.size}", "concluídas", Navy, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kpi("HH previsto × real", "${fmtNum(hhPrev, 0)} × ${fmtNum(hhReal, 0)}", "", Navy, Modifier.weight(1f))
            Kpi("Ordens abertas no SAP", "$openOrders", "não liberadas", if (openOrders > 0) Red else Green, Modifier.weight(1f))
        }

        SectionTitle("Curva S")
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp)) {
                if (curve == null) Text("Sem datas previstas para montar a curva.", color = Color.Gray)
                else {
                    Canvas(Modifier.fillMaxWidth().height(190.dp)) {
                        val n = curve.dates.size
                        val x = { i: Int -> if (n <= 1) 0f else size.width * i / (n - 1) }
                        val y = { v: Double -> size.height - (v.coerceIn(0.0, 100.0) / 100.0 * size.height).toFloat() }
                        listOf(0.0, 25.0, 50.0, 75.0, 100.0).forEach { g -> drawLine(Color(0xFFE5E9EE), Offset(0f, y(g)), Offset(size.width, y(g)), 1.5f) }
                        val pp = Path(); curve.plan.forEachIndexed { i, v -> if (i == 0) pp.moveTo(x(i), y(v)) else pp.lineTo(x(i), y(v)) }
                        drawPath(pp, Blue, style = Stroke(width = 4f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 8f))))
                        val rp = Path(); var started = false
                        curve.real.forEachIndexed { i, v -> if (v != null) { if (!started) { rp.moveTo(x(i), y(v)); started = true } else rp.lineTo(x(i), y(v)) } }
                        drawPath(rp, Green, style = Stroke(width = 6f))
                        val ti = curve.dates.indexOfFirst { !it.isBefore(today) }
                        if (ti >= 0) drawLine(Gold, Offset(x(ti), 0f), Offset(x(ti), size.height), 3f)
                    }
                    Row(Modifier.fillMaxWidth()) {
                        Text(fmtDate(curve.dates.first().toString()), style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.weight(1f))
                        Text(fmtDate(curve.dates.last().toString()), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        Legend(Blue, "Previsto"); Legend(Green, "Realizado"); Legend(Gold, "Hoje")
                    }
                }
            }
        }

        SectionTitle("Avanço por setor")
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp)) {
                bySector.forEach { (name, l, pct) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(name, modifier = Modifier.weight(1f), color = Navy, style = MaterialTheme.typography.bodyMedium)
                        Text("${l.count { it.done }}/${l.size} • ${fmtNum(pct)}%", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    ProgressLine(pct, Modifier.padding(top = 3.dp, bottom = 9.dp))
                }
            }
        }

        SectionTitle("Envio para o servidor")
        Card(colors = CardDefaults.cardColors(containerColor = Color.White)) {
            Column(Modifier.padding(12.dp)) {
                Text(if (pending.isEmpty()) "Tudo enviado." else "${pending.size} lançamento(s) aguardando envio.", fontWeight = FontWeight.SemiBold, color = if (pending.isEmpty()) Green else Navy)
                Text("Última sincronização: ${if (last > 0) SimpleDateFormat("dd/MM/yyyy HH:mm", br.com.amaggi.simparadas.PT_BR).format(Date(last)) else "nunca"}",
                    style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                pending.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                            Text(p.title, style = MaterialTheme.typography.bodySmall, color = Navy)
                            p.error?.let { Text("Erro: $it", style = MaterialTheme.typography.bodySmall, color = Red) }
                        }
                        if (p.error != null) IconButton(onClick = { Repo.discard(p.id) }) { Icon(Icons.Filled.Close, "Descartar") }
                    }
                }
                if (pending.any { it.error != null }) TextButton(onClick = { Repo.retryFailed() }) { Text("Tentar enviar de novo os que deram erro") }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Legend(c: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(10.dp)) { drawCircle(c) }
        Spacer(Modifier.width(4.dp)); Text(label, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}
