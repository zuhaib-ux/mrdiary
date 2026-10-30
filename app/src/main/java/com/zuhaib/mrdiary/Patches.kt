package com.zuhaib.mrdiary

import android.content.Context
import java.util.Calendar

/**
 * Field se aayi maloomat (doctor ke products, timing, naye doctors) — ek dafa lagti hai.
 * Product spec: "Redep" = saari strengths, "Redep:50mg" = sirf 50mg, "Recetam:250mg,500mg"
 */
object Patches {
    private class P(
        val find: List<String>,                   // "#code" ya naam (pehla jo mil jaye)
        val products: Map<Int, List<String>> = emptyMap(),
        val from: String = "", val to: String = "", val days: String = "",
        val newName: String = "", val spec: String = "", val clinic: String = "", val area: String = "",
        val rename: String = "", val tourThuEvening: Boolean = false
    )

    private const val W = Kinds.WRITING
    private const val R = Kinds.REQUEST
    private const val M = Kinds.REMIND
    private const val MON_SAT = "Mon,Tue,Wed,Thu,Fri,Sat"

    private val list = listOf(
        P(listOf("Asif Khaskhali", "Asif Khaskheli"), mapOf(W to listOf("Olagen Plus", "Redep", "Caripz", "Lalap", "Recetam", "Riveme")),
            rename = "Dr. Asif Khaskheli", clinic = "Civil Hospital Khairpur", area = "Khairpur"),
        P(listOf("Gordhan Das"), mapOf(W to listOf("Riveme", "Tadlif"), R to listOf("Redep", "Olagen Plus")), newName = "Dr. Gordhan Das", area = "Sukkur"),
        P(listOf("#09345", "Mukhtiar Lakho"), mapOf(M to listOf("Recetam", "Redep"))),
        P(listOf("#04610", "Ghulam Rasool Ghumro"), mapOf(M to listOf("Recetam:Solution", "Lalap:Syrup"))),
        P(listOf("#04839", "Faqeer Qasmi"), mapOf(R to listOf("Tadlif", "Neram"))),
        P(listOf("#04734", "Inayat Ullah Awan"), mapOf(R to listOf("Redep", "Olagen Plus"))),
        P(listOf("#04748", "Raj Kumar Sachdewani"), mapOf(R to listOf("Redep"))),
        P(listOf("GR Rind", "G Rasool Rind"), mapOf(M to listOf("Redep", "Olagen Plus", "Caripz"))),
        P(listOf("#04723", "Lal Chand Dhingra"), mapOf(M to listOf("Recetam", "Lalap", "Redep"))),
        P(listOf("#04270", "Javaid Phulpoto"), mapOf(M to listOf("Olagen Plus", "Olagen ODT"))),
        P(listOf("GM Abro"), mapOf(R to listOf("Redep", "Neram", "Tadlif")),
            spec = "Cardiologist", clinic = "CCU & Cardio OPD, Civil Hospital Sukkur", area = "Sukkur"),
        P(listOf("#39124", "Agha Wasif"), mapOf(R to listOf("Redep", "Olagen Plus", "Olagen ODT"))),
        P(listOf("#41677", "Bakhtawar Shaikh"), mapOf(R to listOf("Redep", "Olagen Plus", "Olagen ODT"))),
        P(listOf("#40482", "Suneel Kumar"), mapOf(W to listOf("Olagen Plus:6/25", "Riveme:1.5mg,3mg"))),
        P(listOf("#46629", "Hafeez Mushtaq"), mapOf(W to listOf("Recetam:250mg,500mg", "Tadlif"))),
        P(listOf("Murk Channa"), from = "2:00 PM", to = "5:00 PM", days = MON_SAT,
            clinic = "River City Hospital Sukkur", area = "Sukkur"),
        P(listOf("#36075", "Ahsan Soomro"), from = "4:00 PM", to = "8:00 PM", days = MON_SAT),
        P(listOf("Ubedullah"), newName = "Dr. Ubedullah", spec = "Pediatrician (Child specialist)",
            clinic = "River City Hospital Sukkur", area = "Sukkur", from = "3:00 PM", to = "8:00 PM",
            days = "Mon,Tue,Wed,Thu,Fri", tourThuEvening = true),
        P(listOf("#39102", "Rasheed Ahmed Dayo"), from = "3:00 PM", to = "5:00 PM"),
        P(listOf("Najmuddin"), mapOf(R to listOf("Tadlif", "Redep")), newName = "Dr. Najmuddin",
            clinic = "Mind Care, 100 Ft Road Sukkur", area = "Sukkur", from = "5:00 PM", to = "7:00 PM", tourThuEvening = true),
    )

