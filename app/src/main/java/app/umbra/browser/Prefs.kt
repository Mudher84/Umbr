package app.umbra.browser

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import org.mozilla.geckoview.GeckoRuntimeSettings

object Prefs {
    const val NEW_TAB = "resource://android/assets/newtab/index.html"

    class Engine(val id: String, val name: String, val template: String)

    val engines = listOf(
        Engine("google", "Google", "https://www.google.com/search?q=%s"),
        Engine("duckduckgo", "DuckDuckGo", "https://duckduckgo.com/?q=%s"),
        Engine("bing", "Bing", "https://www.bing.com/search?q=%s"),
        Engine("brave", "Brave Search", "https://search.brave.com/search?q=%s"),
        Engine("startpage", "Startpage", "https://www.startpage.com/do/search?q=%s"),
    )

    private fun prefs(c: Context) = c.getSharedPreferences("umbra", Context.MODE_PRIVATE)

    fun engine(c: Context): Engine {
        val id = prefs(c).getString("engine", "google")
        return engines.firstOrNull { it.id == id } ?: engines[0]
    }

    fun setEngine(c: Context, id: String) = prefs(c).edit().putString("engine", id).apply()

    /** "system", "light" or "dark" */
    fun appearance(c: Context): String = prefs(c).getString("appearance", "system") ?: "system"

    fun setAppearance(c: Context, value: String) = prefs(c).edit().putString("appearance", value).apply()

    fun nightMode(c: Context) = when (appearance(c)) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun geckoColorScheme(c: Context) = when (appearance(c)) {
        "light" -> GeckoRuntimeSettings.COLOR_SCHEME_LIGHT
        "dark" -> GeckoRuntimeSettings.COLOR_SCHEME_DARK
        else -> GeckoRuntimeSettings.COLOR_SCHEME_SYSTEM
    }

    fun newTabUrl(c: Context) = "$NEW_TAB?engine=${engine(c).id}"

    fun isNewTab(url: String?) = url != null && url.startsWith(NEW_TAB)

    fun savedTabs(c: Context): String? = prefs(c).getString("tabs", null)

    fun saveTabs(c: Context, json: String) = prefs(c).edit().putString("tabs", json).apply()

    /** Turns what the user typed into a URL: an address as is, anything else as a search. */
    fun toUrl(c: Context, typed: String): String {
        val t = typed.trim()
        if (t.isEmpty()) return newTabUrl(c)
        val hasScheme = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(t) &&
            !Regex("^[^/\\s]+:\\d+").matches(t.substringBefore('/'))
        if (hasScheme && !t.contains(' ')) return t
        val hostLike = !t.contains(' ') && (
            Regex("^[^\\s/]+\\.[a-zA-Z]{2,}(:\\d+)?(/.*)?$").matches(t) ||
                Regex("^localhost(:\\d+)?(/.*)?$").matches(t) ||
                Regex("^\\d{1,3}(\\.\\d{1,3}){3}(:\\d+)?(/.*)?$").matches(t)
            )
        if (hostLike) return "https://$t"
        return engine(c).template.replace("%s", android.net.Uri.encode(t))
    }

    /** What the address pill shows when you are not typing. */
    fun display(url: String?): String {
        if (url == null || isNewTab(url)) return ""
        return url.removePrefix("https://").removePrefix("http://").removePrefix("www.").removeSuffix("/")
    }
}
