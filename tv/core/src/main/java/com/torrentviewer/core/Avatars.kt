package com.torrentviewer.core

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import org.json.JSONObject

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

@Composable
fun Avatar(profile: Profile, size: Dp, modifier: Modifier = Modifier) = AvatarTile(Avatars.of(profile), colorOf(profile.colour), size, modifier)
