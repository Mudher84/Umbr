package app.umbra.browser

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Round site icons for the tab bubbles, fetched from the site itself and cached by host. */
object Favicons {
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())
    private val memory = HashMap<String, Bitmap>()
    private val inFlight = HashSet<String>()

    fun host(url: String?): String? = try {
        Uri.parse(url ?: return null).host?.removePrefix("www.")
    } catch (_: Exception) { null }

    private fun file(c: Context, host: String) = File(File(c.cacheDir, "favicons").apply { mkdirs() }, "$host.png")

    fun cached(c: Context, url: String?): Bitmap? {
        val h = host(url) ?: return null
        memory[h]?.let { return it }
        val f = file(c, h)
        if (!f.exists()) return null
        return BitmapFactory.decodeFile(f.path)?.also { memory[h] = it }
    }

    /** Fetches an icon for [pageUrl] (or [iconUrl] when the page told us one) and calls back on the main thread. */
    fun fetch(c: Context, pageUrl: String?, iconUrl: String? = null, private: Boolean = false, done: (Bitmap) -> Unit) {
        val h = host(pageUrl) ?: return
        val scheme = Uri.parse(pageUrl).scheme
        if (scheme != "http" && scheme != "https") return
        if (iconUrl == null && memory.containsKey(h)) return
        synchronized(inFlight) { if (!inFlight.add(h)) return }
        val app = c.applicationContext
        pool.execute {
            try {
                val tries = listOfNotNull(iconUrl, "$scheme://${Uri.parse(pageUrl).host}/apple-touch-icon.png", "$scheme://${Uri.parse(pageUrl).host}/favicon.ico")
                for (u in tries) {
                    val bmp = download(u) ?: continue
                    memory[h] = bmp
                    if (!private) file(app, h).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    main.post { done(bmp) }
                    break
                }
            } finally {
                synchronized(inFlight) { inFlight.remove(h) }
            }
        }
    }

    private fun download(u: String): Bitmap? = try {
        val conn = URL(u).openConnection() as HttpURLConnection
        conn.connectTimeout = 6000
        conn.readTimeout = 6000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Umbra")
        try {
            if (conn.responseCode != 200) null
            else conn.inputStream.use { BitmapFactory.decodeStream(it) }?.takeIf { it.width >= 16 }
        } finally { conn.disconnect() }
    } catch (_: Exception) { null }

    fun round(c: Context, bmp: Bitmap): Drawable =
        RoundedBitmapDrawableFactory.create(c.resources, bmp).apply { isCircular = true; setAntiAlias(true) }

    /** A violet bubble with the site's first letter, used until (or when no) icon is found. */
    fun letter(url: String?): Drawable = LetterIcon((host(url)?.firstOrNull { it.isLetterOrDigit() } ?: '•').uppercaseChar().toString())

    private class LetterIcon(val text: String) : Drawable() {
        private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }

        override fun draw(canvas: Canvas) {
            val b = bounds
            val r = b.width().coerceAtMost(b.height()) / 2f
            bg.shader = LinearGradient(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), b.bottom.toFloat(),
                Color.rgb(99, 102, 241), Color.rgb(168, 85, 247), Shader.TileMode.CLAMP)
            canvas.drawCircle(b.exactCenterX(), b.exactCenterY(), r, bg)
            fg.textSize = r * 1.05f
            canvas.drawText(text, b.exactCenterX(), b.exactCenterY() - (fg.descent() + fg.ascent()) / 2, fg)
        }

        override fun setAlpha(alpha: Int) { bg.alpha = alpha; fg.alpha = alpha }
        override fun setColorFilter(colorFilter: ColorFilter?) { bg.colorFilter = colorFilter }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
