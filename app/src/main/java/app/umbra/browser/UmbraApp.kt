package app.umbra.browser

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import org.mozilla.geckoview.ContentBlocking
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class UmbraApp : Application() {

    /** One Firefox engine for the whole app. Created on first use, on the main thread. */
    val runtime: GeckoRuntime by lazy {
        val blocking = ContentBlocking.Settings.Builder()
            .antiTracking(ContentBlocking.AntiTracking.STRICT)
            .enhancedTrackingProtectionLevel(ContentBlocking.EtpLevel.STRICT)
            .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_FIRST_PARTY_AND_ISOLATE_OTHERS)
            .safeBrowsing(ContentBlocking.SafeBrowsing.DEFAULT)
            .build()
        val settings = GeckoRuntimeSettings.Builder()
            .contentBlocking(blocking)
            .preferredColorScheme(Prefs.geckoColorScheme(this))
            .aboutConfigEnabled(true)
            .consoleOutput(BuildConfig.DEBUG)
            .build()
        GeckoRuntime.create(this, settings)
    }

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
    }
}
