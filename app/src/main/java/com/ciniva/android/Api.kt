package com.ciniva.android

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

// ---- Models (the Ciniva server's JSON, snake_case on the wire) ------------------------------------------------

@Serializable
data class User(val id: Long, val username: String, val admin: Boolean = false)

@Serializable
data class LoginResponse(val token: String, val user: User)

@Serializable
data class MovieCard(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val rating: Double? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val watched: Boolean? = null,
)

@Serializable
data class ShowCard(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val rating: Double? = null,
    val episodes: Int = 0,
    val watchedEpisodes: Int = 0,
)

@Serializable
data class ContinueItem(
    val fileId: Long,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val movieId: Long? = null,
    val showId: Long? = null,
    val title: String,
    val subtitle: String? = null,
    val imagePath: String? = null,
    val posterPath: String? = null,
    val nextUp: Boolean = false,
)

@Serializable
data class Home(
    val continueWatching: List<ContinueItem> = emptyList(),
    val recentMovies: List<MovieCard> = emptyList(),
    val recentShows: List<ShowCard> = emptyList(),
)

@Serializable
data class VideoInfo(
    val codec: String,
    val profile: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val bitDepth: Int? = null,
    val hdr: String? = null,
    val dolbyVisionProfile: Int? = null,
    val fps: Double? = null,
)

@Serializable
data class AudioInfo(
    val index: Int,
    val codec: String,
    val profile: String? = null,
    val channels: Int? = null,
    val language: String? = null,
    val title: String? = null,
    val atmos: Boolean = false,
    @SerialName("default") val isDefault: Boolean = false,
)

@Serializable
data class SubtitleInfo(
    val index: Int,
    val codec: String,
    val language: String? = null,
    val title: String? = null,
    val forced: Boolean = false,
    @SerialName("default") val isDefault: Boolean = false,
)

@Serializable
data class MediaInfo(
    val container: String? = null,
    val duration: Double? = null,
    val bitrate: Long? = null,
    val video: VideoInfo? = null,
    val audio: List<AudioInfo> = emptyList(),
    val subtitles: List<SubtitleInfo> = emptyList(),
)

@Serializable
data class FileView(
    val id: Long,
    val path: String,
    val size: Long = 0,
    val duration: Double? = null,
    val probe: MediaInfo? = null,
    val creditsStart: Double? = null,
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    val watched: Boolean? = null,
)

@Serializable
data class MovieView(
    val id: Long,
    val title: String,
    val originalTitle: String? = null,
    val year: Int? = null,
    val overview: String? = null,
    val tagline: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val runtime: Int? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val files: List<FileView> = emptyList(),
)

@Serializable
data class SeasonCard(
    val seasonNumber: Int,
    val name: String? = null,
    val posterPath: String? = null,
    val episodeCount: Int? = null,
    val available: Int = 0,
    val watched: Int = 0,
)

@Serializable
data class ShowView(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val overview: String? = null,
    val posterPath: String? = null,
    val backdropPath: String? = null,
    val rating: Double? = null,
    val genres: List<String> = emptyList(),
    val status: String? = null,
    val seasons: List<SeasonCard> = emptyList(),
)

@Serializable
data class EpisodeView(
    val id: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val overview: String? = null,
    val stillPath: String? = null,
    val runtime: Int? = null,
    val files: List<FileView> = emptyList(),
)

@Serializable
data class SearchResults(val movies: List<MovieCard> = emptyList(), val shows: List<ShowCard> = emptyList())

@Serializable
data class TranscodeStarted(val session: String, val playlist: String)

/** POST /files/{id}/transcode. Null fields are left out, so the server uses its defaults. */
@Serializable
data class TranscodeRequest(
    val maxHeight: Int,
    val bitrateKbps: Int,
    val codec: String,
    val hdr: Boolean,
    val audioStream: Int? = null,
    val audioChannels: Int = 2,
    val audioCopy: String? = null,
    val videoCopy: String? = null,
    val dolbyVision: Boolean = false,
    val container: String? = null,
)

@Serializable
private data class Credentials(val username: String, val password: String, val client: String)

