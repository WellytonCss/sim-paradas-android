@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.amaggi.simparadas.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.amaggi.simparadas.Act
import br.com.amaggi.simparadas.Repo
import br.com.amaggi.simparadas.fmtNum

/** Programação do dia: operações programadas para a data, agrupadas por setor. */
@Composable
fun TodayTab(day: String, onDay: (String) -> Unit) {
    val acts by Repo.acts.collectAsState()
    val names by Repo.sectorNames.collectAsState()
    var open by remember { mutableStateOf<Act?>(null) }
    val list = acts.filter { it.programmedOn == day }
    val groups = list.groupBy { names[it.sector] ?: it.sector }.toSortedMap()
    Column(Modifier.fillMaxSize()) {
        DayBar(day, onDay)
        if (list.isEmpty()) {
            EmptyState("Nenhuma operação programada para este dia.", "A programação é feita no site (Programação por operação). Para lançar avanço de qualquer operação, use a aba Apontar.")
        } else {
            val hh = list.sumOf { it.plannedHours }
            Text("${list.size} operação(ões) • ${fmtNum(hh, 0)} HH previstas • ${list.count { it.done }} concluída(s)",
                Modifier.padding(horizontal = 14.dp, vertical = 6.dp), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
                groups.forEach { (sector, ops) ->
                    item(key = "h-$sector") { SectionTitle("$sector (${ops.size})") }
                    items(ops, key = { it.id }) { a -> ActCard(a) { open = a } }
                }
            }
        }
    }
    open?.let { a -> ApontarDialog(a, day) { open = null } }
}

@Composable
fun ActCard(a: Act, showSector: Boolean = false, onClick: () -> Unit) {
    val execs by Repo.executors.collectAsState()
    val names by Repo.sectorNames.collectAsState()
    Card(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.label, fontFamily = FontFamily.Monospace, color = Blue, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (a.orderOpen) Pill("Ordem aberta", Red)
                Spacer(Modifier.width(4.dp))
                Pill("${fmtNum(a.percent, 0)}%", if (a.done) Green else if (a.percent > 0) Blue else Color.Gray)
            }
            Text(a.name, color = Navy, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val who = a.executorIds.mapNotNull { execs[it]?.name }.joinToString(", ")
            val info = listOfNotNull(
                if (showSector) names[a.sector] ?: a.sector else null,
                "${fmtNum(a.plannedHours)} HH prev.",
                if (a.actualHours > 0) "${fmtNum(a.actualHours)} HH real" else null,
                who.ifBlank { null },
            ).joinToString(" • ")
            Text(info, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp)); ProgressLine(a.percent)
        }
    }
}
