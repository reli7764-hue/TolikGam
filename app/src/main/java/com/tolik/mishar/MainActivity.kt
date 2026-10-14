package com.tolik.mishar

import android.app.Activity
import android.app.AlertDialog
import android.content.SharedPreferences
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.net.URL
import kotlin.concurrent.thread
import kotlin.random.Random

class MainActivity : Activity() {

    private lateinit var sp: Spotify

    private var tracks: List<Track> = emptyList()
    private val pool = ArrayList<Track>()
    private var current: Track? = null
    private val sources = ArrayList<Pair<String, String>>()
    private lateinit var adapter: ArrayAdapter<String>

    private lateinit var setupScreen: View
    private lateinit var sourceScreen: View
    private lateinit var gameScreen: View
    private lateinit var loginBtn: Button
    private lateinit var spinner: Spinner
    private lateinit var israelOnly: CheckBox
    private lateinit var genreSpinner: Spinner
    private lateinit var prefs: SharedPreferences
    private var loadedLabel = ""
    private var retryOnReturn: Track? = null
    private lateinit var loadBtn: Button
    private lateinit var loadStatus: TextView
    private lateinit var record: RecordView
    private lateinit var playStatus: TextView
    private lateinit var card: View
    private lateinit var cardQ: View
    private lateinit var cardContent: View
    private lateinit var cover: ImageView
    private lateinit var yearTv: TextView
    private lateinit var artistTv: TextView
    private lateinit var titleTv: TextView
    private lateinit var revealBtn: Button
    private lateinit var countTv: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        sp = Spotify(this)

        setupScreen = findViewById(R.id.setup)
        sourceScreen = findViewById(R.id.source)
        gameScreen = findViewById(R.id.game)
        loginBtn = findViewById(R.id.loginBtn)
        spinner = findViewById(R.id.srcSpinner)
        israelOnly = findViewById(R.id.israelOnly)
        genreSpinner = findViewById(R.id.genreSpinner)
        loadBtn = findViewById(R.id.loadBtn)
        loadStatus = findViewById(R.id.loadStatus)
        record = findViewById(R.id.record)
        playStatus = findViewById(R.id.playStatus)
        card = findViewById(R.id.card)
        cardQ = findViewById(R.id.cardQ)
        cardContent = findViewById(R.id.cardContent)
        cover = findViewById(R.id.cover)
        yearTv = findViewById(R.id.year)
        artistTv = findViewById(R.id.artist)
        titleTv = findViewById(R.id.songTitle)
        revealBtn = findViewById(R.id.revealBtn)
        countTv = findViewById(R.id.count)

