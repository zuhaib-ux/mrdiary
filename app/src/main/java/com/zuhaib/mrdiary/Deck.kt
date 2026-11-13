package com.zuhaib.mrdiary

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * Har doctor ka apna "deck": literature ki photos usi tarteeb mein jis mein chuni thin.
 * Har photo ke upar likha aata hai ke us product ke liye THANKS bolna hai, REQUEST karni hai ya REMINDER dena hai.
 */
object DeckCue {
    /** brand_id -> kind. Ek brand ki kai strengths hon to request > reminder > thanks */
    fun brandKinds(db: Db, doctorId: Long): Map<Long, Int> {
        val kinds = db.doctorProducts(doctorId)
        val out = HashMap<Long, Int>()
        db.products().forEach { p ->
            val k = kinds[p.id] ?: return@forEach
            val cur = out[p.brandId]
            if (cur == null || Kinds.order(k) < Kinds.order(cur)) out[p.brandId] = k
        }
        return out
    }

    fun line(kind: Int?, brand: String): String = when (kind) {
        Kinds.WRITING -> "🙏 THANKS bolo — $brand"
        Kinds.REQUEST -> "🎯 REQUEST karo — $brand"
        Kinds.REMIND -> "🔔 REMINDER do — $brand"
        else -> if (brand.isBlank()) "" else "📄 $brand"
    }

    fun color(kind: Int?): Int = when (kind) {
        Kinds.WRITING -> C.PRIMARY
        Kinds.REQUEST -> C.ORANGE
        Kinds.REMIND -> 0xFF5E35B1.toInt()
        else -> 0xFF455A64.toInt()
    }
}

// =====================================================================
// Deck banao / tarteeb badlo
// =====================================================================
class DeckEditActivity : BaseActivity() {
    private var did = 0L

    companion object { const val RC_GALLERY = 21 }

    override fun onCreate(savedInstanceState: Bundle?) {
        did = intent.getLongExtra("id", 0)
        super.onCreate(savedInstanceState)
    }

    private fun items(): List<DeckItem> = db.deck(did).filter { File(it.path).exists() }

