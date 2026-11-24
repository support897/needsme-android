package xyz.needsme.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import java.io.File

/** Photos live on this phone only (never synced). */
object Photos {
    private fun file(ctx: Context, id: String) = File(ctx.filesDir, "avatar-$id.jpg")
    fun load(ctx: Context, id: String): Bitmap? = file(ctx, id).takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path) }
    fun save(ctx: Context, id: String, uri: Uri) {
        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return
        val side = 256; val s = maxOf(side.toFloat() / src.width, side.toFloat() / src.height)
        val scaled = Bitmap.createScaledBitmap(src, (src.width * s).toInt().coerceAtLeast(side), (src.height * s).toInt().coerceAtLeast(side), true)
        val sq = Bitmap.createBitmap(scaled, (scaled.width - side) / 2, (scaled.height - side) / 2, side, side)
        file(ctx, id).outputStream().use { sq.compress(Bitmap.CompressFormat.JPEG, 85, it) }
    }
    fun clear(ctx: Context, id: String) { file(ctx, id).delete() }
}

/** Light colors get dark marks, dark colors get light marks, so it is always readable. */
fun isLight(c: Long): Boolean { val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255; return 0.299 * r + 0.587 * g + 0.114 * b > 150 }

@Composable
fun Avatar(a: Account, size: Dp, version: Int = 0) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val bmp = remember(a.id, version) { Photos.load(ctx, a.id) }
    val bg = if (a.color != 0L) Color(0xFF000000 or a.color) else Bone
    val fg = if (a.color != 0L && !isLight(a.color)) Bone else Loam
    Box(Modifier.size(size).clip(CircleShape).background(bg), Alignment.Center) {
        if (bmp != null) Image(bmp.asImageBitmap(), null, Modifier.size(size), contentScale = ContentScale.Crop)
        else Text(a.initial, color = fg, fontSize = (size.value * 0.4f).sp)
    }
}
