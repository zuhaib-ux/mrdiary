package com.zuhaib.mrdiary

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.LinearLayout
import java.util.Calendar

// =====================================================================
// Store reorder ka andaza
// =====================================================================
data class ReorderDue(val store: Store, val last: Long, val gapDays: Int, val due: Long) {
    val lateDays: Int get() = ((Range.dayStart() - Range.dayStart(due)) / 86_400_000L).toInt()
}

object Reorder {
    /** Jin stores ka order `withinDays` din mein due hai ya late ho chuka */
    fun due(db: Db, withinDays: Int = 0): List<ReorderDue> {
        val dates = db.purchaseDates()
        val limit = Range.dayEnd(Range.daysAhead(withinDays))
        return db.stores().mapNotNull { s ->
            val days = (dates[s.id] ?: return@mapNotNull null).map { Range.dayStart(it) }.distinct().sorted()
            if (days.isEmpty()) return@mapNotNull null
            val gap = if (days.size >= 2) {
                val avg = (days.last() - days.first()) / 86_400_000.0 / (days.size - 1)
                Math.max(3, Math.round(avg).toInt())
            } else 15
            val due = days.last() + gap * 86_400_000L
            if (due <= limit) ReorderDue(s, days.last(), gap, due) else null
        }.sortedBy { it.due }
    }
}

