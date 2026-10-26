package com.zuhaib.mrdiary

import android.graphics.Color
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout

// =====================================================================
// Naya order / order edit
// =====================================================================
class OrderEditActivity : BaseActivity() {
    private var orderId = 0L
    private var storeId = 0L
    private var storeName = ""
    private var date = System.currentTimeMillis()
    private val items = ArrayList<OrderItem>()
    private var note = ""
    private var dirty = false
    private var lastDisc = 0.0

    override fun onCreate(savedInstanceState: Bundle?) {
        orderId = intent.getLongExtra("id", 0)
        val sid = intent.getLongExtra("store", 0)
        super.onCreate(savedInstanceState)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        lastDisc = getSharedPreferences("prefs", MODE_PRIVATE).getFloat("lastDisc", 0f).toDouble()
        if (orderId > 0) {
            val o = db.order(orderId)
            if (o == null) { finish(); return }
            storeId = o.storeId; storeName = o.storeName; date = o.date; note = o.note
            items.addAll(db.orderItems(orderId))
        } else if (sid > 0) {
            db.store(sid)?.let { storeId = it.id; storeName = it.name }
        }
        updateTitle()
        if (orderId == 0L && storeName.isBlank()) content.post { chooseStore() }
    }

    private fun updateTitle() { title = if (orderId > 0) "Order #$orderId" else "Naya order" }

