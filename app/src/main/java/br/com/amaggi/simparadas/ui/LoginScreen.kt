@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.amaggi.simparadas.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import br.com.amaggi.simparadas.ApiException
import br.com.amaggi.simparadas.R
import br.com.amaggi.simparadas.Repo
import br.com.amaggi.simparadas.fmtDate
import br.com.amaggi.simparadas.fmtNum
import kotlinx.coroutines.launch

@Composable
fun LoginScreen() {
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf("") }
    var pw by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    Column(
        Modifier.fillMaxSize().background(Navy).verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.height(40.dp))
        Image(painterResource(R.drawable.sim_logo), contentDescription = "SIM", modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(12.dp))
        Text("SIM Paradas", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text("Apontamento, programação e RDO de campo", color = Color(0xFFBFD3E3), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
        Card(colors = CardDefaults.cardColors(containerColor = Color.White), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Entrar", fontWeight = FontWeight.Bold, color = Navy)
                Text("Use o mesmo e-mail e senha do site.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(email, { email = it }, label = { Text("E-mail") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(pw, { pw = it }, label = { Text("Senha") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = Red, style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        busy = true; error = null
                        scope.launch {
                            try { Repo.login(email, pw) } catch (e: ApiException) { error = e.message } catch (e: Exception) { error = "Sem conexão com o servidor. Verifique a internet." }
                            busy = false
                        }
                    },
                    enabled = !busy && email.contains("@") && pw.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                ) { if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp) else Text("Entrar") }
            }
        }
        Spacer(Modifier.height(24.dp))
        Image(painterResource(R.drawable.amaggi_logo), contentDescription = "AMAGGI", modifier = Modifier.height(22.dp))
        Text("PCM Navegação", color = Color(0xFFBFD3E3), style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun ProjectsScreen() {
    val scope = rememberCoroutineScope()
    val list by Repo.projects.collectAsState()
    val loading by Repo.loading.collectAsState()
    val session by Repo.session.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { runCatching { Repo.refreshProjects() }.onFailure { error = if (list.isEmpty()) "Sem internet e sem paradas guardadas no celular." else null } }
    Scaffold(topBar = {
        TopAppBar(
            title = { Column { Text("Escolha a parada"); Text(session?.email ?: "", style = MaterialTheme.typography.bodySmall) } },
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White, actionIconContentColor = Color.White),
            actions = {
                IconButton(onClick = { scope.launch { error = runCatching { Repo.refreshProjects(); null }.getOrElse { "Sem conexão com o servidor." } } }) { Icon(Icons.Filled.Refresh, "Atualizar") }
                IconButton(onClick = { if (!Repo.logout()) error = "Há lançamentos ainda não enviados. Conecte à internet e sincronize antes de sair." }) { Icon(Icons.AutoMirrored.Filled.Logout, "Sair") }
            },
        )
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().background(Soft)) {
            if (loading) Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator(Modifier.size(22.dp)) }
            error?.let { Text(it, color = Red, modifier = Modifier.padding(12.dp)) }
            if (list.isEmpty() && !loading) EmptyState("Nenhuma parada encontrada.", "Confira se seu usuário tem acesso às paradas no site.")
            LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
                items(list, key = { it.id }) { p ->
                    Card(
                        Modifier.fillMaxWidth().padding(vertical = 5.dp).clickable { Repo.selectProject(p.id) },
                        colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp),
                    ) {
                        Column(Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(p.code, fontWeight = FontWeight.Bold, color = Blue, modifier = Modifier.weight(1f))
                                Pill(statusLabel(p.status), if (p.status == "completed") Green else if (p.status == "active") Blue else Color.Gray)
                            }
                            Text(p.name, color = Navy)
                            Text("${fmtDate(p.start)} a ${fmtDate(p.finish)} • avanço ${fmtNum(p.progress)}%", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            Spacer(Modifier.height(6.dp)); ProgressLine(p.progress)
                        }
                    }
                }
            }
        }
    }
}

fun statusLabel(s: String) = when (s) { "planning" -> "Planejamento"; "active" -> "Em andamento"; "completed" -> "Concluída"; "on_hold" -> "Pausada"; else -> s }
