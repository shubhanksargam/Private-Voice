package dev.privatevoice.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Saved phrases ("my address is...", a UPI ID, a phone number) — a small
 * directory of reusable text, addable/editable/deletable from either the
 * keyboard's own phrasebook page or [PhrasebookActivity], voice-recorded or
 * typed either way. Stores only text, never audio — there's no reason to
 * keep a recording around once it's been turned into text, and every byte
 * not kept is one less thing to have to explain isn't leaving the device.
 *
 * Plain SharedPreferences + a JSON array, same tier of storage as
 * [KeyboardSettings] — this is a handful of short strings, not a dataset
 * that needs a real database.
 */
object PhrasebookStore {

    data class Phrase(val id: String, val label: String, val text: String)

    private const val PREFS = "phrasebook"
    private const val KEY_ITEMS = "items"
    private const val LABEL_MAX_CHARS = 18

    /** Most recently added first. */
    fun list(context: Context): List<Phrase> {
        val raw = prefs(context).getString(KEY_ITEMS, null) ?: return emptyList()
        val arr = JSONArray(raw)
        return (arr.length() - 1 downTo 0).map { i ->
            val o = arr.getJSONObject(i)
            Phrase(o.getString("id"), o.getString("label"), o.getString("text"))
        }
    }

    fun add(context: Context, text: String): Phrase {
        val trimmed = text.trim()
        val label = if (trimmed.length <= LABEL_MAX_CHARS) trimmed else trimmed.take(LABEL_MAX_CHARS - 1) + "…"
        val phrase = Phrase(UUID.randomUUID().toString(), label, trimmed)
        val arr = JSONArray(prefs(context).getString(KEY_ITEMS, null) ?: "[]")
        arr.put(
            JSONObject().apply {
                put("id", phrase.id)
                put("label", phrase.label)
                put("text", phrase.text)
            },
        )
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
        return phrase
    }

    fun remove(context: Context, id: String) {
        val arr = JSONArray(prefs(context).getString(KEY_ITEMS, null) ?: "[]")
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") != id) kept.put(o)
        }
        prefs(context).edit().putString(KEY_ITEMS, kept.toString()).apply()
    }

    /** In place — same [Phrase.id], new text/label, same position in the list. */
    fun update(context: Context, id: String, text: String): Phrase? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val label = if (trimmed.length <= LABEL_MAX_CHARS) trimmed else trimmed.take(LABEL_MAX_CHARS - 1) + "…"
        val arr = JSONArray(prefs(context).getString(KEY_ITEMS, null) ?: "[]")
        var updated: Phrase? = null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") == id) {
                o.put("label", label)
                o.put("text", trimmed)
                updated = Phrase(id, label, trimmed)
            }
        }
        prefs(context).edit().putString(KEY_ITEMS, arr.toString()).apply()
        return updated
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
