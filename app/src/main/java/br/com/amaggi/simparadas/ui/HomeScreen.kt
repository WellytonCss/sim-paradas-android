@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.amaggi.simparadas.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Today
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import br.com.amaggi.simparadas.Repo
import br.com.amaggi.simparadas.today
import kotlinx.coroutines.launch

@Composable
fun HomeScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pid by Repo.projectId.collectAsState()
    val projects by Repo.projects.collectAsState()
    val pending by Repo.pending.collectAsState()
    val syncing by Repo.syncing.collectAsState()
    val loading by Repo.loading.collectAsState()
    val toast by Repo.toast.collectAsState()
    var tab by rememberSaveable { mutableStateOf(0) }
    var day by rememberSaveable { mutableStateOf(today()) }
    var menu by remember { mutableStateOf(false) }
    val project = projects.firstOrNull { it.id == pid }

    LaunchedEffect(toast) { toast?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); Repo.toast.value = null } }
    BackHandler(enabled = tab != 0) { tab = 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column { Text(project?.code ?: "Parada"); Text(project?.name ?: "", style = MaterialTheme.typography.bodySmall, maxLines = 1) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Navy, titleContentColor = Color.White, actionIconContentColor = Color.White),
                actions = {
                    IconButton(onClick = { scope.launch { Repo.toast.value = Repo.syncAndRefresh() } }) {
                        if (syncing || loading) CircularProgressIndicator(Modifier.size(20.dp), color = Color.White, strokeWidth = 2.dp)
                        else BadgedBox(badge = { if (pending.isNotEmpty()) Badge { Text("${pending.size}") } }) { Icon(Icons.Filled.Sync, "Sincronizar") }
                    }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Menu") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Trocar de parada") }, leadingIcon = { Icon(Icons.Filled.SwapHoriz, null) }, onClick = { menu = false; Repo.selectProject(null) })
                        DropdownMenuItem(text = { Text("Sair") }, leadingIcon = { Icon(Icons.AutoMirrored.Filled.Logout, null) }, onClick = {
                            menu = false
                            if (!Repo.logout()) Repo.toast.value = "Há ${pending.size} lançamento(s) ainda não enviado(s). Sincronize antes de sair."
                        })
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White) {
                listOf(Triple("Hoje", Icons.Filled.Today, 0), Triple("Apontar", Icons.Filled.EditNote, 1), Triple("RDO", Icons.Filled.Description, 2), Triple("Painel", Icons.Filled.Insights, 3))
                    .forEach { (label, icon, i) -> NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Icon(icon, label) }, label = { Text(label) }) }
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                if (loading) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Gold)
                when (tab) {
                    0 -> TodayTab(day, { day = it })
                    1 -> ApontarTab(day)
                    2 -> RdoTab(day, { day = it })
                    else -> PainelTab()
                }
            }
        }
    }
}
