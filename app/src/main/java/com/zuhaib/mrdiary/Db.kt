package com.zuhaib.mrdiary

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Product(
    val id: Long, val name: String, val pack: String, val price: Double,
    val indication: String, val script: String, val audio: String?
)

data class ProductImage(val id: Long, val productId: Long, val path: String)

data class Store(val id: Long, val name: String, val owner: String, val phone: String, val area: String)

data class Doctor(
    val id: Long, val name: String, val specialty: String, val clinic: String,
    val area: String, val phone: String, val lastVisit: Long = 0
)

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

class Db private constructor(ctx: Context) : SQLiteOpenHelper(ctx, "mrdiary.db", null, 2) {

    companion object {
        @Volatile private var inst: Db? = null
        fun get(ctx: Context): Db =
            inst ?: synchronized(this) { inst ?: Db(ctx.applicationContext).also { inst = it } }
    }

    override fun onCreate(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE products(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, pack TEXT DEFAULT '', price REAL DEFAULT 0, indication TEXT DEFAULT '', script TEXT DEFAULT '', audio TEXT)")
        d.execSQL("CREATE TABLE product_images(id INTEGER PRIMARY KEY AUTOINCREMENT, product_id INTEGER, path TEXT, created INTEGER)")
        d.execSQL("CREATE TABLE stores(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, owner TEXT DEFAULT '', phone TEXT DEFAULT '', area TEXT DEFAULT '')")
        d.execSQL("CREATE TABLE orders(id INTEGER PRIMARY KEY AUTOINCREMENT, store_id INTEGER, store_name TEXT, date INTEGER, gross REAL, discount REAL, net REAL, note TEXT DEFAULT '')")
        d.execSQL("CREATE TABLE order_items(id INTEGER PRIMARY KEY AUTOINCREMENT, order_id INTEGER, product_id INTEGER, name TEXT, qty INTEGER, price REAL, disc REAL)")
        d.execSQL("CREATE TABLE doctors(id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, specialty TEXT DEFAULT '', clinic TEXT DEFAULT '', area TEXT DEFAULT '', phone TEXT DEFAULT '')")
        createDoctorProducts(d)
        d.execSQL("CREATE TABLE visits(id INTEGER PRIMARY KEY AUTOINCREMENT, doctor_id INTEGER, date INTEGER, products TEXT DEFAULT '', notes TEXT DEFAULT '', followup INTEGER DEFAULT 0, follow_done INTEGER DEFAULT 0)")
    }

    override fun onUpgrade(d: SQLiteDatabase, old: Int, new: Int) {
        if (old < 2) createDoctorProducts(d)
    }

    /** kind 1 = already prescribe kar rahe, kind 2 = promote karna hai */
    private fun createDoctorProducts(d: SQLiteDatabase) {
        d.execSQL("CREATE TABLE IF NOT EXISTS doctor_products(doctor_id INTEGER, product_id INTEGER, kind INTEGER, PRIMARY KEY(doctor_id, product_id))")
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

    // ---------- products ----------
    private fun Cursor.toProduct() = Product(l("id"), s("name"), s("pack"), d("price"), s("indication"), s("script"), sn("audio"))

    fun products(): List<Product> = q("SELECT * FROM products ORDER BY name COLLATE NOCASE") { it.toProduct() }
    fun product(id: Long): Product? = q("SELECT * FROM products WHERE id=?", id) { it.toProduct() }.firstOrNull()

    fun saveProduct(id: Long, name: String, pack: String, price: Double, indication: String): Long =
        upsert("products", id, cv("name" to name, "pack" to pack, "price" to price, "indication" to indication))

    fun setScript(id: Long, script: String) = upsert("products", id, cv("script" to script))
    fun setAudio(id: Long, path: String?) = upsert("products", id, cv("audio" to path))

    fun deleteProduct(id: Long) {
        del("doctor_products", "product_id=?", id)
        del("product_images", "product_id=?", id)
        del("products", "id=?", id)
    }

    fun images(productId: Long): List<ProductImage> =
        q("SELECT * FROM product_images WHERE product_id=? ORDER BY id", productId) {
            ProductImage(it.l("id"), it.l("product_id"), it.s("path"))
        }

    fun addImage(productId: Long, path: String) =
        upsert("product_images", 0, cv("product_id" to productId, "path" to path, "created" to System.currentTimeMillis()))

    fun deleteImage(id: Long) = del("product_images", "id=?", id)

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
        if (getColumnIndex("last") >= 0) l("last") else 0
    )

    fun doctors(): List<Doctor> = q(
        "SELECT d.*, IFNULL((SELECT MAX(date) FROM visits v WHERE v.doctor_id=d.id),0) AS last FROM doctors d ORDER BY d.name COLLATE NOCASE"
    ) { it.toDoctor() }

    fun doctor(id: Long): Doctor? = q("SELECT * FROM doctors WHERE id=?", id) { it.toDoctor() }.firstOrNull()

    fun saveDoctor(id: Long, name: String, specialty: String, clinic: String, area: String, phone: String): Long =
        upsert("doctors", id, cv("name" to name, "specialty" to specialty, "clinic" to clinic, "area" to area, "phone" to phone))

    fun deleteDoctor(id: Long) {
        del("doctor_products", "doctor_id=?", id)
        del("visits", "doctor_id=?", id)
        del("doctors", "id=?", id)
    }

    /** product_id -> kind (1 prescribing, 2 promote) */
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

    fun removeDoctorProduct(doctorId: Long, productId: Long) = del("doctor_products", "doctor_id=? AND product_id=?", doctorId, productId)

    /** List ke liye: har doctor ke kitne prescribing / promote products */
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

    fun deleteVisit(id: Long) = del("visits", "id=?", id)
    fun setFollowDone(id: Long) = upsert("visits", id, cv("follow_done" to 1))

    /** Woh follow-ups jo `until` tak ke hain aur abhi done nahi hue */
    fun dueFollowups(until: Long): List<Visit> = q(
        "SELECT v.*, d.name AS dname FROM visits v JOIN doctors d ON d.id=v.doctor_id " +
            "WHERE v.followup>0 AND v.followup<=? AND v.follow_done=0 ORDER BY v.followup", until
    ) { it.toVisit() }

    fun pendingFollowups(): List<Visit> = q(
        "SELECT v.*, d.name AS dname FROM visits v JOIN doctors d ON d.id=v.doctor_id " +
            "WHERE v.followup>0 AND v.follow_done=0 ORDER BY v.followup"
    ) { it.toVisit() }

    fun visitsBetween(from: Long, to: Long): List<Visit> = q(
        "SELECT v.*, d.name AS dname FROM visits v JOIN doctors d ON d.id=v.doctor_id " +
            "WHERE v.date BETWEEN ? AND ? ORDER BY v.date DESC", from, to
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

    // ---------- reports ----------
    fun orderTotals(from: Long, to: Long): Agg = q(
        "SELECT COUNT(*) AS c, IFNULL(SUM(gross),0) AS g, IFNULL(SUM(discount),0) AS dsc, IFNULL(SUM(net),0) AS n FROM orders WHERE date BETWEEN ? AND ?",
        from, to
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
