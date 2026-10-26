package com.zuhaib.mrdiary

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object C {
    val PRIMARY = 0xFF00796B.toInt()
    val PRIMARY_DARK = 0xFF004D40.toInt()
    val PRIMARY_LIGHT = 0xFFE0F2F1.toInt()
    val BG = 0xFFF2F4F5.toInt()
    val TEXT = 0xFF1F2933.toInt()
    val SUB = 0xFF65717E.toInt()
    val BORDER = 0xFFE1E5E8.toInt()
    val RED = 0xFFD32F2F.toInt()
    val LIGHT = 0xFFECEFF1.toInt()
    val ORANGE = 0xFFEF6C00.toInt()
}

object T {
    const val TEXT = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
    const val NAME = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
    const val NUM = InputType.TYPE_CLASS_NUMBER
    const val DEC = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
    const val PHONE = InputType.TYPE_CLASS_PHONE
    const val MULTI = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
}

object Fmt {
    private val money = DecimalFormat("#,##0.##", DecimalFormatSymbols(Locale.US))
    fun rs(v: Double): String = "Rs " + money.format(v)
    fun n(v: Double): String = money.format(v)
    fun plain(v: Double): String =
        if (v == Math.floor(v)) v.toLong().toString()
        else String.format(Locale.US, "%.2f", v).trimEnd('0').trimEnd('.')

    fun date(t: Long): String = SimpleDateFormat("d MMM yyyy", Locale.US).format(Date(t))
    fun dateTime(t: Long): String = SimpleDateFormat("d MMM yyyy, h:mm a", Locale.US).format(Date(t))
    fun day(t: Long): String = SimpleDateFormat("EEE, d MMM", Locale.US).format(Date(t))
    fun time(t: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(t))
    fun dur(ms: Int): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
}

object Range {
    fun dayStart(t: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = t; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun dayEnd(t: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart(t); add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis - 1

    fun monthStart(t: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart(t); set(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis

    fun monthEnd(t: Long = System.currentTimeMillis()): Long = Calendar.getInstance().apply {
        timeInMillis = monthStart(t); add(Calendar.MONTH, 1)
    }.timeInMillis - 1

    fun daysAgo(n: Int): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart(); add(Calendar.DAY_OF_MONTH, -n)
    }.timeInMillis

    fun daysAhead(n: Int): Long = Calendar.getInstance().apply {
        timeInMillis = dayStart(); add(Calendar.DAY_OF_MONTH, n)
    }.timeInMillis
}

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Har screen ka base: scroll wala layout + chhote helper functions */
abstract class BaseActivity : Activity() {
    lateinit var db: Db
    lateinit var content: LinearLayout
    private lateinit var scroll: ScrollView
    private var pendingCall: String? = null
    open val showBack = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = Db.get(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(40))
        }
        scroll = ScrollView(this).apply {
            setBackgroundColor(C.BG); isFillViewport = true
            addView(content)
        }
        setContentView(scroll)
        if (showBack) actionBar?.setDisplayHomeAsUpEnabled(true)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    fun refresh() {
        val y = scroll.scrollY
        content.removeAllViews()
        render()
        scroll.post { scroll.scrollTo(0, y) }
    }

    abstract fun render()

    open fun onBackRequested(): Boolean = false   // true = hum ne handle kar liya

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            if (!onBackRequested()) finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!onBackRequested()) super.onBackPressed()
    }

    // ---------- view helpers ----------
    fun dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
    fun dpf(v: Float): Float = v * resources.displayMetrics.density

