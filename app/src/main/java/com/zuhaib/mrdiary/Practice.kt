package com.zuhaib.mrdiary

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.text.SpannableString
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.util.Locale

/**
 * Detailing practice: script ke lafz ek ek karke aahista roshan hote hain (pacer),
 * saath mein timer aur recording. Maqsad: jaldi jaldi bolne ki aadat todna.
 */
class PracticeActivity : BaseActivity() {
    private var bid = 0L
    private var wpm = 110
    private var words: List<String> = emptyList()
    private var pos = -1
    private var running = false
    private var withRec = false
    private var startAt = 0L
    private var result = ""
    private var resultColor = C.TEXT

    private var recorder: MediaRecorder? = null
    private var player: MediaPlayer? = null
    private val h = Handler(Looper.getMainLooper())

    // ---- saaf awaaz mein sunna (phone ki Google awaaz) ----
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var speaking = false
    private var starts: List<Int> = emptyList()      // har lafz kahan se shuru hota hai
    private val VOICES = listOf("hi" to "Roman Urdu", "en" to "English", "ur" to "اردو")

    private var scriptTv: TextView? = null
    private var timeTv: TextView? = null

    companion object {
        const val RC_MIC = 89
        const val TARGET_SEC = 20
    }

    private val step = object : Runnable {
        override fun run() {
            if (!running) return
            pos++
            paint()
            if (pos >= words.size) {
                // Pacer khatam. Recording ho rahi ho to khud Roko dabao (shayad abhi bol rahe ho)
                if (!withRec) stop()
                return
            }
            val w = words[pos]
            val base = 60_000L / wpm
            val mult = when {
                w.endsWith(".") || w.endsWith("?") || w.endsWith("!") || w.endsWith("۔") -> 2.2f   // jumle ke baad saans lo
                w.endsWith(",") || w.endsWith("،") || w.endsWith(":") -> 1.5f
                else -> 1f
            }
            h.postDelayed(this, (base * mult).toLong())
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val ms = System.currentTimeMillis() - startAt
            timeTv?.text = "%.1f sec".format(ms / 1000f)
            timeTv?.setTextColor(if (ms > TARGET_SEC * 1000) C.RED else C.PRIMARY_DARK)
            h.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Detailing practice"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bid = intent.getLongExtra("id", 0)
    }

    override fun onPause() {
        super.onPause()
        if (running) stop(false)
        releasePlayer()
        stopSpeak()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }

    private fun voice(): String {
        val saved = getSharedPreferences("prefs", MODE_PRIVATE).getString("tts_voice", "") ?: ""
        if (saved.isNotBlank()) return saved
        return if (words.any { w -> w.any { it in '\u0600'..'\u06FF' } }) "ur" else "hi"
    }

    private fun stopSpeak() {
        if (!speaking) return
        speaking = false
        try { tts?.stop() } catch (_: Exception) {}
        pos = -1
    }

    /** Script ko aahista, saaf awaaz mein suno; jo lafz bola ja raha ho woh roshan hota hai */
    private fun listen() {
        if (running) stop(false)
        releasePlayer()
        val t = tts
        if (t == null) {
            toast("Awaaz tayyar ho rahi hai…")
            tts = TextToSpeech(applicationContext) { st ->
                h.post {
                    if (st == TextToSpeech.SUCCESS) { ttsReady = true; listen() }
                    else { tts = null; toast("Is phone par awaaz (Text-to-speech) nahi mili") }
                }
            }
            return
        }
        if (!ttsReady) return
        val code = voice()
        var r = t.setLanguage(when (code) { "ur" -> Locale("ur", "PK"); "en" -> Locale("en", "IN"); else -> Locale("hi", "IN") })
        if (r < TextToSpeech.LANG_AVAILABLE && code == "en") r = t.setLanguage(Locale.US)
        if (r < TextToSpeech.LANG_AVAILABLE) {
            toast("Yeh awaaz phone mein install nahi. Neeche doosri awaaz chuno, ya Settings → Text-to-speech → Google → Install voice data")
            return
        }
        t.setSpeechRate(when (wpm) { 90 -> 0.7f; 110 -> 0.85f; else -> 1f })
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) { h.post { if (speaking) { speaking = false; pos = -1; refresh() } } }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) { h.post { if (speaking) { speaking = false; pos = -1; refresh() } } }
            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                val i = starts.indexOfLast { it <= start }
                h.post { if (speaking && i >= 0) { pos = i; paint() } }
            }
        })
        speaking = true; pos = -1; result = ""
        refresh()
        t.speak(words.joinToString(" "), TextToSpeech.QUEUE_FLUSH, null, "detail")
    }

    private fun recFile(): File = File(File(filesDir, "audio").apply { mkdirs() }, "practice_$bid.m4a")

    private fun estimateSec(): Int {
        var units = 0f
        words.forEach { w ->
            units += when {
                w.endsWith(".") || w.endsWith("?") || w.endsWith("!") || w.endsWith("۔") -> 2.2f
                w.endsWith(",") || w.endsWith("،") || w.endsWith(":") -> 1.5f
                else -> 1f
            }
        }
        return Math.round(units * 60f / wpm)
    }

    override fun render() {
        val brands = db.brands()
        if (brands.isEmpty()) { content.add(empty("Pehle Products mein medicines add karo.")); return }
        if (brands.none { it.id == bid }) bid = (brands.firstOrNull { it.script.isNotBlank() } ?: brands[0]).id
        val b = brands.first { it.id == bid }
        words = b.script.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        var o = 0
        starts = words.map { w -> val st = o; o += w.length + 1; st }

        // ---- Aaj ki mashq ----
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        val today = prefs.getInt("prac_" + Range.dayStart(), 0)
        val head = vbox().apply { background = rounded(C.PRIMARY, 14f); setPadding(dp(14), dp(12), dp(14), dp(12)) }
        head.add(text("Roshan lafz ke saath saath bolo — us se aage mat bhaago", 15.5f, true, Color.WHITE))
        head.add(text("Har jumle ke baad lafz ruk jata hai: wahan saans lo. Subah field se pehle har product 2-3 dafa.", 13f, color = 0xDDFFFFFF.toInt()), 4)
        head.add(text(if (today > 0) "✓ Aaj $today dafa practice ho chuki" else "Aaj abhi practice nahi ki", 13f, true, Color.WHITE), 8)
        content.add(head)

        // ---- Product ----
        content.add(chipRow(*brands.map { br ->
            chip((if (br.script.isBlank()) "" else "📝 ") + br.name, br.id == bid) {
                if (running) stop(false)
                stopSpeak(); releasePlayer(); bid = br.id; pos = -1; result = ""; refresh(); scrollToTop()
            }
        }.toTypedArray()), 10)

        if (words.isEmpty()) {
            content.add(empty("${b.name} ki script abhi likhi nahi. 3 jumle likho: (1) kis mareez ke liye, (2) ek faida, (3) doctor se kya chahiye."), 10)
            content.add(btn("📝 ${b.name} ki script likho") { open(ProductDetailActivity::class.java, b.id) }, 6)
            return
        }

        // ---- Raftaar ----
        val speeds = listOf(90 to "🐢 Bohat aahista", 110 to "Aahista", 130 to "Normal")
        content.add(chipRow(*speeds.map { (v, l) ->
            chip(l, v == wpm) { if (running) stop(false); stopSpeak(); wpm = v; pos = -1; refresh() }
        }.toTypedArray()), 10)

        val est = estimateSec()
        val fits = est <= TARGET_SEC + 3
        content.add(text(
            "${words.size} lafz • is raftaar par taqreeban $est sec" +
                if (fits) " ✓" else "\n⚠ Doctor ke paas ~$TARGET_SEC sec hote hain. Script chhoti karo (taqreeban ${wpm * TARGET_SEC / 60 - 6} lafz), tez mat bolo.",
            13.5f, !fits, if (fits) C.SUB else C.ORANGE
        ).apply { setPadding(dp(4), dp(8), dp(4), 0) })

        // ---- Script (pacer) ----
        val sc = card()
        scriptTv = sc.add(text("", 21f).apply { setLineSpacing(0f, 1.4f) })
        content.add(sc, 8)
        paint()

        timeTv = content.add(text(if (running) "0.0 sec" else "", 30f, true, C.PRIMARY_DARK).apply { gravity = Gravity.CENTER }, 8)

        // ---- Buttons ----
        if (speaking) {
            content.add(btn("⏹ Awaaz roko", 2, 17f) { stopSpeak(); refresh() }, 6)
        } else if (running) {
            content.add(btn(if (withRec) "⏹ Bol liya — Roko" else "⏹ Roko", 2, 17f) { stop() }, 6)
        } else {
            content.add(btn("🔊 Pehle saaf awaaz mein suno", 1, 15.5f) { listen() }, 6)
            val cur = voice()
            content.add(chipRow(*VOICES.map { (code, label) ->
                chip("🗣 $label", code == cur) {
                    getSharedPreferences("prefs", MODE_PRIVATE).edit().putString("tts_voice", code).apply(); refresh()
                }
            }.toTypedArray()), 6)
            content.add(btn("🎙 Record karke practice karo", 0, 16f) { start(true) }, 6)
            content.add(btn("▶ Sirf pacer (bina recording)", 1, 14.5f) { start(false) }, 8)
            if (recFile().exists()) content.add(btn(if (player?.isPlaying == true) "⏸ Roko" else "🔊 Apni pichli recording suno", 3, 14.5f) { togglePlay() }, 8)
        }

        if (result.isNotBlank()) content.add(card().apply {
            add(text(result, 15f, true, resultColor).apply { setLineSpacing(0f, 1.25f) })
        }, 10)

        content.add(btn("📝 Script badlo / chhoti karo", 3, 13.5f) { open(ProductDetailActivity::class.java, b.id) }, 14)
    }

    /** Guzre hue lafz halke, maujooda lafz roshan */
    private fun paint() {
        val tv = scriptTv ?: return
        val sp = SpannableString(words.joinToString(" "))
        var off = 0
        words.forEachIndexed { i, w ->
            val end = off + w.length
            when {
                i < pos -> sp.setSpan(ForegroundColorSpan(0xFFB0B8C0.toInt()), off, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                i == pos -> {
                    sp.setSpan(BackgroundColorSpan(0xFFFFEB3B.toInt()), off, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sp.setSpan(StyleSpan(Typeface.BOLD), off, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            off = end + 1
        }
        tv.text = sp
    }

    private fun start(rec: Boolean) {
        stopSpeak()
        if (rec && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC); return
        }
        releasePlayer()
        if (rec) {
            val f = recFile(); f.delete()
            try {
                @Suppress("DEPRECATION")
                val r = MediaRecorder()
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(96_000)
                r.setAudioSamplingRate(44_100)
                r.setOutputFile(f.absolutePath)
                r.prepare(); r.start()
                recorder = r
            } catch (e: Exception) {
                recorder = null; f.delete(); toast("Recording shuru nahi ho saki"); return
            }
        }
        withRec = rec; running = true; pos = -1; result = ""
        startAt = System.currentTimeMillis()
        refresh()
        h.postDelayed(step, 900)     // pehle lafz se pehle ek saans
        h.post(tick)
    }

    private fun stop(count: Boolean = true) {
        if (!running) return
        running = false
        h.removeCallbacks(step); h.removeCallbacks(tick)
        val ms = System.currentTimeMillis() - startAt - 900
        val r = recorder; recorder = null
        if (r != null) {
            try { r.stop() } catch (e: Exception) { recFile().delete() }
            r.release()
        }
        val finished = pos >= words.size - 1
        pos = -1
        if (count && ms > 3000) {
            val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
            val key = "prac_" + Range.dayStart()
            prefs.edit().putInt(key, prefs.getInt(key, 0) + 1).apply()
            val sec = ms / 1000f
            if (withRec) {
                // Poori script boli ho to yeh aap ki asal raftaar hai
                val actual = (words.size * 60_000f / ms).toInt()
                when {
                    actual > 160 -> { result = "⚠ %.0f sec mein khatam — bohat tez (taqreeban $actual lafz/min). Doctor ko samajh nahi aayega. Dobara karo, roshan lafz se aage mat jao.".format(sec); resultColor = C.RED }
                    actual > 135 -> { result = "%.0f sec — thoda tez. Jumlon ke beech ruk kar saans lo.".format(sec); resultColor = C.ORANGE }
                    else -> { result = "✓ %.0f sec — raftaar theek hai. Ab recording suno: har lafz saaf hai?".format(sec); resultColor = C.PRIMARY }
                }
                if (sec > TARGET_SEC + 5) result += "\nLekin ${TARGET_SEC} sec se lambi hai: script chhoti karo."
            } else {
                result = if (finished) "✓ Pacer ke saath poori ho gayi (%.0f sec). Ab record karke karo.".format(sec) else ""
                resultColor = C.PRIMARY
            }
        }
        refresh()
    }

    private fun togglePlay() {
        val p = player
        if (p != null && p.isPlaying) { releasePlayer(); refresh(); return }
        releasePlayer()
        try {
            player = MediaPlayer().apply {
                setDataSource(recFile().absolutePath); prepare()
                setOnCompletionListener { releasePlayer(); refresh() }
                start()
            }
        } catch (e: Exception) { player = null; toast("Recording chal nahi saki") }
        refresh()
    }

    private fun releasePlayer() {
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == RC_MIC) {
            if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) start(true)
            else toast("Recording ke liye mic ki ijazat chahiye")
        }
    }
}
