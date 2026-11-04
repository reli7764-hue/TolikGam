package com.tolik.mishar

import android.content.Context
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.AuthenticationFailedException
import com.spotify.android.appremote.api.error.CouldNotFindSpotifyApp
import com.spotify.android.appremote.api.error.NotLoggedInException
import com.spotify.android.appremote.api.error.OfflineModeException
import com.spotify.android.appremote.api.error.UnsupportedFeatureVersionException
import com.spotify.android.appremote.api.error.UserNotAuthorizedException

/**
 * Plays songs through the Spotify app on this phone directly (Spotify App Remote SDK),
 * instead of going through Spotify's servers. Doesn't need Spotify to be open in front.
 */
class LocalPlayer(private val context: Context) {

    private var remote: SpotifyAppRemote? = null

    /** onDone(null) = playing; onDone(message) = failed, message explains why (Hebrew). */
    fun play(uri: String, onDone: (String?) -> Unit) {
        val r = remote
        if (r != null && r.isConnected) {
            send(r, uri, onDone)
            return
        }
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) {
            onDone("אפליקציית ספוטיפיי לא מותקנת בטלפון")
            return
        }
        val params = ConnectionParams.Builder(Spotify.CLIENT_ID)
            .setRedirectUri(Spotify.REDIRECT)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                send(appRemote, uri, onDone)
            }

            override fun onFailure(error: Throwable) {
                remote = null
                onDone(explain(error))
            }
        })
    }

    private fun send(r: SpotifyAppRemote, uri: String, onDone: (String?) -> Unit) {
        r.playerApi.play(uri)
            .setResultCallback { onDone(null) }
            .setErrorCallback { e -> onDone(explain(e)) }
    }

    fun disconnect() {
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
    }

    private fun explain(e: Throwable): String = when (e) {
        is CouldNotFindSpotifyApp -> "אפליקציית ספוטיפיי לא מותקנת בטלפון"
        is NotLoggedInException -> "צריך להתחבר לאפליקציית ספוטיפיי בטלפון"
        is OfflineModeException -> "ספוטיפיי בטלפון במצב לא מקוון"
        is UnsupportedFeatureVersionException -> "צריך לעדכן את אפליקציית ספוטיפיי"
        is UserNotAuthorizedException, is AuthenticationFailedException ->
            "ספוטיפיי לא מזהה את האפליקציה. צריך להוסיף את טביעת האצבע שלה בדשבורד של ספוטיפיי (${e.message})"
        else -> e.message ?: e.javaClass.simpleName
    }
}
