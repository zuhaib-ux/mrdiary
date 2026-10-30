package com.zuhaib.mrdiary

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

// =====================================================================
// Products (brands) list
// =====================================================================
class ProductListActivity : BaseActivity() {
    private var query = ""
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Products"
    }

    override fun render() {
        val r = hrow()
        r.addWeighted(btn("+ Naya product") { editBrand(null) { id -> open(ProductDetailActivity::class.java, id) } })
        r.addWeighted(btn("📋 Price list", 1) {
            startActivity(Intent(this, ImageViewActivity::class.java).putExtra("paths", Seed.refImages(this)))
        }, 8)
        content.add(r)
        content.add(searchBox("🔍 Medicine ya formula dhoondo", query) { query = it; fillList() }, 10)
        listBox = content.add(vbox(), 4)
        fillList()
    }

    private fun fillList() {
        listBox.removeAllViews()
        val all = db.brands()
        val list = all.filter {
            query.isBlank() || it.name.contains(query, true) || it.generic.contains(query, true) || it.category.contains(query, true)
        }
        if (all.isEmpty()) listBox.add(empty("Abhi koi product nahi. Upar wale button se add karo."))
        list.forEach { b ->
            val skus = db.products(b.id)
            val imgs = db.images(b.id).size
            val badges = buildList {
                add(if (imgs > 0) "📄 $imgs" else "📄 —")
                if (b.script.isNotBlank()) add("📝")
                if (b.audio != null) add("🎙")
                if (skus.isNotEmpty()) add(skus.joinToString(" | ") { s ->
                    (s.strength.ifBlank { s.pack }) + if (s.price > 0) " ${Fmt.n(s.price)}" else ""
                })
            }.joinToString("   ")
            listBox.add(
                listRow(
                    b.name,
                    listOf(b.generic, b.category).filter { it.isNotBlank() }.joinToString(" • "),
                    badges, null, { open(ProductDetailActivity::class.java, b.id) }
                ), 6
            )
        }
    }
}

// =====================================================================
// Brand detail: SKUs/prices + literature photos + script + voice
// =====================================================================
class ProductDetailActivity : BaseActivity() {
    private var pid = 0L
    private var brand: Brand? = null

    private var recorder: MediaRecorder? = null
    private var recFile: File? = null
    private var recStart = 0L
    private var player: MediaPlayer? = null
    private var playerPath: String? = null
    private var speed = 1f
    private val handler = Handler(Looper.getMainLooper())

    // audio UI (har render par naye bante hain)
    private var timeTv: TextView? = null
    private var playBtn: TextView? = null
    private var seek: SeekBar? = null
    private var recTv: TextView? = null

    private val ticker = object : Runnable {
        override fun run() { updateAudioUi(); handler.postDelayed(this, 300) }
    }

    companion object {
        const val RC_CAMERA = 11
        const val RC_GALLERY = 12
        const val RC_MIC = 88
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        pid = intent.getLongExtra("id", 0)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        if (recorder != null) stopRec()
        player?.let { if (it.isPlaying) it.pause() }
    }

    override fun onDestroy() {
        super.onDestroy()
        player?.release(); player = null
    }

