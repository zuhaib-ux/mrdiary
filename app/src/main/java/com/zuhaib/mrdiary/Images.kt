package com.zuhaib.mrdiary

import android.app.Activity
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min

object Img {
    fun dir(ctx: Context): File = File(ctx.filesDir, "images").apply { mkdirs() }

    fun newFile(ctx: Context): File =
        File(dir(ctx), "lit_${System.currentTimeMillis()}_${(Math.random() * 10000).toInt()}.jpg")

    /** Bitmap load karo taake lambi side lagbhag maxPx ho (memory bachane ke liye) */
    fun load(path: String, maxPx: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Photo ko seedha (EXIF rotation) aur munasib size (2400px) mein save karo */
    fun normalize(src: File, dst: File, maxSide: Int = 2400): Boolean = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(src.absolutePath, o)
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= maxSide) sample *= 2
        var bmp = BitmapFactory.decodeFile(src.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IllegalStateException("decode")
        val rot = when (ExifInterface(src.absolutePath).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        val longSide = max(bmp.width, bmp.height)
        val scale = if (longSide > maxSide) maxSide.toFloat() / longSide else 1f
        if (rot != 0f || scale != 1f) {
            val m = Matrix().apply { postScale(scale, scale); postRotate(rot) }
            val r = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            if (r != bmp) bmp.recycle()
            bmp = r
        }
        FileOutputStream(dst).use { bmp.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        bmp.recycle()
        true
    } catch (e: Throwable) {
        dst.delete(); false
    }

    fun importUri(ctx: Context, uri: Uri): File? {
        val tmp = File(ctx.cacheDir, "import_${System.currentTimeMillis()}.jpg")
        return try {
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tmp).use { input.copyTo(it) }
            } ?: return null
            val out = newFile(ctx)
            if (normalize(tmp, out)) out else null
        } catch (e: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }
}

/** Camera app ko photo save karne ki jagah dene ke liye chhota sa provider */
class FilesProvider : ContentProvider() {
    companion object { const val AUTH = "com.zuhaib.mrdiary.files" }

    private fun fileFor(uri: Uri): File {
        val base = context!!.filesDir.canonicalFile
        val f = File(base, uri.path ?: "").canonicalFile
        if (!f.path.startsWith(base.path)) throw SecurityException("bad path")
        return f
    }

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val f = fileFor(uri)
        f.parentFile?.mkdirs()
        return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode))
    }

    override fun query(uri: Uri, projection: Array<out String>?, sel: String?, args: Array<out String>?, sort: String?): Cursor {
        val f = fileFor(uri)
        val c = MatrixCursor(arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE))
        c.addRow(arrayOf<Any>(f.name, f.length()))
        return c
    }

    override fun getType(uri: Uri): String {
        val p = uri.path ?: ""
        return when {
            p.endsWith(".pdf") -> "application/pdf"
            p.endsWith(".png") -> "image/png"
            else -> "image/jpeg"
        }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?): Int = 0
}

/** Literature page full-screen, pinch se zoom */
class ImageViewActivity : Activity() {
    private lateinit var paths: Array<String>
    private var index = 0
    private lateinit var zoom: ZoomImageView
    private lateinit var counter: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = intent.getStringArrayExtra("paths") ?: arrayOf()
        index = intent.getIntExtra("index", 0)
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        zoom = ZoomImageView(this)
        root.addView(zoom, FrameLayout.LayoutParams(MATCH, MATCH))

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            setBackgroundColor(0x99000000.toInt())
            setPadding(0, dp(6), 0, dp(6))
        }
        fun navBtn(t: String, f: () -> Unit) = TextView(this).apply {
            text = t; textSize = 26f; setTextColor(Color.WHITE)
            setPadding(dp(28), dp(4), dp(28), dp(4)); setOnClickListener { f() }
        }
        counter = TextView(this).apply { setTextColor(Color.WHITE); textSize = 16f }
        bar.addView(navBtn("◀") { show(index - 1) })
        bar.addView(counter)
        bar.addView(navBtn("▶") { show(index + 1) })
        root.addView(bar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        setContentView(root)
        show(index)
    }

    private fun show(i: Int) {
        if (paths.isEmpty()) { finish(); return }
        index = (i + paths.size) % paths.size
        val dm = resources.displayMetrics
        val bmp = Img.load(paths[index], max(dm.widthPixels, dm.heightPixels) * 2)
        if (bmp != null) zoom.setBitmap(bmp)
        counter.text = "  ${index + 1} / ${paths.size}  "
    }
}

class ZoomImageView(ctx: Context) : ImageView(ctx) {
    private val m = Matrix()
    private val vals = FloatArray(9)
    private var minScale = 1f

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val cur = curScale()
            val target = (cur * d.scaleFactor).coerceIn(minScale, minScale * 8f)
            val f = target / cur
            m.postScale(f, f, d.focusX, d.focusY); fix()
            return true
        }
    })

    private val gestDet = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy); fix(); return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (curScale() > minScale * 1.05f) fit()
            else { m.postScale(2.5f, 2.5f, e.x, e.y); fix() }
            return true
        }
    })

    init { scaleType = ScaleType.MATRIX }

    fun setBitmap(b: Bitmap) {
        setImageBitmap(b)
        post { fit() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh); fit()
    }

    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || d.intrinsicWidth <= 0) return
        val s = min(width / d.intrinsicWidth.toFloat(), height / d.intrinsicHeight.toFloat())
        minScale = s
        m.reset()
        m.postScale(s, s)
        m.postTranslate((width - d.intrinsicWidth * s) / 2f, (height - d.intrinsicHeight * s) / 2f)
        imageMatrix = m
    }

    private fun curScale(): Float { m.getValues(vals); return vals[Matrix.MSCALE_X] }

    private fun fix() {
        val d = drawable ?: return
        m.getValues(vals)
        val s = vals[Matrix.MSCALE_X]
        val w = d.intrinsicWidth * s
        val h = d.intrinsicHeight * s
        vals[Matrix.MTRANS_X] = if (w <= width) (width - w) / 2f else vals[Matrix.MTRANS_X].coerceIn(width - w, 0f)
        vals[Matrix.MTRANS_Y] = if (h <= height) (height - h) / 2f else vals[Matrix.MTRANS_Y].coerceIn(height - h, 0f)
        m.setValues(vals)
        imageMatrix = m
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        gestDet.onTouchEvent(e)
        return true
    }
}
