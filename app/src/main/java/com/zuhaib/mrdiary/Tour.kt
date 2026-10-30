package com.zuhaib.mrdiary

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.util.Xml
import android.widget.LinearLayout
import org.xmlpull.v1.XmlPullParser
import java.util.Calendar
import java.util.zip.ZipInputStream

// =====================================================================
// Naam milana: "Javeed Phulphoto" == "Dr. Javaid Phulpoto"
// =====================================================================
object NameMatch {
    fun norm(s: String): String {
        var x = s.lowercase().replace(Regex("^\\s*dr\\.?\\s*"), "")
        x = x.replace("muhammad", "m").replace("mohammad", "m").replace("mohamamd", "m")
            .replace("ghulam", "g").replace("gullam", "g")
        x = x.replace(Regex("[^a-z]"), "")
        x = x.replace("ee", "i").replace("oo", "o").replace("aa", "a").replace("w", "v")
            .replace("q", "k").replace("ph", "f").replace(Regex("(.)\\1+"), "$1")
        return x
    }

    private fun lev(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val t = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = t
            }
        }
        return dp[b.length]
    }

    fun score(a: String, b: String): Double {
        val x = norm(a); val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return 0.0
        if (x == y) return 1.0
        val full = 1.0 - lev(x, y).toDouble() / maxOf(x.length, y.length)
        // "Asif Bhatti" vs "Asif Ali Bhatti": chhota naam bade mein shuru/aakhir se mile
        val pre = if (y.startsWith(x) || x.startsWith(y)) 0.9 else 0.0
        // Har lafz doosre naam ke kisi lafz se mile: "Masood Bidvi" ~ "Masood Ahmed Badvi"
        fun toks(s: String) = s.lowercase().replace(Regex("^\\s*dr\\.?\\s*"), "").split(Regex("[^a-z]+"))
            .map { norm(it) }.filter { it.length >= 2 }
        val ta = toks(a); val tb = toks(b)
        val (sm, bg) = if (ta.size <= tb.size) ta to tb else tb to ta
        val tok = if (sm.size >= 2 && sm.all { w -> bg.any { v -> 1.0 - lev(w, v).toDouble() / maxOf(w.length, v.length) >= 0.7 } }) 0.86 else 0.0
        return maxOf(full, pre, tok)
    }

    /** Sab se behtar doctor (aliases pehle) — 0.82 se kam ho to null */
    fun find(name: String, docs: List<Doctor>): Doctor? {
        val n = norm(name)
        docs.firstOrNull { d -> d.aliases.split("|").any { it.isNotBlank() && norm(it) == n } }?.let { return it }
        val best = docs.maxByOrNull { score(name, it.name) } ?: return null
        return if (score(name, best.name) >= 0.82) best else null
    }
}

// =====================================================================
// Excel (.xlsx) tour plan import — Date | Day | Area | Morning | # | Evening | #
// =====================================================================
object TourImport {
    data class Result(val days: Int, val calls: Int, val matched: Int, val created: List<String>, val firstDate: Long)

    private fun colIndex(ref: String): Int {
        var n = 0
        for (ch in ref) { if (!ch.isLetter()) break; n = n * 26 + (ch.uppercaseChar() - 'A' + 1) }
        return n - 1
    }

