package com.ciniva.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.ciniva.android.Api
import com.ciniva.android.BuildConfig
import com.ciniva.android.Home
import com.ciniva.android.MovieCard
import com.ciniva.android.Playback
import com.ciniva.android.PlayerActivity
import com.ciniva.android.SearchResults
import com.ciniva.android.Session
import com.ciniva.android.ShowCard
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ---- Sign in ----------------------------------------------------------------------------------------------

@Composable
fun LoginScreen(onSignedIn: () -> Unit) {
    var server by rememberSaveable { mutableStateOf(Session.server) }
    var username by rememberSaveable { mutableStateOf(Session.username) }
    var password by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun submit() {
        if (busy) return
        val url = Api.normalizeServer(server)
        if (url.isEmpty() || username.isBlank()) {
            error = "Enter the server address and your username"
            return
        }
        busy = true
        error = null
        scope.launch {
            try {
                val r = Api.login(url, username.trim(), password)
                Session.set(url, r.token, r.user.username)
                onSignedIn()
            } catch (e: Exception) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Ciniva", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text("Sign in to your Ciniva server", color = Muted)
            OutlinedTextField(
                value = server, onValueChange = { server = it }, label = { Text("Server address") },
                placeholder = { Text("192.168.1.10:8484") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = username, onValueChange = { username = it }, label = { Text("Username") }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            OutlinedTextField(
                value = password, onValueChange = { password = it }, label = { Text("Password") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { submit() }),
            )
            error?.let { Text(it, color = Danger) }
            Button(onClick = ::submit, enabled = !busy, modifier = Modifier.fillMaxWidth().focusRing(CircleShape)) {
                if (busy) CircularProgressIndicator(Modifier.height(20.dp), strokeWidth = 2.dp) else Text("Sign in")
            }
        }
    }
}

// ---- Home -------------------------------------------------------------------------------------------------

@Composable
fun HomeScreen(nav: Nav, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val home = load { Api.get<Home>("/home") }
    LoadView(home) { h ->
        val continueCards = h.continueWatching.map { c ->
            Card(
                key = "c${c.fileId}",
                title = c.title,
                subtitle = if (c.nextUp) "Up next · ${c.subtitle ?: ""}".trimEnd(' ', '·') else c.subtitle ?: "${clock(c.durationMs - c.positionMs)} left",
                image = Api.image(c.imagePath, "w780") ?: Api.image(c.posterPath),
                progress = if (!c.nextUp && c.durationMs > 0) c.positionMs.toFloat() / c.durationMs else null,
                open = { PlayerActivity.start(context, c.fileId, c.title, c.subtitle, c.showId) },
            )
        }
        if (continueCards.isEmpty() && h.recentMovies.isEmpty() && h.recentShows.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Your library is empty", color = Muted) }
        } else {
            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 16.dp + contentPadding.calculateTopPadding(), bottom = 16.dp)) {
                cardRow("Continue watching", continueCards, wide = true)
                cardRow("Recently added movies", h.recentMovies.map { it.toCard(nav) })
                cardRow("Recently added shows", h.recentShows.map { it.toCard(nav) })
            }
        }
    }
}

// ---- Movies / Shows ---------------------------------------------------------------------------------------

private val SORTS = listOf("title" to "Title", "added" to "Recently added", "year" to "Year", "rating" to "Rating")

@Composable
fun LibraryScreen(movies: Boolean, nav: Nav, contentPadding: PaddingValues) {
    var sort by rememberSaveable(movies) { mutableStateOf(Session.pref(if (movies) "sort.movies" else "sort.shows", "title")) }
    val cards = load(movies, sort) {
        if (movies) Api.get<List<MovieCard>>("/movies?sort=$sort").map { it.toCard(nav) }
        else Api.get<List<ShowCard>>("/shows?sort=$sort").map { it.toCard(nav) }
    }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        LazyRow(
            contentPadding = PaddingValues(horizontal = gutter(), vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(SORTS.size) { i ->
                val (id, label) = SORTS[i]
                FilterChip(
                    selected = sort == id,
                    onClick = {
                        sort = id
                        Session.setPref(if (movies) "sort.movies" else "sort.shows", id)
                    },
                    label = { Text(label) },
                    modifier = Modifier.focusRing(),
                )
            }
        }
        LoadView(cards) { list -> CardGrid(list) }
    }
}

@Composable
fun CardGrid(cards: List<Card>, header: (@Composable () -> Unit)? = null) {
    val tv = LocalIsTv.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(posterWidth()),
        contentPadding = PaddingValues(start = gutter(), end = gutter(), bottom = 24.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(if (tv) 20.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (tv) 24.dp else 16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        header?.let { h -> item(span = { GridItemSpan(maxLineSpan) }) { h() } }
        items(cards.size, key = { cards[it].key }) { i -> PosterCard(cards[i], Modifier) }
    }
}

// ---- Search -----------------------------------------------------------------------------------------------

@Composable
fun SearchScreen(nav: Nav, contentPadding: PaddingValues) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Card>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.isEmpty()) {
            results = null
            return@LaunchedEffect
        }
        delay(300)
        try {
            val r = Api.get<SearchResults>("/search?q=" + java.net.URLEncoder.encode(q, "UTF-8"))
            results = r.movies.map { it.toCard(nav) } + r.shows.map { it.toCard(nav) }
            error = null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        }
    }
    Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true, label = { Text("Search movies and shows") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = gutter(), vertical = 12.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        )
        when {
            error != null -> Text(error!!, color = Danger, modifier = Modifier.padding(horizontal = gutter()))
            results?.isEmpty() == true -> Text("Nothing found", color = Muted, modifier = Modifier.padding(horizontal = gutter()))
            results != null -> CardGrid(results!!)
        }
    }
}

// ---- Settings ---------------------------------------------------------------------------------------------

@Composable
fun SettingsScreen(contentPadding: PaddingValues) {
    val context = LocalContext.current
    val signOut = LocalSignOut.current
    var quality by remember { mutableStateOf(Session.pref("quality", "auto")) }
    val device = remember { Playback(context, ContextCompat.getDisplayOrDefault(context)).summary() }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding())
            .padding(horizontal = gutter(), vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Setting("Server", Session.server)
        Setting("Signed in as", Session.username)
        Column {
            Text("Playback quality", fontWeight = FontWeight.SemiBold)
            Text("Original plays files as they are when this device can, and asks the server to convert them when it can't.", color = Muted, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(Playback.QUALITIES.size) { i ->
                    val q = Playback.QUALITIES[i]
                    FilterChip(
                        selected = quality == q.id,
                        onClick = {
                            quality = q.id
                            Session.setPref("quality", q.id)
                        },
                        label = { Text(q.label) },
                        modifier = Modifier.focusRing(),
                    )
                }
            }
        }
        Setting("This device plays", device)
        Setting("Version", "Ciniva for Android ${BuildConfig.VERSION_NAME}")
        Row {
            OutlinedButton(onClick = signOut, modifier = Modifier.focusRing(CircleShape)) { Text("Sign out") }
        }
    }
}

@Composable
private fun Setting(label: String, value: String) {
    Column {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(value, color = Muted)
    }
}