    override fun render() {
        val d = db.doctor(did)
        if (d == null) { finish(); return }
        title = "Deck — ${d.name}"
        val list = items()
        val bk = DeckCue.brandKinds(db, did)
        val brands = db.brands()
        val byId = brands.associateBy { it.id }

        // ---- Is doctor se kya kehna hai ----
        val cue = card()
        cue.add(text("Is doctor se kya kehna hai", 15f, true, C.PRIMARY_DARK))
        fun names(k: Int) = brands.filter { bk[it.id] == k }.joinToString(", ") { it.name }
        val th = names(Kinds.WRITING); val rq = names(Kinds.REQUEST); val rm = names(Kinds.REMIND)
        if (th.isBlank() && rq.isBlank() && rm.isBlank()) {
            cue.add(text("Abhi set nahi. Neeche button se batao ke yeh doctor kya likh rahe hain aur kis ki request karni hai.", 13.5f, color = C.SUB), 6)
        } else {
            if (th.isNotBlank()) cue.add(text("🙏 Thanks bolo: $th", 14.5f, true, C.PRIMARY), 6)
            if (rq.isNotBlank()) cue.add(text("🎯 Request karo: $rq", 14.5f, true, C.ORANGE), 4)
            if (rm.isNotBlank()) cue.add(text("🔔 Reminder do: $rm", 14.5f, true, 0xFF5E35B1.toInt()), 4)
        }
        cue.add(btn("✏ Thanks / Request / Reminder set karo", 3, 13f) { editDoctor(d) { refresh() } }, 10)
        content.add(cue)

        if (list.isNotEmpty()) content.add(btn("▶ Deck dikhao (${list.size} photos, swipe se badlo)") { play(0) }, 10)

        // ---- Photos add karo ----
        section("➕ Photos add karo")
        content.add(text("Jo photo pehle chuno ge woh pehle dikhegi. Tarteeb baad mein ▲ ▼ se badal sakte ho.", 13f, color = C.SUB).apply { setPadding(dp(4), 0, 0, dp(6)) })
        val ar = hrow()
        ar.addWeighted(btn("📚 App ki literature se", 0, 13.5f) { open(DeckPickActivity::class.java, did) })
        ar.addWeighted(btn("🖼 Gallery se", 1, 13.5f) { openGallery() }, 8)
        content.add(ar)
        if (bk.isNotEmpty()) content.add(btn("🪄 Khud bana do (pehle request, phir reminder, phir thanks)", 3, 13f) { autoFill(bk) }, 8)

        // ---- Tarteeb ----
        section("🖼 Deck ki tarteeb (${list.size})")
        if (list.isEmpty()) content.add(empty("Abhi koi photo nahi. Upar se add karo."))
        list.forEachIndexed { i, item ->
            val kind = bk[item.brandId]
            val bname = byId[item.brandId]?.name ?: ""
            val row = hrow().apply {
                background = rounded(Color.WHITE, 12f, C.BORDER)
                setPadding(dp(8), dp(8), dp(6), dp(8))
            }
            row.addView(text("${i + 1}", 16f, true, Color.WHITE).apply {
                gravity = Gravity.CENTER; background = rounded(C.PRIMARY, 16f)
            }, LinearLayout.LayoutParams(dp(30), dp(30)))
            val iv = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = rounded(C.LIGHT, 8f)
                clipToOutline = true
                setImageBitmap(Img.load(item.path, 260))
                setOnClickListener { play(i) }
            }
            row.addView(iv, LinearLayout.LayoutParams(dp(62), dp(82)).apply { marginStart = dp(8) })
            val mid = vbox()
            mid.add(text(bname.ifBlank { "Product set nahi" }, 15f, true))
            val cl = DeckCue.line(kind, bname)
            if (bname.isNotBlank()) mid.add(text(cl.substringBefore(" —"), 13f, true, DeckCue.color(kind)), 2)
            else mid.add(text("Tap karke product chuno", 12.5f, color = C.SUB), 2)
            mid.setOnClickListener { menu(list, i) }
            row.addView(mid, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(10) })
            row.addView(iconBtn("▲") { move(list, i, i - 1) })
            row.addView(iconBtn("▼") { move(list, i, i + 1) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) })
            row.addView(iconBtn("⋮") { menu(list, i) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(4) })
            content.add(row, 6)
        }
        if (list.size > 1) content.add(btn("🗑 Poora deck khali karo", 3, 13f) {
            confirm("Is doctor ke deck ki saari photos hata dein? (Products wali literature mehfooz rahegi)", "Khali karo") {
                list.forEach { db.deleteDeck(it) }; refresh()
            }
        }, 14)
    }

    private fun play(index: Int) {
        startActivity(Intent(this, DeckViewActivity::class.java).putExtra("id", did).putExtra("index", index))
    }

    /** Photo ko "from" se utha kar "to" par rakho */
    private fun move(list: List<DeckItem>, from: Int, to: Int) {
        if (to < 0 || to >= list.size || from == to) return
        val ids = list.map { it.id }.toMutableList()
        val x = ids.removeAt(from)
        ids.add(to, x)
        db.setDeckOrder(ids)
        refresh()
    }

    private fun menu(list: List<DeckItem>, i: Int) {
        val item = list[i]
        pick("Photo ${i + 1}", listOf("⤒ Sab se pehle dikhao", "⤓ Sab se aakhir mein", "💊 Yeh kis product ki hai?", "🗑 Deck se hatao")) { c ->
            when (c) {
                0 -> move(list, i, 0)
                1 -> move(list, i, list.size - 1)
                2 -> chooseBrand { bid -> db.setDeckBrand(item.id, bid); refresh() }
                else -> { db.deleteDeck(item); refresh() }
            }
        }
    }

    private fun chooseBrand(onPick: (Long) -> Unit) {
        val brands = db.brands()
        pick("Yeh kis product ki hai?", brands.map { it.name } + "Koi product nahi") { c ->
            onPick(if (c < brands.size) brands[c].id else 0L)
        }
    }

    /** Doctor ke products se khud deck: pehle request, phir reminder, phir thanks; har product ka pehla page */
    private fun autoFill(bk: Map<Long, Int>) {
        val have = db.deck(did).map { it.path }.toSet()
        var added = 0
        db.brands().filter { bk.containsKey(it.id) }.sortedBy { Kinds.order(bk[it.id] ?: 1) }.forEach { b ->
            val im = db.images(b.id).firstOrNull { File(it.path).exists() } ?: return@forEach
            if (im.path !in have) { db.addDeck(did, im.path, b.id, false); added++ }
        }
        toast(if (added > 0) "✓ $added photos add ho gayin" else "In products ki literature pehle se deck mein hai, ya photo lagi hi nahi")
        refresh()
    }

    private fun openGallery() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        try { startActivityForResult(Intent.createChooser(i, "Jis tarteeb mein dikhani hain usi tarteeb mein chuno"), RC_GALLERY) }
        catch (e: Exception) { toast("Gallery nahi khuli") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, result: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(rc, result, data)
        if (rc != RC_GALLERY || result != Activity.RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        val clip = data.clipData
        if (clip != null) for (k in 0 until clip.itemCount) uris.add(clip.getItemAt(k).uri)
        else data.data?.let { uris.add(it) }
        val ids = ArrayList<Long>()
        uris.forEach { u -> Img.importUri(this, u)?.let { f -> ids.add(db.addDeck(did, f.absolutePath, 0, true)) } }
        if (ids.isEmpty()) { toast("Photo save nahi hui"); return }
        toast("✓ ${ids.size} photos deck ke aakhir mein lag gayin")
        refresh()
        if (db.brands().isNotEmpty()) chooseBrand { bid ->
            if (bid > 0) { ids.forEach { db.setDeckBrand(it, bid) }; refresh() }
        }
    }
}

