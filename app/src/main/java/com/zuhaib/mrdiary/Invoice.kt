package com.zuhaib.mrdiary

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.TextPaint
import android.text.TextUtils
import java.io.File
import java.io.FileOutputStream

/** Order ko table ki shakal mein image / PDF / text banata hai */
class Invoice(
    private val orderId: Long,
    private val store: String,
    private val storeArea: String,
    private val date: Long,
    private val items: List<OrderItem>,
    private val note: String,
    private val sender: String
) {
    private val W = 1080f
    private val PAD = 48f
    private val ROW = 92f
    private val gross = items.sumOf { it.gross }
    private val disc = items.sumOf { it.discAmt }
    private val net = gross - disc

    // column right edges / starts
    private val cNo = PAD + 14f
    private val cName = PAD + 70f
    private val cQty = 640f      // right aligned ends
    private val cTp = 790f
    private val cDisc = 890f
    private val cAmt = W - PAD - 14f

    private fun p(size: Float, color: Int, bold: Boolean = false, align: Paint.Align = Paint.Align.LEFT) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size; this.color = color; textAlign = align
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }

    private val headerH = 250f
    private val tableHeadH = 70f
    private fun totalsH() = 330f + if (note.isNotBlank()) 70f else 0f
    fun height(): Int = (headerH + 30f + tableHeadH + items.size * ROW + totalsH() + 80f).toInt()

    fun draw(c: Canvas) {
        val teal = 0xFF00796B.toInt(); val dark = 0xFF004D40.toInt()
        val sub = 0xFF65717E.toInt(); val txt = 0xFF1F2933.toInt()
        c.drawColor(Color.WHITE)

        // ---- Header ----
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        fill.color = teal
        c.drawRect(0f, 0f, W, headerH, fill)
        c.drawText("ORDER", PAD, 78f, p(40f, 0xCCFFFFFF.toInt(), true))
        c.drawText(if (orderId > 0) "#$orderId" else "", W - PAD, 78f, p(36f, 0xCCFFFFFF.toInt(), true, Paint.Align.RIGHT))
        val storeP = p(54f, Color.WHITE, true)
        c.drawText(TextUtils.ellipsize(store, storeP, W - 2 * PAD, TextUtils.TruncateAt.END).toString(), PAD, 150f, storeP)
        val line2 = listOf(storeArea, Fmt.dateTime(date)).filter { it.isNotBlank() }.joinToString("   •   ")
        c.drawText(line2, PAD, 205f, p(30f, 0xE6FFFFFF.toInt()))
        if (sender.isNotBlank()) c.drawText(sender, W - PAD, 205f, p(28f, 0xE6FFFFFF.toInt(), false, Paint.Align.RIGHT))

        // ---- Table head ----
        var y = headerH + 30f
        fill.color = 0xFFE0F2F1.toInt()
        c.drawRoundRect(RectF(PAD, y, W - PAD, y + tableHeadH), 14f, 14f, fill)
        val hp = p(27f, dark, true); val hpr = p(27f, dark, true, Paint.Align.RIGHT)
        val hy = y + 45f
        c.drawText("#", cNo, hy, hp)
        c.drawText("MEDICINE", cName, hy, hp)
        c.drawText("QTY", cQty, hy, hpr)
        c.drawText("TP", cTp, hy, hpr)
        c.drawText("DISC", cDisc, hy, hpr)
        c.drawText("AMOUNT", cAmt, hy, hpr)
        y += tableHeadH

        // ---- Rows ----
        val nameP = p(31f, txt, true)
        val smallP = p(24f, sub)
        val numP = p(30f, txt, false, Paint.Align.RIGHT)
        val amtP = p(31f, txt, true, Paint.Align.RIGHT)
        val discP = p(28f, 0xFFEF6C00.toInt(), true, Paint.Align.RIGHT)
        val line = Paint().apply { color = 0xFFE1E5E8.toInt(); strokeWidth = 2f }
        items.forEachIndexed { i, it ->
            if (i % 2 == 1) { fill.color = 0xFFF7F9FA.toInt(); c.drawRect(PAD, y, W - PAD, y + ROW, fill) }
            val ty = y + 42f
            c.drawText("${i + 1}", cNo, ty + 12f, p(28f, sub, true))
            val nameW = cQty - 90f - cName
            c.drawText(TextUtils.ellipsize(it.name, nameP, nameW, TextUtils.TruncateAt.END).toString(), cName, ty, nameP)
            if (it.disc > 0) c.drawText("Discount: −${Fmt.rs(it.discAmt)}", cName, ty + 36f, smallP)
            else c.drawText("Gross: ${Fmt.rs(it.gross)}", cName, ty + 36f, smallP)
            c.drawText("${it.qty}", cQty, ty + 12f, numP)
            c.drawText(Fmt.n(it.price), cTp, ty + 12f, numP)
            c.drawText(if (it.disc > 0) "${Fmt.plain(it.disc)}%" else "—", cDisc, ty + 12f, discP)
            c.drawText(Fmt.n(it.net), cAmt, ty + 12f, amtP)
            c.drawLine(PAD, y + ROW, W - PAD, y + ROW, line)
            y += ROW
        }

        // ---- Totals ----
        y += 30f
        val lp = p(32f, sub); val rp = p(32f, txt, true, Paint.Align.RIGHT)
        val qty = items.sumOf { it.qty }
        fun tl(l: String, r: String, rc: Int = txt) {
            y += 50f
            c.drawText(l, W / 2f, y, lp)
            c.drawText(r, W - PAD - 14f, y, p(32f, rc, true, Paint.Align.RIGHT))
        }
        tl("Items / Qty", "${items.size} / $qty")
        tl("Total (TP)", Fmt.rs(gross))
        tl("Discount", "− " + Fmt.rs(disc), 0xFFEF6C00.toInt())
        y += 30f
        fill.color = dark
        c.drawRoundRect(RectF(W / 2f - 30f, y, W - PAD, y + 110f), 18f, 18f, fill)
        c.drawText("NET TOTAL", W / 2f, y + 68f, p(30f, 0xCCFFFFFF.toInt(), true))
        c.drawText(Fmt.rs(net), W - PAD - 24f, y + 74f, p(50f, Color.WHITE, true, Paint.Align.RIGHT))
        y += 110f
        if (note.isNotBlank()) {
            y += 60f
            val np = p(28f, sub)
            c.drawText(TextUtils.ellipsize("Note: $note", np, W - 2 * PAD, TextUtils.TruncateAt.END).toString(), PAD, y, np)
        }
        c.drawText("MR Diary", W / 2f, height() - 30f, p(22f, 0xFFB0B8BF.toInt(), false, Paint.Align.CENTER))
        rp.hashCode(); lp.hashCode()
    }

    private fun outFile(ctx: Context, ext: String): File {
        val dir = File(ctx.filesDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }   // purani share files saaf
        val safe = store.replace(Regex("[^A-Za-z0-9]+"), "_").take(30).trim('_')
        return File(dir, "Order_${safe}_${if (orderId > 0) orderId else System.currentTimeMillis()}.$ext")
    }

    fun image(ctx: Context): File {
        val bmp = Bitmap.createBitmap(W.toInt(), height(), Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        val f = outFile(ctx, "png")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()
        return f
    }

    fun pdf(ctx: Context): File {
        val scale = 595f / W
        val doc = PdfDocument()
        val page = doc.startPage(PdfDocument.PageInfo.Builder(595, Math.max(842, (height() * scale).toInt() + 1), 1).create())
        page.canvas.scale(scale, scale)
        draw(page.canvas)
        doc.finishPage(page)
        val f = outFile(ctx, "pdf")
        FileOutputStream(f).use { doc.writeTo(it) }
        doc.close()
        return f
    }

    /** WhatsApp text: *bold*, har medicine 2 lines mein, totals ```monospace``` mein seedhe */
    fun text(): String {
        val sb = StringBuilder()
        sb.append("🧾 *ORDER").append(if (orderId > 0) " #$orderId" else "").append("*\n")
        sb.append("🏪 *").append(store).append("*")
        if (storeArea.isNotBlank()) sb.append(" — ").append(storeArea)
        sb.append("\n📅 ").append(Fmt.dateTime(date)).append("\n")
        if (sender.isNotBlank()) sb.append("👤 ").append(sender).append("\n")
        sb.append("━━━━━━━━━━━━━━━━━━\n")
        items.forEachIndexed { i, it ->
            sb.append("*${i + 1}. ${it.name}*\n")
            sb.append("     ${it.qty} × ${Fmt.n(it.price)}")
            if (it.disc > 0) sb.append("  (−${Fmt.plain(it.disc)}%)")
            sb.append("  = *${Fmt.n(it.net)}*\n")
        }
        sb.append("━━━━━━━━━━━━━━━━━━\n")
        val rows = listOf(
            "Items" to "${items.size} / ${items.sumOf { it.qty }} qty",
            "Total TP" to Fmt.n(gross),
            "Discount" to "-" + Fmt.n(disc),
            "NET TOTAL" to "Rs " + Fmt.n(net)
        )
        val w = 26
        sb.append("```\n")
        rows.forEach { (l, r) -> sb.append(l).append(" ".repeat(Math.max(1, w - l.length - r.length))).append(r).append("\n") }
        sb.append("```")
        if (note.isNotBlank()) sb.append("\n📝 ").append(note)
        return sb.toString()
    }

    companion object {
        fun shareFile(a: BaseActivity, f: File, mime: String, caption: String) {
            val uri = Uri.parse("content://${FilesProvider.AUTH}/share/${Uri.encode(f.name)}")
            val i = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_TEXT, caption)
                clipData = android.content.ClipData.newRawUri("", uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            a.startActivity(Intent.createChooser(i, "Kis app se bhejna hai?"))
        }
    }
}
