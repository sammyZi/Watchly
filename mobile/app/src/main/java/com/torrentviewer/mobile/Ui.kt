@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.torrentviewer.mobile

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
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
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.keyframes
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.layout
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
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
import com.torrentviewer.core.accentGlow
import com.torrentviewer.core.glow
import com.torrentviewer.core.Lc
import com.torrentviewer.core.Motion
import com.torrentviewer.core.Note
import com.torrentviewer.core.ImdbMark
import com.torrentviewer.core.ProgressLine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import com.torrentviewer.core.Spinner
import com.torrentviewer.core.Title
import com.torrentviewer.core.Type
import com.composables.icons.lucide.R as L
import dev.chrisbanes.haze.hazeEffect

// The TV's look (Ink, Type, artwork, title marks — all from :core) on touch: what the
// remote's focus ring did is a press that dips the control a little instead.

/** Side margin of every page. */
val EDGE = 16.dp

/** Landscape, or a tablet held either way: the TV's side-by-side layouts fit. */
@Composable
fun isWide(): Boolean = LocalConfiguration.current.let { it.screenWidthDp > it.screenHeightDp }

/**
 * Anything tappable: shrinks while pressed and springs back with a little overshoot on release, no
 * ripple. [propagateMinConstraints] so a caller's fillMaxWidth reaches the content and a bare one wraps it.
 * Calibration knobs: 0.9 is how far it shrinks (0.95 subtler); damping 0.45 the bounce (1 none); stiffness the speed.
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
    val s by animateFloatAsState(if (pressed) PRESSED_SCALE else 1f, spring(dampingRatio = 0.45f, stiffness = 500f), label = "press")
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

/** How far a [Tap] shrinks while pressed: 0.95 is subtler, 0.85 more. */
private const val PRESSED_SCALE = 0.9f