// =====================================================================
// Aaj ka plan
// =====================================================================
class PlanActivity : BaseActivity() {
    private val prefs by lazy { getSharedPreferences("prefs", MODE_PRIVATE) }
    private fun key() = "plan_" + Range.dayStart()
    private var day = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        day = Range.dayStart(intent.getLongExtra("day", System.currentTimeMillis()))
        super.onCreate(savedInstanceState)
        title = if (day == Range.dayStart()) "Aaj ka plan" else Fmt.day(day)
    }

    /** Tour plan wala din: subah / shaam ki list, plan ki tarteeb mein */
    private fun renderTour(rows: List<Db.TourRow>) {
        val isToday = day == Range.dayStart()
        val docs = db.doctors().associateBy { it.id }
        val visited = db.visitsBetween(day, Range.dayEnd(day)).map { it.doctorId }.toSet()
        val followIds = db.dueFollowups(Range.dayEnd(day)).map { it.doctorId }.toSet()
        val area = rows.first().area

        val head = vbox().apply { background = rounded(C.PRIMARY, 14f); setPadding(dp(14), dp(10), dp(14), dp(12)) }
        head.add(text(Fmt.day(day) + " • " + Days.URDU[Days.ALL.indexOf(Days.ALL[(Calendar.getInstance().apply { timeInMillis = day }.get(Calendar.DAY_OF_WEEK) + 5) % 7])], 14f, color = 0xDDFFFFFF.toInt()))
        head.add(text("📍 $area", 20f, true, Color.WHITE), 2)
        val done = rows.map { it.doctorId }.distinct().count { it in visited }
        head.add(text("Tour plan: ${rows.size} calls • $done mil liye", 13.5f, color = 0xE6FFFFFF.toInt()), 4)
        val nav = hrow()
        nav.addWeighted(btn("◀ Pichla din", 3, 13f) { jump(-1) })
        nav.addWeighted(btn("🗓 Mahina", 3, 13f) { open(TourActivity::class.java) }, 6)
        nav.addWeighted(btn("Agla din ▶", 3, 13f) { jump(1) }, 6)
        head.add(nav, 10)
        content.add(head)

        for ((sess, label) in listOf("M" to "🌅 Subah", "E" to "🌆 Shaam")) {
            val list = rows.filter { it.session == sess }
            if (list.isEmpty()) continue
            section("$label (${list.count { it.doctorId in visited }}/${list.size})")
            list.forEach { r ->
                val d = docs[r.doctorId] ?: return@forEach
                val ds = d.daysSince()
                val tags = buildList {
                    if (d.id in visited) add("✓ Mil liye")
                    if (d.id in followIds) add("🔔 Follow-up")
                    if (d.notInDigilyzr) add("⚠ Digilyzr mein nahi")
                    if (d.id !in visited && ds >= 7) add("$ds din se")
                    if (d.id !in visited && ds < 0) add("Pehli visit")
                }.joinToString("  ")
                val sub = listOf(d.specialty, if (d.dclass.isNotBlank()) "Class ${d.dclass}" else "", d.timing).filter { it.isNotBlank() }.joinToString(" • ")
                val row = listRow("${r.idx + 1}. ${d.name}", sub, tags, iconBtn("📞") { call(d.phone) },
                    { open(DetailingActivity::class.java, d.id) })
                if (d.id in visited) row.alpha = 0.55f
                content.add(row, 6)
            }
        }

        if (isToday || day > Range.dayStart()) {
            val due = Reorder.due(db, 1).filter { it.store.area.isBlank() || area.contains(it.store.area.trim(), true) }
            if (due.isNotEmpty()) {
                section("🏪 Stores — order due (${due.size})")
                due.forEach { content.add(reorderRow(it), 6) }
            }
        }
    }

    private fun jump(delta: Int) {
        val c = Calendar.getInstance().apply { timeInMillis = day; add(Calendar.DAY_OF_MONTH, delta) }
        // Off din chhod do (max 7)
        var n = 0
        while (db.tourFor(c.timeInMillis).isEmpty() && n < 7) { c.add(Calendar.DAY_OF_MONTH, delta); n++ }
        startActivity(Intent(this, PlanActivity::class.java).putExtra("day", c.timeInMillis))
        finish()
    }

    private fun selectedAreas(): Set<String> =
        (prefs.getString(key(), "") ?: "").split("|").filter { it.isNotBlank() }.toSet()

    private fun setAreas(a: Set<String>) { prefs.edit().putString(key(), a.joinToString("|")).apply() }

    override fun render() {
        val tour = db.tourFor(day)
        if (tour.isNotEmpty()) { renderTour(tour); return }
        if (day != Range.dayStart()) {
            content.add(empty("${Fmt.day(day)}: tour plan mein is din kuch nahi (off ya plan nahi)."))
            val nav = hrow()
            nav.addWeighted(btn("◀ Pichla din", 3, 13f) { jump(-1) })
            nav.addWeighted(btn("🗓 Mahina", 3, 13f) { open(TourActivity::class.java) }, 6)
            nav.addWeighted(btn("Agla din ▶", 3, 13f) { jump(1) }, 6)
            content.add(nav, 8)
            return
        }
        val today = Days.today()
        val all = db.doctors()
        val areas = all.map { it.area.trim() }.filter { it.isNotBlank() }.distinctBy { it.lowercase() }.sorted()
        val sel = selectedAreas()

        val head = vbox().apply { background = rounded(C.PRIMARY, 14f); setPadding(dp(14), dp(10), dp(14), dp(12)) }
        head.add(text(Fmt.day(System.currentTimeMillis()) + " • " + Days.URDU[Days.ALL.indexOf(today)], 14f, color = 0xDDFFFFFF.toInt()))
        head.add(text("Aaj kis area mein ja rahe ho?", 18f, true, Color.WHITE), 2)
        head.add(text("Aaj ke liye tour plan nahi mila — area chun kar dekho", 12.5f, color = 0xDDFFFFFF.toInt()), 2)
        val nav0 = hrow()
        nav0.addWeighted(btn("🗓 Tour plan", 3, 13f) { open(TourActivity::class.java) })
        nav0.addWeighted(btn("Agla din ▶", 3, 13f) { jump(1) }, 6)
        head.add(nav0, 8)
        content.add(head)

        if (areas.isNotEmpty()) {
            val chips = listOf(chip("Sab areas", sel.isEmpty()) { setAreas(emptySet()); refresh() }) +
                areas.map { a ->
                    chip(a, sel.any { it.equals(a, true) }) {
                        val n = sel.toMutableSet()
                        if (n.any { it.equals(a, true) }) n.removeAll { it.equals(a, true) } else n.add(a)
                        setAreas(n); refresh()
                    }
                }
            content.add(chipRow(*chips.toTypedArray()), 10)
        }

        fun inArea(area: String) = sel.isEmpty() || sel.any { it.equals(area.trim(), true) }

        val dS = Range.dayStart(); val dE = Range.dayEnd()
        val visitedToday = db.visitsBetween(dS, dE).map { it.doctorId }.toSet()
        val followIds = db.dueFollowups(dE).map { it.doctorId }.toSet()

        val docs = all.filter { inArea(it.area) && it.sitsOn(today) }
            .sortedWith(compareBy<Doctor> { it.id in visitedToday }.thenBy { it.startMinutes }.thenBy { it.category })
        val notToday = all.count { inArea(it.area) && !it.sitsOn(today) }

        val done = docs.count { it.id in visitedToday }
        section("👨‍⚕️ Doctors (${done}/${docs.size} mil liye)")
        if (all.isEmpty()) content.add(empty("Abhi koi doctor nahi. Doctors mein add karo, timing aur din bhi daalna."))
        else if (docs.isEmpty()) content.add(empty("Is area mein aaj koi doctor nahi baithta."))
        docs.forEach { d ->
            val ds = d.daysSince()
            val tags = buildList {
                if (d.id in visitedToday) add("✓ Aaj mil liye")
                if (d.id in followIds) add("🔔 Follow-up")
                if (d.id !in visitedToday && d.isOverdue()) add(if (ds < 0) "⚠ Kabhi nahi mile" else "⚠ $ds din se")
            }.joinToString("  ")
            val row = listRow(
                "${d.name}  [${d.category}]",
                listOf(if (d.timing.isNotBlank()) "🕐 ${d.timing}" else "🕐 timing nahi likhi", d.clinic, d.area).filter { it.isNotBlank() }.joinToString(" • "),
                tags, iconBtn("📞") { call(d.phone) },
                { open(DetailingActivity::class.java, d.id) }
            )
            if (d.id in visitedToday) row.alpha = 0.55f
            content.add(row, 6)
        }
        if (notToday > 0) content.add(text("$notToday doctor aaj nahi baithte (un ke din doctor edit mein set hain)", 12f, color = C.SUB).apply {
            setPadding(dp(4), dp(8), 0, 0)
        })

        // ---- Stores jin ka order due hai ----
        val due = Reorder.due(db, 1).filter { inArea(it.store.area) }
        section("🏪 Stores — order due (${due.size})")
        if (due.isEmpty()) content.add(empty("Is area mein abhi kisi store ka order due nahi."))
        due.forEach { r -> content.add(reorderRow(r), 6) }
    }
}