    override fun render() {
        val p = db.brand(pid)
        if (p == null) { finish(); return }
        brand = p
        title = p.name

        // ---- Info ----
        val info = card()
        info.add(text(p.name, 20f, true))
        if (p.generic.isNotBlank()) info.add(text("(${p.generic})", 14.5f, color = C.SUB), 2)
        if (p.category.isNotBlank()) info.add(text("🎯 ${p.category}", 14.5f, color = C.PRIMARY_DARK), 6)
        info.add(btn("✏ Edit naam", 1, 14f) { editBrand(p) { refresh() } }, 10, WRAP)
        content.add(info)

        // ---- SKUs / prices ----
        section("💰 Price (tap karke badlo)")
        val sc0 = card()
        val skus = db.products(pid)
        if (skus.isEmpty()) sc0.add(empty("Abhi koi strength/pack nahi."))
        skus.forEachIndexed { i, s -> sc0.add(skuRow(s) { editSku(pid, s) { refresh() } }, if (i == 0) 0 else 8) }
        sc0.add(btn("+ Strength / pack add karo", 3, 14f) { editSku(pid, null) { refresh() } }, 12, WRAP)
        content.add(sc0)

        // ---- Literature ----
        section("📄 Literature (${db.images(pid).size})")
        val imgs = db.images(pid)
        if (imgs.isNotEmpty()) {
            val strip = hrow()
            imgs.forEachIndexed { idx, im ->
                val iv = ImageView(this).apply {
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    background = rounded(Color.WHITE, 8f, C.BORDER)
                    clipToOutline = true
                    setImageBitmap(Img.load(im.path, dp(240)))
                    setOnClickListener {
                        startActivity(
                            Intent(this@ProductDetailActivity, ImageViewActivity::class.java)
                                .putExtra("paths", imgs.map { it.path }.toTypedArray())
                                .putExtra("index", idx)
                        )
                    }
                    setOnLongClickListener {
                        confirm("Yeh page delete karna hai?", "Delete") {
                            db.deleteImage(im.id); File(im.path).delete(); refresh()
                        }
                        true
                    }
                }
                strip.addView(iv, LinearLayout.LayoutParams(dp(120), dp(165)).apply { if (idx > 0) marginStart = dp(8) })
            }
            content.add(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(strip) })
            content.add(text("Page par tap karo to bada khulega (zoom ho sakta hai). Delete ke liye dabaa kar rakho.", 12.5f, color = C.SUB), 6)
        } else {
            content.add(empty("Abhi koi page nahi. Literature ki photo kheencho ya gallery se lagao."))
        }
        val litRow = hrow()
        litRow.addWeighted(btn("📷 Camera") { openCamera() })
        litRow.addWeighted(btn("🖼 Gallery", 1) { openGallery() }, 8)
        content.add(litRow, 10)

        // ---- Script ----
        section("📝 Detailing script")
        val sc = card()
        if (p.script.isBlank()) sc.add(empty("Script abhi nahi likha. Doctor ke saamne kya bolna hai, yahan likh lo."))
        else sc.add(text(p.script, 17f).apply { setLineSpacing(0f, 1.3f); setTextIsSelectable(true) })
        sc.add(btn(if (p.script.isBlank()) "✏ Script likho" else "✏ Script edit", 1, 14f) { editScript(p) }, 10, WRAP)
        content.add(sc)