/** The TV's buttons, on touch: red for the main action, translucent grey for the rest. */
@Composable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    primary: Boolean = false,
    /** A toggle (My List): switched on, the icon gets [Action]'s disc and pop. */
    on: Boolean? = null,
    onColor: Color = Ink.good,
    /** The word's colour, and the flat fill's tint: for a button on something other than the page. */
    tint: Color = Ink.text,
    /** Beside a line of text in a row, where the full height towers over it. */
    compact: Boolean = false,
) {
    val filled = on == true
    val disc by animateFloatAsState(if (filled) 1f else 0f, if (filled) spring(dampingRatio = 0.55f, stiffness = 420f) else tween(180), label = "disc")
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        if (on == null || on == seen) return@LaunchedEffect
        seen = on
        pop.animateTo(1f, keyframes { durationMillis = 360; 0.72f at 100; 1.15f at 230; 1f at 360 })
    }
    Tap(onClick, modifier, RoundedCornerShape(8.dp)) { pressed ->
        val bg by animateColorAsState(
            when {
                primary -> if (pressed) Ink.accent.copy(alpha = 0.8f) else Ink.accent
                // A tint of the text colour: grey on either page.
                else -> tint.copy(alpha = if (pressed) 0.22f else 0.12f)
            },
            Motion.effects(), label = "bg",
        )
        val fg = if (primary) Ink.onAccent else tint
        Row(
            // The primary one — Play, Watch — a grainy red glow, a shade deeper while pressed; the rest a flat tint.
            (if (primary) Modifier.glow(accentGlow(), 0.85f, 1.1f).background(Color.Black.copy(alpha = if (pressed) 0.18f else 0f)) else Modifier.background(bg))
                .height(if (compact) 34.dp else 46.dp).padding(horizontal = if (compact) 14.dp else 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (icon != null) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    if (on != null) Box(
                        Modifier.size(24.dp).graphicsLayer {
                            scaleX = disc
                            scaleY = disc
                            alpha = disc.coerceIn(0f, 1f)
                        }.clip(CircleShape).background(onColor),
                    )
                    Lc(icon, Modifier.graphicsLayer {
                        scaleX = pop.value
                        scaleY = pop.value
                    }, tint = lerp(fg, Color.White, disc.coerceIn(0f, 1f)), size = if (on != null) 16.dp else 18.dp)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(label, style = Type.label.copy(fontWeight = FontWeight.SemiBold, color = fg), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A pill of choices — seasons, qualities, genres, sorts. */
@Composable
fun Chip(label: String, on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, @DrawableRes trailing: Int? = null, compact: Boolean = false, trailingTint: Color? = null) {
    // The icon last shown, so a tick switched off can shrink away rather than vanish.
    val lastIcon = remember { arrayOf(icon) }
    if (icon != null) lastIcon[0] = icon
    Tap(onClick, modifier, CircleShape) { pressed ->
        // Picked or not, the colours cross-fade, as a toggle pill does in Poetica.
        val bg by animateColorAsState(
            when {
                on -> Ink.accent
                pressed -> Ink.text.copy(alpha = 0.18f)
                else -> Ink.text.copy(alpha = 0.08f)
            },
            tween(350), label = "chipBg",
        )
        val fg by animateColorAsState(if (on) Ink.onAccent else Ink.muted, tween(350), label = "chipFg")
        Row(
            Modifier.background(bg).height(if (compact) 32.dp else 36.dp).animateContentSize(spring(dampingRatio = 0.8f, stiffness = 500f))
                .padding(horizontal = if (compact) 11.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // A tick coming on grows in and widens the pill smoothly; going off it shrinks back out.
            androidx.compose.animation.AnimatedVisibility(
                icon != null,
                enter = androidx.compose.animation.scaleIn() + androidx.compose.animation.fadeIn() + androidx.compose.animation.expandHorizontally(),
                exit = androidx.compose.animation.scaleOut() + androidx.compose.animation.fadeOut() + androidx.compose.animation.shrinkHorizontally(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    lastIcon[0]?.let { Lc(it, tint = fg, size = 16.dp) }
                    Spacer(Modifier.width(8.dp))
                }
            }
            Text(label, style = Type.label.copy(fontSize = if (compact) 13.sp else 14.sp, color = fg, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
            if (trailing != null) {
                Spacer(Modifier.width(if (compact) 3.dp else 6.dp))
                Lc(trailing, tint = trailingTint ?: fg, size = if (compact) 14.dp else 16.dp)
            }
        }
    }
}

/** A pill naming the current choice; a tap drops the [options] under it. */
@Composable
fun Dropdown(label: String, options: List<String>, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Chip(label, false, { open = true }, trailing = L.drawable.lucide_ic_chevron_down, compact = true)
        // Capped: the genre list is long, and an uncapped menu ran off the bottom of the screen.
        androidx.compose.material3.DropdownMenu(open, { open = false }, Modifier.heightIn(max = 320.dp), containerColor = Ink.surface) {
            options.forEach { option ->
                val here = option == label
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(option, style = Type.label.copy(fontSize = 14.sp, color = if (here) Ink.accent else Ink.text, fontWeight = if (here) FontWeight.SemiBold else FontWeight.Medium)) },
                    onClick = {
                        open = false
                        onPick(option)
                    },
                )
            }
        }
    }
}

/** Netflix's phone actions under the Play button: an icon over a word. */
@Composable
fun Action(
    @DrawableRes icon: Int,
    label: String,
    onClick: () -> Unit,
    tint: Color = Ink.text,
    modifier: Modifier = Modifier.width(84.dp),
    /** A toggle (My List, Like): switched on it fills with [onColor] — see below. */
    on: Boolean? = null,
    onColor: Color = Ink.good,
) {
    // Switched on: a disc of onColor springs in behind the icon, which dips and pops back
    // white. Switched off, the disc shrinks away. Every value starts where the last frame
    // left it — nothing snaps, so the first frame after a tap matches the one before.
    val filled = on == true
    val disc by animateFloatAsState(if (filled) 1f else 0f, if (filled) spring(dampingRatio = 0.55f, stiffness = 420f) else tween(180), label = "disc")
    val pop = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        // A title loading in sets the state too; only the viewer's own tap pops.
        if (on == null || on == seen) return@LaunchedEffect
        seen = on
        pop.animateTo(1f, keyframes { durationMillis = 360; 0.72f at 100; 1.15f at 230; 1f at 360 })
    }
    Tap(onClick, modifier) {
        Column(Modifier.padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(36.dp).graphicsLayer {
                        scaleX = disc
                        scaleY = disc
                        alpha = disc.coerceIn(0f, 1f)
                    }.clip(CircleShape).background(onColor),
                )
                Lc(icon, Modifier.graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                }, tint = lerp(tint, Color.White, disc.coerceIn(0f, 1f)), size = 22.dp)
            }
            Text(label, Modifier.padding(top = 4.dp), style = Type.meta.copy(fontSize = 12.sp, color = if (filled) onColor else Ink.muted, fontWeight = if (filled) FontWeight.SemiBold else FontWeight.Medium), maxLines = 1)
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
    /** The keyboard's action key; search unless the box says otherwise. Done also puts the keyboard away. */
    action: ImeAction = if (digits) ImeAction.Done else ImeAction.Search,
    /** The words' colour, and the fill's: white on the tour, which is dark whatever the theme. */
    tint: Color = Ink.text,
    keyboard: KeyboardType = if (digits) KeyboardType.NumberPassword else KeyboardType.Text,
    /** The keyboard opens in capitals and suggests nothing: a code, as the party's. */
    capitals: Boolean = false,
) {
    var focused by remember { mutableStateOf(false) }
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    val ring by animateColorAsState(if (focused) tint.copy(alpha = 0.7f) else Color.Transparent, Motion.effects(), label = "ring")
    Row(
        modifier.height(50.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = if (focused) 0.12f else 0.07f)).border(1.5.dp, ring, RoundedCornerShape(8.dp)).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let {
            Lc(it, tint = if (focused) tint else Ink.faint, size = 20.dp)
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
                textStyle = Type.label.copy(color = tint, fontSize = 16.sp, letterSpacing = if (secret) 8.sp else 0.sp, textAlign = align),
                cursorBrush = SolidColor(Ink.accent),
                visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = if (capitals) KeyboardOptions(androidx.compose.ui.text.input.KeyboardCapitalization.Characters, autoCorrectEnabled = false, keyboardType = keyboard, imeAction = action)
                else KeyboardOptions(keyboardType = keyboard, imeAction = action),
                keyboardActions = KeyboardActions(onAny = {
                    onSubmit()
                    if (action == ImeAction.Done) focus.clearFocus()
                }),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        trailing()
    }
}

/** [image]: a picture to lead the row with — a site's logo — in place of the tick column. [tag]: a badge before the label, "4K". */
data class Option<T>(val value: T, val label: String, val hint: String? = null, val image: String? = null, val tag: String? = null)

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
                            if (option.image != null) {
                                coil3.compose.AsyncImage(option.image, option.label, Modifier.size(24.dp).clip(RoundedCornerShape(5.dp)))
                            } else {
                                Box(Modifier.size(22.dp)) { if (on) Lc(L.drawable.lucide_ic_check, tint = Ink.accent, size = 20.dp) }
                            }
                            Spacer(Modifier.width(12.dp))
                            option.tag?.let {
                                Text(
                                    it,
                                    Modifier.background(Ink.text.copy(alpha = 0.1f), RoundedCornerShape(4.dp)).padding(horizontal = 7.dp, vertical = 3.dp),
                                    style = Type.overline.copy(color = Ink.text, letterSpacing = 0.5.sp),
                                )
                                Spacer(Modifier.width(12.dp))
                            }
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

/** The page a sheet over it may frost: the blurred source behind it, where the page provides one. */
val LocalPageHaze = androidx.compose.runtime.staticCompositionLocalOf<dev.chrisbanes.haze.HazeState?> { null }

/** Something has scrolled under the page's top edge: its frost comes on. */
@Composable
fun androidx.compose.foundation.lazy.grid.LazyGridState.scrolled(): Boolean =
    remember(this) { androidx.compose.runtime.derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 24 } }.value

@Composable
fun androidx.compose.foundation.lazy.LazyListState.scrolled(): Boolean =
    remember(this) { androidx.compose.runtime.derivedStateOf { firstVisibleItemIndex > 0 || firstVisibleItemScrollOffset > 24 } }.value

@Composable
fun androidx.compose.foundation.ScrollState.scrolled(): Boolean = remember(this) { androidx.compose.runtime.derivedStateOf { value > 24 } }.value

/**
 * A page's top edge frosted as Details' bar, once [scrolled]: what passes under it blurred and washed with the
 * page's colour, the whole of it fading out a little past its own foot, so there's no strip and no line.
 */
@Composable
fun FrostTop(haze: dev.chrisbanes.haze.HazeState, scrolled: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val frost by animateFloatAsState(if (scrolled) 1f else 0f, tween(260), label = "frost")
    val page = Ink.bg
    Box(modifier.fillMaxWidth()) {
        if (frost > 0f) Box(
            Modifier.matchParentSize()
                .layout { measurable, constraints ->
                    // Drawn taller than the bar by the tail; the bar's own size is unchanged.
                    val h = constraints.maxHeight + 16.dp.roundToPx()
                    val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    layout(constraints.maxWidth, constraints.maxHeight) { p.place(0, 0) }
                }
                .hazeEffect(haze) {
                    alpha = frost
                    backgroundColor = page
                    tints = listOf(dev.chrisbanes.haze.HazeTint(page.copy(alpha = 0.55f)))
                    blurRadius = 24.dp
                    noiseFactor = 0f
                    // The whole effect fades over the tail, its fill too: fading only the blur left a line.
                    mask = Brush.verticalGradient(0f to Color.Black, 0.7f to Color.Black, 1f to Color.Transparent)
                },
        )
        content()
    }
}

/** A page's back, top-left as [BackButton]; scrolled, on frost with the page's name beside it, as Details does. */
@Composable
fun BoxScope.FrostBackBar(haze: dev.chrisbanes.haze.HazeState, scrolled: Boolean, title: String, onBack: () -> Unit) {
    FrostTop(haze, scrolled, Modifier.align(Alignment.TopStart)) {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundIcon(L.drawable.lucide_ic_arrow_left, onBack)
            AnimatedVisibility(scrolled, enter = fadeIn(), exit = androidx.compose.animation.fadeOut()) {
                Text(title, Modifier.padding(start = 12.dp), style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A floating card's glass: the page behind it blurred under [tint], or the tint alone where nothing offers a page. */
fun Modifier.glass(haze: dev.chrisbanes.haze.HazeState?, tint: Color, alpha: Float = 0.72f): Modifier =
    if (haze == null) background(tint)
    else hazeEffect(haze) {
        backgroundColor = tint
        tints = listOf(dev.chrisbanes.haze.HazeTint(tint.copy(alpha = alpha)))
        blurRadius = 24.dp
        noiseFactor = 0f
    }

/** The frame every sheet shares: scrim, rounded top, a grab handle and a heading — frosted over a page that offers it. */
@Composable
fun BottomSheet(title: String?, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    val haze = LocalPageHaze.current
    val surface = Ink.surface
    val tabBar = LocalTabBarInset.current
    // The keyboard, while it is up: a sheet with a box to type in (Home's party code) sat under it, the box hidden.
    val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
    BackHandler(onBack = onDismiss)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * 0.8f).dp
    // Never shorter than it has been while open: picking 4K, with one release, dropped the sheet to that one
    // row and the pills jumped down under the finger. It grows for more, and keeps its height for fewer.
    var tallest by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val tallestDp = with(LocalDensity.current) { tallest.toDp() }

    // Pulled down — by the handle, or by a list already at its top — it follows the finger,
    // and let go far enough down it closes; short of that it springs back.
    val scope = rememberCoroutineScope()
    var offset by remember { mutableFloatStateOf(0f) }
    val closeAt = with(LocalDensity.current) { 110.dp.toPx() }
    fun settle() {
        if (offset > closeAt) onDismiss() else scope.launch { animate(offset, 0f) { v, _ -> offset = v } }
    }
    val pull = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y >= 0 || offset <= 0f) return Offset.Zero
                val used = maxOf(available.y, -offset)
                offset += used
                return Offset(0f, used)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (available.y <= 0 || source != NestedScrollSource.UserInput) return Offset.Zero
                offset += available.y
                return Offset(0f, available.y)
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset <= 0f) return Velocity.Zero
                settle()
                return available
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        AnimatedVisibility(shown, enter = slideInVertically(Motion.spatial()) { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
            Column(
                Modifier.widthIn(max = 600.dp).fillMaxWidth().heightIn(min = tallestDp.coerceAtMost(maxHeight), max = maxHeight)
                    // Not while the keyboard is up: its room counted, and the sheet stayed keyboard-tall once it went.
                    .onSizeChanged { if (keyboard == 0.dp && it.height > tallest) tallest = it.height }
                    .graphicsLayer { translationY = offset }
                    .nestedScroll(pull)
                    .clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp))
                    .then(
                        if (haze == null) Modifier.background(surface)
                        else Modifier.hazeEffect(haze) {
                            backgroundColor = surface
                            tints = listOf(dev.chrisbanes.haze.HazeTint(surface.copy(alpha = 0.72f)))
                            blurRadius = 24.dp
                            noiseFactor = if (Ink.pureBlack) 0f else 0.03f
                        },
                    )
                    // Taps on the sheet stay on the sheet.
                    .clickable(remember { MutableInteractionSource() }, indication = null) {}
                    // Over a tab's page the tab bar is drawn on top of the sheet: its last options ended up under the
                    // bar (Search's "Series only"). The bar's height includes the navigation bar's.
                    // Up, the keyboard covers both, so it is what the sheet stands on.
                    .then(
                        when {
                            keyboard > 0.dp -> Modifier.padding(bottom = keyboard)
                            tabBar > 0.dp -> Modifier.padding(bottom = tabBar)
                            else -> Modifier.navigationBarsPadding()
                        },
                    ).padding(bottom = 10.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().draggable(
                        rememberDraggableState { d -> offset = (offset + d).coerceAtLeast(0f) },
                        Orientation.Vertical,
                        onDragStopped = { settle() },
                    ),
                ) {
                    Box(Modifier.align(Alignment.CenterHorizontally).padding(top = 10.dp).size(36.dp, 4.dp).clip(CircleShape).background(Ink.stroke))
                    // Null: the sheet draws its own heading (Home's party code, centred).
                    if (title != null) Text(title, Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp), style = Type.title.copy(fontWeight = FontWeight.Bold), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    else Spacer(Modifier.height(14.dp))
                }
                content()
            }
        }
    }
}

