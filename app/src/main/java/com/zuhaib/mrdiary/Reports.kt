package com.zuhaib.mrdiary

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import java.util.Calendar

// =====================================================================
// Reports
// =====================================================================
class ReportActivity : BaseActivity() {
    private var period = 0  // 0 aaj, 1 kal, 2 pichle 7 din, 3 is mahine, 4 pichla mahina

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Reports"
    }

    private fun range(): Triple<Long, Long, String> = when (period) {
        0 -> Triple(Range.dayStart(), Range.dayEnd(), "Aaj (${Fmt.date(System.currentTimeMillis())})")
        1 -> { val y = Range.daysAgo(1); Triple(y, Range.dayEnd(y), "Kal (${Fmt.date(y)})") }
        2 -> Triple(Range.daysAgo(6), Range.dayEnd(), "Pichle 7 din")
        3 -> Triple(Range.monthStart(), Range.monthEnd(), "Is mahine")
        else -> { val t = Range.monthStart() - 1; Triple(Range.monthStart(t), Range.monthEnd(t), "Pichla mahina") }
    }

    override fun render() {
        val labels = listOf("Aaj", "Kal", "7 din", "Is mahine", "Pichla mahina")
        content.add(chipRow(*labels.mapIndexed { i, l -> chip(l, i == period) { period = i; refresh() } }.toTypedArray()))

        val (from, to, name) = range()
        val tot = db.orderTotals(from, to)
        val gross = db.grossTotal(from, to)
        val disc = db.discountTotal(from, to)
        val visits = db.visitCount(from, to)
        val docAgg = db.doctorVisitAgg(from, to)
        val prodAgg = db.productSales(from, to)
        val storeAgg = db.storeSales(from, to)

        content.add(text(name, 15f, true, C.SUB).apply { setPadding(dp(4), dp(12), 0, dp(4)) })

        // ---- big numbers ----
        val head = vbox().apply { background = rounded(C.PRIMARY, 16f); setPadding(dp(16), dp(14), dp(16), dp(16)) }
        head.add(text("Net sale", 13f, color = 0xDDFFFFFF.toInt()))
        head.add(text(Fmt.rs(tot.amount), 30f, true, Color.WHITE))
        val r = hrow()
        r.addWeighted(mini("${tot.count.toInt()}", "Orders"))
        r.addWeighted(mini("$visits", "Visits"), 8)
        r.addWeighted(mini("${docAgg.size}", "Doctors"), 8)
        head.add(r, 12)
        head.add(text("Total ${Fmt.rs(gross)} • Discount ${Fmt.rs(disc)}", 13f, color = 0xE6FFFFFF.toInt()), 10)
        content.add(head, 4)

        // ---- medicine wise ----
        section("💊 Medicine-wise sale")
        content.add(table(prodAgg, "packs") { "${Fmt.plain(it.count)} packs" })

        // ---- store wise ----
        section("🏪 Store-wise sale")
        content.add(table(storeAgg, "orders") { "${it.count.toInt()} orders" })

        // ---- doctor visits ----
        section("👨‍⚕️ Doctor visits")
        val dc = card()
        if (docAgg.isEmpty()) dc.add(empty("Is muddat mein koi visit nahi."))
        docAgg.forEach { a -> dc.add(line(a.name, "${a.count.toInt()} visit"), 6) }
        content.add(dc)

        content.add(btn("📤 Report share karo (WhatsApp)") {
            shareText(reportText(name, tot, gross, disc, visits, docAgg, prodAgg, storeAgg))
        }, 16)
    }

    private fun mini(v: String, l: String) = vbox().apply {
        background = rounded(0x26FFFFFF, 12f); setPadding(dp(10), dp(8), dp(10), dp(8))
        add(text(v, 18f, true, Color.WHITE)); add(text(l, 12f, color = 0xDDFFFFFF.toInt()))
    }

    private fun line(l: String, rgt: String, sub: String = "") = hrow().apply {
        val left = vbox()
        left.add(text(l, 15f, true))
        if (sub.isNotBlank()) left.add(text(sub, 13f, color = C.SUB))
        addView(left, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(text(rgt, 15f, true, C.PRIMARY_DARK))
    }

    private fun table(rows: List<Agg>, unit: String, sub: (Agg) -> String): LinearLayout {
        val c = card()
        if (rows.isEmpty()) c.add(empty("Is muddat mein koi sale nahi."))
        rows.forEachIndexed { i, a -> c.add(line("${i + 1}. ${a.name}", Fmt.rs(a.amount), sub(a)), if (i == 0) 0 else 10) }
        return c
    }

    private fun reportText(
        name: String, tot: Agg, gross: Double, disc: Double, visits: Int,
        docs: List<Agg>, prods: List<Agg>, stores: List<Agg>
    ): String {
        val sb = StringBuilder("*MR Report — $name*\n\n")
        sb.append("Doctor visits: $visits (${docs.size} doctors)\n")
        sb.append("Orders: ${tot.count.toInt()}\n")
        sb.append("Total: ${Fmt.rs(gross)}\nDiscount: ${Fmt.rs(disc)}\n*Net sale: ${Fmt.rs(tot.amount)}*\n")
        if (prods.isNotEmpty()) {
            sb.append("\n*Medicine-wise:*\n")
            prods.forEach { sb.append("• ${it.name}: ${Fmt.plain(it.count)} packs — ${Fmt.rs(it.amount)}\n") }
        }
        if (stores.isNotEmpty()) {
            sb.append("\n*Store-wise:*\n")
            stores.forEach { sb.append("• ${it.name}: ${it.count.toInt()} orders — ${Fmt.rs(it.amount)}\n") }
        }
        if (docs.isNotEmpty()) {
            sb.append("\n*Doctors visited:*\n")
            docs.forEach { sb.append("• ${it.name} (${it.count.toInt()})\n") }
        }
        return sb.toString().trimEnd()
    }
}