/** Store reorder ki row (Home aur Plan dono mein) */
fun BaseActivity.reorderRow(r: ReorderDue): LinearLayout {
    val late = r.lateDays
    val status = when {
        late > 0 -> "⚠ $late din late"
        late == 0 -> "Aaj due"
        else -> "Kal due"
    }
    return listRow(
        r.store.name,
        "Last order: ${Fmt.date(r.last)} • aam tor par har ~${r.gapDays} din",
        status + if (r.store.area.isNotBlank()) " • ${r.store.area}" else "",
        iconBtn("📞") { call(r.store.phone) },
        { startActivity(Intent(this, OrderEditActivity::class.java).putExtra("store", r.store.id)) }
    )
}

// =====================================================================
// Overdue doctors
// =====================================================================
class OverdueActivity : BaseActivity() {
    private var cat = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Kaafi din se nahi mile"
    }

    override fun render() {
        val all = db.doctors().filter { it.isOverdue() }
        val chips = listOf("" to "Sab (${all.size})") + listOf("A", "B", "C").map { k -> k to "$k (${all.count { it.category == k }})" }
        content.add(chipRow(*chips.map { (k, l) -> chip(l, cat == k) { cat = k; refresh() } }.toTypedArray()))
        content.add(text("A = har hafte • B = har 15 din • C = har mahine. Category doctor edit mein badlo.", 12f, color = C.SUB).apply {
            setPadding(dp(4), dp(8), 0, dp(4))
        })
        val list = all.filter { cat.isBlank() || it.category == cat }
            .sortedWith(compareBy<Doctor> { it.category }.thenByDescending { val d = it.daysSince(); if (d < 0) 9999 else d - it.cycleDays })
        if (list.isEmpty()) content.add(empty("Shabaash! Koi doctor overdue nahi. 👏"))
        list.forEach { d ->
            val ds = d.daysSince()
            content.add(
                listRow(
                    "${d.name}  [${d.category}]",
                    listOf(d.specialty, d.area, if (d.timing.isNotBlank()) "🕐 ${d.timing}" else "").filter { it.isNotBlank() }.joinToString(" • "),
                    if (ds < 0) "⚠ Abhi tak koi visit nahi" else "⚠ $ds din se nahi mile (har ${d.cycleDays} din milna hai)",
                    iconBtn("📞") { call(d.phone) },
                    { open(DetailingActivity::class.java, d.id) }
                ), 6
            )
        }
    }
}