/**
 * Fades artwork into the page at the bottom, and darkens the top for the status bar.
 * The art itself fades out, eased, rather than a flat page colour being painted over it:
 * that colour has none of the page's grain, and showed as a band where the hero ended.
 */
fun Modifier.bottomScrim(from: Float = 0.45f): Modifier = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
    drawContent()
    val top = size.height * 0.28f
    drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.5f to Color.Black.copy(alpha = 0.3f), 1f to Color.Transparent, endY = top), size = Size(size.width, top))
    val start = size.height * from
    drawRect(
        Brush.verticalGradient(
            0f to Color.Black, 0.3f to Color.Black.copy(alpha = 0.85f), 0.55f to Color.Black.copy(alpha = 0.5f),
            0.8f to Color.Black.copy(alpha = 0.15f), 1f to Color.Transparent,
            startY = start, endY = size.height,
        ),
        topLeft = Offset(0f, start),
        size = Size(size.width, size.height - start),
        blendMode = BlendMode.DstIn,
    )
}

/**
 * Status bar icons light while dark artwork sits under them — the light page's dark icons
 * were lost on the hero, and some phones washed a pale scrim over it for contrast — and the
 * page's own again once the art has scrolled away or the screen goes.
 */
@Composable
fun StatusIconsOverArt(overArt: Boolean, navigationToo: Boolean = false) {
    val view = LocalView.current
    val dark = Ink.isDark
    LaunchedEffect(overArt, dark) {
        val window = (view.context as? Activity)?.window ?: return@LaunchedEffect
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isStatusBarContrastEnforced = false
        val bars = WindowCompat.getInsetsController(window, view)
        bars.isAppearanceLightStatusBars = !dark && !overArt
        if (navigationToo) bars.isAppearanceLightNavigationBars = !dark && !overArt
    }
    DisposableEffect(view) {
        onDispose {
            val window = (view.context as? Activity)?.window ?: return@onDispose
            val bars = WindowCompat.getInsetsController(window, view)
            bars.isAppearanceLightStatusBars = !Ink.isDark
            if (navigationToo) bars.isAppearanceLightNavigationBars = !Ink.isDark
        }
    }
}

