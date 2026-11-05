package com.torrentviewer.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import android.content.Context
import android.graphics.Bitmap
import coil3.SingletonImageLoader
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

private const val EMOJI = "emoji/"

/**
 * Profile icons from Microsoft's Fluent Emoji (MIT, assets/emoji/LICENSE). A profile's icon is one of
 * these, a drawn face's id, or the absolute path of a picture of its own.
 */
val EMOJI_ICONS = listOf(
    "fox", "cat", "dog", "panda", "bear", "koala", "tiger", "lion", "rabbit", "hamster", "frog", "monkey",
    "penguin", "owl", "unicorn", "dragon", "alien", "invader", "robot", "ghost", "cool", "starstruck", "popcorn", "clapper",
).map { EMOJI + it }

/**
 * A picture from the device or the web, as a small copy of our own: leave to read a picked file ends
 * with the app, and a link can die. Its path, or null if it wouldn't load.
 */
// ponytail: a replaced or deleted profile's copy stays behind, ~300 KB each; sweep unreferenced files if that ever adds up.
suspend fun savedPicture(context: Context, data: Any): String? {
    val request = ImageRequest.Builder(context).data(data).size(512).scale(Scale.FILL).allowHardware(false).build()
    val bitmap = (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
    return withContext(Dispatchers.IO) {
        val file = File(File(context.filesDir, "profile-icons").apply { mkdirs() }, "${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        file.path
    }
}

/** One drawn layer of an avatar, on its 100×100 tile. */
class AvatarLayer(val path: Path, val fill: Color?, val stroke: Color?, val width: Float, val alpha: Float, val dx: Float, val dy: Float)

class AvatarArt(val id: String, val name: String, val layers: List<AvatarLayer>)

/** The profile faces in avatars.json, which the Windows app draws from too. */
object Avatars {
    private const val GUEST_ART = "pebble"
    private val PUPIL = Color(0xFF1D1B2A)
    private val CHEEK = Color(0xFFFF8FB8)

    /** The icon names from before these were drawn: each keeps a likeness. */
    private val OLD = mapOf(
        "emoticon-happy" to "mochi", "emoticon-cool" to "flame", "emoticon-excited" to "popcorn", "emoticon-wink" to "bean",
        "emoticon-tongue" to "frog", "robot" to "jelly", "cat" to "cat", "dog" to "bear", "alien" to "ghost", "penguin" to "penguin",
    )

    val all: List<AvatarArt> by lazy {
        val text = Avatars::class.java.getResourceAsStream("/avatars.json")!!.bufferedReader().use { it.readText() }
        JSONObject(text).getJSONArray("avatars").objects().map { a ->
            val body = a.str("body")
            AvatarArt(a.getString("id"), a.getString("name"), a.getJSONArray("parts").objects().flatMap { layersOf(it, body) })
        }
    }

    /** What a profile can pick; the guest's own is not among them. */
    val pickable: List<AvatarArt> get() = all.filter { it.id != GUEST_ART }

    fun art(id: String?): AvatarArt = all.find { it.id == id } ?: pickable.first()

    /** A profile's face: its pick, the likeness of an old icon, or a steady one from its id for a profile that never picked. */
    fun of(profile: Profile): AvatarArt {
        if (profile.isGuest) return art(GUEST_ART)
        val id = profile.icon?.let { OLD[it] ?: it }
        return all.find { it.id == id && it.id != GUEST_ART } ?: pickable[(profile.id + profile.name).sumOf { it.code } % pickable.size]
    }

    private fun hex(s: String) = Color(android.graphics.Color.parseColor(s))

    private fun oval(x: Float, y: Float, rx: Float, ry: Float) = Path().apply { addOval(Rect(x - rx, y - ry, x + rx, y + ry)) }

    private fun layersOf(o: JSONObject, body: String?): List<AvatarLayer> {
        fun nums(key: String) = o.optJSONArray(key)?.let { a -> FloatArray(a.length()) { a.getDouble(it).toFloat() } }
        fun spot(x: Float, y: Float, rx: Float, ry: Float, c: Color, a: Float = 1f) = AvatarLayer(oval(x, y, rx, ry), c, null, 0f, a, 0f, 0f)
        nums("eye")?.let { (x, y, r) -> return listOf(spot(x, y, r, r, Color.White), spot(x + .1f * r, y + .15f * r, .56f * r, .56f * r, PUPIL), spot(x + .32f * r, y - .12f * r, .2f * r, .2f * r, Color.White)) }
        nums("dot")?.let { (x, y, r) -> return listOf(spot(x, y, r, r, PUPIL), spot(x + .35f * r, y - .35f * r, .36f * r, .36f * r, Color.White)) }
        nums("cheek")?.let { (x, y) -> return listOf(spot(x, y, 6f, 3.5f, CHEEK, .45f)) }
        nums("hi")?.let { (x, y, rx, ry) -> return listOf(spot(x, y, rx, ry, Color.White, .32f)) }
        val shape = nums("o")?.let { (x, y, r) -> oval(x, y, r, r) }
            ?: nums("e")?.let { (x, y, rx, ry) -> oval(x, y, rx, ry) }
            ?: PathParser().parsePathString(if (o.has("b")) body!! else o.getString("d")).toPath()
        val t = nums("t")
        return listOf(
            AvatarLayer(
                shape, o.str("f")?.let(::hex), o.str("s")?.let(::hex), o.optDouble("w", 0.0).toFloat(), o.optDouble("a", 1.0).toFloat(),
                t?.get(0) ?: 0f, t?.get(1) ?: 0f,
            ),
        )
    }
}

/** An avatar on its tile: the page's raised colour, washed with [colour]. */
@Composable
fun AvatarTile(art: AvatarArt, colour: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Ink.surface2).background(colour.copy(alpha = 0.28f))) {
        val k = this.size.width / 100f
        scale(k, k, pivot = Offset.Zero) {
            for (l in art.layers) translate(l.dx, l.dy) {
                l.fill?.let { drawPath(l.path, it, l.alpha) }
                l.stroke?.let { drawPath(l.path, it, l.alpha, Stroke(l.width, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            }
        }
    }
}

/** A picture on an avatar's tile: an emoji sits inside it, a photo fills it. */
@Composable
fun AvatarImage(icon: String, colour: Color, size: Dp, modifier: Modifier = Modifier) {
    val emoji = icon.startsWith(EMOJI)
    AsyncImage(
        if (emoji) "file:///android_asset/$icon.png" else File(icon), null,
        modifier.size(size).clip(RoundedCornerShape(size * 0.22f)).background(Ink.surface2).background(colour.copy(alpha = 0.28f)).padding(if (emoji) size * 0.12f else 0.dp),
        contentScale = if (emoji) ContentScale.Fit else ContentScale.Crop,
    )
}

@Composable
fun Avatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    val icon = profile.icon
    // A photo stays on the device it was picked on: restored from a backup elsewhere, the drawn face stands in.
    val image = remember(icon) { icon?.takeIf { !profile.isGuest && (it.startsWith(EMOJI) || it.startsWith("/") && File(it).exists()) } }
    if (image != null) AvatarImage(image, colorOf(profile.colour), size, modifier)
    else AvatarTile(Avatars.of(profile), colorOf(profile.colour), size, modifier)
}
