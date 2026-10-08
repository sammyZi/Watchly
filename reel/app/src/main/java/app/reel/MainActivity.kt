package app.reel

import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

sealed interface Screen {
    val depth: Int
    val key: String

    data object Tabs : Screen {
        override val depth = 0
        override val key = "tabs"
    }

    data class Details(val opened: Opened) : Screen {
        override val depth = 1
        override val key = "details-${opened.source}"
    }

    data class Player(val opened: Opened, val torrent: Torrent, val file: TorrentFile, val startAt: Long) : Screen {
        override val depth = 2
        override val key = "player-${opened.source}-${file.index}"
    }
}

/** What a screen needs to take part in shared-element transitions. */
@OptIn(ExperimentalSharedTransitionApi::class)
class Shared(val scope: SharedTransitionScope, val visibility: AnimatedVisibilityScope)

/** Artwork that flies between screens: the same [key] on both ends. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.shared(key: String, shared: Shared): Modifier = with(shared.scope) {
    this@shared.sharedElement(rememberSharedContentState(key), shared.visibility)
}

class MainActivity : ComponentActivity() {
    /** A magnet or .torrent handed to us by another app. */
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Engine.start(applicationContext)
        Library.init(applicationContext)
        incoming.value = intent?.data
        setContent {
            val palette = paletteFor(Library.theme)
            // Status- and navigation-bar icons the opposite of the page: light on dark, dark on light.
            LaunchedEffect(palette.dark) {
                val style = if (palette.dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(style, style)
            }
            ReelTheme(palette) { App(incoming) }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        incoming.value = intent.data
    }

    override fun onStop() {
        super.onStop()
        Engine.saveState()
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun App(incoming: MutableState<Uri?>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val stack = remember { mutableStateListOf<Screen>(Screen.Tabs) }
    // Kept out here so coming back from a title lands on the same tab, same scroll.
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val homeList = rememberLazyListState()
    val libraryGrid = rememberLazyGridState()
    var addError by remember { mutableStateOf<String?>(null) }

    fun open(source: String, hint: String?) {
        addError = null
        stack.add(Screen.Details(Opened(source, hint)))
    }
    fun openFile(uri: Uri) = scope.launch {
        runCatching { Engine.import(context, uri) }
            .onSuccess { open(it, null) }
            .onFailure { addError = it.message ?: "Couldn't read that file." }
    }
    fun pop() {
        val leaving = stack.removeAt(stack.lastIndex)
        if (leaving is Screen.Details) leaving.opened.close()
    }
    BackHandler(enabled = stack.size > 1) { pop() }

    // Another app handed us a torrent: back out to the tabs, then open it.
    LaunchedEffect(incoming.value) {
        val uri = incoming.value ?: return@LaunchedEffect
        incoming.value = null
        while (stack.size > 1) pop()
        if (uri.scheme.equals("magnet", true)) open(uri.toString(), magnetName(uri.toString())) else openFile(uri)
    }

    SharedTransitionLayout(Modifier.fillMaxSize().background(Ink.bg)) {
        AnimatedContent(
            targetState = stack.last(),
            contentKey = { it.key },
            transitionSpec = {
                val forward = targetState.depth > initialState.depth
                if (targetState is Screen.Player || initialState is Screen.Player) {
                    val (from, to) = if (forward) 0.94f to 1.04f else 1.04f to 0.94f
                    (fadeIn(Motion.effects()) + scaleIn(Motion.spatial(), initialScale = from)) togetherWith
                        (fadeOut(Motion.effects()) + scaleOut(Motion.spatial(), targetScale = to))
                } else {
                    val dir = if (forward) 1 else -1
                    (slideInHorizontally(Motion.spatial()) { it * dir / 4 } + fadeIn(Motion.effects())) togetherWith
                        (slideOutHorizontally(Motion.spatial()) { -it * dir / 4 } + fadeOut(Motion.effects()))
                }
            },
            label = "screens",
        ) { screen ->
            val shared = Shared(this@SharedTransitionLayout, this@AnimatedContent)
            when (screen) {
                Screen.Tabs -> TabsScreen(
                    tab = tab, onTab = { tab = it }, shared = shared,
                    homeList = homeList, libraryGrid = libraryGrid,
                    addError = addError, onAddError = { addError = it },
                    open = ::open, openFile = { openFile(it) },
                )
                is Screen.Details -> DetailsScreen(
                    screen.opened, shared, back = ::pop,
                    play = { torrent, file, startAt -> stack.add(Screen.Player(screen.opened, torrent, file, startAt)) },
                )
                // Films are watched in the dark, whatever the app's theme.
                is Screen.Player -> ReelTheme(DarkInk) { PlayerScreen(screen, shared, back = ::pop) }
            }
        }
    }
}