        // ---- Voice ----
        section("🎙 Voice recording")
        content.add(audioCard(p))
    }

    // ---------------- script ----------------
    private fun editScript(p: Brand) {
        val box = input("Assalam o Alaikum Doctor sahib...", p.script, T.MULTI).apply { minLines = 10 }
        formDialog("Script — ${p.name}", box) {
            db.setScript(p.id, box.text.toString().trim()); refresh(); true
        }
    }

    // ---------------- audio ----------------
    private fun audioCard(p: Brand): LinearLayout {
        val c = card()
        timeTv = null; playBtn = null; seek = null; recTv = null
        when {
            recorder != null -> {
                recTv = c.add(text("● Recording… 0:00", 18f, true, C.RED))
                c.add(text("Script parhte jao. Khatam ho to Stop dabao.", 13f, color = C.SUB), 4)
                c.add(btn("■ Stop & Save", 2) { stopRec() }, 12)
            }
            p.audio != null && File(p.audio).exists() -> {
                val path = p.audio
                val sb = SeekBar(this).apply {
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                            if (fromUser) player?.seekTo(v)
                        }
                        override fun onStartTrackingTouch(s: SeekBar?) {}
                        override fun onStopTrackingTouch(s: SeekBar?) {}
                    })
                }
                seek = c.add(sb)
                timeTv = c.add(text("0:00", 13f, color = C.SUB).apply { setPadding(dp(14), 0, 0, 0) })
                val row = hrow()
                row.addWeighted(btn("▶ Play") { togglePlay(path) }.also { playBtn = it })
                row.addWeighted(btn("${Fmt.plain(speed.toDouble())}x", 3) { cycleSpeed() }, 8)
                c.add(row, 8)
                val row2 = hrow()
                row2.addWeighted(btn("🔁 Dobara record", 1, 14f) {
                    confirm("Purani recording hata kar nayi karni hai?") { startRec() }
                })
                row2.addWeighted(btn("🗑 Delete", 3, 14f) {
                    confirm("Recording delete karni hai?", "Delete") {
                        releasePlayer(); File(path).delete(); db.setAudio(p.id, null); refresh()
                    }
                }, 8)
                c.add(row2, 8)
            }
            else -> {
                c.add(empty("Apni awaaz mein script record karo, phir raaste mein sun kar yaad karo."))
                c.add(btn("● Record shuru karo", 2) { startRec() }, 8)
            }
        }
        updateAudioUi()
        return c
    }

    private fun startRec() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_MIC); return
        }
        releasePlayer()
        val dir = File(filesDir, "audio").apply { mkdirs() }
        val f = File(dir, "voice_${pid}_${System.currentTimeMillis()}.m4a")
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
            recorder = r; recFile = f; recStart = System.currentTimeMillis()
        } catch (e: Exception) {
            f.delete(); toast("Recording shuru nahi ho saki")
        }
        refresh()
    }

    private fun stopRec() {
        val r = recorder ?: return
        recorder = null
        val f = recFile; recFile = null
        var ok = true
        try { r.stop() } catch (e: Exception) { ok = false }
        r.release()
        if (ok && f != null && f.length() > 0) {
            val old = db.brand(pid)?.audio
            db.setAudio(pid, f.absolutePath)
            if (old != null && old != f.absolutePath) File(old).delete()
            toast("✓ Recording save ho gayi")
        } else {
            f?.delete(); toast("Recording bohat chhoti thi, save nahi hui")
        }
        refresh()
    }

    private fun togglePlay(path: String) {
        val p = player
        if (p == null || playerPath != path) {
            releasePlayer()
            try {
                player = MediaPlayer().apply {
                    setDataSource(path); prepare()
                    setOnCompletionListener { it.seekTo(0); updateAudioUi() }
                    if (speed != 1f) playbackParams = playbackParams.setSpeed(speed)
                    start()
                }
                playerPath = path
            } catch (e: Exception) { toast("Recording chal nahi saki") }
        } else if (p.isPlaying) p.pause() else p.start()
        updateAudioUi()
    }

    private fun cycleSpeed() {
        speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 0.75f; else -> 1f }
        player?.let {
            val wasPlaying = it.isPlaying
            try { it.playbackParams = it.playbackParams.setSpeed(speed) } catch (_: Exception) {}
            if (!wasPlaying && it.isPlaying) it.pause()
        }
        refresh()
    }

    private fun releasePlayer() {
        player?.release(); player = null; playerPath = null
    }

    private fun updateAudioUi() {
        recTv?.text = "● Recording… " + Fmt.dur((System.currentTimeMillis() - recStart).toInt())
        val p = player
        if (p != null) {
            seek?.max = p.duration
            seek?.progress = p.currentPosition
            timeTv?.text = Fmt.dur(p.currentPosition) + " / " + Fmt.dur(p.duration)
            playBtn?.text = if (p.isPlaying) "⏸ Pause" else "▶ Play"
        }
    }

    // ---------------- photos ----------------
    private fun camTemp(): File = File(Img.dir(this), "cam_tmp.jpg")

    private fun openCamera() {
        val f = camTemp(); f.delete()
        val uri = Uri.parse("content://${FilesProvider.AUTH}/images/${f.name}")
        val i = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivityForResult(i, RC_CAMERA) } catch (e: Exception) { toast("Camera app nahi mili") }
    }

    private fun openGallery() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(Intent.createChooser(i, "Literature ki photos chuno"), RC_GALLERY) }
        catch (e: Exception) { toast("Gallery nahi khuli") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, result: Int, data: Intent?) {
        super.onActivityResult(rc, result, data)
        if (rc != RC_CAMERA && rc != RC_GALLERY) return
        if (result != Activity.RESULT_OK) return
        var added = 0
        if (rc == RC_CAMERA) {
            val tmp = camTemp()
            if (tmp.exists() && tmp.length() > 0) {
                val out = Img.newFile(this)
                if (Img.normalize(tmp, out)) { db.addImage(pid, out.absolutePath); added++ }
                tmp.delete()
            }
        } else if (rc == RC_GALLERY && data != null) {
            val uris = ArrayList<Uri>()
            val clip = data.clipData
            if (clip != null) for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
            else data.data?.let { uris.add(it) }
            uris.forEach { u -> Img.importUri(this, u)?.let { db.addImage(pid, it.absolutePath); added++ } }
        }
        if (added > 0) toast("✓ $added page add ho gaye") else toast("Photo save nahi hui")
        refresh()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == RC_MIC) {
            if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startRec()
            else toast("Recording ke liye mic ki ijazat chahiye")
        }
    }

    // ---------------- menu ----------------
    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Product delete karo")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            val p = brand ?: return true
            confirm("${p.name} ke saare SKUs, literature, script aur recording delete ho jayenge. Pakka?", "Delete") {
                releasePlayer()
                db.images(p.id).forEach { File(it.path).delete() }
                p.audio?.let { File(it).delete() }
                db.deleteBrand(p.id)
                finish()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
