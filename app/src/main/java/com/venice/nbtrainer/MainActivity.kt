package com.venice.nbtrainer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.PointF
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import kotlin.math.abs
import kotlin.math.hypot

// ------------------------------------------------------------------ Config

class Config(ctx: Context) {

    private val p: SharedPreferences =
        ctx.getSharedPreferences("nbt_cfg", Context.MODE_PRIVATE)

    var aimEnabled: Boolean
        get() = p.getBoolean("aim", true)
        set(v) = p.edit().putBoolean("aim", v).apply()

    var dodgeEnabled: Boolean
        get() = p.getBoolean("dodge", true)
        set(v) = p.edit().putBoolean("dodge", v).apply()

    var maxRange: Float
        get() = p.getFloat("range", 0.40f)
        set(v) = p.edit().putFloat("range", v).apply()

    var dodgeRadius: Float
        get() = p.getFloat("radius", 0.20f)
        set(v) = p.edit().putFloat("radius", v).apply()

    var dodgeStrength: Float
        get() = p.getFloat("strength", 130f)
        set(v) = p.edit().putFloat("strength", v).apply()

    var dodgeCooldown: Long
        get() = p.getLong("cd", 320L)
        set(v) = p.edit().putLong("cd", v).apply()

    var anchorX: Float
        get() = p.getFloat("ax", 0.5f)
        set(v) = p.edit().putFloat("ax", v).apply()

    var anchorY: Float
        get() = p.getFloat("ay", 0.86f)
        set(v) = p.edit().putFloat("ay", v).apply()
}

// ---------------------------------------------------------------- Detector

class Detector(private val cfg: Config) {

    private val workW = 480

    data class Scene(
        val targets: List<RectF>,
        val dangers: List<RectF>,
        val scale: Float
    )

    fun analyze(src: Bitmap, viewW: Int, viewH: Int): Scene? {
        if (src.width < 10 || src.height < 10) return null

        val scale = viewW.toFloat() / workW
        val w = workW
        val h = (viewH / scale).toInt().coerceIn(1, 2000)

        val small = Bitmap.createScaledBitmap(src, w, h, false)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        if (small !== src) small.recycle()

        val ax = cfg.anchorX * w
        val ay = cfg.anchorY * h
        val maxRange = cfg.maxRange * h

        val bars = ArrayList<RectF>()
        var y = 0
        while (y < h) {
            var x = 0
            while (x < w) {
                if (isRed(px[y * w + x])) {
                    var run = x
                    while (run < w && isRed(px[y * w + run])) run++
                    val len = run - x
                    if (len in 14..110) bars.add(RectF(x.toFloat(), y.toFloat(), run.toFloat(), y + 1f))
                    x = run
                } else x++
            }
            y += 2
        }

        val targets = merge(bars, 40f, 14f)
            .filter { it.top < ay - h * 0.02f }
            .filter { hypot(it.centerX() - ax, it.centerY() - ay) < maxRange }
            .sortedBy { hypot(it.centerX() - ax, it.centerY() - ay) }
            .take(6)

        val dangerPts = ArrayList<RectF>()
        val radius = cfg.dodgeRadius * w
        val y0 = (ay - radius).toInt().coerceAtLeast(0)
        val y1 = (ay + radius * 0.4f).toInt().coerceAtMost(h)
        val x0 = (ax - radius).toInt().coerceAtLeast(0)
        val x1 = (ax + radius).toInt().coerceAtMost(w)
        for (yy in y0 until y1) {
            for (xx in x0 until x1) {
                val c = px[yy * w + xx]
                if (isWarm(c) && !isRed(c)) dangerPts.add(RectF(xx.toFloat(), yy.toFloat(), xx + 1f, yy + 1f))
            }
        }
        val dangers = merge(dangerPts, 8f, 8f)
            .filter { it.width() < 30f && it.height() < 30f }
            .filter { hypot(it.centerX() - ax, it.centerY() - ay) < radius }
            .take(8)

        if (targets.isEmpty() && dangers.isEmpty()) return null
        return Scene(targets, dangers, scale)
    }

    private fun merge(src: List<RectF>, dx: Float, dy: Float): List<RectF> {
        val out = ArrayList<RectF>()
        for (r in src) {
            val hit = out.firstOrNull {
                abs(it.centerX() - r.centerX()) < dx && abs(it.centerY() - r.centerY()) < dy
            }
            if (hit != null) hit.union(r) else out.add(RectF(r))
        }
        return out
    }

    private fun isRed(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= 130 && g <= 85 && b <= 85 && r - g > 60 && r - b > 60
    }

    private fun isWarm(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return r >= 175 && g in 55..190 && b <= 95
    }
}

// --------------------------------------------------------------- Overlay

class OverlayView(ctx: Context) : View(ctx) {

