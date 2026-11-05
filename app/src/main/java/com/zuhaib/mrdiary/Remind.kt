package com.zuhaib.mrdiary

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Calendar

// =====================================================================
// Kaam ke reminders — maslan "RCH working, Thursday 4:30 PM, 30 min pehle"
// =====================================================================
object Work {
    const val CH = "work_alarms_v1"

    fun channel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CH) != null) return
        nm.createNotificationChannel(NotificationChannel(CH, "Kaam ke reminders", NotificationManager.IMPORTANCE_HIGH).apply {
            enableVibration(true); vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
    }

    /** stage 0 = "X min pehle", stage 1 = "abhi time ho gaya" */
    private fun pi(ctx: Context, id: Long, stage: Int, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) = PendingIntent.getBroadcast(
        ctx, 800000 + id.toInt() * 2 + stage,
        Intent(ctx, WorkAlarmReceiver::class.java).putExtra("id", id).putExtra("stage", stage),
        flags or PendingIntent.FLAG_IMMUTABLE)

    /** Agla waqt: din list mein ho, aur abhi se aage */
    fun next(r: WorkReminder, offsetMin: Int): Long? {
        val days = Days.parse(r.days).ifEmpty { Days.ALL }
        val now = System.currentTimeMillis()
        for (i in 0..7) {
            val c = Calendar.getInstance().apply {
                add(Calendar.DAY_OF_MONTH, i)
                set(Calendar.HOUR_OF_DAY, r.hour); set(Calendar.MINUTE, r.minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            val dayName = Days.ALL[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7]
            val t = c.timeInMillis - offsetMin * 60_000L
            if (dayName in days && t > now + 5_000) return t
        }
        return null
    }

    private fun setAt(ctx: Context, t: Long, p: PendingIntent) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val exact = Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()
        if (exact) am.setAlarmClock(AlarmManager.AlarmClockInfo(t, null), p)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, p)
    }

    fun schedule(ctx: Context, r: WorkReminder) {
        cancel(ctx, r.id)
        if (!r.enabled) return
        if (r.before > 0) next(r, r.before)?.let { setAt(ctx, it, pi(ctx, r.id, 0)) }
        next(r, 0)?.let { setAt(ctx, it, pi(ctx, r.id, 1)) }
    }

    fun cancel(ctx: Context, id: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.cancel(pi(ctx, id, 0)); am.cancel(pi(ctx, id, 1))
    }

    fun rescheduleAll(ctx: Context) = Db.get(ctx).reminders().forEach { schedule(ctx, it) }

    // ---------- Doctor ke jaane se pehle ----------
    private fun leavePi(ctx: Context, doctorId: Long, flags: Int = PendingIntent.FLAG_UPDATE_CURRENT) = PendingIntent.getBroadcast(
        ctx, 700000 + doctorId.toInt(),
        Intent(ctx, LeaveSoonReceiver::class.java).putExtra("doctor", doctorId), flags or PendingIntent.FLAG_IMMUTABLE)

    /** Aaj ke plan ke jin doctors ka "kab tak" pata hai, un ke jaane se 30 min pehle alert */
    fun scheduleLeaveSoon(ctx: Context) {
        val db = Db.get(ctx)
        val today = db.tourFor(System.currentTimeMillis())
        if (today.isEmpty()) return
        val docs = db.doctors().associateBy { it.id }
        val now = System.currentTimeMillis()
        today.map { it.doctorId }.distinct().forEach { id ->
            val d = docs[id] ?: return@forEach
            val m = Days.minutes(d.timeTo)
            if (m >= 24 * 60) return@forEach
            val t = Range.dayStart() + (m - 30) * 60_000L
            if (t > now) setAt(ctx, t, leavePi(ctx, id))
        }
    }
}

class WorkAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("id", 0)
        val stage = intent.getIntExtra("stage", 1)
        val db = Db.get(ctx)
        val r = db.reminder(id) ?: return
        Work.schedule(ctx, r)   // agle hafte ka set
        if (!r.enabled) return
        val docs = if (r.placeId > 0) Geo.doctorsAt(ctx, r.placeId) else emptyList()
        val place = if (r.placeId > 0) db.place(r.placeId)?.name ?: "" else ""
        val title = if (stage == 0) "⏰ ${r.before} min mein: ${r.title}" else "🚨 Abhi time hai: ${r.title}"
        val body = buildString {
            append(r.timeText)
            if (place.isNotBlank()) append(" • ").append(place)
            if (docs.isNotEmpty()) {
                append("\n")
                docs.take(10).forEach { d ->
                    append("\n• ").append(d.name.removePrefix("Dr. "))
                    if (d.timeTo.isNotBlank()) append(" — ${d.timeTo} tak")
                }
            }
            if (stage == 0) append("\n\nAbhi nikal jao, warna doctors chale jayenge.")
        }
        Work.channel(ctx)
        val open = PendingIntent.getActivity(ctx, 810000 + id.toInt(),
            if (r.placeId > 0) Intent(ctx, NearbyActivity::class.java).putExtra("place", r.placeId) else Intent(ctx, RemindersActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, Work.CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title).setContentText(body.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(810000 + id.toInt() * 2 + stage, n) } catch (_: SecurityException) {}
    }
}

class LeaveSoonReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getLongExtra("doctor", 0)
        val db = Db.get(ctx)
        val d = db.doctor(id) ?: return
        if (db.visitsBetween(Range.dayStart(), Range.dayEnd()).any { it.doctorId == id }) return  // mil chuke
        Work.channel(ctx)
        val open = PendingIntent.getActivity(ctx, 720000 + id.toInt(),
            Intent(ctx, DetailingActivity::class.java).putExtra("id", id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val where = d.clinic.substringBefore(" / ")
        val n = Notification.Builder(ctx, Work.CH)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle("⏳ ${d.name} 30 min mein chale jayenge")
            .setContentText("${d.timeTo} tak baithte hain" + if (where.isNotBlank()) " • $where" else "")
            .setContentIntent(open).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(720000 + id.toInt(), n) } catch (_: SecurityException) {}
    }
}

