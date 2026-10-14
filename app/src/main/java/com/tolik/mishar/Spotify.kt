package com.tolik.mishar

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

data class Track(
    val uri: String,
    val name: String,
    val artists: String,
    val artistId: String,
    val mainArtist: String,
    val year: String,
    val image: String?
)

private val HEBREW = Regex("[\\u0590-\\u05FF]")

/** Spotify doesn't say which country a song is from, so we treat Hebrew in the title or artist as Israeli. */
val Track.isIsraeli: Boolean
    get() = HEBREW.containsMatchIn(name) || HEBREW.containsMatchIn(artists)

class ApiException(val code: Int, message: String) : Exception(message)

private class Resp(val code: Int, val text: String, val retryAfter: Int)

/** Minimal Spotify Web API client: PKCE login, token refresh, track loading, genres, playback. */
class Spotify(context: Context) {

    companion object {
        const val REDIRECT = "tolikgame://callback"
        const val CLIENT_ID = "673feb023aa4489e8c098a9a37fda321"
        private const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative " +
            "user-modify-playback-state user-read-playback-state"
        private const val API = "https://api.spotify.com/v1"
        private const val MAX_TRACKS = 3000
        const val BAD_LASTFM_KEY = -10
    }

    private val prefs = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)
    private val genreCache = context.getSharedPreferences("genres", Context.MODE_PRIVATE)

    val clientId: String = CLIENT_ID

    val loggedIn: Boolean
        get() = prefs.getString("refresh", null) != null

    fun logout() {
        prefs.edit().remove("token").remove("refresh").remove("expires").apply()
    }

    // ---------- auth ----------

    fun buildAuthUrl(): String {
        val bytes = ByteArray(48).also { SecureRandom().nextBytes(it) }
        val verifier = b64(bytes)
        prefs.edit().putString("verifier", verifier).apply()
        val challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return "https://accounts.spotify.com/authorize?" + form(
            mapOf(
                "client_id" to clientId,
                "response_type" to "code",
                "redirect_uri" to REDIRECT,
                "code_challenge_method" to "S256",
                "code_challenge" to challenge,
                "scope" to SCOPES,
                "show_dialog" to "true"   // always show whose account it is, so switching users is easy
            )
        )
    }

    fun exchangeCode(code: String) = tokenRequest(
        mapOf(
            "grant_type" to "authorization_code",
            "code" to code,
            "redirect_uri" to REDIRECT,
            "client_id" to clientId,
            "code_verifier" to (prefs.getString("verifier", "") ?: "")
        )
    )

    private fun tokenRequest(params: Map<String, String>) {
        val r = http("POST", "https://accounts.spotify.com/api/token",
            form(params), "application/x-www-form-urlencoded", null)
        val j = parse(r.text)
        if (r.code !in 200..299) {
            val msg = j.optString("error_description").ifEmpty { j.optString("error") }
            throw ApiException(r.code, msg.ifEmpty { "HTTP ${r.code}" })
        }
        val edit = prefs.edit()
            .putString("token", j.getString("access_token"))
            .putLong("expires", System.currentTimeMillis() + (j.optLong("expires_in", 3600) - 60) * 1000)
        val refresh = j.optString("refresh_token")
        if (refresh.isNotEmpty() && refresh != "null") edit.putString("refresh", refresh)
        edit.apply()
    }

    @Synchronized
    private fun token(force: Boolean = false): String {
        val t = prefs.getString("token", null)
        if (!force && t != null && System.currentTimeMillis() < prefs.getLong("expires", 0)) return t
        val refresh = prefs.getString("refresh", null) ?: throw ApiException(401, "לא מחובר")
        tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh, "client_id" to clientId))
        return prefs.getString("token", "") ?: ""
    }

    // ---------- API ----------

    private fun api(method: String, path: String, body: String? = null): JSONObject {
        val url = if (path.startsWith("http")) path else API + path
        var r = http(method, url, body, "application/json", token())
        var attempts = 0
        while (attempts < 4) {
            if (r.code == 401) {
                r = http(method, url, body, "application/json", token(force = true))
            } else if (r.code == 429 && !r.text.contains("QUOTA_EXCEEDED")) {
                Thread.sleep(r.retryAfter.coerceIn(1, 10) * 1000L)
                r = http(method, url, body, "application/json", token())
            } else break
            attempts++
        }
        val j = parse(r.text)
        if (r.code !in 200..299) {
            val msg = j.optJSONObject("error")?.optString("message").orEmpty()
            throw ApiException(r.code, if (r.code == 429) "ספוטיפיי הגביל את מספר הבקשות, נסה שוב מאוחר יותר"
                else msg.ifEmpty { "HTTP ${r.code}" })
        }
        return j
    }

    /** Playlists whose songs the app may read. Since 2026 that means ones you own or collaborate on. */
    fun playlists(): List<Pair<String, String>> {
        val items = api("GET", "/me/playlists?limit=50").optJSONArray("items") ?: return emptyList()
        val all = ArrayList<Pair<String, String>>()
        val readable = ArrayList<Pair<String, String>>()
        for (i in 0 until items.length()) {
            val p = items.optJSONObject(i) ?: continue
            val entry = p.optString("id") to p.optString("name")
            all.add(entry)
            if (p.has("items") || p.has("tracks")) readable.add(entry)
        }
        return readable.ifEmpty { all }
    }

    fun loadTracks(source: String, progress: (Int) -> Unit): List<Track> =
        if (source == "liked") {
            fetchAll("/me/tracks?limit=50", progress)
        } else {
            try {
                fetchAll("/playlists/$source/items?limit=50", progress)   // 2026 endpoint name
            } catch (e: ApiException) {
                fetchAll("/playlists/$source/tracks?limit=50", progress)  // older name
            }
        }

    private fun fetchAll(first: String, progress: (Int) -> Unit): List<Track> {
        val out = ArrayList<Track>()
        var next: String? = first
        while (next != null && out.size < MAX_TRACKS) {
            val page = api("GET", next)
            val items = page.optJSONArray("items") ?: JSONArray()
            for (i in 0 until items.length()) {
                val row = items.optJSONObject(i) ?: continue
                val t = row.optJSONObject("item") ?: row.optJSONObject("track") ?: continue
                toTrack(t)?.let { out.add(it) }
            }
            progress(out.size)
            next = page.optString("next").takeIf { it.isNotEmpty() && it != "null" }
        }
        return out
    }

    private fun toTrack(t: JSONObject): Track? {
        if (t.optString("type") == "episode" || t.optBoolean("is_local")) return null
        val uri = t.optString("uri")
        if (!uri.startsWith("spotify:track:")) return null
        val album = t.optJSONObject("album")
        val artistsArr = t.optJSONArray("artists") ?: JSONArray()
        val artists = (0 until artistsArr.length())
            .mapNotNull { artistsArr.optJSONObject(it)?.optString("name") }
            .joinToString(", ")
        val first = artistsArr.optJSONObject(0)
        val year = album?.optString("release_date")?.take(4)?.takeIf { it.length == 4 } ?: "?"
        val images = album?.optJSONArray("images")
        val img = images?.let { im ->
            (if (im.length() > 1) im.optJSONObject(1) else im.optJSONObject(0))?.optString("url")
        }?.takeIf { it.startsWith("http") }
        return Track(uri, t.optString("name"), artists,
            first?.optString("id").orEmpty(), first?.optString("name").orEmpty(), year, img)
    }

    // ---------- genres ----------

    /**
     * Returns artistId -> tags ("rock|israeli rock|..."; empty = unknown).
     * Uses Last.fm when a key is given (much better coverage), otherwise Spotify's own artist genres.
     * Results are cached on the phone, so only the first load is slow.
     */
    fun genresFor(artists: Map<String, String>, lastfmKey: String, progress: (Int, Int) -> Unit): Map<String, String> {
        val src = if (lastfmKey.isNotEmpty()) "lf" else "sp"
        val result = ConcurrentHashMap<String, String>()
        val todo = ArrayList<Pair<String, String>>()
        for ((id, name) in artists) {
            if (id.isEmpty()) continue
            val cached = genreCache.getString("$src:$id", null)
            if (cached != null) result[id] = cached else todo.add(id to name)
        }
        if (todo.isEmpty()) return result

        val done = AtomicInteger(0)
        val fatal = AtomicReference<ApiException?>(null)
        val pool = Executors.newFixedThreadPool(4)
        for ((id, name) in todo) {
            pool.execute {
                if (fatal.get() == null) {
                    try {
                        val tags = if (src == "lf") lastfmTags(name, lastfmKey) else spotifyTags(id)
                        result[id] = tags
                        genreCache.edit().putString("$src:$id", tags).apply()
                    } catch (e: ApiException) {
                        if (e.code == BAD_LASTFM_KEY || e.code == 429) fatal.compareAndSet(null, e)
                    } catch (_: Exception) {
                    }
                }
                progress(done.incrementAndGet(), todo.size)
            }
        }
        pool.shutdown()
        pool.awaitTermination(30, TimeUnit.MINUTES)
        fatal.get()?.let { throw it }
        return result
    }

    private fun spotifyTags(id: String): String {
        val g = api("GET", "/artists/$id").optJSONArray("genres") ?: return ""
        return (0 until g.length()).joinToString("|") { g.optString(it).lowercase() }
    }

    private fun lastfmTags(artist: String, key: String): String {
        val url = "https://ws.audioscrobbler.com/2.0/?method=artist.gettoptags&autocorrect=1&format=json" +
            "&api_key=" + enc(key) + "&artist=" + enc(artist)
        var r = http("GET", url, null, "", null)
        if (r.code == 429) {
            Thread.sleep(2000)
            r = http("GET", url, null, "", null)
        }
        val j = parse(r.text)
        val err = j.optInt("error", 0)
        if (err == 10 || err == 26) throw ApiException(BAD_LASTFM_KEY, "מפתח ה־Last.fm לא תקין")
        if (err == 6) return ""                       // artist not found
        if (r.code !in 200..299 || err != 0) throw ApiException(r.code, "Last.fm: ${j.optString("message")}")
        val top = j.optJSONObject("toptags") ?: return ""
        val arr = top.optJSONArray("tag") ?: top.optJSONObject("tag")?.let { JSONArray().put(it) } ?: return ""
        val tags = ArrayList<String>()
        for (i in 0 until minOf(arr.length(), 8)) {
            val t = arr.optJSONObject(i) ?: continue
            if (t.optInt("count") >= 10) tags.add(t.optString("name").lowercase())
        }
        return tags.joinToString("|")
    }

    // ---------- playback ----------

    /** Plays the track on the active Spotify device. Returns a device name if it had to pick one. */
    fun play(uri: String): String? {
        val body = JSONObject().put("uris", JSONArray().put(uri)).toString()
        try {
            api("PUT", "/me/player/play", body)
            return null
        } catch (e: ApiException) {
            if (e.code != 404) throw e
            val devices = api("GET", "/me/player/devices").optJSONArray("devices") ?: throw e
            val dev = devices.optJSONObject(0) ?: throw e
            api("PUT", "/me/player/play?device_id=" + enc(dev.optString("id")), body)
            return dev.optString("name")
        }
    }

    // ---------- helpers ----------

    private fun http(method: String, url: String, body: String?, contentType: String, token: String?): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            c.setRequestProperty("User-Agent", "TolikGame/1.0")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            val retry = c.getHeaderField("Retry-After")?.toIntOrNull() ?: 2
            return Resp(status, text, retry)
        } finally {
            c.disconnect()
        }
    }

    private fun parse(s: String): JSONObject =
        try { if (s.isBlank()) JSONObject() else JSONObject(s) } catch (e: Exception) { JSONObject() }

    private fun b64(b: ByteArray) =
        Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun form(m: Map<String, String>) =
        m.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
}