    fun rounded(color: Int, radius: Float = 12f, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radius)
        if (stroke != 0) setStroke(dp(1), stroke)
    }

    fun text(s: CharSequence, size: Float = 15f, bold: Boolean = false, color: Int = C.TEXT): TextView =
        TextView(this).apply {
            text = s; textSize = size; setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    /** kind: 0 = bhara hua, 1 = outline, 2 = laal, 3 = halka */
    fun btn(label: String, kind: Int = 0, size: Float = 15f, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label; textSize = size; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(14), dp(11), dp(14), dp(11))
            val bg: Int; val fg: Int; val st: Int
            when (kind) {
                1 -> { bg = Color.WHITE; fg = C.PRIMARY; st = C.PRIMARY }
                2 -> { bg = C.RED; fg = Color.WHITE; st = 0 }
                3 -> { bg = C.LIGHT; fg = C.TEXT; st = 0 }
                else -> { bg = C.PRIMARY; fg = Color.WHITE; st = 0 }
            }
            setTextColor(fg)
            background = RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(bg, 10f, st), null)
            isClickable = true
            setOnClickListener { onClick() }
        }

    fun chip(label: String, selected: Boolean, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            setPadding(dp(14), dp(7), dp(14), dp(7))
            setTextColor(if (selected) Color.WHITE else C.TEXT)
            background = RippleDrawable(
                ColorStateList.valueOf(0x33000000),
                rounded(if (selected) C.PRIMARY else Color.WHITE, 18f, if (selected) 0 else C.BORDER), null
            )
            setOnClickListener { onClick() }
        }

    fun chipRow(vararg chips: View): HorizontalScrollView {
        val row = hrow()
        chips.forEachIndexed { i, c ->
            row.addView(c, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = dp(6) })
        }
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
    }

    fun input(hint: String, value: String = "", type: Int = T.NAME): EditText = EditText(this).apply {
        this.hint = hint; setText(value); inputType = type; textSize = 16f
        if (type == T.MULTI) { minLines = 3; gravity = Gravity.TOP or Gravity.START }
    }

    fun vbox(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    fun hrow(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
    }

    fun card(): LinearLayout = vbox().apply {
        background = rounded(Color.WHITE, 14f, C.BORDER)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    fun <V : View> LinearLayout.add(v: V, top: Int = 0, w: Int = MATCH, h: Int = WRAP, weight: Float = 0f): V {
        addView(v, LinearLayout.LayoutParams(w, h, weight).apply { topMargin = dp(top) })
        return v
    }

    /** Horizontal row mein barabar hisse */
    fun LinearLayout.addWeighted(v: View, gap: Int = 6) {
        addView(v, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (childCount > 0) marginStart = dp(gap) })
    }

    fun section(title: String) {
        content.add(text(title, 17f, true).apply { setPadding(dp(4), dp(18), 0, dp(6)) })
    }

    fun empty(msg: String): TextView = text(msg, 14f, color = C.SUB).apply {
        setPadding(dp(4), dp(8), dp(4), dp(8))
    }

    fun form(vararg fields: Pair<String, View>): LinearLayout = vbox().apply {
        fields.forEach { (label, v) ->
            add(text(label, 13f, color = C.SUB), if (childCount == 0) 4 else 12)
            add(v)
        }
    }

    fun EditText.onChange(f: (String) -> Unit) = addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: Editable?) { f(s?.toString() ?: "") }
    })

    fun EditText.str(): String = text.toString().trim()

    // ---------- dialogs ----------
    fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    fun confirm(msg: String, yes: String = "Haan", onYes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg)
            .setPositiveButton(yes) { _, _ -> onYes() }
            .setNegativeButton("Nahi", null).show()
    }

    fun pick(title: String, items: List<String>, onPick: (Int) -> Unit) {
        AlertDialog.Builder(this).setTitle(title)
            .setItems(items.toTypedArray()) { _, i -> onPick(i) }.show()
    }

    /** onOk false lautaye to dialog band nahi hota (validation ke liye) */
    fun formDialog(title: String, body: View, okLabel: String = "Save", onOk: () -> Boolean): AlertDialog {
        val wrap = ScrollView(this).apply {
            setPadding(dp(20), dp(4), dp(20), dp(4))
            addView(body)
        }
        val d = AlertDialog.Builder(this).setTitle(title).setView(wrap)
            .setPositiveButton(okLabel, null).setNegativeButton("Cancel", null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { if (onOk()) d.dismiss() }
        }
        d.show()
        return d
    }

    // ---------- call / whatsapp / share ----------
    fun call(phone: String) {
        if (phone.isBlank()) { toast("Phone number save nahi hai"); return }
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) {
            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + phone.trim())))
        } else {
            pendingCall = phone
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 77)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (rc == 77) {
            val p = pendingCall ?: return
            pendingCall = null
            val ok = res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED
            startActivity(Intent(if (ok) Intent.ACTION_CALL else Intent.ACTION_DIAL, Uri.parse("tel:" + p.trim())))
        }
    }

    fun whatsapp(phone: String) {
        val digits = phone.filter { it.isDigit() }
        if (digits.isEmpty()) { toast("Phone number save nahi hai"); return }
        val intl = when {
            digits.startsWith("92") -> digits
            digits.startsWith("0") -> "92" + digits.drop(1)
            else -> "92$digits"
        }
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intl"))) }
        catch (e: Exception) { toast("WhatsApp nahi khula") }
    }

    fun shareText(t: String) {
        val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, t) }
        startActivity(Intent.createChooser(i, "Kis app se bhejna hai?"))
    }

    fun open(cls: Class<*>, id: Long = 0, extra: String = "id") {
        startActivity(Intent(this, cls).putExtra(extra, id))
    }

    // ---------- shared edit dialogs ----------
    fun editProduct(p: Product?, onSaved: (Long) -> Unit) {
        val name = input("Maslan: Recetam 500mg", p?.name ?: "")
        val pack = input("Maslan: 10x10 tablets", p?.pack ?: "", T.TEXT)
        val price = input("Rs", p?.let { if (it.price > 0) Fmt.plain(it.price) else "" } ?: "", T.DEC)
        val ind = input("Maslan: Epilepsy, Dementia", p?.indication ?: "", T.TEXT)
        val body = form("Medicine ka naam" to name, "Pack size" to pack, "Trade price (ek pack ka)" to price, "Kis bimari ke liye" to ind)
        formDialog(if (p == null) "Nayi medicine" else "Medicine edit", body) {
            if (name.str().isEmpty()) { name.error = "Naam likho"; return@formDialog false }
            val id = db.saveProduct(p?.id ?: 0, name.str(), pack.str(), price.str().toDoubleOrNull() ?: 0.0, ind.str())
            onSaved(id); true
        }
    }

    fun editStore(s: Store?, onSaved: (Store) -> Unit) {
        val name = input("Maslan: Al-Shifa Medical Store", s?.name ?: "")
        val owner = input("Maalik ka naam", s?.owner ?: "")
        val phone = input("03xx xxxxxxx", s?.phone ?: "", T.PHONE)
        val area = input("Maslan: Sukkur, Shikarpur", s?.area ?: "")
        val body = form("Store ka naam" to name, "Maalik" to owner, "Phone" to phone, "Area / Shehar" to area)
        formDialog(if (s == null) "Naya store" else "Store edit", body) {
            if (name.str().isEmpty()) { name.error = "Naam likho"; return@formDialog false }
            val id = db.saveStore(s?.id ?: 0, name.str(), owner.str(), phone.str(), area.str())
            onSaved(db.store(id)!!); true
        }
    }

    fun editDoctor(d: Doctor?, onSaved: (Long) -> Unit) {
        val name = input("Maslan: Dr. Ahmed Ali", d?.name ?: "")
        val spec = input("Maslan: Neurologist, Psychiatrist", d?.specialty ?: "")
        val clinic = input("Hospital / clinic ka naam", d?.clinic ?: "")
        val area = input("Maslan: Sukkur, Khairpur", d?.area ?: "")
        val phone = input("03xx xxxxxxx", d?.phone ?: "", T.PHONE)
        val body = form("Doctor ka naam" to name, "Specialty" to spec, "Clinic / Hospital" to clinic, "Area / Shehar" to area, "Phone" to phone)
        formDialog(if (d == null) "Naya doctor" else "Doctor edit", body) {
            if (name.str().isEmpty()) { name.error = "Naam likho"; return@formDialog false }
            val id = db.saveDoctor(d?.id ?: 0, name.str(), spec.str(), clinic.str(), area.str(), phone.str())
            onSaved(id); true
        }
    }

    /** List ki ek row: upar bold title, neeche chhoti lines, right par optional buttons */
    fun listRow(title: String, sub: String, sub2: String = "", right: View? = null, onClick: () -> Unit, onLong: (() -> Unit)? = null): LinearLayout {
        val row = hrow().apply {
            background = RippleDrawable(ColorStateList.valueOf(0x22000000), rounded(Color.WHITE, 12f, C.BORDER), null)
            setPadding(dp(14), dp(11), dp(8), dp(11))
            setOnClickListener { onClick() }
            if (onLong != null) setOnLongClickListener { onLong(); true }
        }
        val texts = vbox()
        texts.add(text(title, 16f, true))
        if (sub.isNotBlank()) texts.add(text(sub, 13.5f, color = C.SUB), 2)
        if (sub2.isNotBlank()) texts.add(text(sub2, 13f, color = C.PRIMARY), 2)
        row.addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (right != null) row.addView(right)
        return row
    }

    fun iconBtn(label: String, onClick: () -> Unit): TextView = TextView(this).apply {
        text = label; textSize = 20f; gravity = Gravity.CENTER
        setPadding(dp(10), dp(6), dp(10), dp(6))
        background = RippleDrawable(ColorStateList.valueOf(0x33000000), rounded(C.PRIMARY_LIGHT, 20f), null)
        setOnClickListener { onClick() }
    }

    /** Search box jo poori screen refresh kiye bagair sirf list ko update karta hai */
    fun searchBox(hint: String, current: String, onQuery: (String) -> Unit): EditText =
        input(hint, current, T.TEXT).apply {
            background = rounded(Color.WHITE, 12f, C.BORDER)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            onChange(onQuery)
        }
}