@Serializable
data class ProgressBody(val positionMs: Long, val durationMs: Long)

@Serializable
data class WatchedBody(val watched: Boolean)

// ---- Session ---------------------------------------------------------------------------------------------

/** The server and sign-in token, kept in SharedPreferences. */
object Session {
    private lateinit var prefs: SharedPreferences

    var server: String = ""
        private set
    var token: String = ""
        private set
    var username: String = ""
        private set

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ciniva", Context.MODE_PRIVATE)
        server = prefs.getString("server", "") ?: ""
        token = prefs.getString("token", "") ?: ""
        username = prefs.getString("username", "") ?: ""
    }

    val signedIn get() = server.isNotEmpty() && token.isNotEmpty()

    fun set(server: String, token: String, username: String) {
        this.server = server
        this.token = token
        this.username = username
        prefs.edit().putString("server", server).putString("token", token).putString("username", username).apply()
    }

    fun signOut() = set(server, "", "")

    fun pref(key: String, default: String): String = prefs.getString(key, default) ?: default

    fun setPref(key: String, value: String) = prefs.edit().putString(key, value).apply()
}

// ---- Client ----------------------------------------------------------------------------------------------

/** 401 from the server: the session is gone and the user has to sign in again. */
class AuthException(message: String) : Exception(message)

class ApiException(message: String) : Exception(message)

object Api {
    @OptIn(ExperimentalSerializationApi::class)
    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        coerceInputValues = true
        namingStrategy = JsonNamingStrategy.SnakeCase
    }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val JSON_TYPE = "application/json".toMediaType()

    /** "10.0.0.5:8484", "http://host/", "https://host/api" → "http(s)://host[:port]". */
    fun normalizeServer(input: String): String {
        var s = input.trim().trimEnd('/').removeSuffix("/api")
        if (s.isNotEmpty() && !s.matches(Regex("^https?://.*", RegexOption.IGNORE_CASE))) s = "http://$s"
        return s
    }

    suspend fun raw(method: String, path: String, body: String? = null, server: String = Session.server, token: String = Session.token): String =
        withContext(Dispatchers.IO) {
            val requestBody = body?.toRequestBody(JSON_TYPE) ?: if (method == "GET" || method == "DELETE") null else "".toRequestBody(JSON_TYPE)
            val request = Request.Builder().url("$server/api$path").method(method, requestBody)
            if (token.isNotEmpty()) request.header("Authorization", "Bearer $token")
            val response = try {
                http.newCall(request.build()).execute()
            } catch (e: IOException) {
                throw ApiException("Cannot reach the server")
            } catch (e: IllegalArgumentException) {
                throw ApiException("That server address isn't valid")
            }
            response.use { r ->
                val text = r.body?.string().orEmpty()
                if (r.code == 401) throw AuthException(errorOf(text) ?: "Sign in required")
                if (!r.isSuccessful) throw ApiException(errorOf(text) ?: "HTTP ${r.code}")
                text
            }
        }

    private fun errorOf(text: String): String? =
        runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()

    suspend inline fun <reified T> get(path: String): T = json.decodeFromString(raw("GET", path))

    suspend inline fun <reified B> send(method: String, path: String, body: B) {
        raw(method, path, json.encodeToString(body))
    }

    suspend fun login(server: String, username: String, password: String): LoginResponse {
        val body = json.encodeToString(Credentials(username, password, "Ciniva for Android"))
        return json.decodeFromString(raw("POST", "/auth/login", body, server = server, token = ""))
    }

    /** Absolute URL for something the player or image loader fetches itself; the token rides along as ?token=. */
    fun url(path: String, params: Map<String, String> = emptyMap()): String {
        val all = params + ("token" to Session.token)
        val query = all.entries.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v, "UTF-8") }
        return "${Session.server}/api$path?$query"
    }

    fun image(path: String?, size: String = "w342"): String? = path?.let { url("/images/$size/" + it.trimStart('/')) }

    suspend fun setWatched(files: List<FileView>, watched: Boolean) {
        for (f in files) send("POST", "/files/${f.id}/watched", WatchedBody(watched))
    }
}
