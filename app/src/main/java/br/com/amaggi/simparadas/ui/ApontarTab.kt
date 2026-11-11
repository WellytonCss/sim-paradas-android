@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.amaggi.simparadas.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.amaggi.simparadas.Act
import br.com.amaggi.simparadas.Repo
import br.com.amaggi.simparadas.fmtDate
import br.com.amaggi.simparadas.fmtNum
import br.com.amaggi.simparadas.parseNum
import kotlinx.coroutines.launch

/** Apontamento e consulta: procura qualquer operação (ordem, operação, descrição, equipamento). */
@Composable
fun ApontarTab(day: String) {
    val acts by Repo.acts.collectAsState()
    val names by Repo.sectorNames.collectAsState()
    var q by remember { mutableStateOf("") }
    var sector by remember { mutableStateOf("") }
    var onlyOpen by remember { mutableStateOf(true) }
    var expanded by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<Act?>(null) }
    val sectors = acts.map { it.sector }.distinct().sortedBy { names[it] ?: it }
    val terms = q.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
    val list = acts.asSequence()
        .filter { !onlyOpen || !it.done }
        .filter { sector.isBlank() || it.sector == sector }
        .filter { a -> terms.all { t -> "${a.order} ${a.op} ${a.name} ${a.techObj} ${a.code}".lowercase().contains(t) } }
        .take(300).toList()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            OutlinedTextField(q, { q = it }, Modifier.fillMaxWidth(), placeholder = { Text("Ordem, operação ou descrição") }, singleLine = true,
                leadingIcon = { Icon(Icons.Filled.Search, null) })
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ExposedDropdownMenuBox(expanded, { expanded = it }, Modifier.weight(1f)) {
                    OutlinedTextField(
                        if (sector.isBlank()) "Todos os setores" else names[sector] ?: sector, {}, readOnly = true, singleLine = true,
                        modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, textStyle = MaterialTheme.typography.bodySmall,
                    )
                    ExposedDropdownMenu(expanded, { expanded = false }) {
                        DropdownMenuItem(text = { Text("Todos os setores") }, onClick = { sector = ""; expanded = false })
                        sectors.forEach { s -> DropdownMenuItem(text = { Text(names[s] ?: s) }, onClick = { sector = s; expanded = false }) }
                    }
                }
                FilterChip(onlyOpen, { onlyOpen = !onlyOpen }, label = { Text("Não concluídas") }, modifier = Modifier.padding(top = 8.dp))
            }
            Text("${list.size}${if (list.size == 300) "+" else ""} operação(ões) • apontamento com data de ${fmtDate(day)}",
                style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 4.dp))
        }
        if (list.isEmpty()) EmptyState("Nenhuma operação encontrada.", if (acts.isEmpty()) "Toque em sincronizar (no alto) para baixar a parada." else "Mude a busca ou o filtro.")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp)) {
            items(list, key = { it.id }) { a -> ActCard(a, showSector = sector.isBlank()) { open = a } }
        }
    }
    open?.let { a -> ApontarDialog(a, day) { open = null } }
}

/** Detalhes da operação + lançamento de avanço, horas e observação. */
@Composable
fun ApontarDialog(a: Act, day: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val titles by Repo.orderTitles.collectAsState()
    val names by Repo.sectorNames.collectAsState()
    val execs by Repo.executors.collectAsState()
    var pct by remember { mutableFloatStateOf(a.percent.toFloat()) }
    var hours by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    val h = parseNum(hours)
    val changed = pct.toDouble() != a.percent || (h ?: 0.0) > 0 || note.isNotBlank()
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = {
            Button(enabled = changed && !saving && (hours.isBlank() || (h != null && h >= 0 && h <= 24 * 14)), onClick = {
                saving = true
                scope.launch { Repo.apontar(a, Math.round(pct).toDouble(), h ?: 0.0, note, day); onClose() }
            }) { Text("Salvar") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Fechar") } },
        title = { Text(a.label, color = Blue, fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(a.name, color = Navy, fontWeight = FontWeight.SemiBold)
                titles[a.order]?.takeIf { it.isNotBlank() }?.let { Text("Ordem: $it", style = MaterialTheme.typography.bodySmall) }
                if (a.techObj.isNotBlank()) Text("Equipamento: ${a.techObj}", style = MaterialTheme.typography.bodySmall)
                Text("Setor: ${names[a.sector] ?: a.sector}", style = MaterialTheme.typography.bodySmall)
                Row(Modifier.padding(vertical = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (a.orderOpen) Pill("Ordem aberta no SAP", Red) else if (a.orderStatus.isNotBlank()) Pill("SAP: ${a.orderStatus}", Green)
                    if (a.priority.isNotBlank()) Pill(a.priority, Navy)
                    if (a.capacities > 0) Pill("${a.capacities} pessoa(s)", Blue)
                }
                Text("Previsto: ${fmtDate(a.plannedStart)} a ${fmtDate(a.plannedFinish)} • ${fmtNum(a.plannedHours)} HH", style = MaterialTheme.typography.bodySmall)
                Text("Real: ${if (a.actualStart != null) fmtDate(a.actualStart) else "não iniciada"}${if (a.actualFinish != null) " a ${fmtDate(a.actualFinish)}" else ""} • ${fmtNum(a.actualHours)} HH lançadas",
                    style = MaterialTheme.typography.bodySmall)
                val who = a.executorIds.mapNotNull { execs[it]?.name }
                if (who.isNotEmpty()) Text("Equipe: ${who.joinToString(", ")}", style = MaterialTheme.typography.bodySmall)
                if (a.orderOpen) Text("A ordem ainda está aberta no SAP: o avanço fica no sistema, mas a confirmação no SAP só depois de liberada.",
                    style = MaterialTheme.typography.bodySmall, color = Red, modifier = Modifier.padding(top = 4.dp))
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Text("Avanço em ${fmtDate(day)}: ${Math.round(pct)}%", fontWeight = FontWeight.Bold, color = Navy)
                Slider(pct, { pct = it }, valueRange = 0f..100f, steps = 19)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(25, 50, 75, 100).forEach { v -> AssistChip(onClick = { pct = v.toFloat() }, label = { Text("$v%") }) }
                }
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(hours, { hours = it }, label = { Text("Horas trabalhadas hoje (HH)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(note, { note = it }, label = { Text("Observação / desvio") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                if (pct < a.percent) Text("Atenção: o avanço está menor que o atual (${fmtNum(a.percent, 0)}%).", color = Red, style = MaterialTheme.typography.bodySmall)
            }
        },
    )
}