    fun applyOnce(ctx: Context) {
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("patch_2026_10_30", false)) {
            try { apply(ctx) } catch (_: Exception) {}
            prefs.edit().putBoolean("patch_2026_10_30", true).apply()
        }
        if (!prefs.getBoolean("patch_2026_10_30b", false)) {
            try { deepakFix(ctx) } catch (_: Exception) {}
            prefs.edit().putBoolean("patch_2026_10_30b", true).apply()
        }
    }

    /**
     * "Deepak Kumar" neurologist Hira Sukkur mein baithte hain — Shikarpur wale GP (14318) nahi,
     * aur Deepak Girdhari (neuro surgeon) bhi nahi. Un ka alag doctor + products + Tuesday shaam ka plan.
     */
    private fun deepakFix(ctx: Context) {
        val db = Db.get(ctx)
        val docs = db.doctors()
        val gp = docs.firstOrNull { it.code == "14318" }
        val girdhari = docs.firstOrNull { it.code == "30758" }
        // Pichli dafa ghalat jagah lage products hatao
        gp?.let { g -> (skus(db, "Olagen Plus") + skus(db, "Redep")).forEach { db.removeDoctorProduct(g.id, it.id) } }
        girdhari?.let { g -> (skus(db, "Olagen Plus") + skus(db, "Redep:50mg") + skus(db, "Riveme")).forEach { db.removeDoctorProduct(g.id, it.id) } }

        val existing = docs.firstOrNull { it.name.equals("Dr. Deepak Kumar (Neuro)", true) }
        val id = existing?.id ?: db.addDoctorFromPlan("Dr. Deepak Kumar (Neuro)", "Sukkur")
        val d = db.doctor(id)!!
        db.saveDoctor(id, d.name, "Neurologist", "Hira Medical Complex Sukkur", "Sukkur", d.phone,
            d.timeFrom, d.timeTo, d.days, d.category)
        (skus(db, "Olagen Plus") + skus(db, "Redep") + skus(db, "Riveme")).forEach { db.setDoctorProduct(id, it.id, R) }

        // Tuesday shaam (Hira Sukkur round) mein "Deepak Kumar" = yeh neurologist
        if (gp != null) db.tourDays(0, Long.MAX_VALUE).filter {
            Calendar.getInstance().apply { timeInMillis = it.date }.get(Calendar.DAY_OF_WEEK) == Calendar.TUESDAY
        }.forEach { td -> db.replaceTourDoctor(td.date, "E", gp.id, id) }
        db.setDays(id, "Tue")

        docs.firstOrNull { it.name.contains("Gordhan", true) }?.let { g ->
            if (g.area.isBlank()) db.saveDoctor(g.id, g.name, g.specialty, g.clinic, "Sukkur", g.phone, g.timeFrom, g.timeTo, g.days, g.category)
        }
    }

    private fun skus(db: Db, spec: String): List<Product> {
        val brand = spec.substringBefore(":").trim()
        val strengths = if (spec.contains(":")) spec.substringAfter(":").split(",").map { it.trim().lowercase() } else emptyList()
        return db.products().filter { it.brandName.equals(brand, true) &&
            (strengths.isEmpty() || strengths.any { s -> it.strength.lowercase().contains(s) }) }
    }

    private fun apply(ctx: Context) {
        val db = Db.get(ctx)
        for (p in list) {
            val docs = db.doctors()
            var d: Doctor? = null
            for (f in p.find) {
                d = if (f.startsWith("#")) docs.firstOrNull { it.code == f.drop(1) } else NameMatch.find(f, docs)
                if (d != null) break
            }
            val id = d?.id ?: if (p.newName.isNotBlank()) db.saveDoctor(0, p.newName, p.spec, p.clinic, p.area, "",
                p.from, p.to, p.days, "A") else continue
            if (d != null) {
                db.saveDoctor(id, p.rename.ifBlank { d.name }, p.spec.ifBlank { d.specialty }, p.clinic.ifBlank { d.clinic },
                    p.area.ifBlank { d.area }, d.phone, p.from.ifBlank { d.timeFrom }, p.to.ifBlank { d.timeTo },
                    p.days.ifBlank { d.days }, d.category)
                if (p.rename.isNotBlank()) db.addAlias(id, p.rename.removePrefix("Dr. "))
            }
            p.products.forEach { (kind, specs) -> specs.flatMap { skus(db, it) }.forEach { db.setDoctorProduct(id, it.id, kind) } }
            if (p.tourThuEvening) {
                // Sukkur ki Thursday shaam (River City round) mein add
                db.tourDays(0, Long.MAX_VALUE).filter {
                    Calendar.getInstance().apply { timeInMillis = it.date }.get(Calendar.DAY_OF_WEEK) == Calendar.THURSDAY &&
                        it.area.contains("Sukkur", true)
                }.forEach { td -> db.addTourEntry(td.date, td.area, "E", id) }
            }
        }
    }
}
