@file:OptIn(ExperimentalMaterial3Api::class)

package br.com.amaggi.simparadas.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import br.com.amaggi.simparadas.fmtDate
import br.com.amaggi.simparadas.shiftDay
import br.com.amaggi.simparadas.today
import br.com.amaggi.simparadas.weekdayName

val Navy = Color(0xFF24405A)
val Blue = Color(0xFF006398)
val Green = Color(0xFF589C64)
val Gold = Color(0xFFEBC16B)
val Red = Color(0xFFC0392B)
val Soft = Color(0xFFF2F5F8)

@Composable
fun SimTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Blue, onPrimary = Color.White, primaryContainer = Color(0xFFD6E9F5), onPrimaryContainer = Navy,
            secondary = Green, onSecondary = Color.White, secondaryContainer = Color(0xFFDCEEDF), onSecondaryContainer = Color(0xFF1E4A27),
            tertiary = Gold, background = Soft, surface = Color.White, error = Red,
        ),
        content = content,
    )
}

/** Barra de dia com setas (anterior / próximo) e atalho para hoje. */
@Composable
fun DayBar(day: String, onDay: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { onDay(shiftDay(day, -1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Dia anterior") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(fmtDate(day), fontWeight = FontWeight.Bold, color = Navy)
            Text(weekdayName(day) + if (day == today()) " • hoje" else "", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
        if (day != today()) TextButton(onClick = { onDay(today()) }) { Text("Hoje") }
        IconButton(onClick = { onDay(shiftDay(day, 1)) }) { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Próximo dia") }
    }
}

@Composable
fun Pill(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun ProgressLine(pct: Double, modifier: Modifier = Modifier) {
    val c = when { pct >= 100 -> Green; pct > 0 -> Blue; else -> Color.LightGray }
    LinearProgressIndicator(progress = { (pct / 100.0).toFloat().coerceIn(0f, 1f) }, modifier = modifier.fillMaxWidth().height(6.dp), color = c, trackColor = Color(0xFFE5E9EE))
}

@Composable
fun Kpi(label: String, value: String, sub: String = "", color: Color = Navy, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = Color.White), elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = Color.Gray)
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

@Composable
fun EmptyState(title: String, hint: String = "") {
    Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(title, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, color = Navy)
        if (hint.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(hint, textAlign = TextAlign.Center, color = Color.Gray, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(top = 12.dp, bottom = 4.dp), fontWeight = FontWeight.Bold, color = Navy, style = MaterialTheme.typography.titleSmall)
}
