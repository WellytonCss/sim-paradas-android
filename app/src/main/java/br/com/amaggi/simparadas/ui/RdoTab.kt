@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package br.com.amaggi.simparadas.ui

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import br.com.amaggi.simparadas.Crew
import br.com.amaggi.simparadas.Equip
import br.com.amaggi.simparadas.RemotePhoto
import br.com.amaggi.simparadas.Repo
import br.com.amaggi.simparadas.Report
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

private val WEATHER = listOf("bom" to "Bom", "nublado" to "Nublado", "chuva" to "Chuva", "impraticavel" to "Impraticável")

/** RDO (diário de obra) do dia, com fotos. Salva no celular e envia quando houver sinal. */
@Composable
fun RdoTab(day: String, onDay: (String) -> Unit) {
    Column(Modifier.fillMaxSize()) {
        DayBar(day, onDay)
        RdoForm(day)
    }
}

@Composable
private fun RdoForm(day: String) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var loaded by remember(day) { mutableStateOf(false) }
    var r by remember(day) { mutableStateOf(Report(UUID.randomUUID().toString(), day)) }
    var remote by remember(day) { mutableStateOf<List<RemotePhoto>>(emptyList()) }
    var caption by remember(day) { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val allPhotos by Repo.photos.collectAsState()
    val pid by Repo.projectId.collectAsState()
    val mine = allPhotos.filter { it.date == day && it.projectId == pid }

    LaunchedEffect(day, pid) {
        Repo.loadReport(day)?.let { r = it }
        loaded = true
        remote = runCatching { Repo.remotePhotos(day) }.getOrDefault(emptyList())
    }

    var camFile by rememberSaveable { mutableStateOf<String?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = camFile?.let { File(it) }
        if (ok && f != null && f.exists()) scope.launch {
            runCatching { Repo.addPhoto(Uri.fromFile(f), day, caption); caption = "" }.onFailure { Repo.toast.value = "Não consegui salvar a foto: ${it.message}" }
            f.delete()
        }
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            uris.forEach { u -> runCatching { Repo.addPhoto(u, day, caption) }.onFailure { Repo.toast.value = "Não consegui salvar uma foto: ${it.message}" } }
            caption = ""
        }
    }
    fun takePhoto() {
        val dir = File(ctx.cacheDir, "fotos").apply { mkdirs() }
        val f = File(dir, "cap-${System.currentTimeMillis()}.jpg")
        camFile = f.absolutePath
        camera.launch(FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f))
    }

    if (!loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }; return }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("RDO do dia", fontWeight = FontWeight.Bold, color = Navy, modifier = Modifier.weight(1f))
            Pill(when (r.status) { "submitted" -> "Enviado"; "approved" -> "Aprovado"; else -> "Rascunho" }, when (r.status) { "submitted" -> Blue; "approved" -> Green; else -> Color.Gray })
        }
        val locked = r.status == "approved"
        if (locked) Text("Este RDO já foi aprovado no site e não pode mais ser alterado.", color = Red, style = MaterialTheme.typography.bodySmall)

        SectionTitle("Clima")
        Text("Manhã", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { WEATHER.forEach { (k, l) -> FilterChip(r.weatherMorning == k, { if (!locked) r = r.copy(weatherMorning = k) }, label = { Text(l) }) } }
        Text("Tarde", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { WEATHER.forEach { (k, l) -> FilterChip(r.weatherAfternoon == k, { if (!locked) r = r.copy(weatherAfternoon = k) }, label = { Text(l) }) } }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
            Text("Dia trabalhável", modifier = Modifier.weight(1f)); Switch(r.workable, { if (!locked) r = r.copy(workable = it) })
        }

        SectionTitle("Efetivo")
        r.crew.forEachIndexed { i, c ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(c.role, { v -> r = r.copy(crew = r.crew.toMutableList().also { it[i] = c.copy(role = v) }) }, Modifier.weight(1f), label = { Text("Função") }, singleLine = true, enabled = !locked)
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(if (c.count == 0) "" else "${c.count}", { v -> r = r.copy(crew = r.crew.toMutableList().also { it[i] = c.copy(count = v.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) }) },
                    Modifier.width(80.dp), label = { Text("Qtde") }, singleLine = true, enabled = !locked, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                IconButton(onClick = { r = r.copy(crew = r.crew.filterIndexed { j, _ -> j != i }) }, enabled = !locked) { Icon(Icons.Filled.Delete, "Remover") }
            }
        }
        TextButton(onClick = { r = r.copy(crew = r.crew + Crew("", 1)) }, enabled = !locked) { Icon(Icons.Filled.Add, null); Text(" Adicionar função") }

        SectionTitle("Equipamentos")
        r.equipment.forEachIndexed { i, e ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(e.name, { v -> r = r.copy(equipment = r.equipment.toMutableList().also { it[i] = e.copy(name = v) }) }, Modifier.weight(1f), label = { Text("Equipamento") }, singleLine = true, enabled = !locked)
                Spacer(Modifier.width(6.dp))
                OutlinedTextField(if (e.count == 0) "" else "${e.count}", { v -> r = r.copy(equipment = r.equipment.toMutableList().also { it[i] = e.copy(count = v.filter(Char::isDigit).take(4).toIntOrNull() ?: 0) }) },
                    Modifier.width(80.dp), label = { Text("Qtde") }, singleLine = true, enabled = !locked, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                IconButton(onClick = { r = r.copy(equipment = r.equipment.filterIndexed { j, _ -> j != i }) }, enabled = !locked) { Icon(Icons.Filled.Delete, "Remover") }
            }
        }
        TextButton(onClick = { r = r.copy(equipment = r.equipment + Equip("", 1, "operando")) }, enabled = !locked) { Icon(Icons.Filled.Add, null); Text(" Adicionar equipamento") }

        SectionTitle("Ocorrências")
        OutlinedTextField(r.occurrences, { r = r.copy(occurrences = it) }, Modifier.fillMaxWidth(), placeholder = { Text("Paralisações, falta de material, retrabalho…") }, minLines = 2, enabled = !locked)
        SectionTitle("Segurança")
        OutlinedTextField(r.safety, { r = r.copy(safety = it) }, Modifier.fillMaxWidth(), placeholder = { Text("DDS, quase acidentes, liberações…") }, minLines = 2, enabled = !locked)
        SectionTitle("Observações gerais")
        OutlinedTextField(r.notes, { r = r.copy(notes = it) }, Modifier.fillMaxWidth(), minLines = 2, enabled = !locked)

        SectionTitle("Fotos do dia (${mine.size + remote.size})")
        OutlinedTextField(caption, { caption = it }, Modifier.fillMaxWidth(), label = { Text("Legenda da próxima foto (opcional)") }, singleLine = true)
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { takePhoto() }) { Icon(Icons.Filled.PhotoCamera, null); Text(" Tirar foto") }
            OutlinedButton(onClick = { gallery.launch("image/*") }) { Icon(Icons.Filled.PhotoLibrary, null); Text(" Galeria") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            mine.forEach { p -> Thumb(p.file, remote = false, uploaded = p.uploaded) }
            remote.forEach { p -> Thumb(p.path, remote = true, uploaded = true) }
        }

        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = !busy && !locked, modifier = Modifier.weight(1f), onClick = {
                busy = true; scope.launch { Repo.saveReport(r.copy(status = if (r.status == "submitted") "submitted" else "draft")); busy = false }
            }) { Text("Salvar") }
            Button(enabled = !busy && !locked, modifier = Modifier.weight(1f), onClick = {
                busy = true; scope.launch { r = r.copy(status = "submitted"); Repo.saveReport(r); busy = false }
            }) { Text("Enviar RDO") }
        }
        Text("Sem sinal, o RDO e as fotos ficam guardados no celular e são enviados sozinhos quando a internet voltar.",
            style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(top = 8.dp, bottom = 24.dp))
    }
}

@Composable
private fun Thumb(path: String, remote: Boolean, uploaded: Boolean) {
    val bmp by produceState<Bitmap?>(null, path) { value = Repo.thumb(path, remote) }
    Box(Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xFFE5E9EE)), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), contentDescription = "Foto", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        Box(Modifier.align(Alignment.BottomEnd).padding(3.dp).background(Color.White.copy(alpha = 0.85f), RoundedCornerShape(50)).padding(2.dp)) {
            Icon(if (uploaded) Icons.Filled.CloudDone else Icons.Filled.CloudUpload, if (uploaded) "Enviada" else "Aguardando envio",
                tint = if (uploaded) Green else Gold, modifier = Modifier.size(16.dp))
        }
    }
}
