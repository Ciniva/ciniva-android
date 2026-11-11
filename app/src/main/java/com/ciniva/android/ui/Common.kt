package com.ciniva.android.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import com.ciniva.android.AuthException
import com.ciniva.android.MovieCard
import com.ciniva.android.ShowCard
import com.ciniva.android.Api

// ---- Theme (the web UI's palette) -----------------------------------------------------------------------------

val Bg = Color(0xFF0B0D12)
val Surface = Color(0xFF181C25)
val Line = Color(0xFF2A3040)
val TextColor = Color(0xFFEEF0F4)
val Muted = Color(0xFF9AA2B4)
val Accent = Color(0xFFF5B84A)
val AccentInk = Color(0xFF1A1306)
val Danger = Color(0xFFFF6B6B)

@Composable
fun CinivaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent, onPrimary = AccentInk,
            secondary = Accent, onSecondary = AccentInk,
            secondaryContainer = Accent.copy(alpha = 0.25f), onSecondaryContainer = TextColor,
            background = Bg, onBackground = TextColor,
            surface = Bg, onSurface = TextColor,
            surfaceVariant = Surface, onSurfaceVariant = Muted,
            surfaceContainer = Surface, surfaceContainerHigh = Surface, surfaceContainerLow = Bg,
            outline = Line, error = Danger,
        ),
        content = content,
    )
}

/** True on Android TV: remote-driven layout (side rail, bigger cards, focus rings). */
val LocalIsTv = staticCompositionLocalOf { false }

/** Called when the server says the session is gone (401). */
val LocalSignOut = staticCompositionLocalOf<() -> Unit> { {} }

class Nav(val openMovie: (Long) -> Unit, val openShow: (Long) -> Unit)

@Composable
fun gutter(): Dp = if (LocalIsTv.current) 48.dp else 16.dp

@Composable
fun posterWidth(): Dp = if (LocalIsTv.current) 150.dp else 112.dp

/** A visible ring and a slight lift on the focused item, so the remote's position is obvious from the sofa. */
fun Modifier.focusRing(shape: Shape = RoundedCornerShape(10.dp)): Modifier = composed {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.05f else 1f, label = "focus")
    this
        .onFocusChanged { focused = it.isFocused || it.hasFocus }
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .border(if (focused) 3.dp else 0.dp, if (focused) Accent else Color.Transparent, shape)
}

fun clock(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = s / 60 % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s % 60) else "%d:%02d".format(m, s % 60)
}

fun runtimeText(minutes: Int?): String? = minutes?.takeIf { it > 0 }?.let { if (it >= 60) "${it / 60}h ${it % 60}m" else "${it}m" }

// ---- Loading ----------------------------------------------------------------------------------------------

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Ok<T>(val value: T) : Load<T>
    data class Failed(val message: String) : Load<Nothing>
}

class Loaded<T>(val state: Load<T>, val reload: () -> Unit)

/** Runs [fetch] when [keys] change and again whenever the screen comes back (e.g. after the player closes). */
@Composable
fun <T> load(vararg keys: Any?, fetch: suspend () -> T): Loaded<T> {
    val signOut = LocalSignOut.current
    var generation by remember { mutableIntStateOf(0) }
    var resumedOnce by remember { mutableStateOf(false) }
    var state by remember(*keys) { mutableStateOf<Load<T>>(Load.Loading) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (resumedOnce) generation++
        resumedOnce = true
    }
    LaunchedEffect(*keys, generation) {
        state = try {
            Load.Ok(fetch())
        } catch (e: AuthException) {
            signOut()
            Load.Failed(e.message ?: "Sign in required")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (state is Load.Ok) state else Load.Failed(e.message ?: "Something went wrong")
        }
    }
    return Loaded(state) { generation++ }
}

@Composable
fun <T> LoadView(loaded: Loaded<T>, modifier: Modifier = Modifier, content: @Composable (T) -> Unit) {
    when (val s = loaded.state) {
        is Load.Loading -> Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is Load.Failed -> Column(
            modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(s.message, color = Muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Button(onClick = loaded.reload, modifier = Modifier.focusRing(CircleShape)) { Text("Try again") }
        }
        is Load.Ok -> content(s.value)
    }
}

// ---- Cards ------------------------------------------------------------------------------------------------

/** A poster or still for the grids and rows; movies and shows both become one of these. */
data class Card(
    val key: String,
    val title: String,
    val subtitle: String?,
    val image: String?,
    val progress: Float? = null,
    val watched: Boolean = false,
    val open: () -> Unit,
)

fun MovieCard.toCard(nav: Nav) = Card(
    key = "m$id",
    title = title,
    subtitle = year?.toString(),
    image = Api.image(posterPath),
    progress = if (watched != true && (positionMs ?: 0) > 0 && (durationMs ?: 0) > 0) positionMs!!.toFloat() / durationMs!! else null,
    watched = watched == true,
    open = { nav.openMovie(id) },
)

fun ShowCard.toCard(nav: Nav) = Card(
    key = "s$id",
    title = title,
    subtitle = listOfNotNull(year?.toString(), "$episodes episodes".takeIf { episodes > 0 }).joinToString(" · "),
    image = Api.image(posterPath),
    watched = episodes > 0 && watchedEpisodes >= episodes,
    open = { nav.openShow(id) },
)

@Composable
fun PosterCard(card: Card, modifier: Modifier = Modifier.width(posterWidth()), aspect: Float = 2f / 3f) {
    val shape = RoundedCornerShape(10.dp)
    Column(modifier) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .focusRing(shape)
                .clip(shape)
                .background(Surface)
                .clickable(onClick = card.open),
        ) {
            if (card.image != null) {
                AsyncImage(model = card.image, contentDescription = card.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(card.title, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).padding(8.dp))
            }
            if (card.watched) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(22.dp).clip(CircleShape).background(Accent),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Default.Check, contentDescription = "Watched", tint = AccentInk, modifier = Modifier.size(16.dp)) }
            }
            card.progress?.let { p ->
                LinearProgressIndicator(
                    progress = { p.coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp),
                    color = Accent,
                    trackColor = Color.Black.copy(alpha = 0.5f),
                    drawStopIndicator = {},
                )
            }
        }
        Text(
            card.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(top = 6.dp),
        )
        card.subtitle?.takeIf { it.isNotEmpty() }?.let { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 12.sp, color = Muted) }
    }
}

/** A titled horizontal row of cards inside a LazyColumn. */
fun LazyListScope.cardRow(title: String, cards: List<Card>, wide: Boolean = false) {
    if (cards.isEmpty()) return
    item(key = "row-$title") {
        val tv = LocalIsTv.current
        Column(Modifier.padding(bottom = 20.dp)) {
            Text(title, fontSize = if (tv) 20.sp else 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = gutter(), bottom = 10.dp))
            LazyRow(contentPadding = PaddingValues(horizontal = gutter()), horizontalArrangement = Arrangement.spacedBy(if (tv) 18.dp else 12.dp)) {
                items(cards.size, key = { cards[it].key }) { i ->
                    if (wide) {
                        PosterCard(cards[i], Modifier.width(if (tv) 300.dp else 240.dp), aspect = 16f / 9f)
                    } else {
                        PosterCard(cards[i])
                    }
                }
            }
        }
    }
}
