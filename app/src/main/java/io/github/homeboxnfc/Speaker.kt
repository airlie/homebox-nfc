package io.github.homeboxnfc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import io.github.homeboxnfc.data.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Screens that can read names aloud, each switched on or off separately. */
enum class SpeakMode { BATCH, INVENTORY, READ }

/**
 * Reads entry names aloud, shortened to the part that helps you find the thing
 * (see [SpokenName]). Uses the phone's text-to-speech engine, started the first time it's needed.
 */
class Speaker(private val context: Context, private val prefs: Prefs) {
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ready = false
    private var pending: String? = null

    private val _enabled = MutableStateFlow(
        buildSet {
            if (prefs.speakBatch) add(SpeakMode.BATCH)
            if (prefs.speakInventory) add(SpeakMode.INVENTORY)
            if (prefs.speakRead) add(SpeakMode.READ)
        }
    )
    val enabled: StateFlow<Set<SpeakMode>> = _enabled.asStateFlow()

    fun isOn(mode: SpeakMode) = mode in _enabled.value

    fun setOn(mode: SpeakMode, on: Boolean) {
        when (mode) {
            SpeakMode.BATCH -> prefs.speakBatch = on
            SpeakMode.INVENTORY -> prefs.speakInventory = on
            SpeakMode.READ -> prefs.speakRead = on
        }
        _enabled.value = if (on) _enabled.value + mode else _enabled.value - mode
        if (on) main.post { engine() }
    }

    /** Starts the speech engine early if [mode] will need it, so the first name isn't delayed. */
    fun prepare(mode: SpeakMode) {
        if (isOn(mode)) main.post { engine() }
    }

    /** The shortened form of [name] that would be spoken. */
    fun shorten(name: String): String = SpokenName.shorten(
        name,
        stopAtSymbol = prefs.speakStopAtSymbol,
        skipWords = prefs.speakSkipWords.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        maxChars = prefs.speakMaxChars,
    )

    /** Speaks the shortened [name] if [mode] has reading aloud on. Safe from any thread. */
    fun speak(mode: SpeakMode, name: String) {
        if (isOn(mode)) say(shorten(name))
    }

    /** Speaks [text] as is if [mode] has reading aloud on. */
    fun speakPhrase(mode: SpeakMode, text: String) {
        if (isOn(mode)) say(text)
    }

    /** Speaks [text] now, cutting off anything still being said. */
    fun say(text: String) {
        if (text.isBlank()) return
        main.post {
            val engine = engine()
            if (ready) engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "name") else pending = text
        }
    }

    private fun engine(): TextToSpeech = tts ?: TextToSpeech(context.applicationContext) { status ->
        ready = status == TextToSpeech.SUCCESS
        pending?.takeIf { ready }?.let { tts?.speak(it, TextToSpeech.QUEUE_FLUSH, null, "name") }
        pending = null
    }.also { tts = it }
}

/**
 * Turns a full entry name into a short spoken cue: "Box 12 - Christmas lights" becomes "12".
 * Leading filler words are skipped, then the name is cut at the first symbol, then trimmed
 * to whole words within [maxChars]. Never returns an empty string for a non-blank name.
 */
object SpokenName {
    fun shorten(name: String, stopAtSymbol: Boolean, skipWords: List<String>, maxChars: Int): String {
        val skip = skipWords.map { it.lowercase() }.toSet()
        fun core(word: String) = word.filter { it.isLetterOrDigit() }.lowercase()
        val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return ""

        // Skip filler words at the start, but always keep at least one word.
        var start = 0
        while (start < words.size - 1 && core(words[start]) in skip) start++
        var text = words.drop(start).joinToString(" ").trimStart { !it.isLetterOrDigit() }

        if (stopAtSymbol) {
            // Apostrophes inside a word ("Dad's") don't count as symbols.
            val cut = text.indices.firstOrNull { i ->
                val c = text[i]
                val inWordApostrophe = (c == '\'' || c == '’') && i > 0 && i < text.length - 1 &&
                    text[i - 1].isLetter() && text[i + 1].isLetter()
                !(c.isLetterOrDigit() || c == ' ' || inWordApostrophe)
            }
            if (cut != null) text = text.substring(0, cut)
        }
        text = text.trim()

        if (maxChars > 0 && text.length > maxChars) {
            val out = StringBuilder()
            for (w in text.split(' ').filter { it.isNotEmpty() }) {
                if (out.isNotEmpty() && out.length + 1 + w.length > maxChars) break
                if (out.isNotEmpty()) out.append(' ')
                out.append(w) // a single word longer than the limit is still read whole
            }
            text = out.toString()
        }
        return text.ifBlank { words.first() }
    }
}