// =====================================================================
// Follow-up reminders (subah 9 baje notification)
// =====================================================================
object Followups {
    const val CH = "followups"

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) == null)
            nm.createNotificationChannel(NotificationChannel(CH, "Doctor follow-ups", NotificationManager.IMPORTANCE_HIGH))
    }

    private fun pi(ctx: Context, visitId: Long): PendingIntent = PendingIntent.getBroadcast(
        ctx, visitId.toInt(),
        Intent(ctx, FollowupReceiver::class.java).putExtra("visit", visitId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    fun schedule(ctx: Context, visitId: Long, day: Long) {
        val t = Calendar.getInstance().apply {
            timeInMillis = day; set(Calendar.HOUR_OF_DAY, 9); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        if (t <= System.currentTimeMillis()) return
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(ctx, visitId))
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(ctx, visitId))
    }

    fun cancel(ctx: Context, visitId: Long) {
        ctx.getSystemService(AlarmManager::class.java).cancel(pi(ctx, visitId))
        ctx.getSystemService(NotificationManager::class.java).cancel(visitId.toInt())
    }

    fun rescheduleAll(ctx: Context) {
        Db.get(ctx).pendingFollowups().forEach { schedule(ctx, it.id, it.followup) }
    }
}

class FollowupReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("visit", 0)
        val v = Db.get(ctx).visit(id) ?: return
        if (v.followDone) return
        Followups.channel(ctx)
        val open = PendingIntent.getActivity(
            ctx, id.toInt(),
            Intent(ctx, DoctorDetailActivity::class.java).putExtra("id", v.doctorId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val body = listOf(v.products, v.notes).filter { it.isNotBlank() }.joinToString(" • ")
            .ifBlank { "Pichli visit: ${Fmt.date(v.date)}" }
        val n = Notification.Builder(ctx, Followups.CH)
            .setSmallIcon(android.R.drawable.ic_menu_call)
            .setContentTitle("🔔 Follow-up: ${v.doctorName}")
            .setContentText(body)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id.toInt(), n) } catch (_: SecurityException) {}
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Followups.rescheduleAll(ctx)
    }
}
