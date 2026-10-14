package com.tolik.mishar

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.Button
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
    private lateinit var clientIdEdit: EditText
    private lateinit var loginBtn: Button
    private lateinit var spinner: Spinner
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
        clientIdEdit = findViewById(R.id.clientId)
        loginBtn = findViewById(R.id.loginBtn)
        spinner = findViewById(R.id.srcSpinner)
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

        clientIdEdit.setText(sp.clientId)
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
        val cid = clientIdEdit.text.toString().trim()
        if (cid.isEmpty()) {
            clientIdEdit.error = "צריך להדביק Client ID"
            return
        }
        sp.clientId = cid
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
        setEnabled(loadBtn, false)
        loadStatus.text = "טוען…"
        thread {
            try {
                val list = sp.loadTracks(id) { n -> ui { loadStatus.text = "נטענו $n שירים…" } }
                ui {
                    setEnabled(loadBtn, true)
                    if (list.isEmpty()) {
                        loadStatus.text = "לא נמצאו שירים ברשימה הזו"
                    } else {
                        tracks = list
                        pool.clear()
                        pool.addAll(list)
                        current = null
                        resetCard()
                        playStatus.text = "לחץ על טוליק כדי להגריל שיר"
                        updateCount()
                        show(gameScreen)
                    }
                }
            } catch (e: Exception) {
                ui { setEnabled(loadBtn, true); loadStatus.text = "הטעינה נכשלה: ${e.message}" }
            }
        }
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
        playStatus.text = "מנגן…"
        thread {
            try {
                val device = sp.play(t.uri)
                ui {
                    if (current === t) playStatus.text =
                        if (device == null) "🎵 מתנגן בספוטיפיי – נחשו!" else "🎵 מתנגן ב־$device – נחשו!"
                }
            } catch (e: ApiException) {
                ui {
                    playStatus.text = when (e.code) {
                        404 -> {
                            openSpotify()
                            "ספוטיפיי לא היה פעיל, אז פתחתי אותו. נגן בו משהו לשנייה, חזור ולחץ שוב על טוליק."
                        }
                        403 -> "ספוטיפיי סירב לנגן (צריך חשבון Premium)."
                        else -> "הניגון נכשל: ${e.message}"
                    }
                }
            } catch (e: Exception) {
                ui { playStatus.text = "אין חיבור לאינטרנט: ${e.message}" }
            }
        }
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
        countTv.text = "נשארו ${pool.size} מתוך ${tracks.size}"
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
