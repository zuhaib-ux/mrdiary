package com.zuhaib.mrdiary

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.LinearLayout

// =====================================================================
// Jagahein: Google Maps se li hui, aur doctors ke clinic ke naam se jodi hui
// =====================================================================
object Geo {
    private class Seed(val name: String, val lat: Double, val lon: Double, val radius: Int, val keys: List<String>)

    private val seeds = listOf(
        Seed("Civil Hospital Sukkur / GMMMC", 27.7017, 68.8748, 300, listOf("CIVIL HOSPITAL SUKKUR", "GMMMC", "GHULAM MUHAMMAD MEDICAL COLLEGE", "DEEWAN", "CCU")),
        Seed("Hira Medical Centre Sukkur", 27.6912, 68.8554, 180, listOf("HIRA MEDICAL")),
        Seed("Workshop Road Sukkur (Sakhi Clinic)", 27.6929, 68.8550, 150, listOf("WORK SHOP", "WORKSHOP")),
        Seed("Bhatti Hospital Sukkur", 27.6932, 68.8583, 150, listOf("BHATTI HOSPITAL")),
        Seed("Hindu Panchayat Hospital Sukkur", 27.6889, 68.8597, 150, listOf("PANCHIAT", "PANCHAYAT")),
        Seed("Diabetic Care Clinic, Bunder Road", 27.6939, 68.8719, 150, listOf("DIABETIC CARE")),
        Seed("River City Hospital (RCH) Sukkur", 27.7255, 68.8148, 250, listOf("RIVER CITY")),
        Seed("NICVD Sukkur", 27.7016, 68.8342, 300, listOf("NICVD")),
        Seed("Behar Colony Sukkur", 27.7051, 68.8403, 400, listOf("BEHAR COLONY")),
        Seed("New Goth Sukkur", 27.7036, 68.8508, 300, listOf("NEW GOTH")),
        Seed("100 Ft Road Sukkur", 27.7116, 68.8368, 400, listOf("100 FT")),
        Seed("Civil Hospital Khairpur", 27.5165, 68.7491, 300, listOf("CIVIL HOSPITAL KHAIR")),
        Seed("Khairpur Medical College", 27.5236, 68.7455, 250, listOf("KHAIRPUR MEDICAL COLLEGE")),
        Seed("Mehran Medical Centre Khairpur", 27.5196, 68.7436, 150, listOf("MEHRAN MEDICAL")),
        Seed("Kamran Clinic Khairpur", 27.5327, 68.7629, 150, listOf("KAMRAN CLINIC")),
        Seed("Shahani Bone Care Khairpur", 27.5136, 68.7580, 150, listOf("SHAHANI")),
        Seed("Civil Hospital Shikarpur (Brohi Mohalla)", 27.9522, 68.6463, 250, listOf("BROHI CLINIC")),
        Seed("Jahaz Chowk Shikarpur", 27.9517, 68.6410, 200, listOf("JAHAZ")),
        Seed("Hiranand Gangabai Hospital Shikarpur", 27.9527, 68.6381, 150, listOf("GANGA BAI")),
        Seed("Fatima Medicare Shikarpur", 27.9564, 68.6377, 150, listOf("FATIMA MEDICARE")),
        Seed("GIMS Gambat", 27.3452, 68.5398, 450, listOf("GAMBAT INSTITUTE", "GIMS")),
    )