// =====================================================================
// App ki literature se chuno: jis tarteeb mein tap karo wahi number lagta hai
// =====================================================================
class DeckPickActivity : BaseActivity() {
    private var did = 0L
    private val chosen = ArrayList<Pair<String, Long>>()      // path, brand_id — tap ki tarteeb mein
    private val badges = HashMap<String, TextView>()
    private val saveBtns = ArrayList<TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        did = intent.getLongExtra("id", 0)
        super.onCreate(savedInstanceState)
        title = "Tarteeb se chuno"
    }

    override fun render() {
        badges.clear(); saveBtns.clear()
        val inDeck = db.deck(did).map { it.path }.toSet()
        val bk = DeckCue.brandKinds(db, did)
        content.add(text("Jis photo par pehle tap karo ge us par 1 lagega, doosri par 2… Deck mein isi tarteeb se dikhengi. Dobara tap = hata do.", 13.5f, color = C.SUB).apply {
            setPadding(dp(4), 0, dp(4), dp(8))
        })
        saveBtns.add(content.add(btn("") { save() }))

        // Doctor ke apne products pehle (request, reminder, thanks), baqi baad mein
        val brands = db.brands().sortedBy { b -> bk[b.id]?.let { Kinds.order(it) } ?: 9 }
        var any = false
        brands.forEach { b ->
            val imgs = db.images(b.id).filter { File(it.path).exists() }
            if (imgs.isEmpty()) return@forEach
            any = true
            val kind = bk[b.id]
            content.add(text(if (kind != null) DeckCue.line(kind, b.name) else b.name, 15f, true, DeckCue.color(kind)).apply {
                setPadding(dp(4), dp(16), 0, dp(6))
            })
            imgs.chunked(3).forEach { rowImgs ->
                val row = hrow()
                for (k in 0 until 3) {
                    val lp = LinearLayout.LayoutParams(0, dp(132), 1f).apply { if (k > 0) marginStart = dp(6) }
                    val im = rowImgs.getOrNull(k)
                    if (im == null) { row.addView(View(this), lp); continue }
                    val cell = FrameLayout(this)
                    val iv = ImageView(this).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        background = rounded(Color.WHITE, 8f, C.BORDER)
                        clipToOutline = true
                        setImageBitmap(Img.load(im.path, 300))
                    }
                    cell.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
                    val badge = TextView(this).apply {
                        textSize = 15f; typeface = Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
                        setTextColor(Color.WHITE)
                    }
                    cell.addView(badge, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END).apply {
                        topMargin = dp(4); marginEnd = dp(4)
                    })
                    if (im.path in inDeck) {
                        iv.alpha = 0.4f
                        badge.text = "✓"; badge.background = rounded(C.SUB, 15f)
                    } else {
                        badges[im.path] = badge
                        cell.setOnClickListener {
                            val idx = chosen.indexOfFirst { it.first == im.path }
                            if (idx >= 0) chosen.removeAt(idx) else chosen.add(im.path to b.id)
                            paint()
                        }
                    }
                    row.addView(cell, lp)
                }
                content.add(row, 6)
            }
        }
        if (!any) content.add(empty("App mein abhi kisi product ki literature nahi lagi. Products mein ja kar photos lagao, ya wapas ja kar \"Gallery se\" chuno."))
        else saveBtns.add(content.add(btn("") { save() }, 16))
        paint()
    }

    private fun paint() {
        badges.forEach { (path, tv) ->
            val idx = chosen.indexOfFirst { it.first == path }
            if (idx >= 0) { tv.text = "${idx + 1}"; tv.background = rounded(C.ORANGE, 15f); tv.visibility = View.VISIBLE }
            else tv.visibility = View.INVISIBLE
        }
        saveBtns.forEach {
            it.text = if (chosen.isEmpty()) "Photos par tap karke chuno" else "✓ Yeh ${chosen.size} photos isi tarteeb mein deck mein daalo"
            it.alpha = if (chosen.isEmpty()) 0.5f else 1f
        }
    }

    private fun save() {
        if (chosen.isEmpty()) { toast("Pehle photos par tap karo"); return }
        chosen.forEach { (path, bid) -> db.addDeck(did, path, bid, false) }
        toast("✓ ${chosen.size} photos deck mein lag gayin")
        finish()
    }
}

