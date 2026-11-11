package com.ciniva.android

import android.app.UiModeManager
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import com.ciniva.android.ui.Bg
import com.ciniva.android.ui.CinivaTheme
import com.ciniva.android.ui.HomeScreen
import com.ciniva.android.ui.LibraryScreen
import com.ciniva.android.ui.LocalIsTv
import com.ciniva.android.ui.LocalSignOut
import com.ciniva.android.ui.LoginScreen
import com.ciniva.android.ui.MovieScreen
import com.ciniva.android.ui.Nav
import com.ciniva.android.ui.SearchScreen
import com.ciniva.android.ui.SettingsScreen
import com.ciniva.android.ui.ShowScreen
import com.ciniva.android.ui.Surface
import com.ciniva.android.ui.focusRing
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val tv = isTv(this)
        setContent {
            CinivaTheme {
                CompositionLocalProvider(LocalIsTv provides tv) { App() }
            }
        }
    }
}

fun isTv(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK) ||
        (context.getSystemService(Context.UI_MODE_SERVICE) as UiModeManager).currentModeType == Configuration.UI_MODE_TYPE_TELEVISION

private enum class Tab(val label: String, val icon: ImageVector) {
    Home("Home", Icons.Default.Home),
    Movies("Movies", Icons.Default.PlayArrow),
    Shows("Shows", Icons.AutoMirrored.Filled.List),
    Search("Search", Icons.Default.Search),
    Settings("Settings", Icons.Default.Settings),
}

private sealed interface Route {
    data class Movie(val id: Long) : Route
    data class Show(val id: Long) : Route
}

@Composable
private fun App() {
    var signedIn by remember { mutableStateOf(Session.signedIn) }
    val signOut: () -> Unit = {
        val token = Session.token
        if (token.isNotEmpty()) CinivaApp.scope.launch { runCatching { Api.raw("POST", "/auth/logout", token = token) } }
        Session.signOut()
        signedIn = false
    }
    CompositionLocalProvider(LocalSignOut provides signOut) {
        Box(Modifier.fillMaxSize().background(Bg)) {
            if (!signedIn) LoginScreen { signedIn = true } else Shell()
        }
    }
}

@Composable
private fun Shell() {
    val tv = LocalIsTv.current
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    val stack = remember { mutableStateListOf<Route>() }
    val nav = remember { Nav(openMovie = { stack.add(Route.Movie(it)) }, openShow = { stack.add(Route.Show(it)) }) }

    BackHandler(enabled = stack.isNotEmpty()) { stack.removeAt(stack.lastIndex) }
    BackHandler(enabled = stack.isEmpty() && tab != Tab.Home) { tab = Tab.Home }

    fun select(t: Tab) {
        stack.clear()
        tab = t
    }

    @Composable
    fun Content(padding: PaddingValues) {
        when (val top = stack.lastOrNull()) {
            is Route.Movie -> Detail(tv) { MovieScreen(top.id) }
            is Route.Show -> Detail(tv) { ShowScreen(top.id) }
            null -> when (tab) {
                Tab.Home -> HomeScreen(nav, padding)
                Tab.Movies -> LibraryScreen(movies = true, nav, padding)
                Tab.Shows -> LibraryScreen(movies = false, nav, padding)
                Tab.Search -> SearchScreen(nav, padding)
                Tab.Settings -> SettingsScreen(padding)
            }
        }
    }

    if (tv) {
        Row(Modifier.fillMaxSize()) {
            NavigationRail(containerColor = Surface) {
                Spacer(Modifier.height(24.dp))
                Tab.entries.forEach { t ->
                    NavigationRailItem(
                        selected = tab == t && stack.isEmpty(),
                        onClick = { select(t) },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                        modifier = Modifier.padding(vertical = 4.dp).focusRing(),
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxSize()) { Content(PaddingValues(top = 24.dp)) }
        }
    } else {
        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = Surface) {
                    Tab.entries.forEach { t ->
                        NavigationBarItem(
                            selected = tab == t && stack.isEmpty(),
                            onClick = { select(t) },
                            icon = { Icon(t.icon, contentDescription = null) },
                            label = { Text(t.label) },
                        )
                    }
                }
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(bottom = padding.calculateBottomPadding())) {
                Content(PaddingValues(top = padding.calculateTopPadding()))
            }
        }
    }
}

/** A movie or show page; phones get a back button over the backdrop, TVs use the remote's Back. */
@Composable
private fun Detail(tv: Boolean, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        content()
        if (!tv) {
            val stackBack = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current
            IconButton(
                onClick = { stackBack?.onBackPressedDispatcher?.onBackPressed() },
                modifier = Modifier.statusBarsPadding().padding(8.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.45f)),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
        }
    }
}