// =====================================================================
// Reminders screen
// =====================================================================
class RemindersActivity : BaseActivity() {
    private val BEFORE = listOf(0, 15, 30, 45, 60, 90)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Kaam ke reminders"
        Geo.seedOnce(this)
    }

    override fun render() {
        content.add(btn("+ Naya reminder") { form(null) })
        content.add(text("Maslan: \"RCH working\" Thursday 4:30 PM, 30 min pehle. Alarm ki awaaz ke saath aata hai — pehle bhi aur time par bhi.", 12.5f, color = C.SUB).apply {
            setPadding(dp(4), dp(8), 0, dp(4))
        })
        val list = db.reminders()
        if (list.isEmpty()) content.add(empty("Koi reminder nahi."))
        list.forEach { r ->
            val place = if (r.placeId > 0) db.place(r.placeId)?.name ?: "" else ""
            val nxt = Work.next(r, 0)
            val sub = "${r.timeText} • ${Days.text(Days.parse(r.days)).ifBlank { "Rozana" }}" +
                (if (r.before > 0) " • ${r.before} min pehle" else "")
            val row = listRow((if (r.enabled) "⏰ " else "🔕 ") + r.title, sub,
                listOf(place, if (r.enabled && nxt != null) "Agla: " + Fmt.dateTime(nxt) else "").filter { it.isNotBlank() }.joinToString(" • "),
                iconBtn(if (r.enabled) "🔔" else "🔕") {
                    val n = r.copy(enabled = !r.enabled); db.saveReminder(n); Work.schedule(this, n); refresh()
                },
                { form(r) },
                { confirm("\"${r.title}\" delete karna hai?", "Delete") { Work.cancel(this, r.id); db.deleteReminder(r.id); refresh() } })
            if (!r.enabled) row.alpha = 0.55f
            content.add(row, 6)
        }
        content.add(text("ℹ Aaj ke tour plan ke jin doctors ka \"kab tak\" time likha hai, un ke jaane se 30 min pehle bhi khud alert aata hai.", 12.5f, color = C.SUB).apply {
            setPadding(dp(4), dp(14), 0, 0)
        })
    }

    private fun form(ex: WorkReminder?) {
        var h = ex?.hour ?: 16; var m = ex?.minute ?: 30
        var before = ex?.before ?: 30
        val title = input("Maslan: RCH working", ex?.title ?: "")
        val timeB = btn("", 1, 15f) {}
        fun showTime() { timeB.text = "🕐 " + Fmt.time(Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }.timeInMillis) }
        showTime()
        timeB.setOnClickListener { TimePickerDialog(this, { _, hh, mm -> h = hh; m = mm; showTime() }, h, m, false).show() }

        val dayChecked = BooleanArray(7) { Days.ALL[it] in Days.parse(ex?.days ?: "") }
        val dayViews = ArrayList<TextView>()
        fun paintDays() = dayViews.forEachIndexed { i, v ->
            v.setTextColor(if (dayChecked[i]) Color.WHITE else C.TEXT)
            v.background = rounded(if (dayChecked[i]) C.PRIMARY else Color.WHITE, 18f, if (dayChecked[i]) 0 else C.BORDER)
        }
        Days.ALL.forEachIndexed { i, d -> dayViews.add(chip(d, false) { dayChecked[i] = !dayChecked[i]; paintDays() }) }
        paintDays()

        val beforeViews = ArrayList<TextView>()
        fun paintBefore() = beforeViews.forEachIndexed { i, v ->
            val sel = BEFORE[i] == before
            v.setTextColor(if (sel) Color.WHITE else C.TEXT)
            v.background = rounded(if (sel) C.PRIMARY else Color.WHITE, 18f, if (sel) 0 else C.BORDER)
        }
        BEFORE.forEach { b -> beforeViews.add(chip(if (b == 0) "Sirf time par" else "$b min", false) { before = b; paintBefore() }) }
        paintBefore()

        val places = db.places()
        var placeId = ex?.placeId ?: 0L
        val placeB = btn("", 1, 14f) {}
        fun showPlace() { placeB.text = "📍 " + (places.firstOrNull { it.id == placeId }?.name ?: "Jagah (optional) ▾") }
        showPlace()
        placeB.setOnClickListener {
            pick("Kahan?", listOf("— Koi nahi —") + places.map { it.name }) { i -> placeId = if (i == 0) 0 else places[i - 1].id; showPlace() }
        }

        val body = vbox()
        body.add(text("Kaam", 13f, color = C.SUB), 4); body.add(title)
        body.add(text("Time", 13f, color = C.SUB), 12); body.add(timeB, 4)
        body.add(text("Kin dinon? (koi na chuno = rozana)", 13f, color = C.SUB), 12); body.add(chipRow(*dayViews.toTypedArray()), 4)
        body.add(text("Kitna pehle yaad dilaun?", 13f, color = C.SUB), 12); body.add(chipRow(*beforeViews.toTypedArray()), 4)
        body.add(text("Jagah — notification mein wahan ke doctors bhi aayenge", 13f, color = C.SUB), 12); body.add(placeB, 4)

        formDialog(if (ex == null) "Naya reminder" else "Reminder edit", body) {
            if (title.str().isEmpty()) { title.error = "Kaam ka naam likho"; return@formDialog false }
            val r = WorkReminder(ex?.id ?: 0, title.str(), h, m, Days.ALL.filterIndexed { i, _ -> dayChecked[i] }.joinToString(","),
                before, true, placeId)
            val id = db.saveReminder(r)
            Work.schedule(this, r.copy(id = id))
            toast("✓ Reminder set: " + (Work.next(r.copy(id = id), before)?.let { Fmt.dateTime(it) } ?: ""))
            refresh(); true
        }
    }
}
