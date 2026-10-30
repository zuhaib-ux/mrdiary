package com.zuhaib.mrdiary

import android.app.DatePickerDialog
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.LinearLayout
import java.util.Calendar

// =====================================================================
// Doctors list
// =====================================================================
class DoctorListActivity : BaseActivity() {
    private var query = ""
    private var area = ""
    private lateinit var listBox: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Doctors"
    }

    override fun render() {
        content.add(btn("+ Naya doctor add karo") { editDoctor(null) { id -> open(DoctorDetailActivity::class.java, id) } })
        content.add(searchBox("🔍 Naam, specialty ya clinic", query) { query = it; fillList() }, 10)
        val areas = db.doctors().map { it.area.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sorted()
        if (areas.size > 1) {
            val chips = listOf(chip("Sab", area.isBlank()) { area = ""; refresh() }) +
                areas.map { a -> chip(a, a.equals(area, true)) { area = a; refresh() } }
            content.add(chipRow(*chips.toTypedArray()), 8)
        }
        listBox = content.add(vbox(), 4)
        fillList()
    }

    private fun fillList() {
        listBox.removeAllViews()
        val all = db.doctors()
        val counts = db.doctorProductCounts()
        val list = all.filter { d ->
            (area.isBlank() || d.area.trim().equals(area, true)) &&
                (query.isBlank() || listOf(d.name, d.specialty, d.clinic, d.area).any { it.contains(query, true) })
        }
        if (all.isEmpty()) listBox.add(empty("Abhi koi doctor nahi. Apne area ke doctors add karo."))
        else listBox.add(text("Naam par tap = literature / detailing • 📋 = detail aur visits", 12f, color = C.SUB).apply { setPadding(dp(4), dp(4), 0, dp(2)) })
        list.forEach { d ->
            val c = counts[d.id]
            val pc = if (c != null && c.sum() > 0) "✅ ${c[0]}  🎯 ${c[1]}  🔔 ${c[2]}  •  " else ""
            val ds = d.daysSince()
            val last = pc + when {
                ds < 0 -> "⚠ Abhi visit nahi hui"
                ds >= d.cycleDays -> "⚠ $ds din se nahi mile"
                ds == 0 -> "✓ Aaj mile"
                else -> "Last visit: $ds din pehle"
            }
            listBox.add(
                listRow(
                    d.label,
                    listOf(d.specialty, if (d.dclass.isNotBlank()) "Class ${d.dclass}" else "", d.area).filter { it.isNotBlank() }.joinToString(" • ") +
                        (if (d.notInDigilyzr) "\n⚠ Digilyzr mein nahi" else "") +
                        (if (d.timing.isNotBlank()) "\n🕐 ${d.timing}" else ""),
                    last, rowBtns(d),
                    { open(DetailingActivity::class.java, d.id) },
                    { open(DoctorDetailActivity::class.java, d.id) }
                ), 6
            )
        }
    }

    private fun rowBtns(d: Doctor) = hrow().apply {
        addView(iconBtn(if (d.fav) "★" else "☆") { db.setFav(d.id, !d.fav); fillList() })
        addView(iconBtn("📞") { call(d.phone) }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
        addView(iconBtn("📋") { open(DoctorDetailActivity::class.java, d.id) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(6) })
    }
}


// =====================================================================
// Doctor detail + visits
// =====================================================================
class DoctorDetailActivity : BaseActivity() {
    private var did = 0L
    private var doctor: Doctor? = null
    private var autoVisit = false

    override fun onCreate(savedInstanceState: Bundle?) {
        did = intent.getLongExtra("id", 0)
        autoVisit = intent.getBooleanExtra("addVisit", false)
        super.onCreate(savedInstanceState)
    }

    override fun render() {
        val d = db.doctor(did)
        if (d == null) { finish(); return }
        doctor = d
        title = d.name

        val c = card()
        val nameRow = hrow()
        nameRow.addView(text(d.name, 20f, true), LinearLayout.LayoutParams(0, WRAP, 1f))
        nameRow.addView(btn(if (d.fav) "★ Favourite" else "☆ Favourite", if (d.fav) 0 else 1, 13f) { db.setFav(d.id, !d.fav); refresh() })
        c.add(nameRow)
        listOf(
            if (d.specialty.isNotBlank()) "🩺 ${d.specialty}" + (if (d.qual.isNotBlank()) " (${d.qual})" else "") else "",
            if (d.code.isNotBlank() || d.dclass.isNotBlank()) "🆔 Digilyzr ${d.code}" + (if (d.dclass.isNotBlank()) " • Class ${d.dclass}" else "") else "",
            if (d.notInDigilyzr) "⚠ Tour plan mein hai, Digilyzr list mein nahi" else "",
            if (d.clinic.isNotBlank()) "🏥 ${d.clinic}" else "",
            if (d.area.isNotBlank()) "📍 ${d.area}" else "",
            if (d.phone.isNotBlank()) "📱 ${d.phone}" else "",
            if (d.timing.isNotBlank()) "🕐 ${d.timing}" else ""
        ).filter { it.isNotBlank() }.forEach { c.add(text(it, 14.5f, color = C.SUB), 4) }
        val r = hrow()
        r.addWeighted(btn("📞 Call") { call(d.phone) })
        r.addWeighted(btn("💬 WhatsApp", 1) { whatsapp(d.phone) }, 8)
        r.addWeighted(btn("✏ Edit", 3) { editDoctor(d) { refresh() } }, 8)
        c.add(r, 12)
        content.add(c)

        // ---- Products ----
        val prods = db.products()
        val kinds = db.doctorProducts(did)
        val pc = card()
        Kinds.ALL.forEachIndexed { i, k ->
            val list = prods.filter { kinds[it.id] == k }
            pc.add(text("${Kinds.title(k)} (${list.size})", 15f, true, if (k == 1) C.PRIMARY else C.ORANGE), if (i == 0) 0 else 14)
            if (list.isEmpty()) pc.add(text("Koi nahi", 13.5f, color = C.SUB), 4)
            else pc.add(chipRow(*list.map { p -> chip(p.name, k == 1) { productMenu(p, k) } }.toTypedArray()), 6)
        }
        pc.add(text("Medicine par tap karo to list badal sakte ho. Lists ✏ Edit se bhi set hoti hain.", 12f, color = C.SUB), 10)
        content.add(pc, 10)

        val ar = hrow()
        ar.addWeighted(btn("📖 Literature dikhao", 1) { open(DetailingActivity::class.java, did) })
        ar.addWeighted(btn("+ Visit add karo") { visitDialog(d) }, 8)
        content.add(ar, 12)
        if (autoVisit) { autoVisit = false; content.post { visitDialog(d) } }

        val visits = db.visits(did)
        section("🗓 Visits (${visits.size})")
        if (visits.isEmpty()) content.add(empty("Abhi koi visit record nahi."))
        val today = Range.dayEnd()
        visits.forEach { v ->
            val vc = card()
            vc.add(text(Fmt.dateTime(v.date), 13f, true, C.PRIMARY_DARK))
            if (v.products.isNotBlank()) vc.add(text("💊 ${v.products}", 15f), 4)
            if (v.notes.isNotBlank()) vc.add(text("📝 ${v.notes}", 14.5f, color = C.SUB), 4)
            if (v.followup > 0) {
                val row = hrow()
                val label = when {
                    v.followDone -> "✓ Follow-up ho gaya (${Fmt.date(v.followup)})"
                    v.followup <= today -> "🔔 Follow-up: ${Fmt.date(v.followup)} — pending"
                    else -> "🔔 Follow-up: ${Fmt.date(v.followup)}"
                }
                row.addView(text(label, 14f, true, if (v.followDone) C.PRIMARY else C.ORANGE), LinearLayout.LayoutParams(0, WRAP, 1f))
                if (!v.followDone) row.addView(btn("✓ Done", 3, 13f) {
                    db.setFollowDone(v.id); Followups.cancel(this, v.id); refresh()
                })
                vc.add(row, 8)
            }
            vc.setOnLongClickListener {
                confirm("Yeh visit delete karni hai?", "Delete") {
                    Followups.cancel(this, v.id); db.deleteVisit(v.id); refresh()
                }
                true
            }
            content.add(vc, 8)
        }
    }

    private fun productMenu(p: Product, kind: Int) {
        val moves = Kinds.ALL.filter { it != kind }
        val opts = moves.map { "Badal kar: " + Kinds.title(it) } + listOf("🗑 List se hatao", "📄 Literature / script kholo")
        pick(p.name, opts) { i ->
            when {
                i < moves.size -> {
                    db.setDoctorProduct(did, p.id, moves[i]); refresh()
                    if (moves[i] == 1) toast("✓ Mubarak ho! ${p.name} ab prescribing mein")
                }
                i == moves.size -> { db.removeDoctorProduct(did, p.id); refresh() }
                else -> open(ProductDetailActivity::class.java, p.brandId)
            }
        }
    }

    private fun visitDialog(d: Doctor) {
        val prods = db.products()
        val kinds = db.doctorProducts(d.id)
        // Promote wali medicines pehle se chuni hui, kyunki visit mein wahi detail karni hain
        val checked = BooleanArray(prods.size) { kinds[prods[it].id] == 2 || kinds[prods[it].id] == 3 }
        val prodIn = input("Kaun si medicines detail kin",
            prods.filterIndexed { i, _ -> checked[i] }.joinToString(", ") { it.name }, T.TEXT)
        val notes = input("Doctor ne kya kaha, kya promise kiya…", "", T.MULTI)
        var follow = 0L
        val followBtn = btn("🔔 Follow-up: Nahi ▾", 1, 14f) {}

        val body = vbox()
        body.add(text("Medicines", 13f, color = C.SUB), 4)
        if (prods.isNotEmpty()) body.add(btn("💊 List se chuno", 3, 14f) {
            android.app.AlertDialog.Builder(this).setTitle("Medicines chuno")
                .setMultiChoiceItems(prods.map { it.name }.toTypedArray(), checked) { _, i, b -> checked[i] = b }
                .setPositiveButton("OK") { _, _ ->
                    prodIn.setText(prods.filterIndexed { i, _ -> checked[i] }.joinToString(", ") { it.name })
                }.show()
        }, 4)
        body.add(prodIn, 4)
        body.add(text("Notes", 13f, color = C.SUB), 12)
        body.add(notes)
        body.add(voiceRow(notes), 4)
        body.add(text("Dobara kab milna hai?", 13f, color = C.SUB), 12)
        body.add(followBtn, 4)

        val opts = listOf("Nahi" to -1, "Kal" to 1, "3 din baad" to 3, "1 hafte baad" to 7, "15 din baad" to 15, "1 mahine baad" to 30, "📅 Date chuno…" to 0)
        followBtn.setOnClickListener {
            pick("Follow-up", opts.map { it.first }) { i ->
                val days = opts[i].second
                when {
                    days < 0 -> { follow = 0; followBtn.text = "🔔 Follow-up: Nahi ▾" }
                    days > 0 -> { follow = Range.daysAhead(days); followBtn.text = "🔔 Follow-up: ${Fmt.day(follow)} ▾" }
                    else -> {
                        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, 1) }
                        DatePickerDialog(this, { _, y, m, dd ->
                            follow = Range.dayStart(Calendar.getInstance().apply { set(y, m, dd) }.timeInMillis)
                            followBtn.text = "🔔 Follow-up: ${Fmt.day(follow)} ▾"
                        }, cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH)).show()
                    }
                }
            }
        }

        formDialog("Visit — ${d.name}", body) {
            if (prodIn.str().isEmpty() && notes.str().isEmpty()) {
                toast("Medicine ya note mein se kuch to likho"); return@formDialog false
            }
            val id = db.addVisit(d.id, System.currentTimeMillis(), prodIn.str(), notes.str(), follow)
            if (follow > 0) Followups.schedule(this, id, follow)
            toast("✓ Visit save ho gayi"); refresh(); true
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "Doctor delete karo")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            val d = doctor ?: return true
            confirm("${d.name} aur un ki saari visits delete ho jayengi. Pakka?", "Delete") {
                db.visits(d.id).forEach { Followups.cancel(this, it.id) }
                db.deleteDoctor(d.id); finish()
            }
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