    @Volatile private var scene: Detector.Scene? = null
    private val cfg = Config(ctx)

    private val aim = Paint().apply {
        color = -0x1A1A01; strokeWidth = 4f
        style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val box = Paint().apply {
        color = -0x4C293; strokeWidth = 3f
        style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val dangerPaint = Paint().apply {
        color = -0x589D8; strokeWidth = 3f
        style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val dot = Paint().apply { color = -0x1A1A01; isAntiAlias = true }

    fun push(s: Detector.Scene?) { scene = s; postInvalidateOnAnimation() }

    override fun onDraw(canvas: Canvas) {
        val s = scene ?: return
        val ax = cfg.anchorX * width
        val ay = cfg.anchorY * height

        for (d in s.dangers) {
            canvas.drawCircle(d.centerX() * s.scale, d.centerY() * s.scale, 26f, dangerPaint)
        }
        for ((i, t) in s.targets.withIndex()) {
            val r = RectF(t.left * s.scale, t.top * s.scale, t.right * s.scale, t.bottom * s.scale)
            canvas.drawRect(r, box)
            if (i == 0) {
                val tx = r.centerX()
                val ty = r.centerY()
                canvas.drawLine(ax, ay, tx, ty, aim)
                val len = hypot(tx - ax, ty - ay).coerceAtLeast(1f)
                val nx = (tx - ax) / len
                val ny = (ty - ay) / len
                val tipX = ax + nx * (len + 40f)
                val tipY = ay + ny * (len + 40f)
                val a = Math.toRadians(160.0).toFloat()
                val p1x = tipX + (nx * kotlin.math.cos(a) - ny * kotlin.math.sin(a)) * 22f
                val p1y = tipY + (nx * kotlin.math.sin(a) + ny * kotlin.math.cos(a)) * 22f
                val p2x = tipX + (nx * kotlin.math.cos(-a) - ny * kotlin.math.sin(-a)) * 22f
                val p2y = tipY + (nx * kotlin.math.sin(-a) + ny * kotlin.math.cos(-a)) * 22f
                val head = Path().apply {
                    moveTo(tipX, tipY); lineTo(p1x, p1y)
                    moveTo(tipX, tipY); lineTo(p2x, p2y)
                }
                canvas.drawPath(head, aim)
            }
        }
        canvas.drawCircle(ax, ay, 7f, dot)
    }
}

class OverlayService : Service() {

    companion object { var instance: OverlayService? = null }

    private var view: OverlayView? = null
    private lateinit var wm: WindowManager

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        wm = getSystemService(WindowManager::class.java)
        val v = OverlayView(this)
        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        wm.addView(v, lp)
        view = v
    }

    fun push(s: Detector.Scene?) { view?.push(s) }

    override fun onDestroy() {
        instance = null
        view?.let { runCatching { wm.removeView(it) } }
        super.onDestroy()
    }
}

// ------------------------------------------------------ Accessibilité

class DodgeAccessibilityService : AccessibilityService() {

    companion object { var instance: DodgeAccessibilityService? = null }

    override fun onServiceConnected() { instance = this }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() { instance = null; super.onDestroy() }

    fun swipe(x: Float, y: Float, dx: Float, dy: Float, ms: Long = 110) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + dx, y + dy)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, ms)
        dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
    }
}

// -------------------------------------------------------------- Capture

class CaptureService : Service() {

