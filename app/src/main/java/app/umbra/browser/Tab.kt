package app.umbra.browser

import android.graphics.Bitmap
import org.mozilla.geckoview.GeckoSession

class Tab(var session: GeckoSession, val private: Boolean) {
    var url: String? = null
    var title: String? = null
    var icon: Bitmap? = null
    var progress = 0
    var loading = false
    var canGoBack = false
    var canGoForward = false
    var desktop = false
    var fullscreen = false
    var state: GeckoSession.SessionState? = null
    /** set for restored tabs that have not been opened yet */
    var pendingState: String? = null
    var pendingUrl: String? = null
    var parent: Tab? = null
}