// =====================================================================
// Daily report (WhatsApp ke liye)
// =====================================================================
object DailyReport {
    fun build(ctx: Context, day: Long = System.currentTimeMillis()): String {
        val db = Db.get(ctx)
        val dS = Range.dayStart(day); val dE = Range.dayEnd(day)
        val sender = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("sender", "") ?: ""
        val visits = db.visitsBetween(dS, dE)
        val orders = db.orders(dS, dE).reversed()
        val invs = db.invoices(dS, dE)
        val exps = db.expenses(dS, dE)
        val tomorrow = Range.daysAhead(1)
        val fu = db.followupsBetween(tomorrow, Range.dayEnd(tomorrow))
        val docs = db.doctors().associateBy { it.id }

        val sb = StringBuilder()
        sb.append("📋 *Daily Report*").append(if (sender.isNotBlank()) " — $sender" else "").append("\n")
        sb.append("📅 ").append(Fmt.date(day)).append(" (").append(Fmt.day(day).substringBefore(",")).append(")\n\n")

        sb.append("👨‍⚕️ *Doctor visits: ${visits.size}*\n")
        if (visits.isEmpty()) sb.append("—\n")
        visits.forEachIndexed { i, v ->
            val d = docs[v.doctorId]
            sb.append("${i + 1}. ${v.doctorName}")
            if (d != null && d.area.isNotBlank()) sb.append(" (${d.area})")
            sb.append("\n")
            if (v.products.isNotBlank()) sb.append("    💊 ${v.products}\n")
            if (v.notes.isNotBlank()) sb.append("    📝 ${v.notes}\n")
        }

        sb.append("\n🧾 *Orders: ${orders.size}*")
        if (orders.isNotEmpty()) sb.append(" — ${Fmt.rs(orders.sumOf { it.net })}")
        sb.append("\n")
        orders.forEach { sb.append("• ${it.storeName} — ${Fmt.rs(it.net)}\n") }

        if (invs.isNotEmpty()) {
            sb.append("\n📄 *Supplier invoices: ${invs.size}* — ${Fmt.rs(invs.sumOf { it.amount })}\n")
            invs.forEach { sb.append("• ${it.storeName}").append(if (it.invNo.isNotBlank()) " #${it.invNo}" else "").append(" — ${Fmt.rs(it.amount)}\n") }
        }

        if (fu.isNotEmpty()) {
            sb.append("\n🔔 *Kal ke follow-ups:* ")
            sb.append(fu.joinToString(", ") { it.doctorName }).append("\n")
        }

        if (exps.isNotEmpty()) {
            val km = exps.sumOf { it.km }
            sb.append("\n⛽ *Kharcha:* ${Fmt.rs(exps.sumOf { it.amount })}")
            if (km > 0) sb.append(" • ${Fmt.plain(km)} km")
            sb.append("\n")
        }
        return sb.toString().trimEnd()
    }
}

// =====================================================================
// Subah ki notification: aaj kya kya karna hai
// =====================================================================
object Morning {
    private fun pi(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 4242, Intent(ctx, MorningReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context) {
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 8); set(Calendar.MINUTE, 30); set(Calendar.SECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_MONTH, 1)
        }
        ctx.getSystemService(AlarmManager::class.java)
            .setInexactRepeating(AlarmManager.RTC_WAKEUP, c.timeInMillis, AlarmManager.INTERVAL_DAY, pi(ctx))
    }
}

class MorningReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val db = Db.get(ctx)
        val today = Days.today()
        val docs = db.doctors()
        val tourToday = db.tourFor(System.currentTimeMillis())
        val sitting = if (tourToday.isNotEmpty()) tourToday.size else docs.count { it.dayList.isNotEmpty() && it.sitsOn(today) }
        val fu = db.dueFollowups(Range.dayEnd()).size
        val stores = Reorder.due(db).size
        val overdueA = docs.count { it.category == "A" && it.isOverdue() }
        if (fu + stores + overdueA == 0 && sitting == 0) return
        val parts = buildList {
            if (tourToday.isNotEmpty()) add("📍 ${tourToday.first().area}: $sitting calls (tour plan)")
            else if (sitting > 0) add("$sitting doctors aaj baithe hain")
            if (fu > 0) add("$fu follow-up")
            if (stores > 0) add("$stores store orders due")
            if (overdueA > 0) add("$overdueA 'A' doctors overdue")
        }
        Followups.channel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 4243, Intent(ctx, PlanActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, Followups.CH)
            .setSmallIcon(android.R.drawable.ic_menu_my_calendar)
            .setContentTitle("☀ Aaj ka plan")
            .setContentText(parts.joinToString(" • "))
            .setStyle(Notification.BigTextStyle().bigText(parts.joinToString("\n")))
            .setContentIntent(open).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(4242, n) } catch (_: SecurityException) {}
    }
}