    /** Pehli sheet ki rows: har row = column index -> text */
    private fun readSheet(ctx: Context, uri: Uri): List<Map<Int, String>> {
        val files = HashMap<String, ByteArray>()
        ctx.contentResolver.openInputStream(uri)!!.use { ins ->
            ZipInputStream(ins).use { z ->
                var e = z.nextEntry
                while (e != null) {
                    if (e.name == "xl/sharedStrings.xml" || e.name.startsWith("xl/worksheets/sheet")) files[e.name] = z.readBytes()
                    e = z.nextEntry
                }
            }
        }
        val shared = ArrayList<String>()
        files["xl/sharedStrings.xml"]?.let { bytes ->
            val p = Xml.newPullParser(); p.setInput(bytes.inputStream(), "UTF-8")
            val sb = StringBuilder(); var inT = false
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                when (p.eventType) {
                    XmlPullParser.START_TAG -> { if (p.name == "si") sb.setLength(0); if (p.name == "t") inT = true }
                    XmlPullParser.TEXT -> if (inT) sb.append(p.text)
                    XmlPullParser.END_TAG -> { if (p.name == "t") inT = false; if (p.name == "si") shared.add(sb.toString()) }
                }
            }
        }
        val sheet = files["xl/worksheets/sheet1.xml"] ?: files.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted().firstOrNull()?.let { files[it] }
            ?: throw IllegalStateException("Sheet nahi mili")
        val rows = ArrayList<Map<Int, String>>()
        val p = Xml.newPullParser(); p.setInput(sheet.inputStream(), "UTF-8")
        var row = HashMap<Int, String>(); var col = 0; var type = ""; val sb = StringBuilder(); var inV = false
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            when (p.eventType) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "row" -> row = HashMap()
                    "c" -> { col = colIndex(p.getAttributeValue(null, "r") ?: "A"); type = p.getAttributeValue(null, "t") ?: ""; sb.setLength(0) }
                    "v", "t" -> inV = true
                }
                XmlPullParser.TEXT -> if (inV) sb.append(p.text)
                XmlPullParser.END_TAG -> when (p.name) {
                    "v", "t" -> inV = false
                    "c" -> {
                        val raw = sb.toString()
                        row[col] = if (type == "s") shared.getOrElse(raw.toIntOrNull() ?: -1) { "" } else raw
                    }
                    "row" -> rows.add(row)
                }
            }
        }
        return rows
    }

    private fun parseDate(s: String): Long? {
        val t = s.trim()
        t.toDoubleOrNull()?.let { serial ->
            if (serial < 30000 || serial > 80000) return null
            val c = Calendar.getInstance().apply { clear(); set(1899, Calendar.DECEMBER, 30) }
            c.add(Calendar.DAY_OF_MONTH, serial.toInt())
            return Range.dayStart(c.timeInMillis)
        }
        for (f in listOf("yyyy-MM-dd", "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-MMM-yyyy", "d MMM yyyy")) {
            try { return Range.dayStart(java.text.SimpleDateFormat(f, java.util.Locale.US).parse(t.take(10))!!.time) } catch (_: Exception) {}
        }
        return null
    }

    fun run(ctx: Context, uri: Uri): Result {
        val db = Db.get(ctx)
        val rows = readSheet(ctx, uri)
        val out = ArrayList<Db.TourRow>()
        val created = ArrayList<String>()
        var matched = 0
        val dayMap = HashMap<Long, MutableSet<String>>()
        for (r in rows) {
            val date = parseDate(r[0] ?: continue) ?: continue
            val area = (r[2] ?: "").trim()
            if (area.equals("off", true) || area.isBlank()) continue
            for ((sess, ci) in listOf("M" to 3, "E" to 5)) {
                val txt = (r[ci] ?: "").trim()
                if (txt.isBlank() || txt.equals("off", true)) continue
                txt.split(",").map { it.trim() }.filter { it.isNotBlank() }.forEachIndexed { i, name ->
                    val docs = db.doctors()
                    val d = NameMatch.find(name, docs)
                    val id = if (d != null) { matched++; db.addAlias(d.id, name); d.id }
                    else { created.add(name); db.addDoctorFromPlan("Dr. " + name, area.substringBefore(" +").trim()) }
                    out.add(Db.TourRow(date, area, sess, i, id))
                    val c = Calendar.getInstance().apply { timeInMillis = date }
                    dayMap.getOrPut(id) { mutableSetOf() }.add(Days.ALL[(c.get(Calendar.DAY_OF_WEEK) + 5) % 7])
                }
            }
        }
        if (out.isEmpty()) throw IllegalStateException("Is file mein tour plan ki rows nahi milin")
        db.replaceTour(out)
        // Doctor ke din tour plan se (sirf jin ke din khali hain)
        dayMap.forEach { (id, ds) -> if (db.doctor(id)?.days.isNullOrBlank()) db.setDays(id, Days.ALL.filter { it in ds }.joinToString(",")) }
        return Result(out.map { it.date }.distinct().size, out.size, matched, created.distinct(), out.minOf { it.date })
    }
}

// =====================================================================
// Mahine ka tour plan
// =====================================================================
class TourActivity : BaseActivity() {
    private var monthOffset = 0
    private var showGaps = false

    companion object { const val RC_XLSX = 51 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Tour plan"
        // Pehli dafa: jis mahine ka plan hai wahi dikhao
        val r = db.tourRange()
        if (r != null && db.tourDays(Range.monthStart(), Range.monthEnd()).isEmpty() && r.second > System.currentTimeMillis()) {
            val now = Calendar.getInstance(); val s = Calendar.getInstance().apply { timeInMillis = r.first }
            monthOffset = (s.get(Calendar.YEAR) - now.get(Calendar.YEAR)) * 12 + s.get(Calendar.MONTH) - now.get(Calendar.MONTH)
        }
    }

    private fun monthStart(): Long = Calendar.getInstance().apply {
        timeInMillis = Range.monthStart(); add(Calendar.MONTH, monthOffset)
    }.timeInMillis