    /** Pehli dafa: jagahein + doctors jodo + RCH ka reminder */
    fun seedOnce(ctx: Context) {
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (prefs.getBoolean("geo_seed_v1", false)) return
        try {
            val db = Db.get(ctx)
            val docs = db.doctors()
            var rch = 0L
            seeds.forEach { s ->
                val id = db.savePlace(Place(0, s.name, s.lat, s.lon, s.radius))
                if (s.keys.contains("RIVER CITY")) rch = id
                val ids = docs.filter { d -> s.keys.any { k -> d.clinic.uppercase().contains(k) } }.map { it.id }
                db.setPlaceDoctors(id, ids)
            }
            if (db.reminders().isEmpty())
                db.saveReminder(WorkReminder(0, "RCH (River City) working", 16, 30, "Thu", 30, true, rch))
        } catch (_: Exception) {}
        prefs.edit().putBoolean("geo_seed_v1", true).apply()
    }

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val r = FloatArray(1); Location.distanceBetween(lat1, lon1, lat2, lon2, r); return r[0]
    }

    fun distText(m: Float) = if (m < 1000) "${m.toInt()} m" else String.format(java.util.Locale.US, "%.1f km", m / 1000)

    fun hasFine(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    fun hasBackground(ctx: Context) = Build.VERSION.SDK_INT < 29 ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun alertsOn(ctx: Context) = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("arrive_alerts", false)

    private fun pi(ctx: Context, placeId: Long, flags: Int) = PendingIntent.getBroadcast(
        ctx, 500000 + placeId.toInt(),
        Intent(ctx, ArriveReceiver::class.java).putExtra("place", placeId), flags
    )

    /** Har jagah ke gird "pahunchne" ka alert (Android ka apna proximity alert, Google Play ki zaroorat nahi) */
    @SuppressLint("MissingPermission")
    fun registerAll(ctx: Context) {
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        val places = Db.get(ctx).places()
        places.forEach { p ->
            pi(ctx, p.id, PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_MUTABLE)?.let { lm.removeProximityAlert(it) }
        }
        if (!alertsOn(ctx) || !hasFine(ctx)) return
        places.forEach { p ->
            try {
                lm.addProximityAlert(p.lat, p.lon, p.radius.toFloat(), -1,
                    pi(ctx, p.id, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE))
            } catch (_: Exception) {}
        }
    }

    /** Is jagah ke doctors, aaj ke plan wale pehle */
    fun doctorsAt(ctx: Context, placeId: Long): List<Doctor> {
        val db = Db.get(ctx)
        val ids = db.placeDoctorIds(placeId)
        val plan = db.tourFor(System.currentTimeMillis()).map { it.doctorId }.toSet()
        val visited = db.visitsBetween(Range.dayStart(), Range.dayEnd()).map { it.doctorId }.toSet()
        return db.doctors().filter { it.id in ids }
            .sortedWith(compareBy<Doctor> { it.id in visited }.thenBy { it.id !in plan }.thenBy { !it.fav }.thenBy { it.name })
    }
}

/** Kisi jagah pahunche to notification: yahan yeh doctors hain */
class ArriveReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) return
        val pid = intent.getLongExtra("place", 0)
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val key = "arrived_$pid"
        if (System.currentTimeMillis() - prefs.getLong(key, 0) < 2 * 3600_000L) return   // 2 ghante mein dobara nahi
        prefs.edit().putLong(key, System.currentTimeMillis()).apply()
        val db = Db.get(ctx)
        val p = db.place(pid) ?: return
        val docs = Geo.doctorsAt(ctx, pid)
        if (docs.isEmpty()) return
        val plan = db.tourFor(System.currentTimeMillis()).map { it.doctorId }.toSet()
        val visited = db.visitsBetween(Range.dayStart(), Range.dayEnd()).map { it.doctorId }.toSet()
        val lines = docs.take(12).map { d ->
            (if (d.id in visited) "✓ " else if (d.id in plan) "📅 " else "• ") + d.name.removePrefix("Dr. ") +
                (if (d.specialty.isNotBlank()) " — ${d.specialty}" else "") +
                (if (d.timing.isNotBlank()) " (${d.timing})" else "")
        }
        val inPlan = docs.count { it.id in plan && it.id !in visited }
        Followups.channel(ctx)
        val open = PendingIntent.getActivity(ctx, 600000 + pid.toInt(),
            Intent(ctx, NearbyActivity::class.java).putExtra("place", pid),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, Followups.CH)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("📍 ${p.name}: ${docs.size} doctors")
            .setContentText(if (inPlan > 0) "$inPlan aaj ke plan mein • " + lines.first() else lines.first())
            .setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
            .setContentIntent(open).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(600000 + pid.toInt(), n) } catch (_: SecurityException) {}
    }
}

