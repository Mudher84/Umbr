package app.umbra.browser

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.ContentDelegate
import org.mozilla.geckoview.GeckoSession.NavigationDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate
import org.mozilla.geckoview.GeckoSession.PermissionDelegate.ContentPermission
import org.mozilla.geckoview.GeckoSession.ProgressDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.StorageController
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebRequestError
import org.mozilla.geckoview.WebResponse

class MainActivity : AppCompatActivity() {

    private val runtime: GeckoRuntime get() = (application as UmbraApp).runtime
    private val tabs = ArrayList<Tab>()
    private var current: Tab? = null
    private var ublock: WebExtension? = null

    // views (rebuilt when light/dark changes)
    private lateinit var root: View
    private lateinit var topBar: LinearLayout
    private lateinit var content: FrameLayout
    private lateinit var geckoView: GeckoView
    private lateinit var tabScroll: HorizontalScrollView
    private lateinit var tabStrip: LinearLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnForward: ImageButton
    private lateinit var btnReload: ImageButton
    private lateinit var findBar: LinearLayout
    private lateinit var findText: EditText
    private lateinit var findCount: TextView
    private lateinit var notice: LinearLayout
    private lateinit var pill: FrameLayout
    private lateinit var url: EditText
    private lateinit var pillIcon: ImageView
    private lateinit var liquid: LiquidView
    private var night = false

    private val dp get() = resources.displayMetrics.density

