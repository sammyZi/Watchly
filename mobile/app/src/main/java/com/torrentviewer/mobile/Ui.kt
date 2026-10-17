@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.torrentviewer.mobile

import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.torrentviewer.core.Artwork
import com.torrentviewer.core.Ink
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Motion
import com.torrentviewer.core.Note
import com.torrentviewer.core.ProgressLine
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.composables.icons.lucide.R as L

// The TV's look (Ink, Type, artwork, title marks — all from :core) on touch: what the
// remote's focus ring did is a press that dips the control a little instead.

/** Side margin of every page. */
val EDGE = 16.dp

/** Landscape, or a tablet held either way: the TV's side-by-side layouts fit. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

/**
 * Anything tappable: dips while pressed, no ripple. [propagateMinConstraints] so a
 * caller's fillMaxWidth reaches the content and a bare one wraps it.
 */
@Composable
fun Tap(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    onLongClick: (() -> Unit)? = null,
    content: @Composable BoxScope.(pressed: Boolean) -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "press")
    Box(
        modifier
            .graphicsLayer {
                scaleX = s
                scaleY = s
            }
            .clip(shape)
            .combinedClickable(source, indication = null, onLongClick = onLongClick, onClick = onClick),
        propagateMinConstraints = true,
    ) { content(pressed) }
}

/** The TV's buttons, on touch: red for the main action, translucent grey for the rest. */
@Composable
fun Button(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, primary: Boolean = false) {
    Tap(onClick, modifier, RoundedCornerShape(8.dp)) { pressed ->
        val bg by animateColorAsState(
            when {
                primary -> if (pressed) Ink.accent.copy(alpha = 0.8f) else Ink.accent
                // A tint of the text colour: grey on either page.
                else -> Ink.text.copy(alpha = if (pressed) 0.22f else 0.12f)
            },
            Motion.effects(), label = "bg",
        )
        val fg = if (primary) Color.White else Ink.text
        Row(
            Modifier.background(bg).height(46.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Lc(icon, tint = fg, size = 18.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(label, style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A pill of choices — seasons, qualities, genres, sorts. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null) {
    Tap(onClick, modifier, CircleShape) { pressed ->
        val bg = when {
            on -> Ink.accent
            pressed -> Ink.text.copy(alpha = 0.18f)
            else -> Ink.text.copy(alpha = 0.08f)
        }
        val fg = if (on) Color.White else Ink.muted
        Row(Modifier.background(bg).height(36.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Lc(icon, tint = fg, size = 16.dp)
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = Type.label.copy(fontSize = 14.sp, color = fg, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
        }
    }
}

/** Netflix's phone actions under the Play button: an icon over a word. */
@Composable
fun Action(@DrawableRes icon: Int, label: String, onClick: () -> Unit, tint: Color = Ink.text, modifier: Modifier = Modifier.width(84.dp)) {
    Tap(onClick, modifier) {
        Column(Modifier.padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Lc(icon, tint = tint, size = 24.dp)
            Text(label, Modifier.padding(top = 6.dp), style = Type.meta.copy(fontSize = 12.sp), maxLines = 1)
        }
    }
}

/** A round icon button floating over artwork — back, close. */
@Composable
fun RoundIcon(@DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Tap(onClick, modifier.size(size), CircleShape) {
        Box(Modifier.background(Color.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) { Lc(icon, tint = Color.White, size = size * 0.5f) }
    }
}

/** Back, top-left under the status bar. */
@Composable
fun BoxScope.BackButton(onBack: () -> Unit) {
    RoundIcon(L.drawable.lucide_ic_arrow_left, onBack, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 8.dp, top = 6.dp))
}

/** A text box; the ring shows where the keyboard is typing. */
@Composable
fun Field(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    secret: Boolean = false,
    digits: Boolean = false,
    onSubmit: () -> Unit = {},
    leading: Int? = null,
    trailing: @Composable () -> Unit = {},
) {
    var focused by remember { mutableStateOf(false) }
    val ring by animateColorAsState(if (focused) Ink.text.copy(alpha = 0.7f) else Color.Transparent, Motion.effects(), label = "ring")
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(8.dp)).background(Ink.text.copy(alpha = if (focused) 0.12f else 0.07f)).border(1.5.dp, ring, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Lc(it, tint = if (focused) Ink.text else Ink.faint, size = 20.dp)
            Spacer(Modifier.width(12.dp))
        }
        // A PIN sits in the middle of its box, placeholder and dots alike.
        val align = if (digits) TextAlign.Center else TextAlign.Start
        Box(Modifier.weight(1f), contentAlignment = if (digits) Alignment.Center else Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = Type.label.copy(color = Ink.faint, fontSize = 16.sp, textAlign = align), maxLines = 1)
            BasicTextField(
                value = value,
                onValueChange = onValue,
                singleLine = true,
                textStyle = Type.label.copy(fontSize = 16.sp, letterSpacing = if (secret) 8.sp else 0.sp, textAlign = align),
                cursorBrush = SolidColor(Ink.accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = if (digits) KeyboardType.NumberPassword else KeyboardType.Text, imeAction = if (digits) ImeAction.Done else ImeAction.Search),
                keyboardActions = KeyboardActions(onAny = { onSubmit() }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        trailing()
    }
}

data class Option<T>(val value: T, val label: String, val hint: String? = null)

/** A choice, up from the bottom edge over a dimmed page; a tap outside or Back closes it. */
@Composable
fun <T> Sheet(
    title: String,
    options: List<Option<T>>,
    selected: T?,
    onPick: (T) -> Unit,
    onDismiss: () -> Unit,
    empty: String? = null,
    busy: Boolean = false,
) {
    BottomSheet(title, onDismiss) {
        when {
            busy && options.isEmpty() -> Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) { Spinner(size = 40.dp) }
            options.isEmpty() -> Note(empty ?: "Nothing to choose from.", Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            else -> LazyColumn {
                itemsIndexed(options) { _, option ->
                    val on = option.value == selected
                    Tap({ onPick(option.value) }, Modifier.fillMaxWidth(), RoundedCornerShape(0.dp)) { pressed ->
                        Row(
                            Modifier.background(if (pressed) Ink.text.copy(alpha = 0.08f) else Color.Transparent).padding(horizontal = 20.dp, vertical = 13.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(22.dp)) { if (on) Lc(L.drawable.lucide_ic_check, tint = Ink.accent, size = 20.dp) }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(option.label, style = Type.label.copy(color = if (on) Ink.text else Ink.muted, fontWeight = if (on) FontWeight.Bold else FontWeight.Medium), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                option.hint?.let { Text(it, style = Type.meta.copy(color = Ink.faint), maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    }
                }
                if (busy) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner(size = 28.dp) } }
            }
        }
    }
}

/** The frame every sheet shares: scrim, rounded top, a grab handle and a heading. */
@Composable
fun BottomSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler(onBack = onDismiss)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(shown, enter = slideInVertically(Motion.spatial()) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(max = maxHeight)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Ink.surface)
                    // Taps on the sheet stay on the sheet.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    .navigationBarsPadding().padding(bottom = 10.dp),
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(Ink.stroke))
                Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp), style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                content()
            }
        }
    }
}

/** Fades artwork into the page at the bottom, and darkens the top for the status bar. */
fun Modifier.bottomScrim(from: Float = 0.45f): Modifier = drawWithContent {
    drawContent()
    val top = size.height * 0.22f
    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.55f), 1f to Color.Transparent, endY = top), size = Size(size.width, top))
    val start = size.height * from
    drawRect(
        Brush.verticalGradient(0f to Color.Transparent, 1f to Ink.bg, startY = start, endY = size.height),
        topLeft = Offset(0f, start),
        size = Size(size.width, size.height - start),
    )
}

