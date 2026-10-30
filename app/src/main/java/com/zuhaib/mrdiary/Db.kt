package com.zuhaib.mrdiary

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** Brand = ek medicine ka naam (maslan Redep). Literature, script aur voice brand ke saath hoti hai. */
data class Brand(
    val id: Long, val name: String, val generic: String, val category: String,
    val script: String, val audio: String?
)

/** Product = brand ka ek SKU (maslan Redep 50mg, 30 tabs). Price SKU ki hoti hai. */
data class Product(
    val id: Long, val brandId: Long, val brandName: String, val strength: String, val pack: String,
    val price: Double, val unitPrice: Double, val unitLabel: String, val tp: Double = 0.0
) {
    /** Order mein TP lagti hai; TP na ho to MRP */
    val orderPrice: Double get() = if (tp > 0) tp else price
    val name: String get() = "$brandName $strength".trim()
    val label: String get() = if (pack.isBlank()) name else "$name ($pack)"
    val unitText: String get() = if (unitPrice > 0) "${Fmt.rs(unitPrice)} / ${unitLabel.ifBlank { "unit" }}" else ""
}

data class BrandImage(val id: Long, val brandId: Long, val path: String)

data class Store(val id: Long, val name: String, val owner: String, val phone: String, val area: String)

data class Doctor(
    val id: Long, val name: String, val specialty: String, val clinic: String,
    val area: String, val phone: String, val timeFrom: String = "", val timeTo: String = "",
    val days: String = "", val lastVisit: Long = 0, val category: String = "B"
) {
    val dayList: List<String> get() = Days.parse(days)
    val daysText: String get() = Days.text(dayList)
    val timing: String get() = when {
        timeFrom.isNotBlank() && timeTo.isNotBlank() -> "$timeFrom – $timeTo"
        timeFrom.isNotBlank() -> "$timeFrom se"
        timeTo.isNotBlank() -> "$timeTo tak"
        else -> ""
    }.let { t -> listOf(t, daysText).filter { it.isNotBlank() }.joinToString(" • ") }

    /** Din khali hon to har din maan lo */
    fun sitsOn(day: String): Boolean = dayList.isEmpty() || dayList.contains(day)

    /** A = har hafte, B = 15 din, C = mahina */
    val cycleDays: Int get() = when (category) { "A" -> 7; "C" -> 30; else -> 15 }

    /** Kitne din se nahi mile (-1 = kabhi nahi) */
    fun daysSince(now: Long = System.currentTimeMillis()): Int =
        if (lastVisit <= 0) -1 else ((Range.dayStart(now) - Range.dayStart(lastVisit)) / 86_400_000L).toInt()

    fun isOverdue(): Boolean { val d = daysSince(); return d < 0 || d >= cycleDays }

    /** Clinic timing minutes mein, sort ke liye */
    val startMinutes: Int get() = Days.minutes(timeFrom)
}

object Days {
    val ALL = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    val URDU = listOf("Peer", "Mangal", "Budh", "Jumerat", "Juma", "Hafta", "Itwar")

    fun today(): String = ALL[(java.util.Calendar.getInstance().get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7]

    fun parse(s: String): List<String> {
        val low = s.lowercase()
        if (low.isBlank()) return emptyList()
        if (low.contains("daily") || low.contains("rozana") || low.contains("roz")) return ALL
        return ALL.filter { low.contains(it.lowercase()) }
    }

    fun text(d: List<String>): String = when {
        d.isEmpty() -> ""
        d.size == 7 -> "Rozana"
        d == ALL.take(6) -> "Mon–Sat"
        d == ALL.take(5) -> "Mon–Fri"
        d.size == 6 -> "${ALL.first { it !in d }} off"
        else -> d.joinToString(", ")
    }

    fun minutes(t: String): Int = try {
        val c = java.util.Calendar.getInstance()
        c.time = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US).parse(t.trim())!!
        c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    } catch (e: Exception) { 24 * 60 }
}

data class SupplierInvoice(
    val id: Long, val storeId: Long, val storeName: String, val date: Long,
    val invNo: String, val amount: Double, val photo: String?, val note: String
)

