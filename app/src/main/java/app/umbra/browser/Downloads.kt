package app.umbra.browser

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import androidx.core.content.FileProvider
import org.mozilla.geckoview.WebResponse
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Saves files into the Downloads folder. */
object Downloads {
    private val pool = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    class Result(val name: String, val uri: Uri?, val mime: String, val error: String?)

    fun header(headers: Map<String, String>, name: String) =
        headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value

    /** A response the engine cannot show (a file): stream its body to Downloads. */
    fun save(c: Context, response: WebResponse, started: (String) -> Unit, done: (Result) -> Unit) {
        val disposition = header(response.headers, "Content-Disposition")
        val mime = header(response.headers, "Content-Type")?.substringBefore(';')?.trim()
        val name = URLUtil.guessFileName(response.uri, disposition, mime)
        val body = response.body ?: return done(Result(name, null, mime ?: "", "empty"))
        started(name)
        write(c, name, mime, body, done)
    }

    /** A plain link (for example "Save image"). */
    fun saveUrl(c: Context, url: String, started: (String) -> Unit, done: (Result) -> Unit) {
        val name = URLUtil.guessFileName(url, null, null)
        started(name)
        pool.execute {
            try {
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Umbra")
                val mime = conn.contentType?.substringBefore(';')
                val real = URLUtil.guessFileName(url, conn.getHeaderField("Content-Disposition"), mime)
                writeBlocking(c, real, mime, conn.inputStream, done)
                conn.disconnect()
            } catch (e: Exception) {
                main.post { done(Result(name, null, "", e.message ?: "error")) }
            }
        }
    }

    private fun write(c: Context, name: String, mime: String?, body: InputStream, done: (Result) -> Unit) {
        pool.execute { writeBlocking(c, name, mime, body, done) }
    }

    private fun writeBlocking(c: Context, name: String, mime0: String?, body: InputStream, done: (Result) -> Unit) {
        val mime = mime0?.takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
            ?: "application/octet-stream"
        try {
            val uri: Uri
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, mime)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = c.contentResolver
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("no storage")
                resolver.openOutputStream(uri)!!.use { out -> body.use { it.copyTo(out) } }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            } else {
                val dir = c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: error("no storage")
                var f = File(dir, name)
                var i = 1
                while (f.exists()) f = File(dir, name.substringBeforeLast('.') + " ($i)." + name.substringAfterLast('.', "bin")).also { i++ }
                f.outputStream().use { out -> body.use { it.copyTo(out) } }
                uri = FileProvider.getUriForFile(c, c.packageName + ".files", f)
            }
            main.post { done(Result(name, uri, mime, null)) }
        } catch (e: Exception) {
            main.post { done(Result(name, null, mime, e.message ?: "error")) }
        }
    }
}