// =====================================================================
// 📍 Mere paas kaun — GPS se jagah, aur wahan ke doctors
// =====================================================================
class NearbyActivity : BaseActivity() {
    private var loc: Location? = null
    private var status = "Location dhoondi ja rahi hai…"
    private var open = HashSet<Long>()
    private var lm: LocationManager? = null
    private val listener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            if (loc == null || l.accuracy <= (loc?.accuracy ?: 9999f) || l.time - (loc?.time ?: 0) > 20_000) {
                loc = l; status = "Location mil gayi (±${l.accuracy.toInt()} m)"; refresh()
            }
        }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
        override fun onProviderEnabled(p: String) {}
        override fun onProviderDisabled(p: String) {}
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "Mere paas kaun?"
        Geo.seedOnce(this)
        intent.getLongExtra("place", 0).takeIf { it > 0 }?.let { open.add(it) }
    }

    override fun onResume() {
        super.onResume()
        startGps()
    }

    override fun onPause() {
        super.onPause()
        lm?.removeUpdates(listener)
    }

    @SuppressLint("MissingPermission")
    private fun startGps() {
        if (!Geo.hasFine(this)) {
            status = "Location ki ijazat chahiye"
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 41)
            refresh(); return
        }
        val m = getSystemService(LocationManager::class.java) ?: return
        lm = m
        if (!m.isProviderEnabled(LocationManager.GPS_PROVIDER) && !m.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            status = "Phone ki Location (GPS) band hai — upar se on karo"; refresh(); return
        }
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { pr ->
            try {
                m.getLastKnownLocation(pr)?.let { l -> if (System.currentTimeMillis() - l.time < 10 * 60_000 && (loc == null || l.accuracy < loc!!.accuracy)) loc = l }
                m.requestLocationUpdates(pr, 5000L, 10f, listener, Looper.getMainLooper())
            } catch (_: Exception) {}
        }
        if (loc != null) status = "Location (±${loc!!.accuracy.toInt()} m) — behtar ho rahi hai…"
        refresh()
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 41) { if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startGps() else { status = "Location ki ijazat nahi mili"; refresh() } }
        if (rc == 42) { Geo.registerAll(this); refresh() }
    }

    override fun render() {
        val places = db.places()
        val l = loc
        val withDist = places.map { p -> p to (if (l != null) Geo.dist(l.latitude, l.longitude, p.lat, p.lon) else Float.MAX_VALUE) }
            .sortedBy { it.second }
        val here = withDist.firstOrNull { l != null && it.second <= it.first.radius + (l.accuracy.coerceAtMost(150f)) }

        // ---- Header ----
        val head = vbox().apply { background = rounded(C.PRIMARY, 14f); setPadding(dp(14), dp(10), dp(14), dp(12)) }
        head.add(text(status, 13f, color = 0xDDFFFFFF.toInt()))
        if (here != null) {
            head.add(text("📍 Aap yahan hain:", 13.5f, color = 0xE6FFFFFF.toInt()), 6)
            head.add(text(here.first.name, 20f, true, Color.WHITE))
            open.add(here.first.id)
        } else if (l != null && withDist.isNotEmpty()) {
            val n = withDist.first()
            head.add(text("Sab se qareeb: ${n.first.name}", 17f, true, Color.WHITE), 6)
            head.add(text(Geo.distText(n.second) + " door", 13.5f, color = 0xE6FFFFFF.toInt()))
        }
        val hr = hrow()
        hr.addWeighted(btn("🔄 Dobara dhoondo", 3, 13f) { loc = null; status = "Location dhoondi ja rahi hai…"; lm?.removeUpdates(listener); startGps() })
        hr.addWeighted(btn("➕ Yahan ki jagah save", 3, 13f) { saveHere() }, 6)
        head.add(hr, 10)
        content.add(head)

        // ---- Arrival alerts toggle ----
        val on = Geo.alertsOn(this) && Geo.hasFine(this)
        content.add(btn(if (on) "🔔 Pahunchne par notification: ON" else "🔕 Pahunchne par notification: OFF (on karo)", if (on) 0 else 1, 14f) { toggleAlerts() }, 10)
        if (on && !Geo.hasBackground(this)) content.add(text("⚠ App band ho tab bhi alert ke liye: Settings → Location → \"Allow all the time\" chuno", 12.5f, true, C.ORANGE).apply {
            setPadding(dp(4), dp(6), 0, 0); setOnClickListener { openAppSettings() }
        })

        // ---- Jagahein ----
        section("🏥 Jagahein (${places.size})")
        withDist.forEach { (p, d) ->
            val docs = Geo.doctorsAt(this, p.id)
            val isOpen = p.id in open
            val sub = (if (d != Float.MAX_VALUE) Geo.distText(d) + " door • " else "") + "${docs.size} doctors"
            val row = listRow((if (here?.first?.id == p.id) "📍 " else "") + p.name, sub, "",
                iconBtn("🗺") { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(${Uri.encode(p.name)})"))) },
                { if (isOpen) open.remove(p.id) else open.add(p.id); refresh() },
                { placeMenu(p) })
            if (here?.first?.id == p.id) row.background = rounded(C.PRIMARY_LIGHT, 12f, C.PRIMARY)
            content.add(row, 6)
            if (isOpen) {
                val plan = db.tourFor(System.currentTimeMillis()).map { it.doctorId }.toSet()
                val visited = db.visitsBetween(Range.dayStart(), Range.dayEnd()).map { it.doctorId }.toSet()
                if (docs.isEmpty()) content.add(empty("Is jagah koi doctor nahi joda. Naam dabaa kar rakho → doctors chuno."))
                docs.forEach { doc ->
                    val tags = listOf(
                        if (doc.id in visited) "✓ Aaj mil liye" else if (doc.id in plan) "📅 Aaj ke plan mein" else "",
                        doc.timing
                    ).filter { it.isNotBlank() }.joinToString(" • ")
                    content.add(listRow("   " + doc.label, doc.specialty, tags, iconBtn("📞") { call(doc.phone) },
                        { open(DetailingActivity::class.java, doc.id) }).apply { if (doc.id in visited) alpha = 0.55f }, 4)
                }
            }
        }
    }

    private fun placeMenu(p: Place) {
        pick(p.name, listOf("👨‍⚕️ Doctors chuno", "✏ Naam badlo", "📍 Jagah ko meri abhi ki location par set karo", "🗑 Delete")) { i ->
            when (i) {
                0 -> chooseDoctors(p.id)
                1 -> { val b = input("Naam", p.name); formDialog("Jagah ka naam", b) { db.savePlace(p.copy(name = b.str())); refresh(); true } }
                2 -> loc?.let { l -> db.savePlace(p.copy(lat = l.latitude, lon = l.longitude)); Geo.registerAll(this); toast("✓ Jagah update"); refresh() }
                    ?: toast("Pehle location milne do")
                3 -> confirm("${p.name} delete karni hai?", "Delete") { db.deletePlace(p.id); Geo.registerAll(this); refresh() }
            }
        }
    }

    private fun chooseDoctors(placeId: Long) {
        val docs = db.doctors().sortedBy { it.name }
        val cur = db.placeDoctorIds(placeId)
        val checked = BooleanArray(docs.size) { docs[it].id in cur }
        AlertDialog.Builder(this).setTitle("Yahan kaun baithta hai?")
            .setMultiChoiceItems(docs.map { it.name + if (it.area.isNotBlank()) " (${it.area})" else "" }.toTypedArray(), checked) { _, i, b -> checked[i] = b }
            .setPositiveButton("Save") { _, _ -> db.setPlaceDoctors(placeId, docs.filterIndexed { i, _ -> checked[i] }.map { it.id }); refresh() }
            .setNegativeButton("Cancel", null).show()
    }

    private fun saveHere() {
        val l = loc ?: run { toast("Pehle location milne do"); return }
        val name = input("Maslan: Maheem Clinic Sukkur", "")
        formDialog("Yeh jagah save karo", form("Jagah / clinic ka naam" to name,
            "Location" to text("±${l.accuracy.toInt()} m • ${"%.5f".format(l.latitude)}, ${"%.5f".format(l.longitude)}", 13f, color = C.SUB))) {
            if (name.str().isEmpty()) { name.error = "Naam likho"; return@formDialog false }
            val id = db.savePlace(Place(0, name.str(), l.latitude, l.longitude, 150))
            Geo.registerAll(this); open.add(id); refresh(); chooseDoctors(id); true
        }
    }

    private fun toggleAlerts() {
        val prefs = getSharedPreferences("prefs", MODE_PRIVATE)
        if (Geo.alertsOn(this) && Geo.hasFine(this)) {
            prefs.edit().putBoolean("arrive_alerts", false).apply(); Geo.registerAll(this); refresh(); return
        }
        if (!Geo.hasFine(this)) { requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 41); return }
        prefs.edit().putBoolean("arrive_alerts", true).apply()
        Geo.registerAll(this)
        if (!Geo.hasBackground(this)) {
            AlertDialog.Builder(this).setTitle("App band ho tab bhi alert?")
                .setMessage("Jab app band ho tab bhi pahunchne par notification chahiye to agli screen par Location → \"Allow all the time\" chuno.")
                .setPositiveButton("Theek hai") { _, _ ->
                    if (Build.VERSION.SDK_INT >= 30) openAppSettings()
                    else requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 42)
                }.setNegativeButton("Baad mein", null).show()
        }
        toast("✓ Pahunchne par notification ON"); refresh()
    }

    private fun openAppSettings() {
        try { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } catch (_: Exception) {}
    }
}