    override fun render() {
        val ms = monthStart(); val me = Range.monthEnd(ms)
        val nav = hrow()
        nav.addView(iconBtn("◀") { monthOffset--; refresh() })
        nav.addView(text(java.text.SimpleDateFormat("MMMM yyyy", java.util.Locale.US).format(java.util.Date(ms)), 18f, true).apply {
            gravity = android.view.Gravity.CENTER
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        nav.addView(iconBtn("▶") { monthOffset++; refresh() })
        content.add(nav)
        content.add(btn("📥 Naya tour plan import karo (Excel)", 1, 14f) { pickFile() }, 10)

        val days = db.tourDays(ms, me)
        if (days.isEmpty()) {
            content.add(empty("Is mahine ka tour plan nahi hai. Excel file import karo (wahi format: Date | Day | Area | Morning | # | Evening | #)."), 10)
            return
        }
        val totalCalls = days.sumOf { it.morning + it.evening }
        val visited = db.visitsBetween(ms, me)
        content.add(text("${days.size} working days • $totalCalls planned calls • ${visited.size} visits ho chuki", 13.5f, true, C.PRIMARY_DARK).apply {
            setPadding(dp(4), dp(12), 0, dp(6))
        })

        val today = Range.dayStart()
        days.forEach { td ->
            val doneIds = visited.filter { Range.dayStart(it.date) == td.date }.map { it.doctorId }.toSet()
            val planned = db.tourFor(td.date).map { it.doctorId }.toSet()
            val done = planned.count { it in doneIds }
            val status = when {
                td.date == today -> "👉 Aaj"
                td.date < today -> "✓ $done/${planned.size} mile"
                else -> ""
            }
            val row = listRow(
                Fmt.day(td.date) + "  •  " + td.area,
                "🌅 Subah ${td.morning}   🌆 Shaam ${td.evening}",
                status, null,
                { startActivity(Intent(this, PlanActivity::class.java).putExtra("day", td.date)) }
            )
            if (td.date == today) row.background = rounded(C.PRIMARY_LIGHT, 12f, C.PRIMARY)
            content.add(row, 6)
        }

        // ---- Coverage gaps ----
        val all = db.doctors()
        val inPlan = db.tourDoctorIds(ms, me)
        val missing = all.filter { it.digilyzr == 1 && it.id !in inPlan }
        val extra = all.filter { it.notInDigilyzr && it.id in inPlan }
        section("🔍 Plan check")
        content.add(btn((if (showGaps) "▾ " else "▸ ") + "Digilyzr mein hain, plan mein nahi (${missing.size})  •  Plan mein hain, Digilyzr mein nahi (${extra.size})", 3, 13.5f) {
            showGaps = !showGaps; refresh()
        })
        if (showGaps) {
            content.add(text("⚠ Digilyzr list mein hain lekin is mahine ke plan mein nahi:", 14f, true, C.ORANGE).apply { setPadding(dp(4), dp(10), 0, dp(2)) })
            missing.groupBy { it.area }.toSortedMap().forEach { (area, l) ->
                content.add(text("$area: " + l.joinToString(", ") { it.name.removePrefix("Dr. ") + " (${it.specialty})" }, 13.5f).apply { setPadding(dp(4), dp(4), 0, 0) })
            }
            content.add(text("➕ Plan mein hain lekin Digilyzr mein nahi (Digilyzr mein add karwao):", 14f, true, C.ORANGE).apply { setPadding(dp(4), dp(14), 0, dp(2)) })
            content.add(text(extra.joinToString(", ") { it.name.removePrefix("Dr. ") + " (${it.area})" }, 13.5f).apply { setPadding(dp(4), dp(4), 0, 0) })
        }
    }

    private fun pickFile() {
        val i = Intent(Intent.ACTION_GET_CONTENT).apply {
            type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "application/octet-stream", "application/zip"))
        }
        try { startActivityForResult(Intent.createChooser(i, "Tour plan ki Excel file chuno"), RC_XLSX) }
        catch (e: Exception) { toast("File picker nahi khula") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(rc: Int, result: Int, data: Intent?) {
        super.onActivityResult(rc, result, data)
        if (rc != RC_XLSX || result != Activity.RESULT_OK) return
        val uri = data?.data ?: return
        try {
            val r = TourImport.run(this, uri)
            val msg = "✓ ${r.days} din, ${r.calls} calls import ho gayi.\n${r.matched} calls purane doctors se mil gayin." +
                if (r.created.isNotEmpty()) "\n\n${r.created.size} naye naam (list mein nahi mile, naye doctor bana diye):\n" + r.created.joinToString(", ") else ""
            android.app.AlertDialog.Builder(this).setTitle("Tour plan").setMessage(msg).setPositiveButton("OK", null).show()
            val now = Calendar.getInstance(); val st = Calendar.getInstance().apply { timeInMillis = r.firstDate }
            monthOffset = (st.get(Calendar.YEAR) - now.get(Calendar.YEAR)) * 12 + st.get(Calendar.MONTH) - now.get(Calendar.MONTH)
        } catch (e: Exception) {
            toast("Import nahi hua: ${e.message}")
        }
        refresh()
    }
}
