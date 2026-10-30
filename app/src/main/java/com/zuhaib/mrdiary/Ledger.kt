package com.zuhaib.mrdiary

import android.app.Activity
import android.app.DatePickerDialog
import android.content.ClipData
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.util.Calendar

/** Period chips: aaj / is mahine / pichla mahina / sab */
private fun periodRange(p: Int): Pair<Long, Long> = when (p) {
    0 -> Range.dayStart() to Range.dayEnd()
    1 -> Range.monthStart() to Range.monthEnd()
    2 -> { val t = Range.monthStart() - 1; Range.monthStart(t) to Range.monthEnd(t) }
    else -> 0L to Long.MAX_VALUE
}

private val PERIODS = listOf("Aaj", "Is mahine", "Pichla mahina", "Sab")

fun BaseActivity.dateBtn(initial: Long, onSet: (Long) -> Unit): TextView {
    var v = initial
    val b = btn("📅 " + Fmt.date(v), 1, 14f) {}
    b.setOnClickListener {
        val c = Calendar.getInstance().apply { timeInMillis = v }
        DatePickerDialog(this, { _, y, m, d ->
            val n = Calendar.getInstance().apply { timeInMillis = v; set(y, m, d) }.timeInMillis
            v = n; b.text = "📅 " + Fmt.date(n); onSet(n)
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }
    return b
}

// =====================================================================
// Supplier invoices (secondary sale)
// =====================================================================
class InvoiceListActivity : BaseActivity() {
    private var period = 1
    private var pendingEdit: SupplierInvoice? = null

    companion object { const val RC_CAM = 31; const val RC_GAL = 32 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Supplier invoices"
    }

    override fun render() {
        content.add(btn("+ Invoice add karo (photo ke saath)") { addFlow(null) })
        content.add(chipRow(*PERIODS.mapIndexed { i, l -> chip(l, i == period) { period = i; refresh() } }.toTypedArray()), 12)

        val (from, to) = periodRange(period)
        val list = db.invoices(from, to)
        val total = list.sumOf { it.amount }

        val head = vbox().apply { background = rounded(C.PRIMARY_DARK, 14f); setPadding(dp(14), dp(12), dp(14), dp(14)) }
        head.add(text("Secondary sale (supplier se stores ko)", 13f, color = 0xDDFFFFFF.toInt()))
        head.add(text(Fmt.rs(total), 28f, true, Color.WHITE))
        head.add(text("${list.size} invoices • ${list.map { it.storeId }.distinct().size} stores", 13f, color = 0xDDFFFFFF.toInt()), 2)
        content.add(head, 10)

        if (list.isNotEmpty()) {
            section("🏪 Store-wise")
            val c = card()
            list.groupBy { it.storeName }.map { (n, l) -> Triple(n, l.size, l.sumOf { it.amount }) }
                .sortedByDescending { it.third }.forEachIndexed { i, (n, cnt, amt) ->
                    val r = hrow()
                    r.addView(text("${i + 1}. $n  ($cnt)", 14.5f, true), LinearLayout.LayoutParams(0, WRAP, 1f))
                    r.addView(text(Fmt.rs(amt), 14.5f, true, C.PRIMARY_DARK))
                    c.add(r, if (i == 0) 0 else 8)
                }
            content.add(c)
        }

        section("📄 Invoices")
        if (list.isEmpty()) content.add(empty("Is muddat mein koi invoice nahi. Store par jo supplier invoice mile, us ki photo yahan daal do."))
        list.forEach { inv ->
            val row = listRow(
                inv.storeName,
                Fmt.date(inv.date) + (if (inv.invNo.isNotBlank()) " • #${inv.invNo}" else "") + (if (inv.photo != null) " • 📷" else ""),
                Fmt.rs(inv.amount) + if (inv.note.isNotBlank()) " • ${inv.note}" else "",
                null, { invMenu(inv) }
            )
            content.add(row, 6)
        }
    }

    private fun invMenu(inv: SupplierInvoice) {
        val opts = mutableListOf("✏ Edit", "🗑 Delete")
        if (inv.photo != null) opts.add(0, "🖼 Photo dekho")
        pick(inv.storeName, opts) { i ->
            when (opts[i]) {
                "🖼 Photo dekho" -> startActivity(Intent(this, ImageViewActivity::class.java).putExtra("paths", arrayOf(inv.photo)))
                "✏ Edit" -> form(inv, inv.photo)
                "🗑 Delete" -> confirm("Yeh invoice delete karni hai?", "Delete") {
                    inv.photo?.let { File(it).delete() }; db.deleteInvoice(inv.id); refresh()
                }
            }
        }
    }

    private fun addFlow(edit: SupplierInvoice?) {
        pendingEdit = edit
        pick("Invoice ki photo", listOf("📷 Camera se photo", "🖼 Gallery se", "✍ Bina photo ke")) { i ->
            when (i) {
                0 -> {
                    val f = File(Img.dir(this), "cam_tmp.jpg"); f.delete()
                    val uri = Uri.parse("content://${FilesProvider.AUTH}/images/${f.name}")
                    val it = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        putExtra(MediaStore.EXTRA_OUTPUT, uri)
                        clipData = ClipData.newRawUri("", uri)
                        addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    try { startActivityForResult(it, RC_CAM) } catch (e: Exception) { toast("Camera app nahi mili") }
                }
                1 -> {
                    val it = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
                    try { startActivityForResult(it, RC_GAL) } catch (e: Exception) { toast("Gallery nahi khuli") }
                }
                else -> form(edit, edit?.photo)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, result: Int, data: Intent?) {
        super.onActivityResult(rc, result, data)
        if (rc != RC_CAM && rc != RC_GAL) return
        if (result != Activity.RESULT_OK) return
        var photo: File? = null
        if (rc == RC_CAM) {
            val tmp = File(Img.dir(this), "cam_tmp.jpg")
            if (tmp.exists() && tmp.length() > 0) {
                val out = Img.newFile(this)
                if (Img.normalize(tmp, out)) photo = out
                tmp.delete()
            }
        } else data?.data?.let { photo = Img.importUri(this, it) }
        if (photo == null) toast("Photo save nahi hui")
        form(pendingEdit, photo?.absolutePath ?: pendingEdit?.photo)
    }

    private fun form(ex: SupplierInvoice?, photo: String?) {
        var storeId = ex?.storeId ?: 0L
        var storeName = ex?.storeName ?: ""
        var date = ex?.date ?: System.currentTimeMillis()
        val storeBtn = btn(if (storeName.isBlank()) "🏪 Store chuno ▾" else "$storeName ▾", 1, 14f) {}
        storeBtn.setOnClickListener {
            val stores = db.stores()
            pick("Store chuno", listOf("➕ Naya store") + stores.map { it.name }) { i ->
                if (i == 0) editStore(null) { s -> storeId = s.id; storeName = s.name; storeBtn.text = "${s.name} ▾" }
                else { storeId = stores[i - 1].id; storeName = stores[i - 1].name; storeBtn.text = "$storeName ▾" }
            }
        }
        val invNo = input("Maslan: S54355", ex?.invNo ?: "", T.TEXT)
        val amount = input("Rs (INV Total)", ex?.let { Fmt.plain(it.amount) } ?: "", T.DEC)
        val note = input("Maslan: Credit on counter, 5% discount", ex?.note ?: "", T.TEXT)

        val body = vbox()
        if (photo != null) {
            val iv = ImageView(this).apply {
                adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER
                setImageBitmap(Img.load(photo, dp(300)))
                setOnClickListener { startActivity(Intent(this@InvoiceListActivity, ImageViewActivity::class.java).putExtra("paths", arrayOf(photo))) }
            }
            body.addView(iv, LinearLayout.LayoutParams(MATCH, dp(180)))
            body.add(text("Photo dekh kar neeche bharo (tap = zoom)", 12f, color = C.SUB), 4)
        }
        body.add(text("Store", 13f, color = C.SUB), 10)
        body.add(storeBtn, 4)
        body.add(text("Date", 13f, color = C.SUB), 10)
        body.add(dateBtn(date) { date = it }, 4)
        body.add(text("Invoice number", 13f, color = C.SUB), 10)
        body.add(invNo)
        body.add(text("Total raqam", 13f, color = C.SUB), 10)
        body.add(amount)
        body.add(text("Note", 13f, color = C.SUB), 10)
        body.add(note)

        formDialog(if (ex == null) "Supplier invoice" else "Invoice edit", body) {
            if (storeName.isBlank()) { toast("Store chuno"); return@formDialog false }
            val amt = amount.str().toDoubleOrNull() ?: 0.0
            if (amt <= 0) { amount.error = "Raqam likho"; return@formDialog false }
            if (ex?.photo != null && ex.photo != photo) File(ex.photo).delete()
            db.saveInvoice(SupplierInvoice(ex?.id ?: 0, storeId, storeName, date, invNo.str(), amt, photo, note.str()))
            toast("✓ Invoice save ho gayi"); refresh(); true
        }
    }
}

// =====================================================================
// TA/DA — kharcha aur km
// =====================================================================
class ExpenseActivity : BaseActivity() {
    private var period = 1
    private val TYPES = listOf("⛽ Fuel", "🍽 Khana", "📱 Mobile", "🅿 Parking", "📦 Courier", "🔧 Bike repair", "➕ Doosra")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "TA / DA"
    }

    override fun render() {
        content.add(btn("+ Kharcha / km add karo") { form(null) })
        content.add(chipRow(*PERIODS.mapIndexed { i, l -> chip(l, i == period) { period = i; refresh() } }.toTypedArray()), 12)

        val (from, to) = periodRange(period)
        val list = db.expenses(from, to)
        val total = list.sumOf { it.amount }
        val km = list.sumOf { it.km }

        val head = vbox().apply { background = rounded(C.PRIMARY_DARK, 14f); setPadding(dp(14), dp(12), dp(14), dp(14)) }
        head.add(text("Kul kharcha", 13f, color = 0xDDFFFFFF.toInt()))
        head.add(text(Fmt.rs(total), 28f, true, Color.WHITE))
        head.add(text("${Fmt.plain(km)} km • ${list.map { Range.dayStart(it.date) }.distinct().size} din", 13.5f, color = 0xDDFFFFFF.toInt()), 2)
        content.add(head, 10)

        if (list.isNotEmpty()) {
            val c = card()
            list.groupBy { it.type }.map { it.key to it.value.sumOf { e -> e.amount } }.sortedByDescending { it.second }
                .forEachIndexed { i, (t, a) ->
                    val r = hrow()
                    r.addView(text(t, 14.5f), LinearLayout.LayoutParams(0, WRAP, 1f))
                    r.addView(text(Fmt.rs(a), 14.5f, true, C.PRIMARY_DARK))
                    c.add(r, if (i == 0) 0 else 6)
                }
            content.add(c, 10)
            content.add(btn("📤 Claim list bhejo", 1) { shareText(claimText(list, PERIODS[period])) }, 10)
        }

        section("🗓 Entries")
        if (list.isEmpty()) content.add(empty("Is muddat mein koi kharcha nahi likha."))
        list.groupBy { Range.dayStart(it.date) }.forEach { (day, l) ->
            content.add(text(Fmt.day(day) + "  —  " + Fmt.rs(l.sumOf { it.amount }), 13.5f, true, C.SUB).apply {
                setPadding(dp(4), dp(10), 0, dp(2))
            })
            l.forEach { e ->
                content.add(listRow(
                    "${e.type}  ${Fmt.rs(e.amount)}",
                    if (e.km > 0) "🛵 ${Fmt.plain(e.kmStart)} → ${Fmt.plain(e.kmEnd)} = ${Fmt.plain(e.km)} km" else "",
                    e.note, null, { form(e) },
                    { confirm("Yeh entry delete karni hai?", "Delete") { db.deleteExpense(e.id); refresh() } }
                ), 4)
            }
        }
    }

    private fun form(ex: Expense?) {
        var date = ex?.date ?: System.currentTimeMillis()
        var type = ex?.type ?: TYPES[0]
        val amount = input("Rs", ex?.let { if (it.amount > 0) Fmt.plain(it.amount) else "" } ?: "", T.DEC)
        val lastKm = db.lastKm()
        val kmS = input("Meter subah", ex?.let { if (it.kmStart > 0) Fmt.plain(it.kmStart) else "" }
            ?: if (lastKm > 0) Fmt.plain(lastKm) else "", T.DEC)
        val kmE = input("Meter shaam", ex?.let { if (it.kmEnd > 0) Fmt.plain(it.kmEnd) else "" } ?: "", T.DEC)
        val kmOut = text("", 13.5f, true, C.PRIMARY_DARK)
        fun upd() {
            val a = kmS.str().toDoubleOrNull() ?: 0.0; val b = kmE.str().toDoubleOrNull() ?: 0.0
            kmOut.text = if (b > a && a > 0) "= ${Fmt.plain(b - a)} km" else ""
        }
        kmS.onChange { upd() }; kmE.onChange { upd() }; upd()
        val note = input("Maslan: Sukkur → Khairpur", ex?.note ?: "", T.TEXT)

        val typeViews = ArrayList<TextView>()
        fun paint() = typeViews.forEachIndexed { i, v ->
            val sel = TYPES[i] == type
            v.setTextColor(if (sel) Color.WHITE else C.TEXT)
            v.background = rounded(if (sel) C.PRIMARY else Color.WHITE, 18f, if (sel) 0 else C.BORDER)
        }
        TYPES.forEach { t -> typeViews.add(chip(t, false) { type = t; paint() }) }
        paint()

        val body = vbox()
        body.add(text("Date", 13f, color = C.SUB), 4)
        body.add(dateBtn(date) { date = it }, 4)
        body.add(text("Kis cheez ka", 13f, color = C.SUB), 12)
        body.add(chipRow(*typeViews.toTypedArray()), 4)
        body.add(text("Raqam", 13f, color = C.SUB), 12)
        body.add(amount)
        body.add(text("Bike meter (km) — optional", 13f, color = C.SUB), 12)
        val r = hrow(); r.addWeighted(kmS); r.addWeighted(kmE, 8)
        body.add(r); body.add(kmOut, 2)
        body.add(text("Note", 13f, color = C.SUB), 12)
        body.add(note)

        formDialog(if (ex == null) "Kharcha" else "Kharcha edit", body) {
            val amt = amount.str().toDoubleOrNull() ?: 0.0
            val a = kmS.str().toDoubleOrNull() ?: 0.0
            val b = kmE.str().toDoubleOrNull() ?: 0.0
            if (amt <= 0 && b <= 0) { amount.error = "Raqam ya km likho"; return@formDialog false }
            db.saveExpense(Expense(ex?.id ?: 0, date, type, amt, a, b, note.str()))
            refresh(); true
        }
    }

    private fun claimText(list: List<Expense>, name: String): String {
        val sender = getSharedPreferences("prefs", MODE_PRIVATE).getString("sender", "") ?: ""
        val sb = StringBuilder("⛽ *TA/DA Claim — $name*")
        if (sender.isNotBlank()) sb.append("\n👤 $sender")
        sb.append("\n\n")
        list.sortedBy { it.date }.groupBy { Range.dayStart(it.date) }.forEach { (day, l) ->
            sb.append("*${Fmt.date(day)}*\n")
            l.forEach { e ->
                sb.append("• ${e.type.substringAfter(" ")}: ${Fmt.rs(e.amount)}")
                if (e.km > 0) sb.append(" (${Fmt.plain(e.km)} km)")
                if (e.note.isNotBlank()) sb.append(" — ${e.note}")
                sb.append("\n")
            }
        }
        sb.append("\n*Kul: ${Fmt.rs(list.sumOf { it.amount })}*")
        val km = list.sumOf { it.km }
        if (km > 0) sb.append("\n*Kul km: ${Fmt.plain(km)}*")
        return sb.toString()
    }
}
