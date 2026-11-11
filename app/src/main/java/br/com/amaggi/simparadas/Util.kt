package br.com.amaggi.simparadas

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

val PT_BR: Locale = Locale.forLanguageTag("pt-BR")

fun JSONObject.str(k: String): String? = if (isNull(k)) null else optString(k).takeIf { it.isNotBlank() }
fun JSONObject.num(k: String): Double = if (isNull(k)) 0.0 else optDouble(k, 0.0).let { if (it.isNaN()) 0.0 else it }
fun Any?.orNull(): Any = this ?: JSONObject.NULL

fun today(): String = LocalDate.now().toString()
fun shiftDay(s: String, n: Long): String = LocalDate.parse(s).plusDays(n).toString()
fun fmtDate(s: String?): String = s?.let { runCatching { LocalDate.parse(it.take(10)).format(DateTimeFormatter.ofPattern("dd/MM/yyyy")) }.getOrNull() } ?: "—"
fun weekdayName(s: String): String = runCatching { LocalDate.parse(s).dayOfWeek.getDisplayName(TextStyle.FULL, PT_BR) }.getOrDefault("")
fun fmtNum(d: Double, dec: Int = 1): String = String.format(PT_BR, "%.${dec}f", d)
fun parseNum(s: String): Double? {
    val t = s.trim()
    return if (t.contains(',')) t.replace(".", "").replace(',', '.').toDoubleOrNull() else t.toDoubleOrNull()
}

/** Foto do celular: reduz para no máximo 1600 px, corrige a rotação e grava em JPEG. */
fun compressPhoto(ctx: Context, uri: Uri, out: File): Long {
    val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Não consegui ler a foto")
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1600) sample *= 2
    var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        ?: throw IOException("Imagem inválida")
    val rot = runCatching {
        when (ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
    }.getOrDefault(0f)
    val side = maxOf(bmp.width, bmp.height)
    if (side > 1600) { val k = 1600f / side; bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * k).toInt(), (bmp.height * k).toInt(), true) }
    if (rot != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(rot) }, true)
    out.parentFile?.mkdirs()
    out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 82, it) }
    return out.length()
}

/** Miniatura leve para mostrar na tela. */
fun thumbOf(bytes: ByteArray, max: Int = 320): Bitmap? {
    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, b)
    var s = 1
    while (maxOf(b.outWidth, b.outHeight) / (s * 2) >= max) s *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = s })
}
