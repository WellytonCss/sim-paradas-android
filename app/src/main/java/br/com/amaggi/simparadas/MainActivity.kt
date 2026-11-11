package br.com.amaggi.simparadas

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import br.com.amaggi.simparadas.ui.HomeScreen
import br.com.amaggi.simparadas.ui.LoginScreen
import br.com.amaggi.simparadas.ui.ProjectsScreen
import br.com.amaggi.simparadas.ui.SimTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(applicationContext)
        setContent { SimTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        Repo.kick()
    }
}

@Composable
fun AppRoot() {
    val session by Repo.session.collectAsState()
    val pid by Repo.projectId.collectAsState()
    when {
        session == null -> LoginScreen()
        pid == null -> ProjectsScreen()
        else -> HomeScreen()
    }
}