    companion object {
        private const val CH_ID = "nbt_capture"
        private const val NOTIF_ID = 42
        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, CaptureService::class.java)) }
    }

    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var cfg: Config
    private lateinit var detector: Detector
    private var lastDodge = 0L
    private var flip = false
    private var busy = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        cfg = Config(this)
        detector = Detector(cfg)
        thread = HandlerThread("nbt-capture").also { it.start() }
        handler = Handler(thread.looper)
        startForeground(NOTIF_ID, notification())
        startService(Intent(this, OverlayService::class.java))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val code = intent?.getIntExtra("code", 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>("data") ?: return START_STICKY

        val mgr = getSystemService(MediaProjectionManager::class.java)
        projection = mgr.getMediaProjection(code, data)
        projection?.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, handler)

        val dm = getSystemService(DisplayManager::class.java)
        val metrics = resources.displayMetrics
        val display = dm.getDisplay(DisplayManager.DISPLAY_CATEGORY_PRESENTATION) ?: dm.getDisplay(0)

        reader = ImageReader.newInstance(
            metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2
        )
        projection?.createVirtualDisplay(
            "nbt", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, handler
        )

        reader?.setOnImageAvailableListener({ r ->
            if (busy) { r.acquireLatestImage()?.close(); return@setOnImageAvailableListener }
            val img = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            try {
                busy = true
                val plane = img.planes[0]
                val bmp = Bitmap.createBitmap(img.width, img.height, Bitmap.Config.ARGB_8888)
                bmp.copyPixelsFromBuffer(plane.buffer)
                handle(bmp, img.width, img.height)
                bmp.recycle()
            } catch (_: Throwable) {
            } finally {
                img.close()
                busy = false
            }
        }, handler)

        return START_STICKY
    }

    private fun handle(bmp: Bitmap, w: Int, h: Int) {
        val scene = detector.analyze(bmp, w, h)
        OverlayService.instance?.push(if (scene == null || !cfg.aimEnabled) null else scene)
        if (scene == null || !cfg.dodgeEnabled) return

        val now = System.currentTimeMillis()
        if (now - lastDodge < cfg.dodgeCooldown) return

        val ax = cfg.anchorX * w
        val ay = cfg.anchorY * h
        val danger = scene.dangers.minByOrNull {
            hypot(it.centerX() * scene.scale - ax, it.centerY() * scene.scale - ay)
        } ?: return
        if (danger.centerY() * scene.scale > ay) return

        val vx = ax - danger.centerX() * scene.scale
        val vy = ay - danger.centerY() * scene.scale
        flip = !flip
        val side = if (flip) 1f else -1f
        val len = hypot(vx, vy).coerceAtLeast(1f)
        val dx = (-vy / len) * cfg.dodgeStrength * side
        val dy = (vx / len) * cfg.dodgeStrength * side * 0.35f

        DodgeAccessibilityService.instance?.swipe(w * 0.18f, h * 0.82f, dx, dy)
        lastDodge = now
    }

    private fun notification(): Notification {
        val ch = NotificationChannel(CH_ID, "Capture", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        return NotificationCompat.Builder(this, CH_ID)
            .setContentTitle("NB Trainer actif")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        reader?.close()
        projection?.stop()
        thread.quitSafely()
        stopService(Intent(this, OverlayService::class.java))
        super.onDestroy()
    }
}

// ------------------------------------------------------------ MainActivity

class MainActivity : AppCompatActivity() {

    private lateinit var cfg: Config

    private val projectionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            if (res.resultCode == RESULT_OK && res.data != null) {
                val i = Intent(this, CaptureService::class.java)
                    .putExtra("code", res.resultCode)
                    .putExtra("data", res.data)
                if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            } else toast("Capture refusée")
        }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        cfg = Config(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 96, 48, 48)
        }

        fun toggle(label: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            val sw = Switch(this).apply { text = label; isChecked = get() }
            sw.setOnCheckedChangeListener { _, v -> set(v) }
            root.addView(sw)
        }

        fun slider(label: String, from: Float, to: Float, get: () -> Float, set: (Float) -> Unit) {
            val tv = TextView(this).apply { text = "$label : ${"%.2f".format(get())}" }
            val sb = SeekBar(this).apply {
                max = 100
                progress = (((get() - from) / (to - from)) * 100).toInt().coerceIn(0, 100)
            }
            sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    val v = from + (to - from) * p / 100f
                    set(v); tv.text = "$label : ${"%.2f".format(v)}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
            root.addView(tv)
            root.addView(sb)
        }

        toggle("Aide à la visée", { cfg.aimEnabled }, { cfg.aimEnabled = it })
        slider("Portée", 0.15f, 0.90f, { cfg.maxRange }, { cfg.maxRange = it })
        toggle("Autododge", { cfg.dodgeEnabled }, { cfg.dodgeEnabled = it })
        slider("Rayon d'esquive", 0.08f, 0.45f, { cfg.dodgeRadius }, { cfg.dodgeRadius = it })
        slider("Force du swipe", 60f, 260f, { cfg.dodgeStrength }, { cfg.dodgeStrength = it })
        slider("Ancre X", 0.2f, 0.8f, { cfg.anchorX }, { cfg.anchorX = it })
        slider("Ancre Y", 0.6f, 0.98f, { cfg.anchorY }, { cfg.anchorY = it })

        root.addView(Button(this).apply {
            text = "1. Autoriser l'overlay"
            setOnClickListener {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                )
            }
        })
        root.addView(Button(this).apply {
            text = "2. Autoriser l'accessibilité"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(Button(this).apply {
            text = "3. DÉMARRER"
            setOnClickListener {
                if (!Settings.canDrawOverlays(this@MainActivity)) { toast("Overlay non autorisé"); return@setOnClickListener }
                if (DodgeAccessibilityService.instance == null) { toast("Accessibilité non activée"); return@setOnClickListener }
                val mgr = getSystemService(MediaProjectionManager::class.java)
                projectionLauncher.launch(mgr.createScreenCaptureIntent())
            }
        })
        root.addView(Button(this).apply {
            text = "ARRÊTER"
            setOnClickListener { CaptureService.stop(this@MainActivity) }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}