package com.zuhaib.mrdiary

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.MotionEvent
import android.widget.ImageView
import android.widget.LinearLayout
import kotlin.math.abs

/**
 * Doctor ke naam par tap karo -> us ke products ki literature, ek ke baad ek.
 * Har page par pehle price + per-tab/dose price, phir literature. Left/right swipe = agla/pichla product.
 */
class DetailingActivity : BaseActivity() {
    private var did = 0L
    private var page = 0
    private var showScript = false

    private class Page(val brand: Brand, val kind: Int, val selected: Set<Long>)
    private var pages: List<Page> = emptyList()

    private lateinit var swipe: GestureDetector

    override fun onCreate(savedInstanceState: Bundle?) {
        did = intent.getLongExtra("id", 0)
        super.onCreate(savedInstanceState)
        swipe = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (e1 == null) return false
                val dx = e2.x - e1.x
                if (abs(vx) > abs(vy) * 1.5f && abs(dx) > dp(70) && abs(vx) > 600) {
                    go(if (dx < 0) page + 1 else page - 1)
                    return true
                }
                return false
            }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        swipe.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    private fun buildPages() {
        val kinds = db.doctorProducts(did)
        val prods = db.products().filter { kinds.containsKey(it.id) }
        val byBrand = LinkedHashMap<Long, MutableList<Product>>()
        prods.forEach { byBrand.getOrPut(it.brandId) { mutableListOf() }.add(it) }
        val list = byBrand.mapNotNull { (bid, skus) ->
            val b = db.brand(bid) ?: return@mapNotNull null
            val kind = if (skus.any { kinds[it.id] == 2 }) 2 else 1
            Page(b, kind, skus.map { it.id }.toSet())
        }
        // Promote wale pehle, phir jo already likh rahe hain
        pages = list.sortedBy { if (it.kind == 2) 0 else 1 }
    }

    private fun go(i: Int) {
        if (pages.isEmpty()) return
        val n = i.coerceIn(0, pages.size - 1)
        if (n == page) return
        page = n; showScript = false
        refresh(); scrollToTop()
        content.alpha = 0f
        content.animate().alpha(1f).setDuration(180).start()
    }

    override fun render() {
        val d = db.doctor(did)
        if (d == null) { finish(); return }
        title = d.name
        buildPages()
        if (page >= pages.size) page = 0

        // ---- Doctor header ----
        val head = vbox().apply { background = rounded(C.PRIMARY, 14f); setPadding(dp(14), dp(10), dp(14), dp(12)) }
        head.add(text(d.name, 18f, true, Color.WHITE))
        val sub = listOf(d.specialty, d.clinic).filter { it.isNotBlank() }.joinToString(" • ")
        if (sub.isNotBlank()) head.add(text(sub, 13f, color = 0xDDFFFFFF.toInt()), 2)
        if (d.timing.isNotBlank()) head.add(text("🕐 ${d.timing}", 13.5f, true, Color.WHITE), 4)
        val hr = hrow()
        hr.addWeighted(btn("📞 Call", 3, 13f) { call(d.phone) })
        hr.addWeighted(btn("✍ Visit likho", 3, 13f) {
            startActivity(Intent(this, DoctorDetailActivity::class.java).putExtra("id", did).putExtra("addVisit", true))
        }, 6)
        hr.addWeighted(btn("📋 Detail", 3, 13f) { open(DoctorDetailActivity::class.java, did) }, 6)
        head.add(hr, 10)
        content.add(head)

        if (pages.isEmpty()) {
            content.add(empty("Is doctor ke saath abhi koi product nahi. Doctor edit karke \"Prescribe kar rahe\" aur \"Promote karna hai\" mein medicines chuno."), 12)
            content.add(btn("✏ Products chuno") { editDoctor(d) { refresh() } }, 6)
            return
        }

        // ---- Product tabs ----
        val tabs = pages.mapIndexed { i, pg ->
            chip((if (pg.kind == 2) "🎯 " else "✅ ") + pg.brand.name, i == page) { go(i) }
        }
        content.add(chipRow(*tabs.toTypedArray()), 10)

        val pg = pages[page]
        val b = pg.brand

        // ---- Price card (literature se pehle) ----
        val pc = card()
        val top = hrow()
        val names = vbox()
        names.add(text(b.name, 24f, true))
        if (b.generic.isNotBlank()) names.add(text("(${b.generic})", 14f, color = C.SUB))
        top.addView(names, LinearLayout.LayoutParams(0, WRAP, 1f))
        top.addView(text(if (pg.kind == 2) " 🎯 PROMOTE " else " ✅ PRESCRIBING ", 12f, true, Color.WHITE).apply {
            background = rounded(if (pg.kind == 2) C.ORANGE else C.PRIMARY, 8f)
            setPadding(dp(8), dp(4), dp(8), dp(4))
        })
        pc.add(top)
        if (b.category.isNotBlank()) pc.add(text(b.category, 13.5f, color = C.PRIMARY_DARK), 4)
        val skus = db.products(b.id)
        val sel = skus.filter { it.id in pg.selected }
        val other = skus.filter { it.id !in pg.selected }
        sel.forEachIndexed { i, s -> pc.add(skuRow(s, true), if (i == 0) 12 else 6) }
        if (other.isNotEmpty()) {
            pc.add(text("Baqi strengths", 12.5f, color = C.SUB), 12)
            other.forEach { s -> pc.add(skuRow(s, false), 6) }
        }
        content.add(pc, 10)

        // ---- Literature ----
        val imgs = db.images(b.id)
        if (imgs.isEmpty()) {
            content.add(empty("Is product ki literature abhi nahi lagi. Products → ${b.name} mein photo lagao."), 8)
        } else {
            val w = resources.displayMetrics.widthPixels
            imgs.forEachIndexed { idx, im ->
                val iv = ImageView(this).apply {
                    adjustViewBounds = true
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    background = rounded(Color.WHITE, 10f, C.BORDER)
                    clipToOutline = true
                    setImageBitmap(Img.load(im.path, (w * 1.3).toInt()))
                    setOnClickListener {
                        startActivity(
                            Intent(this@DetailingActivity, ImageViewActivity::class.java)
                                .putExtra("paths", imgs.map { it.path }.toTypedArray())
                                .putExtra("index", idx)
                        )
                    }
                }
                content.add(iv, 10)
            }
            content.add(text("Page par tap karo to zoom ho jayega", 12f, color = C.SUB).apply { setPadding(dp(4), dp(4), 0, 0) })
        }

        // ---- Script ----
        if (b.script.isNotBlank()) {
            content.add(btn(if (showScript) "📝 Script chhupao" else "📝 Script dekho", 1, 14f) {
                showScript = !showScript; refresh()
            }, 12)
            if (showScript) content.add(card().apply {
                add(text(b.script, 17f).apply { setLineSpacing(0f, 1.3f) })
            }, 6)
        }

        // ---- Navigation ----
        val nav = hrow()
        nav.addWeighted(btn("◀ Pichla", 3) { go(page - 1) })
        nav.addWeighted(text("${page + 1} / ${pages.size}", 15f, true).apply { gravity = android.view.Gravity.CENTER }, 6)
        nav.addWeighted(btn("Agla ▶") { go(page + 1) }, 6)
        content.add(nav, 16)
        content.add(text("Left / right swipe karke bhi product badal sakte ho", 12f, color = C.SUB).apply {
            gravity = android.view.Gravity.CENTER; setPadding(0, dp(6), 0, 0)
        })
    }
}