/** Posters, for search results, lists and whole shelves. */
@Composable
fun PosterCard(title: Title, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null, onLongClick: (() -> Unit)? = null) {
    Column(modifier) {
        Tap(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), RoundedCornerShape(6.dp), onLongClick) {
            Artwork(title.poster, title.title, Modifier.fillMaxSize())
        }
        Text(title.title, Modifier.padding(top = 6.dp), style = Type.meta.copy(color = Ink.text, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp), maxLines = 1) }
    }
}

/** 16:9 art with the name set over a fade, the way Netflix's boxart reads. */
@Composable
fun LandscapeCard(art: String?, name: String, stamp: String? = null, progress: Float? = null) {
    Box(Modifier.fillMaxSize()) {
        Artwork(art, name, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.6f).background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = if (progress != null) 6.dp else 7.dp)) {
            // Over the artwork's dark fade: white on either page.
            Text(name, style = Type.meta.copy(color = Color.White, fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            stamp?.let { Text(it, style = Type.meta.copy(fontSize = 11.sp, color = Color.White.copy(alpha = 0.75f)), maxLines = 1) }
            if (progress != null) {
                Spacer(Modifier.height(5.dp))
                ProgressLine(progress)
            }
        }
    }
}

/** A page heading under the status bar. */
@Composable
fun Heading(text: String, modifier: Modifier = Modifier, trailing: @Composable () -> Unit = {}) {
    Row(modifier.statusBarsPadding().padding(top = 12.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f), style = Type.display)
        trailing()
    }
}