    // file pickers and Android permissions, asked for by web pages
    private var filesCallback: ((List<Uri>) -> Unit)? = null
    private val pickOne = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        filesCallback?.invoke(listOfNotNull(uri)); filesCallback = null
    }
    private val pickMany = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        filesCallback?.invoke(uris); filesCallback = null
    }
    private var permsCallback: ((Boolean) -> Unit)? = null
    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        permsCallback?.invoke(res.values.all { it }); permsCallback = null
    }

    fun pickFiles(mime: String, multiple: Boolean, done: (List<Uri>) -> Unit) {
        filesCallback = done
        try {
            if (multiple) pickMany.launch(mime) else pickOne.launch(mime)
        } catch (_: ActivityNotFoundException) {
            filesCallback = null; done(emptyList())
        }
    }

    // ------------------------------------------------------------------ lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        buildUi()
        installUblock()
        restoreTabs()
        if (!handleIntent(intent) && tabs.isEmpty()) newTab(null, select = true)
        if (current == null) select(tabs.last())

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        saveTabs()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val nowNight = (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (nowNight != night) buildUi() else renderTabs()
    }

    private fun handleIntent(intent: Intent?): Boolean {
        val target = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra("query")?.let { Prefs.toUrl(this, it) }
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                Regex("https?://\\S+").find(text)?.value ?: Prefs.toUrl(this, text)
            }
            else -> null
        } ?: return false
        intent?.action = null
        newTab(target, select = true)
        return true
    }

    // ------------------------------------------------------------------ UI

    private fun buildUi() {
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        if (::geckoView.isInitialized) geckoView.releaseSession()
        setContentView(R.layout.activity_main)
        root = findViewById(R.id.root)
        topBar = findViewById(R.id.top_bar)
        content = findViewById(R.id.content)
        geckoView = findViewById(R.id.gecko)
        tabScroll = findViewById(R.id.tab_scroll)
        tabStrip = findViewById(R.id.tab_strip)
        btnBack = findViewById(R.id.btn_back)
        btnForward = findViewById(R.id.btn_forward)
        btnReload = findViewById(R.id.btn_reload)
        findBar = findViewById(R.id.find_bar)
        findText = findViewById(R.id.find_text)
        findCount = findViewById(R.id.find_count)
        notice = findViewById(R.id.notice)

        pill = LayoutInflater.from(this).inflate(R.layout.view_pill, tabStrip, false) as FrameLayout
        url = pill.findViewById(R.id.url)
        pillIcon = pill.findViewById(R.id.pill_icon)
        liquid = pill.findViewById(R.id.liquid)
        pill.findViewById<ImageButton>(R.id.pill_close).setOnClickListener { current?.let { closeTab(it) } }

        WindowInsetsControllerCompat(window, root).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets -> applyInsets(insets); insets }

        btnBack.setOnClickListener { current?.session?.goBack() }
        btnForward.setOnClickListener { current?.session?.goForward() }
        btnReload.setOnClickListener {
            val t = current ?: return@setOnClickListener
            if (t.loading) t.session.stop() else t.session.reload()
        }
        findViewById<ImageButton>(R.id.btn_menu).setOnClickListener { showMenu(it) }

        url.setOnFocusChangeListener { _, focused ->
            pill.setBackgroundResource(if (focused) R.drawable.pill_focus_bg else R.drawable.pill_bg)
            if (focused) {
                val u = current?.url
                url.setText(if (Prefs.isNewTab(u)) "" else u.orEmpty())
                url.selectAll()
            } else updatePill()
        }
        url.setOnEditorActionListener { _, action, event ->
            if (action == EditorInfo.IME_ACTION_GO || event?.keyCode == KeyEvent.KEYCODE_ENTER) {
                val target = Prefs.toUrl(this, url.text.toString())
                current?.session?.loadUri(target)
                url.clearFocus()
                hideKeyboard(url)
                true
            } else false
        }

        findText.setOnEditorActionListener { _, _, _ -> find(false); true }
        findViewById<ImageButton>(R.id.find_next).setOnClickListener { find(false) }
        findViewById<ImageButton>(R.id.find_prev).setOnClickListener { find(true) }
        findViewById<ImageButton>(R.id.find_close).setOnClickListener { closeFind() }

        current?.let { t ->
            geckoView.setSession(t.session)
            applyFullscreen(t.fullscreen)
        }
        renderTabs()
        ViewCompat.requestApplyInsets(root)
    }

    private fun applyInsets(insets: WindowInsetsCompat) {
        val full = current?.fullscreen == true
        val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
        val pad = (7 * dp).toInt()
        if (full) {
            root.setPadding(0, 0, 0, 0)
            content.setPadding(0, 0, 0, 0)
        } else {
            root.setPadding(bars.left, 0, bars.right, 0)
            topBar.setPadding(topBar.paddingLeft, bars.top + pad, topBar.paddingRight, pad)
            content.setPadding(0, 0, 0, maxOf(bars.bottom, ime.bottom))
        }
    }

    private fun applyFullscreen(full: Boolean) {
        val c = WindowInsetsControllerCompat(window, root)
        if (full) {
            topBar.visibility = View.GONE
            c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            topBar.visibility = View.VISIBLE
            c.show(WindowInsetsCompat.Type.systemBars())
        }
        ViewCompat.requestApplyInsets(root)
    }

    /** The tab row: every tab is a round icon bubble, except the open one, which is the address pill. */
    private fun renderTabs() {
        tabStrip.removeAllViews()
        val size = resources.getDimensionPixelSize(R.dimen.bubble)
        val gap = (3 * dp).toInt()
        val available = tabScroll.width
        val pillWidth = if (available > 0)
            (available * 0.66f).toInt().coerceIn(resources.getDimensionPixelSize(R.dimen.pill_min).coerceAtMost(available - size - gap * 4), resources.getDimensionPixelSize(R.dimen.pill_max))
        else resources.getDimensionPixelSize(R.dimen.pill_min)

        for (t in tabs) {
            if (t === current) {
                (pill.parent as? ViewGroup)?.removeView(pill)
                tabStrip.addView(pill, LinearLayout.LayoutParams(pillWidth, size).apply { setMargins(gap, 0, gap, 0) })
            } else {
                val b = ImageView(this)
                b.setBackgroundResource(if (t.private) R.drawable.bubble_private_bg else R.drawable.bubble_bg)
                val p = (7 * dp).toInt()
                b.setPadding(p, p, p, p)
                b.setImageDrawable(iconFor(t))
                b.contentDescription = t.title ?: t.url
                b.setOnClickListener { select(t) }
                b.setOnLongClickListener { tabMenu(it, t); true }
                tabStrip.addView(b, LinearLayout.LayoutParams(size, size).apply { setMargins(gap, 0, gap, 0) })
            }
        }
        val add = ImageButton(this)
        add.setBackgroundResource(R.drawable.bubble_bg)
        add.setImageResource(R.drawable.ic_add)
        val p = (9 * dp).toInt()
        add.setPadding(p, p, p, p)
        add.scaleType = ImageView.ScaleType.FIT_CENTER
        add.contentDescription = getString(R.string.new_tab)
        add.setOnClickListener { newTab(null, select = true, private = current?.private == true) }
        add.setOnLongClickListener { newTab(null, select = true, private = true); true }
        tabStrip.addView(add, LinearLayout.LayoutParams(size, size).apply { setMargins(gap, 0, gap, 0) })

        updatePill()
        updateNav()
        if (available == 0) tabScroll.post { if (tabScroll.width > 0) renderTabs() }
        else tabScroll.post {
            val target = pill.left - (tabScroll.width - pill.width) / 2
            tabScroll.smoothScrollTo(target.coerceAtLeast(0), 0)
        }
    }

    private fun iconFor(t: Tab): Drawable {
        if (Prefs.isNewTab(t.url) || t.url == null) return ContextCompat.getDrawable(this, R.drawable.umbra_logo)!!
        val bmp = t.icon ?: Favicons.cached(this, t.url)?.also { t.icon = it }
        return if (bmp != null) Favicons.round(this, bmp) else Favicons.letter(t.url)
    }

    private fun updatePill() {
        val t = current ?: return
        pillIcon.setImageDrawable(iconFor(t))
        if (!url.hasFocus()) url.setText(Prefs.display(t.url))
        pill.alpha = 1f
        liquid.setProgress(t.progress, t.loading)
    }

    private fun updateNav() {
        val t = current
        btnBack.isEnabled = t?.canGoBack == true
        btnBack.alpha = if (btnBack.isEnabled) 1f else 0.35f
        btnForward.isEnabled = t?.canGoForward == true
        btnForward.alpha = if (btnForward.isEnabled) 1f else 0.35f
        btnReload.setImageResource(if (t?.loading == true) R.drawable.ic_stop else R.drawable.ic_reload)
        btnReload.contentDescription = getString(if (t?.loading == true) R.string.stop else R.string.reload)
    }

    private fun hideKeyboard(v: View) {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(v.windowToken, 0)
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    private fun showNotice(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        notice.findViewById<TextView>(R.id.notice_text).text = text
        val a = notice.findViewById<TextView>(R.id.notice_action)
        a.visibility = if (action != null) View.VISIBLE else View.GONE
        a.text = action
        a.setOnClickListener { notice.visibility = View.GONE; onAction?.invoke() }
        notice.visibility = View.VISIBLE
        notice.removeCallbacks(hideNotice)
        notice.postDelayed(hideNotice, 6000)
    }

    private val hideNotice = Runnable { notice.visibility = View.GONE }

    // ------------------------------------------------------------------ menu

    private fun showMenu(anchor: View) {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.menu_panel_bg)
            val p = (6 * dp).toInt()
            setPadding(p, p, p, p)
        }
        val popup = PopupWindow(panel, (260 * dp).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 12 * dp
        fun row(text: String, checked: Boolean = false, action: () -> Unit) {
            val r = TextView(this)
            r.text = if (checked) "$text   ✓" else text
            r.textSize = 15f
            r.setTextColor(ContextCompat.getColor(this, R.color.umbra_text))
            r.setBackgroundResource(R.drawable.menu_row_bg)
            r.setPadding((16 * dp).toInt(), (11 * dp).toInt(), (16 * dp).toInt(), (11 * dp).toInt())
            r.setOnClickListener { popup.dismiss(); action() }
            panel.addView(r, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val t = current
        row(getString(R.string.new_tab)) { newTab(null, select = true) }
        row(getString(R.string.new_private_tab)) { newTab(null, select = true, private = true) }
        row(getString(R.string.find_in_page)) { openFind() }
        row(getString(R.string.desktop_site), t?.desktop == true) { t?.let { toggleDesktop(it) } }
        row(getString(R.string.share)) { t?.url?.takeUnless { Prefs.isNewTab(it) }?.let { shareText(it) } }
        row(getString(R.string.downloads)) {
            try { startActivity(Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { toast(getString(R.string.no_app)) }
        }
        row(getString(R.string.search_engine) + " · " + Prefs.engine(this).name) { chooseEngine() }
        row(getString(R.string.appearance)) { chooseAppearance() }
        if (ublock?.metaData?.optionsPageUrl != null) row(getString(R.string.ad_blocker)) {
            newTab(ublock?.metaData?.optionsPageUrl, select = true)
        }
        row(getString(R.string.clear_data)) { clearData() }
        row(getString(R.string.about)) { about() }
        popup.showAsDropDown(anchor, 0, (6 * dp).toInt(), Gravity.END)
    }

    private fun tabMenu(anchor: View, t: Tab) {
        val items = arrayOf(getString(R.string.close_tab), getString(R.string.close_other_tabs))
        AlertDialog.Builder(this).setTitle(t.title ?: Prefs.display(t.url)).setItems(items) { _, which ->
            when (which) {
                0 -> closeTab(t)
                1 -> tabs.filter { it !== t }.forEach { closeTab(it, render = false) }.also { select(t) }
            }
        }.show()
    }

    private fun chooseEngine() {
        val engines = Prefs.engines
        val selected = engines.indexOf(Prefs.engine(this))
        AlertDialog.Builder(this).setTitle(R.string.search_engine)
            .setSingleChoiceItems(engines.map { it.name }.toTypedArray(), selected) { d, which ->
                Prefs.setEngine(this, engines[which].id)
                d.dismiss()
            }.show()
    }

    private fun chooseAppearance() {
        val values = listOf("system", "light", "dark")
        val labels = arrayOf(getString(R.string.appearance_system), getString(R.string.appearance_light), getString(R.string.appearance_dark))
        AlertDialog.Builder(this).setTitle(R.string.appearance)
            .setSingleChoiceItems(labels, values.indexOf(Prefs.appearance(this))) { d, which ->
                d.dismiss()
                Prefs.setAppearance(this, values[which])
                runtime.settings.preferredColorScheme = Prefs.geckoColorScheme(this)
                AppCompatDelegate.setDefaultNightMode(Prefs.nightMode(this))
            }.show()
    }

    private fun clearData() {
        AlertDialog.Builder(this).setMessage(R.string.clear_data_question)
            .setPositiveButton(R.string.clear_data) { _, _ ->
                runtime.storageController.clearData(StorageController.ClearFlags.ALL)
                    .accept({ toast(getString(R.string.cleared)) }, { e -> Log.w(TAG, "clear", e) })
            }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun about() {
        val version = packageManager.getPackageInfo(packageName, 0).versionName
        AlertDialog.Builder(this).setIcon(R.mipmap.ic_launcher).setTitle("Umbra Browser")
            .setMessage(getString(R.string.about_text, version, org.mozilla.geckoview.BuildConfig.MOZILLA_VERSION))
            .setPositiveButton(R.string.ok, null).show()
    }

    private fun toggleDesktop(t: Tab) {
        t.desktop = !t.desktop
        t.session.settings.userAgentMode =
            if (t.desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE
        t.session.settings.viewportMode =
            if (t.desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE
        t.session.reload()
    }

    private fun shareText(text: String) {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
    }

    // ------------------------------------------------------------------ find in page

    private fun openFind() {
        findBar.visibility = View.VISIBLE
        findText.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(findText, 0)
    }

    private fun closeFind() {
        findBar.visibility = View.GONE
        hideKeyboard(findText)
        findText.setText("")
        findCount.text = ""
        current?.session?.finder?.clear()
    }

    private fun find(backwards: Boolean) {
        val s = current?.session ?: return
        val q = findText.text.toString()
        if (q.isEmpty()) return
        s.finder.displayFlags = GeckoSession.FINDER_DISPLAY_HIGHLIGHT_ALL
        s.finder.find(q, if (backwards) GeckoSession.FINDER_FIND_BACKWARDS else 0).accept({ r ->
            findCount.text = if (r == null || r.total == 0) "0/0" else "${r.current}/${r.total}"
        }, { _ -> })
    }

    // ------------------------------------------------------------------ tabs

    private fun newSession(private: Boolean): GeckoSession = GeckoSession(
        GeckoSessionSettings.Builder()
            .usePrivateMode(private)
            .useTrackingProtection(true)
            .build()
    )

    private fun newTab(target: String?, select: Boolean, private: Boolean = false, parent: Tab? = null, background: Boolean = false): Tab {
        val t = Tab(newSession(private), private)
        t.parent = parent
        t.pendingUrl = target ?: Prefs.newTabUrl(this)
        t.url = t.pendingUrl
        bind(t)
        val at = if (parent != null) tabs.indexOf(parent) + 1 + tabs.count { it.parent === parent } else tabs.size
        tabs.add(at.coerceIn(0, tabs.size), t)
        if (background) {
            ensureOpen(t)
            t.session.setActive(false)
        }
        if (select) select(t) else renderTabs()
        return t
    }

    private fun ensureOpen(t: Tab) {
        if (t.session.isOpen) return
        t.session.open(runtime)
        val state = t.pendingState?.let { GeckoSession.SessionState.fromString(it) }
        if (state != null) t.session.restoreState(state)
        else t.pendingUrl?.let { t.session.loadUri(it) }
        t.pendingState = null
        t.pendingUrl = null
    }

    private fun select(t: Tab) {
        if (!tabs.contains(t)) return
        val prev = current
        if (prev !== t) prev?.session?.setActive(false)
        current = t
        if (geckoView.session !== t.session) {
            geckoView.releaseSession()
            ensureOpen(t)
            geckoView.setSession(t.session)
        }
        t.session.setActive(true)
        if (url.hasFocus()) url.clearFocus()
        if (findBar.visibility == View.VISIBLE) closeFind()
        applyFullscreen(t.fullscreen)
        renderTabs()
        if (Prefs.isNewTab(t.url) && t.parent == null && t.state == null) {
            // fresh tab: put the cursor in the address pill like a desktop new tab
            url.post { url.requestFocus() }
        }
    }

    private fun closeTab(t: Tab, render: Boolean = true) {
        val i = tabs.indexOf(t)
        if (i < 0) return
        tabs.removeAt(i)
        tabs.forEach { if (it.parent === t) it.parent = t.parent }
        if (t === current) {
            current = null
            geckoView.releaseSession()
        }
        t.session.close()
        if (!render) return
        if (tabs.isEmpty()) newTab(null, select = true)
        else if (current == null) select(t.parent?.takeIf { tabs.contains(it) } ?: tabs[(i - 1).coerceAtLeast(0)])
        else renderTabs()
    }

    private fun goBack() {
        val t = current ?: return finishSoft()
        when {
            t.fullscreen -> t.session.exitFullScreen()
            url.hasFocus() -> url.clearFocus()
            findBar.visibility == View.VISIBLE -> closeFind()
            t.canGoBack -> t.session.goBack()
            t.parent != null && tabs.contains(t.parent) -> closeTab(t)
            else -> finishSoft()
        }
    }

    private fun finishSoft() {
        saveTabs()
        moveTaskToBack(true)
    }

    private fun saveTabs() {
        val arr = JSONArray()
        var selected = 0
        tabs.filter { !it.private }.forEachIndexed { i, t ->
            if (t === current) selected = i
            arr.put(JSONObject().apply {
                put("url", t.url ?: t.pendingUrl)
                put("title", t.title)
                (t.state?.toString() ?: t.pendingState)?.let { put("state", it) }
            })
        }
        Prefs.saveTabs(this, JSONObject().put("selected", selected).put("tabs", arr).toString())
    }

    private fun restoreTabs() {
        if (tabs.isNotEmpty()) return
        val saved = Prefs.savedTabs(this) ?: return
        try {
            val o = JSONObject(saved)
            val arr = o.getJSONArray("tabs")
            for (i in 0 until arr.length()) {
                val j = arr.getJSONObject(i)
                val t = Tab(newSession(false), false)
                t.url = j.optString("url").takeIf { it.isNotEmpty() }
                t.title = j.optString("title").takeIf { it.isNotEmpty() && it != "null" }
                t.pendingUrl = t.url ?: Prefs.newTabUrl(this)
                t.pendingState = j.optString("state").takeIf { it.isNotEmpty() }
                bind(t)
                tabs.add(t)
            }
            if (tabs.isNotEmpty()) select(tabs[o.optInt("selected").coerceIn(0, tabs.size - 1)])
        } catch (e: Exception) {
            Log.w(TAG, "restore", e)
        }
    }

    private fun recreateSession(t: Tab) {
        // the page's process died: give the tab a fresh session where it left off
        t.pendingState = t.state?.toString()
        t.pendingUrl = t.url
        t.session = newSession(t.private)
        bind(t)
        if (t === current) {
            geckoView.releaseSession()
            ensureOpen(t)
            geckoView.setSession(t.session)
        }
    }

    // ------------------------------------------------------------------ engine delegates

    private fun bind(t: Tab) {
        val s = t.session
        s.progressDelegate = object : ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                t.loading = true
                t.progress = 8
                t.url = url
                if (t === current) { updatePill(); updateNav() }
            }

            override fun onProgressChange(session: GeckoSession, progress: Int) {
                t.progress = progress
                if (t === current) liquid.setProgress(progress, t.loading)
            }

            override fun onPageStop(session: GeckoSession, success: Boolean) {
                t.loading = false
                t.progress = 100
                if (t === current) { updatePill(); updateNav() }
                if (success) Favicons.fetch(this@MainActivity, t.url, private = t.private) { bmp ->
                    if (Favicons.host(t.url) != null) { t.icon = bmp; renderTabs() }
                }
            }

            override fun onSessionStateChange(session: GeckoSession, sessionState: GeckoSession.SessionState) {
                t.state = sessionState
            }
        }

        s.navigationDelegate = object : NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: List<ContentPermission>, hasUserGesture: Boolean) {
                if (Favicons.host(url) != Favicons.host(t.url)) t.icon = null
                t.url = url
                if (t === current) renderTabs()
            }

            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) {
                t.canGoBack = canGoBack
                if (t === current) updateNav()
            }

            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) {
                t.canGoForward = canGoForward
                if (t === current) updateNav()
            }

            override fun onLoadRequest(session: GeckoSession, request: NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny>? {
                val scheme = Uri.parse(request.uri).scheme?.lowercase() ?: return null
                if (scheme in WEB_SCHEMES) return null
                openExternal(request.uri)
                return GeckoResult.fromValue(AllowOrDeny.DENY)
            }

            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession>? {
                // a page opened a window: it becomes a new tab next to this one
                val child = Tab(newSession(t.private), t.private)
                child.parent = t
                child.url = uri
                bind(child)
                tabs.add(tabs.indexOf(t) + 1, child)
                root.post { waitAndSelect(child, 0) }
                return GeckoResult.fromValue(child.session)
            }

            override fun onLoadError(session: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                val page = "resource://android/assets/newtab/error.html?code=${error.code}&category=${error.category}&url=" + Uri.encode(uri ?: "")
                return GeckoResult.fromValue(page)
            }
        }

        s.contentDelegate = object : ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                t.title = title
            }

            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                t.fullscreen = fullScreen
                if (t === current) applyFullscreen(fullScreen)
            }

            override fun onCloseRequest(session: GeckoSession) = closeTab(t)

            override fun onExternalResponse(session: GeckoSession, response: WebResponse) {
                Downloads.save(this@MainActivity, response,
                    started = { name -> showNotice(getString(R.string.downloading, name)) },
                    done = ::downloadDone)
            }

            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: ContentDelegate.ContextElement) {
                contextMenu(t, element)
            }

            override fun onWebAppManifest(session: GeckoSession, manifest: JSONObject) {
                val icons = manifest.optJSONArray("icons") ?: return
                var best: String? = null
                var bestSize = 0
                for (i in 0 until icons.length()) {
                    val ic = icons.optJSONObject(i) ?: continue
                    val size = ic.optString("sizes").split(' ', 'x', 'X').firstOrNull()?.toIntOrNull() ?: 0
                    if (size in (bestSize + 1)..512) { bestSize = size; best = ic.optString("src") }
                }
                if (best != null && best.startsWith("http")) Favicons.fetch(this@MainActivity, t.url, best, t.private) { bmp ->
                    t.icon = bmp; renderTabs()
                }
            }

            override fun onCrash(session: GeckoSession) {
                if (t === current) toast(getString(R.string.page_crashed))
                recreateSession(t)
            }

            override fun onKill(session: GeckoSession) = recreateSession(t)
        }

        s.promptDelegate = Prompts(this)
        s.permissionDelegate = permissions
    }

    private fun waitAndSelect(t: Tab, tries: Int) {
        if (!tabs.contains(t)) return
        if (t.session.isOpen || tries > 40) select(t)
        else root.postDelayed({ waitAndSelect(t, tries + 1) }, 25)
    }

    private fun downloadDone(r: Downloads.Result) {
        if (r.error != null || r.uri == null) {
            showNotice(getString(R.string.download_failed, r.name))
            return
        }
        showNotice(getString(R.string.downloaded, r.name), getString(R.string.open)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(r.uri, r.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            } catch (_: ActivityNotFoundException) { toast(getString(R.string.no_app)) }
        }
    }

    private fun contextMenu(t: Tab, e: ContentDelegate.ContextElement) {
        val link = e.linkUri
        val src = e.srcUri
        val image = e.type == ContentDelegate.ContextElement.TYPE_IMAGE && src != null
        if (link == null && !image) return
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (link != null) {
            items += getString(R.string.open_in_new_tab) to { newTab(link, select = true, private = t.private, parent = t) }
            items += getString(R.string.open_in_background) to { newTab(link, select = false, private = t.private, parent = t, background = true) }
            if (!t.private) items += getString(R.string.open_in_private_tab) to { newTab(link, select = true, private = true) }
            items += getString(R.string.copy_link) to { copy(link) }
            items += getString(R.string.share_link) to { shareText(link) }
        }
        if (image && src != null) {
            items += getString(R.string.open_image) to { newTab(src, select = true, private = t.private, parent = t) }
            if (src.startsWith("http")) items += getString(R.string.save_image) to {
                Downloads.saveUrl(this, src, { name -> showNotice(getString(R.string.downloading, name)) }, ::downloadDone)
            }
        }
        AlertDialog.Builder(this).setTitle(link ?: e.title ?: src)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("link", text))
        toast(getString(R.string.copied))
    }

    private fun openExternal(uri: String) {
        try {
            val intent = if (uri.startsWith("intent:")) Intent.parseUri(uri, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            intent.component = null
            intent.selector = null
            try {
                startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                val fallback = intent.getStringExtra("browser_fallback_url")
                if (fallback != null && fallback.startsWith("http")) current?.session?.loadUri(fallback)
                else toast(getString(R.string.no_app))
            }
        } catch (e: Exception) {
            Log.w(TAG, "external", e)
        }
    }

    // ------------------------------------------------------------------ site permissions

    private val permissions = object : PermissionDelegate {
        override fun onContentPermissionRequest(session: GeckoSession, perm: ContentPermission): GeckoResult<Int>? {
            val host = Favicons.host(perm.uri) ?: perm.uri
            val question = when (perm.permission) {
                PermissionDelegate.PERMISSION_GEOLOCATION -> getString(R.string.perm_location, host)
                PermissionDelegate.PERMISSION_DESKTOP_NOTIFICATION -> return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
                PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE -> return GeckoResult.fromValue(ContentPermission.VALUE_ALLOW)
                PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE -> return GeckoResult.fromValue(ContentPermission.VALUE_DENY)
                PermissionDelegate.PERMISSION_TRACKING -> return null
                else -> getString(R.string.perm_generic, host)
            }
            val result = GeckoResult<Int>()
            ask(question) { ok -> result.complete(if (ok) ContentPermission.VALUE_ALLOW else ContentPermission.VALUE_DENY) }
            return result
        }

        override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: PermissionDelegate.Callback) {
            val missing = permissions.orEmpty().filter {
                ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missing.isEmpty()) return callback.grant()
            permsCallback = { ok -> if (ok) callback.grant() else callback.reject() }
            askPerms.launch(missing.toTypedArray())
        }

        override fun onMediaPermissionRequest(
            session: GeckoSession, uri: String,
            video: Array<out PermissionDelegate.MediaSource>?, audio: Array<out PermissionDelegate.MediaSource>?,
            callback: PermissionDelegate.MediaCallback
        ) {
            ask(getString(R.string.perm_camera_mic, Favicons.host(uri) ?: uri)) { ok ->
                if (!ok) return@ask callback.reject()
                val needed = ArrayList<String>()
                if (video != null) needed += Manifest.permission.CAMERA
                if (audio != null) needed += Manifest.permission.RECORD_AUDIO
                val v: PermissionDelegate.MediaSource? = video?.firstOrNull()
                val a: PermissionDelegate.MediaSource? = audio?.firstOrNull()
                val missing = needed.filter { ContextCompat.checkSelfPermission(this@MainActivity, it) != PackageManager.PERMISSION_GRANTED }
                if (missing.isEmpty()) callback.grant(v, a)
                else {
                    permsCallback = { granted -> if (granted) callback.grant(v, a) else callback.reject() }
                    askPerms.launch(missing.toTypedArray())
                }
            }
        }
    }

    private fun ask(question: String, done: (Boolean) -> Unit) {
        var answered = false
        AlertDialog.Builder(this).setMessage(question)
            .setPositiveButton(R.string.allow) { _, _ -> answered = true; done(true) }
            .setNegativeButton(R.string.deny) { _, _ -> answered = true; done(false) }
            .setOnDismissListener { if (!answered) done(false) }
            .show()
    }

    // ------------------------------------------------------------------ uBlock Origin

    private fun installUblock() {
        val controller = runtime.webExtensionController
        controller.ensureBuiltIn(UBLOCK_URI, UBLOCK_ID).accept({ ext ->
            if (ext == null) return@accept
            ublock = ext
            if (!ext.metaData.allowedInPrivateBrowsing) controller.setAllowedInPrivateBrowsing(ext, true)
        }, { e -> Log.w(TAG, "uBlock Origin was not installed", e) })
    }

    companion object {
        private const val TAG = "Umbra"
        private const val UBLOCK_ID = "uBlock0@raymondhill.net"
        private const val UBLOCK_URI = "resource://android/assets/extensions/ublock/"
        private val WEB_SCHEMES = setOf("http", "https", "about", "resource", "moz-extension", "data", "blob", "file", "javascript", "view-source")
    }
}