        prefs = getSharedPreferences("game", MODE_PRIVATE)
        val genreAdapter = ArrayAdapter(this, R.layout.spinner_item,
            listOf("כל הז'אנרים") + Genres.buckets.map { it.label })
        genreAdapter.setDropDownViewResource(R.layout.spinner_item)
        genreSpinner.adapter = genreAdapter
        genreSpinner.setSelection(prefs.getInt("genre", 0).coerceIn(0, Genres.buckets.size))
        findViewById<View>(R.id.lastfmBtn).setOnClickListener { askLastfmKey() }
        israelOnly.isChecked = prefs.getBoolean("israelOnly", false)
        israelOnly.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("israelOnly", checked).apply()
        }
        adapter = ArrayAdapter(this, R.layout.spinner_item, ArrayList<String>())
        adapter.setDropDownViewResource(R.layout.spinner_item)
        spinner.adapter = adapter

        loginBtn.setOnClickListener { login() }
        loadBtn.setOnClickListener { loadTracks() }
        record.setOnClickListener { draw() }
        revealBtn.setOnClickListener { reveal() }
        findViewById<View>(R.id.changeSrc).setOnClickListener { openSource() }
        findViewById<View>(R.id.logoutBtn).setOnClickListener {
            sp.logout()
            show(setupScreen)
        }

        if (!handleRedirect(intent)) {
            if (sp.loggedIn) openSource() else show(setupScreen)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleRedirect(intent)
    }

    // ---------- login ----------

    private fun login() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(sp.buildAuthUrl())))
    }

    private fun handleRedirect(i: Intent?): Boolean {
        val data = i?.data ?: return false
        if (data.scheme != "tolikgame") return false
        i.data = null // don't handle it twice
        val code = data.getQueryParameter("code")
        show(setupScreen)
        if (code == null) {
            toast("ההתחברות בוטלה")
            return true
        }
        setEnabled(loginBtn, false)
        thread {
            try {
                sp.exchangeCode(code)
                ui { setEnabled(loginBtn, true); openSource() }
            } catch (e: Exception) {
                ui { setEnabled(loginBtn, true); toast("ההתחברות נכשלה: ${e.message}") }
            }
        }
        return true
    }

    // ---------- choosing a list ----------

    private fun openSource() {
        show(sourceScreen)
        loadStatus.text = ""
        sources.clear()
        sources.add("liked" to "השירים שאהבתי")
        refreshSources()
        thread {
            try {
                val lists = sp.playlists()
                ui { sources.addAll(lists); refreshSources() }
            } catch (e: Exception) {
                ui { loadStatus.text = "לא הצלחתי לטעון את הפלייליסטים: ${e.message}" }
            }
        }
    }

    private fun refreshSources() {
        adapter.clear()
        adapter.addAll(sources.map { it.second })
        adapter.notifyDataSetChanged()
    }

    private fun loadTracks() {
        val id = sources.getOrNull(spinner.selectedItemPosition)?.first ?: "liked"
        val listName = sources.getOrNull(spinner.selectedItemPosition)?.second ?: ""
        val onlyIsrael = israelOnly.isChecked
        val genreIndex = genreSpinner.selectedItemPosition
        val bucket = Genres.buckets.getOrNull(genreIndex - 1)
        val lastfmKey = prefs.getString("lastfm", "") ?: ""
        prefs.edit().putInt("genre", genreIndex).apply()
        setEnabled(loadBtn, false)
        loadStatus.text = "טוען…"
        thread {
            try {
                val all = sp.loadTracks(id) { n -> ui { loadStatus.text = "נטענו $n שירים…" } }
                var list = if (onlyIsrael) all.filter { it.isIsraeli } else all
                var unknown = 0
                if (bucket != null && list.isNotEmpty()) {
                    val artists = list.associate { it.artistId to it.mainArtist }
                    val tags = sp.genresFor(artists, lastfmKey) { done, total ->
                        ui { loadStatus.text = "מזהה ז'אנרים: $done מתוך $total אמנים…" }
                    }
                    unknown = list.count { tags[it.artistId].isNullOrEmpty() }
                    list = list.filter { bucket.matches(tags[it.artistId].orEmpty()) }
                }
                ui {
                    setEnabled(loadBtn, true)
                    if (list.isEmpty()) {
                        val parts = ArrayList<String>()
                        parts.add(when {
                            all.isEmpty() -> "לא נמצאו שירים ברשימה הזו."
                            bucket != null -> "לא נמצאו שירים בז'אנר ${bucket.label}."
                            else -> "מתוך ${all.size} שירים ברשימה, לא נמצאו שירים בעברית."
                        })
                        if (bucket != null && unknown > 0 && lastfmKey.isEmpty())
                            parts.add("לספוטיפיי אין ז'אנר ל־$unknown שירים. מפתח Last.fm ישפר את הזיהוי.")
                        loadStatus.text = parts.joinToString("\n")
                    } else {
                        tracks = list
                        pool.clear()
                        pool.addAll(list)
                        current = null
                        loadedLabel = listOfNotNull(listName.takeIf { it.isNotEmpty() }, bucket?.label,
                            if (onlyIsrael) "🇮🇱" else null).joinToString(" · ")
                        resetCard()
                        playStatus.text = "לחץ על טוליק כדי להגריל שיר"
                        updateCount()
                        show(gameScreen)
                    }
                }
            } catch (e: Exception) {
                val msg = if (e is ApiException && id != "liked" && (e.code == 403 || e.code == 404))
                    "ספוטיפיי לא מרשה לקרוא את הרשימה הזו. אפשר להעתיק אותה לפלייליסט משלך " +
                        "(באפליקציית ספוטיפיי: ⋯ ← הוספה לפלייליסט אחר ← פלייליסט חדש)."
                else "הטעינה נכשלה: ${e.message}"
                ui { setEnabled(loadBtn, true); loadStatus.text = msg }
            }
        }
    }

    private fun askLastfmKey() {
        val input = EditText(this).apply {
            setText(prefs.getString("lastfm", ""))
            hint = "API key"
            textDirection = View.TEXT_DIRECTION_LTR
            setSingleLine()
        }
        val box = android.widget.FrameLayout(this).apply {
            val pad = (20 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle("מפתח Last.fm")
            .setMessage("לא חובה. ספוטיפיי לא מסווג הרבה אמנים לז'אנרים, ו־Last.fm מכיר הרבה יותר. " +
                "מפתח חינמי מקבלים ב־last.fm/api/account/create.")
            .setView(box)
            .setPositiveButton("שמור") { _, _ ->
                prefs.edit().putString("lastfm", input.text.toString().trim()).apply()
                toast("נשמר")
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ---------- the game ----------

    private fun draw() {
        if (tracks.isEmpty()) return
        if (pool.isEmpty()) pool.addAll(tracks)
        val t = pool.removeAt(Random.nextInt(pool.size))
        current = t
        updateCount()
        resetCard()
        setEnabled(revealBtn, true)
        record.animate().cancel()
        record.animate().rotationBy(1080f).setDuration(1600)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        retryOnReturn = null
        startPlayback(t, firstTry = true)
    }

    /** Spotify needs to be awake on some device. If it isn't, we open it and retry when you come back. */
    private fun startPlayback(t: Track, firstTry: Boolean) {
        playStatus.text = "מנגן…"
        thread {
            var attempt = 0
            while (true) {
                try {
                    val device = sp.play(t.uri)
                    ui {
                        if (current === t) playStatus.text =
                            if (device == null) "🎵 מתנגן בספוטיפיי – נחשו!" else "🎵 מתנגן ב־$device – נחשו!"
                    }
                    return@thread
                } catch (e: ApiException) {
                    // right after Spotify opens it can take a moment to show up as a device
                    if (e.code == 404 && !firstTry && attempt < 4) {
                        attempt++
                        Thread.sleep(1500)
                        continue
                    }
                    ui {
                        playStatus.text = when (e.code) {
                            404 -> if (firstTry) {
                                retryOnReturn = t
                                openSpotify()
                                "פתחתי את ספוטיפיי כדי להעיר אותו. חזרו לכאן והשיר יתחיל לבד."
                            } else {
                                "ספוטיפיי עדיין לא זמין. פתחו אותו, נגנו שיר כלשהו לשנייה, וחזרו ללחוץ על טוליק."
                            }
                            403 -> "ספוטיפיי סירב לנגן: ${e.message}. צריך חשבון Premium מחובר."
                            else -> "הניגון נכשל (${e.code}): ${e.message}"
                        }
                    }
                    return@thread
                } catch (e: Exception) {
                    ui { playStatus.text = "אין חיבור לאינטרנט: ${e.message}" }
                    return@thread
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val t = retryOnReturn ?: return
        retryOnReturn = null
        if (t === current) startPlayback(t, firstTry = false)
    }

    private fun reveal() {
        val t = current ?: return
        cardQ.visibility = View.GONE
        cardContent.visibility = View.VISIBLE
        card.setBackgroundResource(R.drawable.card_revealed)
        yearTv.text = t.year
        artistTv.text = t.artists
        titleTv.text = t.name
        cover.setImageDrawable(null)
        setEnabled(revealBtn, false)
        card.rotationX = 80f
        card.animate().rotationX(0f).setDuration(350).start()
        val url = t.image ?: return
        thread {
            try {
                val bmp = URL(url).openStream().use { BitmapFactory.decodeStream(it) }
                ui { if (current === t) cover.setImageBitmap(bmp) }
            } catch (_: Exception) {
            }
        }
    }

    private fun resetCard() {
        cardQ.visibility = View.VISIBLE
        cardContent.visibility = View.GONE
        card.setBackgroundResource(R.drawable.card_hidden)
        setEnabled(revealBtn, false)
    }

    private fun updateCount() {
        countTv.text = "נשארו ${pool.size} מתוך ${tracks.size}\n$loadedLabel"
    }

    private fun openSpotify() {
        val launch = packageManager.getLaunchIntentForPackage("com.spotify.music")
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://open.spotify.com"))
        startActivity(launch)
    }

    // ---------- helpers ----------

    private fun show(screen: View) {
        for (v in listOf(setupScreen, sourceScreen, gameScreen)) {
            v.visibility = if (v === screen) View.VISIBLE else View.GONE
        }
    }

    private fun setEnabled(b: Button, enabled: Boolean) {
        b.isEnabled = enabled
        b.alpha = if (enabled) 1f else 0.4f
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    private fun ui(block: () -> Unit) = runOnUiThread(block)
}
