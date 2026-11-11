package com.ciniva.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.ciniva.android.Api
import com.ciniva.android.EpisodeView
import com.ciniva.android.FileView
import com.ciniva.android.MovieView
import com.ciniva.android.PlayerActivity
import com.ciniva.android.ShowView
import kotlinx.coroutines.launch

/** "2160p HEVC Dolby Vision · 58.2 GB" for the version picker. */
private fun fileLabel(f: FileView): String {
    val v = f.probe?.video
    val parts = listOfNotNull(
        v?.height?.takeIf { it > 0 }?.let { "${it}p" },
        v?.codec?.uppercase(),
        if (v?.dolbyVisionProfile != null) "Dolby Vision" else v?.hdr?.uppercase(),
    )
    return (parts.joinToString(" ").ifEmpty { f.path.substringAfterLast('/') }) + " · " + "%.1f GB".format(f.size / 1e9)
}

@Composable
private fun Backdrop(image: String?) {
    val tv = LocalIsTv.current
    Box(Modifier.fillMaxWidth().heightIn(max = if (tv) 420.dp else 300.dp).aspectRatio(16f / 9f)) {
        if (image != null) AsyncImage(model = image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Bg.copy(alpha = 0.3f), Color.Transparent, Bg))))
    }
}

@Composable
private fun Header(title: String, meta: String, genres: List<String>, tagline: String?, poster: String?) {
    val tv = LocalIsTv.current
    Row(Modifier.padding(horizontal = gutter()), verticalAlignment = Alignment.Bottom) {
        if (poster != null) {
            AsyncImage(
                model = poster, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.width(if (tv) 160.dp else 100.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(16.dp))
        }
        Column {
            Text(title, fontSize = if (tv) 32.sp else 24.sp, fontWeight = FontWeight.Bold, lineHeight = if (tv) 36.sp else 28.sp)
            if (meta.isNotEmpty()) Text(meta, color = Muted, modifier = Modifier.padding(top = 4.dp))
            if (genres.isNotEmpty()) Text(genres.joinToString(" · "), color = Muted, fontSize = 13.sp)
            tagline?.takeIf { it.isNotBlank() }?.let { Text(it, fontStyle = FontStyle.Italic, color = Muted, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

// ---- Movie ------------------------------------------------------------------------------------------------

@Composable
fun MovieScreen(id: Long) {
    val movie = load(id) { Api.get<MovieView>("/movies/$id") }
    LoadView(movie) { m -> MovieContent(m, movie.reload) }
}

@Composable
private fun MovieContent(m: MovieView, reload: () -> Unit) {
    val context = LocalContext.current
    val tv = LocalIsTv.current
    val scope = rememberCoroutineScope()
    var fileIndex by rememberSaveable(m.id) { mutableIntStateOf(0) }
    val file = m.files.getOrNull(fileIndex)
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(m.id) { if (tv) runCatching { playFocus.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Backdrop(Api.image(m.backdropPath, "w1280")) }
        item {
            val meta = listOfNotNull(m.year?.toString(), runtimeText(m.runtime), m.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ")
            Header(m.title, meta, m.genres, m.tagline, Api.image(m.posterPath))
        }
        item {
            FlowRow(
                Modifier.padding(horizontal = gutter(), vertical = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (file == null) {
                    Text("No playable file", color = Muted)
                } else {
                    val resume = file.positionMs?.takeIf { it > 30_000 && file.watched != true }
                    Button(
                        onClick = { PlayerActivity.start(context, file.id, m.title, startMs = resume ?: 0) },
                        modifier = Modifier.focusRequester(playFocus).focusRing(CircleShape),
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (resume != null) "Resume from ${clock(resume)}" else "Play")
                    }
                    if (resume != null) {
                        OutlinedButton(onClick = { PlayerActivity.start(context, file.id, m.title, startMs = 0) }, modifier = Modifier.focusRing(CircleShape)) {
                            Text("Play from start")
                        }
                    }
                    val watched = file.watched == true
                    OutlinedButton(
                        onClick = { scope.launch { runCatching { Api.setWatched(m.files, !watched) }; reload() } },
                        modifier = Modifier.focusRing(CircleShape),
                    ) {
                        if (watched) Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text(if (watched) "Watched" else "Mark watched")
                    }
                }
            }
        }
        m.overview?.takeIf { it.isNotBlank() }?.let { o ->
            item { Text(o, modifier = Modifier.padding(horizontal = gutter()), lineHeight = 22.sp) }
        }
        if (m.files.size > 1) {
            item {
                Column(Modifier.padding(top = 20.dp)) {
                    Text("Versions", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = gutter(), vertical = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = gutter()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(m.files.size) { i ->
                            FilterChip(selected = i == fileIndex, onClick = { fileIndex = i }, label = { Text(fileLabel(m.files[i])) }, modifier = Modifier.focusRing())
                        }
                    }
                }
            }
        }
    }
}

// ---- Show -------------------------------------------------------------------------------------------------

@Composable
fun ShowScreen(id: Long) {
    val show = load(id) { Api.get<ShowView>("/shows/$id") }
    LoadView(show) { s -> ShowContent(s) }
}

@Composable
private fun ShowContent(s: ShowView) {
    val context = LocalContext.current
    val tv = LocalIsTv.current
    val firstSeason = s.seasons.firstOrNull { it.seasonNumber > 0 }?.seasonNumber ?: s.seasons.firstOrNull()?.seasonNumber ?: 1
    var season by rememberSaveable(s.id) { mutableIntStateOf(firstSeason) }
    val episodes = load(s.id, season) { Api.get<List<EpisodeView>>("/shows/${s.id}/seasons/$season") }
    val seasonFocus = remember { FocusRequester() }
    LaunchedEffect(s.id) { if (tv) runCatching { seasonFocus.requestFocus() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
        item { Backdrop(Api.image(s.backdropPath, "w1280")) }
        item {
            val meta = listOfNotNull(s.year?.toString(), s.status, s.rating?.takeIf { it > 0 }?.let { "★ %.1f".format(it) }).joinToString(" · ")
            Header(s.title, meta, s.genres, null, Api.image(s.posterPath))
        }
        s.overview?.takeIf { it.isNotBlank() }?.let { o ->
            item { Text(o, maxLines = 5, overflow = TextOverflow.Ellipsis, lineHeight = 22.sp, modifier = Modifier.padding(horizontal = gutter(), vertical = 16.dp)) }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = gutter(), vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(s.seasons.size) { i ->
                    val se = s.seasons[i]
                    val label = se.name ?: if (se.seasonNumber == 0) "Specials" else "Season ${se.seasonNumber}"
                    FilterChip(
                        selected = se.seasonNumber == season,
                        onClick = { season = se.seasonNumber },
                        label = { Text(if (se.available > 0 && se.watched >= se.available) "$label ✓" else label) },
                        modifier = (if (se.seasonNumber == season) Modifier.focusRequester(seasonFocus) else Modifier).focusRing(),
                    )
                }
            }
        }
        when (val st = episodes.state) {
            is Load.Loading -> item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            is Load.Failed -> item { Text(st.message, color = Danger, modifier = Modifier.padding(gutter())) }
            is Load.Ok -> items(st.value, key = { it.id }) { e ->
                EpisodeRow(e) {
                    val f = e.files.firstOrNull() ?: return@EpisodeRow
                    val sub = "S${e.seasonNumber} · E${e.episodeNumber}" + (e.title?.let { " — $it" } ?: "")
                    PlayerActivity.start(context, f.id, s.title, sub, s.id)
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: EpisodeView, onClick: () -> Unit) {
    val tv = LocalIsTv.current
    val f = e.files.firstOrNull()
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter(), vertical = 6.dp)
            .focusRing(shape)
            .clip(shape)
            .clickable(enabled = f != null, onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(if (tv) 220.dp else 140.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)).background(Surface)) {
            Api.image(e.stillPath, "w300")?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (f?.watched == true) {
                Box(Modifier.align(Alignment.TopEnd).padding(4.dp).clip(CircleShape).background(Accent).padding(2.dp)) {
                    Icon(Icons.Default.Check, contentDescription = "Watched", tint = AccentInk, modifier = Modifier.width(14.dp).height(14.dp))
                }
            }
            val pos = f?.positionMs ?: 0
            val dur = f?.durationMs ?: 0
            if (f?.watched != true && pos > 0 && dur > 0) {
                LinearProgressIndicator(
                    progress = { (pos.toFloat() / dur).coerceIn(0f, 1f) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),
                    color = Accent, trackColor = Color.Black.copy(alpha = 0.5f), drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                "${e.episodeNumber}. " + (e.title ?: "Episode ${e.episodeNumber}"),
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (f == null) Muted else TextColor,
            )
            Text(listOfNotNull(runtimeText(e.runtime), "Not in library".takeIf { f == null }).joinToString(" · "), color = Muted, fontSize = 12.sp)
            e.overview?.takeIf { it.isNotBlank() }?.let { Text(it, color = Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
        }
    }
}
