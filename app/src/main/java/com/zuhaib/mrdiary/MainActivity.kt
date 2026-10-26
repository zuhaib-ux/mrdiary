package com.zuhaib.mrdiary

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout

class MainActivity : BaseActivity() {
    override val showBack = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "MR Diary"
        Followups.channel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 5)
    }

    override fun render() {
        val now = System.currentTimeMillis()
        val dS = Range.dayStart(); val dE = Range.dayEnd()
        val today = db.orderTotals(dS, dE)
        val month = db.orderTotals(Range.monthStart(), Range.monthEnd())
        val visits = db.visitCount(dS, dE)

        // ---- Header ----
        val head = vbox().apply {
            background = rounded(C.PRIMARY, 16f)
            setPadding(dp(16), dp(14), dp(16), dp(16))
        }
        head.add(text(Fmt.day(now), 14f, color = 0xCCFFFFFF.toInt()))
        head.add(text("Aaj ka kaam", 20f, true, Color.WHITE), 2)
        val stats = hrow()
        stats.addWeighted(stat("$visits", "Visits"))
        stats.addWeighted(stat("${today.count.toInt()}", "Orders"))
        stats.addWeighted(stat(Fmt.n(today.amount), "Sale (Rs)"))
        head.add(stats, 12)
        head.add(text("Is mahine: ${month.count.toInt()} orders • ${Fmt.rs(month.amount)}", 13.5f, color = 0xE6FFFFFF.toInt()), 12)
        content.add(head)

        // ---- Tiles ----
        val tiles = listOf(
            Triple("💊", "Products", "Literature, script, voice") to { open(ProductListActivity::class.java) },
            Triple("🧾", "Naya Order", "Store ka order likho") to { open(OrderEditActivity::class.java) },
            Triple("👨‍⚕️", "Doctors", "Call, visits, follow-up") to { open(DoctorListActivity::class.java) },
            Triple("🏪", "Stores", "Medical stores ki list") to { open(StoreListActivity::class.java) },
            Triple("📋", "Orders", "Purane orders") to { open(OrderListActivity::class.java) },
            Triple("📊", "Reports", "Din / mahine ka hisaab") to { open(ReportActivity::class.java) },
        )
        tiles.chunked(2).forEach { pair ->
            val row = hrow()
            pair.forEach { (t, action) -> row.addWeighted(tile(t.first, t.second, t.third, action), 10) }
            content.add(row, 10)
        }

        // ---- Follow-ups ----
        val due = db.dueFollowups(dE)
        section("🔔 Aaj ke follow-ups (${due.size})")
        if (due.isEmpty()) content.add(empty("Aaj koi follow-up nahi. Doctor visit mein follow-up date daalo to yahan nazar aayegi."))
        due.forEach { v ->
            val late = v.followup < dS
            val doneBtn = iconBtn("✓") { db.setFollowDone(v.id); Followups.cancel(this, v.id); refresh() }
            content.add(
                listRow(
                    v.doctorName,
                    (if (late) "⚠ ${Fmt.date(v.followup)} se pending" else "Aaj") +
                        (if (v.products.isNotBlank()) " • ${v.products}" else ""),
                    if (v.notes.isNotBlank()) v.notes else "",
                    doneBtn, { open(DoctorDetailActivity::class.java, v.doctorId) }
                ), 6
            )
        }
    }

    private fun stat(value: String, label: String) = vbox().apply {
        background = rounded(0x26FFFFFF, 12f)
        setPadding(dp(10), dp(8), dp(10), dp(8))
        add(text(value, 19f, true, Color.WHITE))
        add(text(label, 12f, color = 0xDDFFFFFF.toInt()))
    }

    private fun tile(emoji: String, title: String, sub: String, action: () -> Unit): LinearLayout = vbox().apply {
        background = android.graphics.drawable.RippleDrawable(
            android.content.res.ColorStateList.valueOf(0x22000000), rounded(Color.WHITE, 16f, C.BORDER), null
        )
        setPadding(dp(14), dp(14), dp(14), dp(14))
        gravity = Gravity.START
        add(text(emoji, 28f))
        add(text(title, 16f, true), 6)
        add(text(sub, 12.5f, color = C.SUB), 2)
        setOnClickListener { action() }
    }
}