    override fun render() {
        // ---- Store ----
        val sc = card()
        sc.add(text("Medical store", 13f, color = C.SUB))
        if (storeName.isBlank()) {
            sc.add(btn("🏪 Store chuno ▾", 1) { chooseStore() }, 6)
        } else {
            val r = hrow()
            r.addView(text(storeName, 19f, true), LinearLayout.LayoutParams(0, WRAP, 1f))
            r.addView(btn("Badlo", 3, 13f) { chooseStore() })
            sc.add(r, 2)
            db.store(storeId)?.let { s ->
                val extra = listOf(s.owner, s.area).filter { it.isNotBlank() }.joinToString(" • ")
                if (extra.isNotBlank()) sc.add(text(extra, 13f, color = C.SUB), 2)
            }
        }
        sc.add(text("📅 " + Fmt.dateTime(date), 13f, color = C.SUB), 8)
        content.add(sc)

        // ---- Items ----
        section("💊 Medicines (${items.size})")
        if (items.isEmpty()) content.add(empty("Abhi koi medicine nahi. Neeche wale button se add karo."))
        items.forEachIndexed { idx, it ->
            val del = iconBtn("✕") {
                confirm("${it.name} hatani hai?") { items.removeAt(idx); dirty = true; refresh() }
            }
            val discLine = if (it.disc > 0) "  −${Fmt.plain(it.disc)}% (−${Fmt.rs(it.discAmt)})" else ""
            content.add(
                listRow(
                    "${idx + 1}. ${it.name}",
                    "${it.qty} × ${Fmt.rs(it.price)} = ${Fmt.rs(it.gross)}$discLine",
                    "Net: ${Fmt.rs(it.net)}",
                    del, { itemDialog(idx) }
                ), 6
            )
        }
        content.add(btn("+ Medicine add karo") { itemDialog(-1) }, 10)

        // ---- Totals ----
        val gross = items.sumOf { it.gross }
        val disc = items.sumOf { it.discAmt }
        val tc = vbox().apply {
            background = rounded(C.PRIMARY_DARK, 16f)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        tc.add(totalLine("Total (bagair discount)", Fmt.rs(gross)))
        tc.add(totalLine("Discount", "− " + Fmt.rs(disc)), 4)
        if (gross > 0) tc.add(totalLine("Average discount", Fmt.plain(Math.round(disc / gross * 1000) / 10.0) + "%"), 4)
        tc.add(text("NET TOTAL", 13f, true, 0xCCFFFFFF.toInt()), 12)
        tc.add(text(Fmt.rs(gross - disc), 32f, true, Color.WHITE))
        content.add(tc, 14)

        // ---- Note ----
        val noteIn = input("Maslan: Payment agle hafte, delivery kal", note, T.TEXT)
        noteIn.onChange { note = it; dirty = true }
        content.add(text("Note (optional)", 13f, color = C.SUB).apply { setPadding(dp(4), dp(14), 0, 0) })
        content.add(noteIn)

        // ---- Actions ----
        val row = hrow()
        row.addWeighted(btn("💾 Save") { if (save()) toast("✓ Order save ho gaya") })
        row.addWeighted(btn("📤 WhatsApp", 1) { if (save()) shareText(shareMsg()) }, 8)
        content.add(row, 14)
    }

    private fun totalLine(l: String, v: String) = hrow().apply {
        addView(text(l, 14f, color = 0xDDFFFFFF.toInt()), LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(text(v, 15f, true, Color.WHITE))
    }

    private fun chooseStore() {
        val stores = db.stores()
        val names = listOf("➕ Naya store add karo") + stores.map { if (it.area.isBlank()) it.name else "${it.name}  (${it.area})" }
        pick("Store chuno", names) { i ->
            if (i == 0) editStore(null) { s -> setStore(s) } else setStore(stores[i - 1])
        }
    }

    private fun setStore(s: Store) {
        storeId = s.id; storeName = s.name; dirty = true; refresh()
    }

    private fun itemDialog(index: Int) {
        val prods = db.products()
        if (prods.isEmpty()) {
            confirm("Pehle Products mein medicine add karni hogi. Abhi add karein?") {
                editProduct(null) { refresh(); itemDialog(index) }
            }
            return
        }
        val ex = items.getOrNull(index)
        var sel: Product? = ex?.let { e -> prods.firstOrNull { it.id == e.productId } }
        var selName = ex?.name ?: ""

        val prodBtn = btn(if (selName.isBlank()) "Medicine chuno ▾" else "$selName ▾", 1) {}
        val qty = input("Kitne pack", ex?.qty?.toString() ?: "", T.NUM)
        val rate = input("Rs", ex?.let { Fmt.plain(it.price) } ?: "", T.DEC)
        val disc = input("%", Fmt.plain(ex?.disc ?: lastDisc), T.DEC)
        val preview = text("", 15f, true, C.PRIMARY_DARK)

        fun upd() {
            val q = qty.str().toIntOrNull() ?: 0
            val r = rate.str().toDoubleOrNull() ?: 0.0
            val d = disc.str().toDoubleOrNull() ?: 0.0
            val g = q * r; val da = g * d / 100
            preview.text = "${Fmt.rs(g)} − ${Fmt.rs(da)} = ${Fmt.rs(g - da)}"
        }
        qty.onChange { upd() }; rate.onChange { upd() }; disc.onChange { upd() }

        prodBtn.setOnClickListener {
            pick("Medicine chuno", prods.map { if (it.price > 0) "${it.name}  — ${Fmt.rs(it.price)}" else it.name }) { i ->
                sel = prods[i]; selName = prods[i].name
                prodBtn.text = "$selName ▾"
                rate.setText(if (prods[i].price > 0) Fmt.plain(prods[i].price) else "")
                qty.requestFocus()
            }
        }

        val quick = listOf(0, 5, 10, 12, 15, 20, 25).map { v ->
            chip("$v%", false) { disc.setText(v.toString()); disc.setSelection(disc.text.length) }
        }

        val body = vbox()
        body.add(text("Medicine", 13f, color = C.SUB), 4)
        body.add(prodBtn, 4)
        val r1 = hrow()
        r1.addWeighted(labeled("Quantity", qty))
        r1.addWeighted(labeled("Rate (Rs)", rate), 10)
        body.add(r1, 8)
        body.add(labeled("Discount %", disc), 8)
        body.add(chipRow(*quick.toTypedArray()), 6)
        body.add(preview, 14)
        upd()

        formDialog(if (ex == null) "Medicine add karo" else "Edit", body, "OK") {
            if (selName.isBlank()) { toast("Medicine chuno"); return@formDialog false }
            val q = qty.str().toIntOrNull() ?: 0
            if (q <= 0) { qty.error = "Quantity likho"; return@formDialog false }
            val r = rate.str().toDoubleOrNull() ?: 0.0
            val d = (disc.str().toDoubleOrNull() ?: 0.0).coerceIn(0.0, 100.0)
            val item = OrderItem(sel?.id ?: ex?.productId ?: 0, selName, q, r, d)
            if (ex == null) items.add(item) else items[index] = item
            lastDisc = d
            getSharedPreferences("prefs", MODE_PRIVATE).edit().putFloat("lastDisc", d.toFloat()).apply()
            dirty = true; refresh(); true
        }
        if (ex == null) prodBtn.post { prodBtn.performClick() }
    }

    private fun labeled(label: String, v: EditText) = vbox().apply {
        add(text(label, 13f, color = C.SUB)); add(v)
    }

    private fun save(): Boolean {
        if (storeName.isBlank()) { toast("Pehle store chuno"); chooseStore(); return false }
        if (items.isEmpty()) { toast("Kam az kam ek medicine add karo"); return false }
        orderId = db.saveOrder(orderId, storeId, storeName, date, items, note.trim())
        dirty = false
        updateTitle(); invalidateOptionsMenu()
        return true
    }

    private fun shareMsg(): String {
        val sb = StringBuilder()
        sb.append("*Order — $storeName*\n")
        sb.append("Date: ${Fmt.dateTime(date)}\n\n")
        items.forEachIndexed { i, it ->
            sb.append("${i + 1}. ${it.name} — ${it.qty} × ${Fmt.n(it.price)}")
            if (it.disc > 0) sb.append(" (−${Fmt.plain(it.disc)}%)")
            sb.append(" = ${Fmt.rs(it.net)}\n")
        }
        val gross = items.sumOf { it.gross }; val disc = items.sumOf { it.discAmt }
        sb.append("\nTotal: ${Fmt.rs(gross)}\nDiscount: ${Fmt.rs(disc)}\n*Net Total: ${Fmt.rs(gross - disc)}*")
        if (note.isNotBlank()) sb.append("\n\nNote: ${note.trim()}")
        return sb.toString()
    }

    override fun onBackRequested(): Boolean {
        if (!dirty || items.isEmpty()) return false
        confirm("Order save nahi hua. Save karna hai?", "Save") { if (save()) finish() }
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (orderId > 0) menu.add(0, 1, 0, "Order delete karo")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            confirm("Yeh order delete karna hai?", "Delete") { db.deleteOrder(orderId); dirty = false; finish() }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}

// =====================================================================
// Orders history
// =====================================================================
class OrderListActivity : BaseActivity() {
    private var period = 1   // 0 aaj, 1 is mahine, 2 pichla mahina, 3 sab
    private var storeId = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        storeId = intent.getLongExtra("store", 0)
        super.onCreate(savedInstanceState)
        title = if (storeId > 0) (db.store(storeId)?.name ?: "Orders") else "Orders"
        if (storeId > 0) period = 3
    }

    override fun render() {
        content.add(btn("+ Naya order") {
            startActivity(android.content.Intent(this, OrderEditActivity::class.java).putExtra("store", storeId))
        })
        val labels = listOf("Aaj", "Is mahine", "Pichla mahina", "Sab")
        content.add(chipRow(*labels.mapIndexed { i, l -> chip(l, i == period) { period = i; refresh() } }.toTypedArray()), 12)

        val (from, to) = when (period) {
            0 -> Range.dayStart() to Range.dayEnd()
            1 -> Range.monthStart() to Range.monthEnd()
            2 -> { val t = Range.monthStart() - 1; Range.monthStart(t) to Range.monthEnd(t) }
            else -> 0L to Long.MAX_VALUE
        }
        val list = db.orders(from, to, storeId)
        val total = list.sumOf { it.net }
        content.add(text("${list.size} orders • ${Fmt.rs(total)}", 15f, true, C.PRIMARY_DARK).apply {
            setPadding(dp(4), dp(12), 0, dp(4))
        })
        if (list.isEmpty()) content.add(empty("Is muddat mein koi order nahi."))
        list.forEach { o ->
            content.add(
                listRow(
                    o.storeName,
                    "${Fmt.dateTime(o.date)} • ${o.itemCount} medicines",
                    "Net: ${Fmt.rs(o.net)}" + if (o.discount > 0) "  (discount ${Fmt.rs(o.discount)})" else "",
                    null, { open(OrderEditActivity::class.java, o.id) },
                    { confirm("${o.storeName} ka yeh order delete karna hai?", "Delete") { db.deleteOrder(o.id); refresh() } }
                ), 6
            )
        }
    }
}

// =====================================================================
// Stores
// =====================================================================
class StoreListActivity : BaseActivity() {
    private var query = ""
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Medical Stores"
    }

    override fun render() {
        content.add(btn("+ Naya store add karo") { editStore(null) { refresh() } })
        content.add(searchBox("🔍 Store ya area dhoondo", query) { query = it; fillList() }, 10)
        listBox = content.add(vbox(), 4)
        fillList()
    }

    private fun fillList() {
        listBox.removeAllViews()
        val all = db.stores()
        val list = all.filter { query.isBlank() || it.name.contains(query, true) || it.area.contains(query, true) || it.owner.contains(query, true) }
        if (all.isEmpty()) listBox.add(empty("Abhi koi store nahi. Store add karo ya order banate waqt bhi add ho sakta hai."))
        list.forEach { s ->
            val right = hrow()
            right.addView(iconBtn("🧾") {
                startActivity(android.content.Intent(this, OrderEditActivity::class.java).putExtra("store", s.id))
            })
            right.addView(iconBtn("📞") { call(s.phone) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
            listBox.add(
                listRow(
                    s.name,
                    listOf(s.owner, s.area).filter { it.isNotBlank() }.joinToString(" • "),
                    s.phone, right,
                    { startActivity(android.content.Intent(this, OrderListActivity::class.java).putExtra("store", s.id)) },
                    { storeMenu(s) }
                ), 6
            )
        }
        if (all.isNotEmpty()) listBox.add(text("Tap = orders dekho • 🧾 = naya order • Dabaa kar rakho = edit/delete", 12f, color = C.SUB).apply {
            setPadding(dp(4), dp(10), 0, 0)
        })
    }

    private fun storeMenu(s: Store) {
        pick(s.name, listOf("✏ Edit", "💬 WhatsApp", "🗑 Delete")) { i ->
            when (i) {
                0 -> editStore(s) { refresh() }
                1 -> whatsapp(s.phone)
                2 -> confirm("${s.name} delete karna hai? (Purane orders rahenge)", "Delete") { db.deleteStore(s.id); refresh() }
            }
        }
    }
}
