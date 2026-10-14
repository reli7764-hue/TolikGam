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

data class Track(
    val uri: String,
    val name: String,
    val artists: String,
    val year: String,
    val image: String?
)

class ApiException(val code: Int, message: String) : Exception(message)

/** Minimal Spotify Web API client: PKCE login, token refresh, track loading, playback. */
class Spotify(context: Context) {

    companion object {
        const val REDIRECT = "tolikgame://callback"
        private const val SCOPES = "user-library-read playlist-read-private playlist-read-collaborative " +
            "user-modify-playback-state user-read-playback-state"
        private const val API = "https://api.spotify.com/v1"
        private const val MAX_TRACKS = 3000
    }

    private val prefs = context.getSharedPreferences("spotify", Context.MODE_PRIVATE)

    var clientId: String
        get() = prefs.getString("cid", "") ?: ""
        set(value) = prefs.edit().putString("cid", value).apply()

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
                "scope" to SCOPES
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
        val (status, body) = http("POST", "https://accounts.spotify.com/api/token",
            form(params), "application/x-www-form-urlencoded", null)
        val j = parse(body)
        if (status !in 200..299) {
            val msg = j.optString("error_description").ifEmpty { j.optString("error") }
            throw ApiException(status, msg.ifEmpty { "HTTP $status" })
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
        var res = http(method, url, body, "application/json", token())
        if (res.first == 401) res = http(method, url, body, "application/json", token(force = true))
        val j = parse(res.second)
        if (res.first !in 200..299) {
            val msg = j.optJSONObject("error")?.optString("message").orEmpty()
            throw ApiException(res.first, msg.ifEmpty { "HTTP ${res.first}" })
        }
        return j
    }

    fun playlists(): List<Pair<String, String>> {
        val items = api("GET", "/me/playlists?limit=50").optJSONArray("items") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until items.length()) {
            val p = items.optJSONObject(i) ?: continue
            out.add(p.optString("id") to p.optString("name"))
        }
        return out
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
        val year = album?.optString("release_date")?.take(4)?.takeIf { it.length == 4 } ?: "?"
        val images = album?.optJSONArray("images")
        val img = images?.let { im ->
            (if (im.length() > 1) im.optJSONObject(1) else im.optJSONObject(0))?.optString("url")
        }?.takeIf { it.startsWith("http") }
        return Track(uri, t.optString("name"), artists, year, img)
    }

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

    private fun http(method: String, url: String, body: String?, contentType: String, token: String?): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15000
            c.readTimeout = 20000
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", contentType)
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val status = c.responseCode
            val stream = if (status >= 400) c.errorStream else c.inputStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            return status to text
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