// =====================================================================
// Deck dikhao: full screen, swipe se agli / pichli photo
// =====================================================================
class DeckViewActivity : Activity() {
    private var did = 0L
    private var index = 0
    private var items: List<DeckItem> = emptyList()
    private var kinds: Map<Long, Int> = emptyMap()
    private var names: Map<Long, String> = emptyMap()
    private lateinit var zoom: ZoomImageView
    private lateinit var banner: TextView
    private lateinit var counter: TextView
    private lateinit var swipe: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        did = intent.getLongExtra("id", 0)
        index = savedInstanceState?.getInt("index") ?: intent.getIntExtra("index", 0)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val db = Db.get(this)
        items = db.deck(did).filter { File(it.path).exists() }
        kinds = DeckCue.brandKinds(db, did)
        names = db.brands().associate { it.id to it.name }
        val dn = resources.displayMetrics.density
        fun dp(v: Int) = (v * dn).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        zoom = ZoomImageView(this)
        root.addView(zoom, FrameLayout.LayoutParams(MATCH, MATCH))

        banner = TextView(this).apply {
            textSize = 17f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Color.WHITE)
            gravity = Gravity.CENTER; setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        root.addView(banner, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt()); setPadding(0, dp(6), 0, dp(6))
        }
        fun navBtn(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 26f; setTextColor(Color.WHITE)
            setPadding(dp(28), dp(4), dp(28), dp(4)); setOnClickListener { f() }
        }
        counter = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }
        bar.addView(navBtn("◀") { show(index - 1) })
        bar.addView(counter)
        bar.addView(navBtn("▶") { show(index + 1) })
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)

        swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null || zoom.isZoomed()) return false
                val dx = e2.x - e1.x
                if (abs(vx) > abs(vy) * 1.5f && abs(dx) > dp(60) && abs(vx) > 500) {
                    show(if (dx < 0) index + 1 else index - 1)
                    return true
                }
                return false
            }
        })

        if (items.isEmpty()) {
            Toast.makeText(this, "Deck khali hai", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        index = index.coerceIn(0, items.size - 1)
        show(index, true)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("index", index)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun show(i: Int, first: Boolean = false) {
        if (i < 0) return
        if (i >= items.size) {
            Toast.makeText(this, "Deck khatam — yeh aakhri photo thi", Toast.LENGTH_SHORT).show()
            return
        }
        if (i == index && !first) return
        index = i
        val item = items[index]
        val dm = resources.displayMetrics
        val bmp = Img.load(item.path, max(dm.widthPixels, dm.heightPixels) * 2)
        if (bmp != null) zoom.setBitmap(bmp)
        val kind = kinds[item.brandId]
        val line = DeckCue.line(kind, names[item.brandId] ?: "")
        banner.text = line
        banner.setBackgroundColor((DeckCue.color(kind) and 0x00FFFFFF) or 0xE6000000.toInt())
        banner.visibility = if (line.isBlank()) View.GONE else View.VISIBLE
        counter.text = "  ${index + 1} / ${items.size}  "
        if (!first) { zoom.alpha = 0.3f; zoom.animate().alpha(1f).setDuration(160).start() }
    }
}