data class Expense(
    val id: Long, val date: Long, val type: String, val amount: Double,
    val kmStart: Double, val kmEnd: Double, val note: String
) { val km: Double get() = if (kmEnd > kmStart && kmStart > 0) kmEnd - kmStart else 0.0 }

data class Visit(
    val id: Long, val doctorId: Long, val date: Long, val products: String, val notes: String,
    val followup: Long, val followDone: Boolean, val doctorName: String = ""
)

data class Order(
    val id: Long, val storeId: Long, val storeName: String, val date: Long,
    val gross: Double, val discount: Double, val net: Double, val note: String, val itemCount: Int = 0
)

data class OrderItem(var productId: Long, var name: String, var qty: Int, var price: Double, var disc: Double) {
    val gross: Double get() = qty * price
    val discAmt: Double get() = gross * disc / 100.0
    val net: Double get() = gross - discAmt
}

/** Report ke liye: naam, ginti, raqam */
data class Agg(val name: String, val count: Double, val amount: Double)

class Db private constructor(private val app: Context) : SQLiteOpenHelper(app, "mrdiary_v3.db", null, 3) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
    }

    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE brands(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, generic TEXT DEFAULT '', category TEXT DEFAULT '', script TEXT DEFAULT '', audio TEXT, sort INTEGER DEFAULT 0)")
        d.execSQL("CREATE TABLE brand_images(id INTEGER PRIMARY KEY AUTOINCREMENT, brand_id INTEGER, path TEXT, created INTEGER)")
        d.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT, brand_id INTEGER, strength TEXT DEFAULT '', pack TEXT DEFAULT '', price REAL DEFAULT 0, unit_price REAL DEFAULT 0, unit_label TEXT DEFAULT '', sort INTEGER DEFAULT 0, tp REAL DEFAULT 0)")
        d.execSQL("CREATE TABLE stores(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, owner TEXT DEFAULT '', phone TEXT DEFAULT '', area TEXT DEFAULT '')")
        d.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT, store_id INTEGER, store_name TEXT, date INTEGER, gross REAL, discount REAL, net REAL, note TEXT DEFAULT '')")
        d.execSQL("CREATE TABLE order_items(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, product_id INTEGER, name TEXT, qty INTEGER, price REAL, disc REAL)")
        d.execSQL("CREATE TABLE doctors(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, specialty TEXT DEFAULT '', clinic TEXT DEFAULT '', area TEXT DEFAULT '', phone TEXT DEFAULT '', time_from TEXT DEFAULT '', time_to TEXT DEFAULT '', days TEXT DEFAULT '')")
        d.execSQL("CREATE TABLE doctor_products(doctor_id INTEGER, product_id INTEGER, kind INTEGER, PRIMARY KEY(doctor_id, product_id))")
        d.execSQL("CREATE TABLE visits(id INTEGER PRIMARY KEY AUTOINCREMENT, doctor_id INTEGER, date INTEGER, products TEXT DEFAULT '', notes TEXT DEFAULT '', followup INTEGER DEFAULT 0, follow_done INTEGER DEFAULT 0)")
        v3(d)
        Seed.run(app, d)
    }

    override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) {
            d.execSQL("ALTER TABLE products ADD COLUMN tp REAL DEFAULT 0")
            Seed.applyPrices(d)
        }
        if (old < 3) v3(d)
    }

    /** v3: doctor category, supplier invoices, TA/DA */
    private fun v3(d: SQLiteDatabase) {
        d.execSQL("ALTER TABLE doctors ADD COLUMN category TEXT DEFAULT 'B'")
        d.execSQL("CREATE TABLE IF NOT EXISTS invoices(id INTEGER PRIMARY KEY AUTOINCREMENT, store_id INTEGER, store_name TEXT, date INTEGER, inv_no TEXT DEFAULT '', amount REAL DEFAULT 0, photo TEXT, note TEXT DEFAULT '')")
        d.execSQL("CREATE TABLE IF NOT EXISTS expenses(id INTEGER PRIMARY KEY AUTOINCREMENT, date INTEGER, type TEXT, amount REAL DEFAULT 0, km_start REAL DEFAULT 0, km_end REAL DEFAULT 0, note TEXT DEFAULT '')")
    }

    // ---------- helpers ----------
    private fun cv(vararg p: Pair<String, Any?>) = ContentValues().apply {
        p.forEach { (k, v) ->
            when (v) {
                null -> putNull(k)
                is String -> put(k, v)
                is Int -> put(k, v)
                is Long -> put(k, v)
                is Double -> put(k, v)
                is Boolean -> put(k, if (v) 1 else 0)
                else -> put(k, v.toString())
            }
        }
    }

    private fun <T> q(sql: String, vararg args: Any, map: (Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        readableDatabase.rawQuery(sql, args.map { it.toString() }.toTypedArray()).use { c ->
            while (c.moveToNext()) out.add(map(c))
        }
        return out
    }

    private fun Cursor.s(n: String): String = getString(getColumnIndexOrThrow(n)) ?: ""
    private fun Cursor.sn(n: String): String? = getString(getColumnIndexOrThrow(n))
    private fun Cursor.l(n: String): Long = getLong(getColumnIndexOrThrow(n))
    private fun Cursor.d(n: String): Double = getDouble(getColumnIndexOrThrow(n))
    private fun Cursor.i(n: String): Int = getInt(getColumnIndexOrThrow(n))

    private fun upsert(table: String, id: Long, v: ContentValues): Long {
        val w = writableDatabase
        return if (id == 0L) w.insert(table, null, v)
        else { w.update(table, v, "id=?", arrayOf(id.toString())); id }
    }

    private fun del(table: String, where: String, vararg args: Any) {
        writableDatabase.delete(table, where, args.map { it.toString() }.toTypedArray())
    }

    // ---------- brands ----------
    private fun Cursor.toBrand() = Brand(l("id"), s("name"), s("generic"), s("category"), s("script"), sn("audio"))

    fun brands(): List<Brand> = q("SELECT * FROM brands ORDER BY sort, name COLLATE NOCASE") { it.toBrand() }
    fun brand(id: Long): Brand? = q("SELECT * FROM brands WHERE id=?", id) { it.toBrand() }.firstOrNull()

    fun saveBrand(id: Long, name: String, generic: String, category: String): Long {
        val v = cv("name" to name, "generic" to generic, "category" to category)
        if (id == 0L) v.put("sort", 1000)
        return upsert("brands", id, v)
    }

    fun setScript(id: Long, script: String) = upsert("brands", id, cv("script" to script))
    fun setAudio(id: Long, path: String?) = upsert("brands", id, cv("audio" to path))

    fun deleteBrand(id: Long) {
        products(id).forEach { deleteProduct(it.id) }
        del("brand_images", "brand_id=?", id)
        del("brands", "id=?", id)
    }

    fun images(brandId: Long): List<BrandImage> =
        q("SELECT * FROM brand_images WHERE brand_id=? ORDER BY id", brandId) {
            BrandImage(it.l("id"), it.l("brand_id"), it.s("path"))
        }

    fun addImage(brandId: Long, path: String) =
        upsert("brand_images", 0, cv("brand_id" to brandId, "path" to path, "created" to System.currentTimeMillis()))

    fun deleteImage(id: Long) = del("brand_images", "id=?", id)

    // ---------- products (SKUs) ----------
    private val pSql = "SELECT p.*, b.name AS bname FROM products p JOIN brands b ON b.id=p.brand_id"
    private fun Cursor.toProduct() = Product(
        l("id"), l("brand_id"), s("bname"), s("strength"), s("pack"), d("price"), d("unit_price"), s("unit_label"), d("tp")
    )

    /** Saare SKUs, brand ke order mein */
    fun products(): List<Product> = q("$pSql ORDER BY b.sort, b.name COLLATE NOCASE, p.sort, p.id") { it.toProduct() }
    fun products(brandId: Long): List<Product> = q("$pSql WHERE p.brand_id=? ORDER BY p.sort, p.id", brandId) { it.toProduct() }
    fun product(id: Long): Product? = q("$pSql WHERE p.id=?", id) { it.toProduct() }.firstOrNull()

    fun saveProduct(id: Long, brandId: Long, strength: String, pack: String, price: Double, unitPrice: Double, unitLabel: String, tp: Double): Long =
        upsert("products", id, cv(
            "brand_id" to brandId, "strength" to strength, "pack" to pack,
            "price" to price, "unit_price" to unitPrice, "unit_label" to unitLabel, "tp" to tp
        ))

    fun deleteProduct(id: Long) {
        del("doctor_products", "product_id=?", id)
        del("products", "id=?", id)
    }

    // ---------- stores ----------
    private fun Cursor.toStore() = Store(l("id"), s("name"), s("owner"), s("phone"), s("area"))

    fun stores(): List<Store> = q("SELECT * FROM stores ORDER BY name COLLATE NOCASE") { it.toStore() }
    fun store(id: Long): Store? = q("SELECT * FROM stores WHERE id=?", id) { it.toStore() }.firstOrNull()

    fun saveStore(id: Long, name: String, owner: String, phone: String, area: String): Long =
        upsert("stores", id, cv("name" to name, "owner" to owner, "phone" to phone, "area" to area))

    fun deleteStore(id: Long) = del("stores", "id=?", id)

    // ---------- doctors ----------
    private fun Cursor.toDoctor() = Doctor(
        l("id"), s("name"), s("specialty"), s("clinic"), s("area"), s("phone"),
        s("time_from"), s("time_to"), s("days"),
        if (getColumnIndex("last") >= 0) l("last") else 0,
        s("category").ifBlank { "B" }
    )

    fun doctors(): List<Doctor> = q(
        "SELECT d.*, IFNULL((SELECT MAX(date) FROM visits v WHERE v.doctor_id=d.id),0) AS last FROM doctors d ORDER BY d.name COLLATE NOCASE"
    ) { it.toDoctor() }

    fun doctor(id: Long): Doctor? = q(
        "SELECT d.*, IFNULL((SELECT MAX(date) FROM visits v WHERE v.doctor_id=d.id),0) AS last FROM doctors d WHERE d.id=?", id
    ) { it.toDoctor() }.firstOrNull()

    fun saveDoctor(id: Long, name: String, specialty: String, clinic: String, area: String, phone: String,
                   timeFrom: String, timeTo: String, days: String, category: String): Long =
        upsert("doctors", id, cv(
            "name" to name, "specialty" to specialty, "clinic" to clinic, "area" to area, "phone" to phone,
            "time_from" to timeFrom, "time_to" to timeTo, "days" to days, "category" to category
        ))

    fun deleteDoctor(id: Long) {
        del("doctor_products", "doctor_id=?", id)
        del("visits", "doctor_id=?", id)
        del("doctors", "id=?", id)
    }

    /** product_id -> kind (1 = already prescribe kar rahe, 2 = promote karna hai) */
    fun doctorProducts(doctorId: Long): Map<Long, Int> =
        q("SELECT product_id, kind FROM doctor_products WHERE doctor_id=?", doctorId) { it.l("product_id") to it.i("kind") }.toMap()

    fun setDoctorProducts(doctorId: Long, map: Map<Long, Int>) {
        val w = writableDatabase
        w.beginTransaction()
        try {
            del("doctor_products", "doctor_id=?", doctorId)
            map.forEach { (pid, kind) ->
                w.insert("doctor_products", null, cv("doctor_id" to doctorId, "product_id" to pid, "kind" to kind))
            }
            w.setTransactionSuccessful()
        } finally { w.endTransaction() }
    }

    fun setDoctorProduct(doctorId: Long, productId: Long, kind: Int) {
        writableDatabase.insertWithOnConflict(
            "doctor_products", null, cv("doctor_id" to doctorId, "product_id" to productId, "kind" to kind),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun removeDoctorProduct(doctorId: Long, productId: Long) =
        del("doctor_products", "doctor_id=? AND product_id=?", doctorId, productId)

    fun doctorProductCounts(): Map<Long, Pair<Int, Int>> {
        val out = HashMap<Long, Pair<Int, Int>>()
        q("SELECT doctor_id, SUM(kind=1) AS a, SUM(kind=2) AS b FROM doctor_products GROUP BY doctor_id") {
            out[it.l("doctor_id")] = it.i("a") to it.i("b")
        }
        return out
    }

    // ---------- visits ----------
    private fun Cursor.toVisit() = Visit(
        l("id"), l("doctor_id"), l("date"), s("products"), s("notes"), l("followup"), i("follow_done") == 1,
        if (getColumnIndex("dname") >= 0) s("dname") else ""
    )

    fun visits(doctorId: Long): List<Visit> =
        q("SELECT * FROM visits WHERE doctor_id=? ORDER BY date DESC", doctorId) { it.toVisit() }

    fun visit(id: Long): Visit? = q(
        "SELECT v.*, d.name AS dname FROM visits v JOIN doctors d ON d.id=v.doctor_id WHERE v.id=?", id
    ) { it.toVisit() }.firstOrNull()

    fun addVisit(doctorId: Long, date: Long, products: String, notes: String, followup: Long): Long =
        upsert("visits", 0, cv("doctor_id" to doctorId, "date" to date, "products" to products, "notes" to notes, "followup" to followup, "follow_done" to 0))

    fun lastVisit(doctorId: Long): Visit? =
        q("SELECT * FROM visits WHERE doctor_id=? ORDER BY date DESC LIMIT 1", doctorId) { it.toVisit() }.firstOrNull()

    fun visitsBetween(from: Long, to: Long): List<Visit> = q(
        "SELECT v.*, d.name AS dname FROM visits v JOIN doctors d ON d.id=v.doctor_id " +
            "WHERE v.date BETWEEN ? AND ? ORDER BY v.date", from, to
    ) { it.toVisit() }

    fun followupsBetween(from: Long, to: Long): List<Visit> = q(
        "SELECT v.*, d.name AS dname FROM visits v JOIN doctors d ON d.id=v.doctor_id " +
            "WHERE v.followup BETWEEN ? AND ? AND v.follow_done=0 ORDER BY v.followup", from, to
    ) { it.toVisit() }

    fun deleteVisit(id: Long) = del("visits", "id=?", id)
    fun setFollowDone(id: Long) = upsert("visits", id, cv("follow_done" to 1))

    fun dueFollowups(until: Long): List<Visit> = q(
        "SELECT v.*, d.name AS dname FROM visits v JOIN doctors d ON d.id=v.doctor_id " +
            "WHERE v.followup>0 AND v.followup<=? AND v.follow_done=0 ORDER BY v.followup", until
    ) { it.toVisit() }

    fun pendingFollowups(): List<Visit> = q(
        "SELECT v.*, d.name AS dname FROM visits v JOIN doctors d ON d.id=v.doctor_id " +
            "WHERE v.followup>0 AND v.follow_done=0 ORDER BY v.followup"
    ) { it.toVisit() }

    // ---------- orders ----------
    private fun Cursor.toOrder() = Order(
        l("id"), l("store_id"), s("store_name"), l("date"), d("gross"), d("discount"), d("net"), s("note"),
        if (getColumnIndex("cnt") >= 0) i("cnt") else 0
    )

    fun orders(from: Long, to: Long, storeId: Long = 0): List<Order> {
        val base = "SELECT o.*, (SELECT COUNT(*) FROM order_items i WHERE i.order_id=o.id) AS cnt FROM orders o WHERE o.date BETWEEN ? AND ?"
        return if (storeId > 0) q("$base AND o.store_id=? ORDER BY o.date DESC", from, to, storeId) { it.toOrder() }
        else q("$base ORDER BY o.date DESC", from, to) { it.toOrder() }
    }

    fun order(id: Long): Order? = q("SELECT * FROM orders WHERE id=?", id) { it.toOrder() }.firstOrNull()

    fun orderItems(orderId: Long): List<OrderItem> =
        q("SELECT * FROM order_items WHERE order_id=? ORDER BY id", orderId) {
            OrderItem(it.l("product_id"), it.s("name"), it.i("qty"), it.d("price"), it.d("disc"))
        }

    fun saveOrder(id: Long, storeId: Long, storeName: String, date: Long, items: List<OrderItem>, note: String): Long {
        val w = writableDatabase
        w.beginTransaction()
        try {
            val gross = items.sumOf { it.gross }
            val disc = items.sumOf { it.discAmt }
            val oid = upsert(
                "orders", id, cv(
                    "store_id" to storeId, "store_name" to storeName, "date" to date,
                    "gross" to gross, "discount" to disc, "net" to gross - disc, "note" to note
                )
            )
            del("order_items", "order_id=?", oid)
            items.forEach {
                w.insert("order_items", null, cv(
                    "order_id" to oid, "product_id" to it.productId, "name" to it.name,
                    "qty" to it.qty, "price" to it.price, "disc" to it.disc
                ))
            }
            w.setTransactionSuccessful()
            return oid
        } finally {
            w.endTransaction()
        }
    }

    fun deleteOrder(id: Long) {
        del("order_items", "order_id=?", id)
        del("orders", "id=?", id)
    }

    // ---------- supplier invoices ----------
    private fun Cursor.toInv() = SupplierInvoice(
        l("id"), l("store_id"), s("store_name"), l("date"), s("inv_no"), d("amount"), sn("photo"), s("note")
    )

    fun invoices(from: Long, to: Long): List<SupplierInvoice> =
        q("SELECT * FROM invoices WHERE date BETWEEN ? AND ? ORDER BY date DESC, id DESC", from, to) { it.toInv() }

    fun saveInvoice(i: SupplierInvoice): Long = upsert("invoices", i.id, cv(
        "store_id" to i.storeId, "store_name" to i.storeName, "date" to i.date, "inv_no" to i.invNo,
        "amount" to i.amount, "photo" to i.photo, "note" to i.note
    ))

    fun deleteInvoice(id: Long) = del("invoices", "id=?", id)

    /** store_id -> purchase dates (orders + supplier invoices), reorder andaze ke liye */
    fun purchaseDates(): Map<Long, List<Long>> {
        val m = HashMap<Long, MutableList<Long>>()
        q("SELECT store_id AS s, date AS d FROM orders WHERE store_id>0 UNION ALL SELECT store_id AS s, date AS d FROM invoices WHERE store_id>0") {
            m.getOrPut(it.l("s")) { mutableListOf() }.add(it.l("d"))
        }
        return m
    }

    // ---------- TA/DA ----------
    private fun Cursor.toExp() = Expense(l("id"), l("date"), s("type"), d("amount"), d("km_start"), d("km_end"), s("note"))

    fun expenses(from: Long, to: Long): List<Expense> =
        q("SELECT * FROM expenses WHERE date BETWEEN ? AND ? ORDER BY date DESC, id DESC", from, to) { it.toExp() }

    fun lastKm(): Double = q("SELECT MAX(km_end) AS k FROM expenses") { it.d("k") }.firstOrNull() ?: 0.0

    fun saveExpense(e: Expense): Long = upsert("expenses", e.id, cv(
        "date" to e.date, "type" to e.type, "amount" to e.amount,
        "km_start" to e.kmStart, "km_end" to e.kmEnd, "note" to e.note
    ))

    fun deleteExpense(id: Long) = del("expenses", "id=?", id)

    // ---------- reports ----------
    fun orderTotals(from: Long, to: Long): Agg = q(
        "SELECT COUNT(*) AS c, IFNULL(SUM(net),0) AS n FROM orders WHERE date BETWEEN ? AND ?", from, to
    ) { Agg("", it.d("c"), it.d("n")) }.first()

    fun discountTotal(from: Long, to: Long): Double = q(
        "SELECT IFNULL(SUM(discount),0) AS dsc FROM orders WHERE date BETWEEN ? AND ?", from, to
    ) { it.d("dsc") }.first()

    fun grossTotal(from: Long, to: Long): Double = q(
        "SELECT IFNULL(SUM(gross),0) AS g FROM orders WHERE date BETWEEN ? AND ?", from, to
    ) { it.d("g") }.first()

    fun productSales(from: Long, to: Long): List<Agg> = q(
        "SELECT i.name AS name, SUM(i.qty) AS c, SUM(i.qty*i.price*(100-i.disc)/100.0) AS a " +
            "FROM order_items i JOIN orders o ON o.id=i.order_id WHERE o.date BETWEEN ? AND ? " +
            "GROUP BY i.name ORDER BY a DESC", from, to
    ) { Agg(it.s("name"), it.d("c"), it.d("a")) }

    fun storeSales(from: Long, to: Long): List<Agg> = q(
        "SELECT store_name AS name, COUNT(*) AS c, SUM(net) AS a FROM orders WHERE date BETWEEN ? AND ? " +
            "GROUP BY store_id, store_name ORDER BY a DESC", from, to
    ) { Agg(it.s("name"), it.d("c"), it.d("a")) }

    fun visitCount(from: Long, to: Long): Int = q(
        "SELECT COUNT(*) AS c FROM visits WHERE date BETWEEN ? AND ?", from, to
    ) { it.i("c") }.first()

    fun doctorVisitAgg(from: Long, to: Long): List<Agg> = q(
        "SELECT d.name AS name, COUNT(*) AS c FROM visits v JOIN doctors d ON d.id=v.doctor_id " +
            "WHERE v.date BETWEEN ? AND ? GROUP BY v.doctor_id, d.name ORDER BY c DESC", from, to
    ) { Agg(it.s("name"), it.d("c"), 0.0) }
}

// =====================================================================
// Pehli baar app khulne par GeniX ki saari medicines + literature daal do
// =====================================================================
object Seed {
    /** price = MRP, tp = Trade Price (order mein yahi lagti hai), unit = per tab/cap/dose (MRP se) */
    private class Sku(val strength: String, val pack: String, val price: Double, val tp: Double, val unit: Double, val label: String)
    private class B(val name: String, val generic: String, val cat: String, val images: List<String>, val skus: List<Sku>)

    private fun per(mrp: Double, n: Int) = Math.round(mrp / n * 100) / 100.0

    private val data = listOf(
        B("Neram", "Alprazolam", "Anxiety (GAD)", listOf("neram"), listOf(
            Sku("0.5mg", "30 Tabs", 330.0, 280.5, per(330.0, 30), "tab"))),
        B("Lalap", "Lacosamide", "Antiepileptic", listOf("lalap_1", "lalap_2"), listOf(
            Sku("200mg", "14 Tabs", 1500.0, 1275.0, per(1500.0, 14), "tab"),
            Sku("10mg/ml Syrup", "100ml", 664.0, 564.4, 0.0, "dose"))),
        B("Recetam", "Levetiracetam", "Antiepileptic", listOf("recetam"), listOf(
            Sku("250mg", "10 Tabs", 420.0, 357.0, per(420.0, 10), "tab"),
            Sku("500mg", "10 Tabs", 650.0, 552.5, per(650.0, 10), "tab"),
            Sku("100mg/ml Solution", "60ml", 565.0, 480.25, 0.0, "dose"),
            Sku("100mg/ml Solution 30ml", "30ml", 299.0, 254.15, 0.0, "dose"),
            Sku("500mg/5ml I.V Infusion", "1 Vial", 450.0, 382.5, 0.0, "dose"))),
        B("Olagen Plus", "Olanzapine / Fluoxetine", "Antidepressant", listOf("olagen_plus"), listOf(
            Sku("3/25mg", "30 Caps", 650.0, 552.5, per(650.0, 30), "cap"),
            Sku("6/25mg", "30 Caps", 750.0, 637.5, per(750.0, 30), "cap"))),
        B("Olagen ODT", "Olanzapine", "Antipsychotic", listOf("olagen_odt"), listOf(
            Sku("5mg", "10 Tabs", 350.0, 297.5, per(350.0, 10), "tab"),
            Sku("10mg", "10 Tabs", 345.0, 293.25, per(345.0, 10), "tab"))),
        B("Redep", "Sertraline HCl", "Antidepressant", listOf("redep_tablets", "redep_solution"), listOf(
            Sku("50mg", "30 Tabs", 1080.0, 918.0, per(1080.0, 30), "tab"),
            Sku("100mg", "20 Tabs", 1200.0, 1020.0, per(1200.0, 20), "tab"),
            Sku("20mg/ml Solution", "50ml", 600.0, 510.0, 30.0, "dose"))),
        B("Ilodon", "Iloperidone", "Antipsychotic", listOf(), listOf(
            Sku("2mg", "10 Tabs", 250.0, 212.5, per(250.0, 10), "tab"),
            Sku("4mg", "10 Tabs", 400.0, 340.0, per(400.0, 10), "tab"))),
        B("Zacloz", "Clozapine", "Antipsychotic", listOf("zacloz"), listOf(
            Sku("25mg", "50 Tabs", 1214.06, 1055.7, per(1214.06, 50), "tab"),
            Sku("100mg", "50 Tabs", 2428.11, 2111.4, per(2428.11, 50), "tab"),
            Sku("50mg/ml Suspension", "60ml", 1400.0, 1190.0, 0.0, "dose"))),
        B("Riveme", "Rivastigmine Tartrate", "Alzheimer's / Dementia", listOf("riveme"), listOf(
            Sku("1.5mg", "60 Caps", 4127.79, 3589.38, per(4127.79, 60), "cap"),
            Sku("3mg", "60 Caps", 4953.34, 4307.26, per(4953.34, 60), "cap"),
            Sku("2mg/ml Solution", "60ml", 777.0, 675.65, 0.0, "dose"))),
        B("Memor", "Ginkgo + Bacopa + Huperzia (10 ingredients)", "Brain energizer", listOf("memor"), listOf(
            Sku("", "30 Caps", 770.0, 654.5, per(770.0, 30), "cap"))),
        B("Caripz", "Cariprazine", "Antipsychotic", listOf("caripz"), listOf(
            Sku("1.5mg", "10 Caps", 291.37, 253.37, per(291.37, 10), "cap"),
            Sku("3mg", "10 Caps", 437.06, 380.05, per(437.06, 10), "cap"),
            Sku("4.5mg", "10 Caps", 631.31, 548.96, per(631.31, 10), "cap"),
            Sku("6mg", "10 Caps", 825.56, 717.88, per(825.56, 10), "cap"))),
        B("Tadlif", "Tadalafil", "Antidepressant / Men's health", listOf("tadlif"), listOf(
            Sku("20mg", "8 Tabs", 560.0, 476.0, per(560.0, 8), "tab"))),
    )

    private fun skuValues(bid: Long, s: Sku, sort: Int) = ContentValues().apply {
        put("brand_id", bid); put("strength", s.strength); put("pack", s.pack)
        put("price", s.price); put("tp", s.tp); put("unit_price", s.unit); put("unit_label", s.label); put("sort", sort)
    }

    /** Purani install par naye MRP/TP lagao (brand + strength se match), naye SKU add karo */
    fun applyPrices(d: SQLiteDatabase) {
        data.forEach { b ->
            val bid = d.rawQuery("SELECT id FROM brands WHERE name=? COLLATE NOCASE", arrayOf(b.name)).use {
                if (it.moveToFirst()) it.getLong(0) else -1L
            }
            if (bid < 0) return@forEach
            b.skus.forEachIndexed { si, s ->
                val n = d.update("products", skuValues(bid, s, si), "brand_id=? AND strength=?", arrayOf(bid.toString(), s.strength))
                if (n == 0) d.insert("products", null, skuValues(bid, s, si))
            }
        }
    }

    fun run(ctx: Context, d: SQLiteDatabase) {
        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        data.forEachIndexed { bi, b ->
            val bid = d.insert("brands", null, ContentValues().apply {
                put("name", b.name); put("generic", b.generic); put("category", b.cat); put("sort", bi)
            })
            b.images.forEach { img ->
                val f = File(dir, "lit_$img.jpg")
                if (copyAsset(ctx, "lit/$img.jpg", f)) d.insert("brand_images", null, ContentValues().apply {
                    put("brand_id", bid); put("path", f.absolutePath); put("created", System.currentTimeMillis())
                })
            }
            b.skus.forEachIndexed { si, s -> d.insert("products", null, skuValues(bid, s, si)) }
        }
    }

    fun copyAsset(ctx: Context, asset: String, out: File): Boolean = try {
        if (!out.exists()) ctx.assets.open(asset).use { i -> out.outputStream().use { i.copyTo(it) } }
        true
    } catch (e: Exception) { false }

    /** Price list aur portfolio ki reference images */
    fun refImages(ctx: Context): Array<String> {
        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        return listOf("price_list", "portfolio").mapNotNull { n ->
            val f = File(dir, "ref_$n.jpg")
            if (copyAsset(ctx, "lit/$n.jpg", f)) f.absolutePath else null
        }.toTypedArray()
    }
}