private val CARD = RoundedCornerShape(10.dp)

/**
 * A poster with its name and a line of facts under it, as the streaming apps set them:
 * a hairline so a pale poster keeps its edge on the light page, a [badge] in the corner,
 * and how far it was watched along the foot.
 */
@Composable
fun TitleCard(
    art: String?, name: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    info: String? = null, badge: String? = null, progress: Float? = null, onLongClick: (() -> Unit)? = null, rating: String? = null,
    /** The hairline round the poster; Home's rows go without it. */
    outline: Boolean = true,
) {
    Column(modifier) {
        Tap(onClick, Modifier.fillMaxWidth().aspectRatio(2f / 3f), CARD, onLongClick) {
            Artwork(art, name, Modifier.fillMaxSize())
            if (outline) Box(Modifier.matchParentSize().border(1.dp, Ink.stroke, CARD))
            rating?.takeIf { it.isNotBlank() }?.let { ImdbMark(it, Modifier.align(Alignment.BottomStart).padding(6.dp)) }
            badge?.let {
                Text(
                    it, Modifier.align(Alignment.TopStart).padding(6.dp).wrapContentSize(Alignment.TopStart).background(Ink.accent, RoundedCornerShape(5.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                    style = Type.meta.copy(fontSize = 10.sp, color = Ink.onAccent, fontWeight = FontWeight.Bold), maxLines = 1,
                )
            }
            progress?.let { ProgressLine(it, Modifier.align(Alignment.BottomStart)) }
        }
        Text(name, Modifier.padding(top = 7.dp), style = Type.label.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        // Always a line, so cards with no facts keep the row's height.
        Text(info.orEmpty(), Modifier.padding(top = 1.dp), style = Type.meta.copy(color = Ink.faint, fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Posters, for search results, lists and whole shelves. */
@Composable
fun PosterCard(title: Title, onClick: () -> Unit, modifier: Modifier = Modifier, sub: String? = null, onLongClick: (() -> Unit)? = null) =
    TitleCard(title.poster ?: title.wide, title.title, onClick, modifier, info = sub, onLongClick = onLongClick, rating = title.rating)

/** Cards still on their way: the same shape, gently pulsing. */
@Composable
fun CardPlaceholder(modifier: Modifier = Modifier) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(0.45f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
    Column(modifier.graphicsLayer { alpha = pulse }) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(Ink.surface, CARD))
        Box(Modifier.padding(top = 9.dp).fillMaxWidth(0.8f).height(11.dp).background(Ink.surface, RoundedCornerShape(4.dp)))
        Box(Modifier.padding(top = 6.dp).fillMaxWidth(0.5f).height(9.dp).background(Ink.surface, RoundedCornerShape(4.dp)))
    }
}

/** Continue watching's art: the time left and how far over a fade at its foot, no outline, as Home's posters; the name sits under the card. */
@Composable
fun LandscapeCard(art: String?, name: String, stamp: String? = null, progress: Float? = null) {
    Box(Modifier.fillMaxSize()) {
        Artwork(art, name, Modifier.fillMaxSize())
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().fillMaxHeight(0.6f).background(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = if (progress != null) 6.dp else 7.dp)) {
            // Over the artwork's dark fade: white on either page.
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
