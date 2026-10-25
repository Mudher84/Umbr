package app.umbra.browser

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.net.Uri
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSession.PromptDelegate.PromptResponse
import java.util.Calendar

/** Page dialogs: alert, confirm, prompt, <select>, file pickers, date and time inputs. */
class Prompts(private val activity: MainActivity) : PromptDelegate {

    private fun dialog() = AlertDialog.Builder(activity)

    override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        dialog().setTitle(prompt.title).setMessage(prompt.message)
            .setPositiveButton(R.string.ok, null)
            .setOnDismissListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        dialog().setTitle(prompt.title).setMessage(prompt.message)
            .setPositiveButton(R.string.ok) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)) }
            .setNegativeButton(R.string.cancel) { _, _ -> result.complete(prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val input = field(prompt.defaultValue)
        dialog().setTitle(prompt.title).setMessage(prompt.message).setView(wrap(input))
            .setPositiveButton(R.string.ok) { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
            .setNegativeButton(R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
            .setOnCancelListener { result.complete(prompt.dismiss()) }
            .show()
        return result
    }

    override fun onBeforeUnloadPrompt(session: GeckoSession, prompt: PromptDelegate.BeforeUnloadPrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        dialog().setMessage(R.string.leave_page)
            .setPositiveButton(R.string.leave) { _, _ -> result.complete(prompt.confirm(AllowOrDeny.ALLOW)) }
            .setNegativeButton(R.string.stay) { _, _ -> result.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .setOnCancelListener { result.complete(prompt.confirm(AllowOrDeny.DENY)) }
            .show()
        return result
    }

    override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        var answered = false
        val flat = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
        fun add(list: Array<PromptDelegate.ChoicePrompt.Choice>?, depth: Int) {
            list?.forEach { ch ->
                if (ch.separator) return@forEach
                if (ch.items != null) { flat.add(ch); add(ch.items, depth + 1) } else flat.add(ch)
            }
        }
        add(prompt.choices, 0)
        val labels = flat.map { if (it.items != null) "— ${it.label} —" else it.label }.toTypedArray()
        val b = dialog().setTitle(prompt.title)
        if (prompt.type == PromptDelegate.ChoicePrompt.Type.MULTIPLE) {
            val checked = BooleanArray(flat.size) { flat[it].selected }
            b.setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                .setPositiveButton(R.string.ok) { _, _ ->
                    val ids = flat.indices.filter { checked[it] && flat[it].items == null }.map { flat[it].id }
                    answered = true
                    result.complete(prompt.confirm(ids.toTypedArray()))
                }
        } else {
            val selected = flat.indexOfFirst { it.selected }
            b.setSingleChoiceItems(labels, selected) { d, which ->
                val ch = flat[which]
                if (ch.items != null || ch.disabled) return@setSingleChoiceItems
                answered = true
                result.complete(prompt.confirm(ch))
                d.dismiss()
            }
        }
        val shown = b.create()
        shown.setOnDismissListener { if (!answered) { answered = true; result.complete(prompt.dismiss()) } }
        shown.show()
        return result
    }

    override fun onFilePrompt(session: GeckoSession, prompt: PromptDelegate.FilePrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val mime = prompt.mimeTypes?.firstOrNull { it.contains('/') } ?: "*/*"
        val multiple = prompt.type == PromptDelegate.FilePrompt.Type.MULTIPLE
        activity.pickFiles(mime, multiple) { uris: List<Uri> ->
            when {
                uris.isEmpty() -> result.complete(prompt.dismiss())
                multiple -> result.complete(prompt.confirm(activity, uris.toTypedArray()))
                else -> result.complete(prompt.confirm(activity, uris[0]))
            }
        }
        return result
    }

    override fun onDateTimePrompt(session: GeckoSession, prompt: PromptDelegate.DateTimePrompt): GeckoResult<PromptResponse> {
        val result = GeckoResult<PromptResponse>()
        val now = Calendar.getInstance()
        val value = prompt.defaultValue.orEmpty()
        when (prompt.type) {
            PromptDelegate.DateTimePrompt.Type.DATE -> {
                val p = value.split('-').mapNotNull { it.toIntOrNull() }
                val d = DatePickerDialog(activity, { _, y, m, day ->
                    result.complete(prompt.confirm("%04d-%02d-%02d".format(y, m + 1, day)))
                }, p.getOrNull(0) ?: now.get(Calendar.YEAR), (p.getOrNull(1) ?: (now.get(Calendar.MONTH) + 1)) - 1, p.getOrNull(2) ?: now.get(Calendar.DAY_OF_MONTH))
                d.setOnCancelListener { result.complete(prompt.dismiss()) }
                d.show()
            }
            PromptDelegate.DateTimePrompt.Type.TIME -> {
                val p = value.split(':').mapNotNull { it.toIntOrNull() }
                val d = TimePickerDialog(activity, { _, h, m ->
                    result.complete(prompt.confirm("%02d:%02d".format(h, m)))
                }, p.getOrNull(0) ?: now.get(Calendar.HOUR_OF_DAY), p.getOrNull(1) ?: now.get(Calendar.MINUTE), true)
                d.setOnCancelListener { result.complete(prompt.dismiss()) }
                d.show()
            }
            else -> {
                val input = field(value)
                dialog().setTitle(prompt.title).setView(wrap(input))
                    .setPositiveButton(R.string.ok) { _, _ -> result.complete(prompt.confirm(input.text.toString())) }
                    .setNegativeButton(R.string.cancel) { _, _ -> result.complete(prompt.dismiss()) }
                    .setOnCancelListener { result.complete(prompt.dismiss()) }
                    .show()
            }
        }
        return result
    }

    private fun field(value: String?) = EditText(activity).apply {
        setText(value.orEmpty())
        inputType = InputType.TYPE_CLASS_TEXT
        setSelectAllOnFocus(true)
    }

    private fun wrap(v: EditText) = FrameLayout(activity).apply {
        val pad = (20 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 2, pad, 0)
        addView(v)
    }
}
