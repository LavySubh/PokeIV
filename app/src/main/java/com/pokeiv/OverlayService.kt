package com.pokeiv

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlin.math.abs

/** Floating "IV" button + minimal result strip. Captures the screen on tap. */
class OverlayService : Service() {
    companion object {
        const val ACTION_START = "start"; const val ACTION_STOP = "stop"; const val ACTION_SHOW = "show"
        const val EXTRA_CODE = "code"; const val EXTRA_DATA = "data"
        @Volatile var running = false
    }

    private lateinit var wm: WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var bgThread: HandlerThread? = null
    private var button: TextView? = null
    private var panel: View? = null
    /** Result currently on the strip and the state of its what-if chips. */
    private var current: ScanResult? = null
    private var whatIf = WhatIf()
    private val metrics = DisplayMetrics()
    private val hidePanel = Runnable { removePanel() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopSelf(); return START_NOT_STICKY }
            ACTION_SHOW -> { LastScan.result?.let { showResult(it) }; return START_STICKY }
            ACTION_START -> start(intent)
        }
        return START_STICKY
    }

    private fun start(intent: Intent) {
        startForegroundCompat()
        if (projection != null) return
        val code = intent.getIntExtra(EXTRA_CODE, Activity.RESULT_CANCELED)
        val data: Intent = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)) ?: run { stopSelf(); return }
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(metrics)
        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        projection = mpm.getMediaProjection(code, data).also { p ->
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { main.post { stopSelf() } } }, main)
        }
        bgThread = HandlerThread("capture").also { it.start() }
        reader = ImageReader.newInstance(metrics.widthPixels, metrics.heightPixels, PixelFormat.RGBA_8888, 2)
        display = projection!!.createVirtualDisplay("pokeiv", metrics.widthPixels, metrics.heightPixels, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader!!.surface, null, Handler(bgThread!!.looper))
        addButton(); running = true
    }

    private fun startForegroundCompat() {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel("pokeiv", "PokéIV overlay", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, "pokeiv").setContentTitle("PokéIV is running")
            .setContentText("Open an appraisal and tap the IV button").setSmallIcon(R.drawable.ic_iv).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(1, n)
    }

    private fun overlayParams(w: Int, h: Int) = WindowManager.LayoutParams(w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT)

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun addButton() {
        val size = dp(52)
        val b = TextView(this).apply {
            text = "IV"; setTextColor(Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.parseColor("#E6E07A2E")); setStroke(dp(2), Color.WHITE) }
        }
        val lp = overlayParams(size, size).apply { gravity = Gravity.TOP or Gravity.START; x = metrics.widthPixels - size - dp(8); y = metrics.heightPixels / 3 }
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0
        b.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; true }
                MotionEvent.ACTION_MOVE -> { lp.x = startX + (e.rawX - downX).toInt(); lp.y = startY + (e.rawY - downY).toInt(); wm.updateViewLayout(v, lp); true }
                MotionEvent.ACTION_UP -> { if (abs(e.rawX - downX) < dp(8) && abs(e.rawY - downY) < dp(8)) capture(); true }
                else -> false
            }
        }
        wm.addView(b, lp); button = b
    }

    private fun capture() {
        removePanel()
        button?.visibility = View.INVISIBLE
        main.postDelayed({
            val bmp = grab()
            button?.visibility = View.VISIBLE
            if (bmp == null) { toast("Couldn't capture the screen – try again"); return@postDelayed }
            current = null; showPanel("…", "Reading", false)
            ScreenReader.read(bmp) { read, err ->
                if (read == null) { showPanel("!", "Read failed: ${err?.message}", false); return@read }
                Thread {
                    val res = Analyzer.analyze(this, read)
                    main.post { showResult(res) }
                }.start()
            }
        }, 180)
    }

    private fun grab(): Bitmap? {
        val img = reader?.acquireLatestImage() ?: return null
        return try {
            val plane = img.planes[0]
            val rowPad = plane.rowStride - plane.pixelStride * img.width
            val tmp = Bitmap.createBitmap(img.width + rowPad / plane.pixelStride, img.height, Bitmap.Config.ARGB_8888)
            tmp.copyPixelsFromBuffer(plane.buffer)
            Bitmap.createBitmap(tmp, 0, 0, img.width, img.height)
        } finally { img.close() }
    }

    private fun showResult(res: ScanResult) {
        current = res; whatIf = WhatIf()
        val (l, r) = Analyzer.format(this, res, whatIf)
        showPanel(l, r, !res.confident)
    }

    /** What-if chips (design decision 9): "If Best Buddy" unless the boost is already in; "If Purified" for Shadows. */
    private fun chips(res: ScanResult): View? {
        val canBuddy = !res.buddyBoost && res.best != null && res.best.level > 0.0
        val canPurify = res.shadow && res.best != null && res.best.level > 0.0
        if (!canBuddy && !canPurify) return null
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(6), 0, 0) }
        fun chip(label: String, on: () -> Boolean, toggle: () -> Unit) = TextView(this).apply {
            textSize = 12f; setPadding(dp(10), dp(3), dp(10), dp(3))
            fun paint() {
                text = (if (on()) "☑ " else "☐ ") + label
                setTextColor(if (on()) Color.parseColor("#202124") else Color.WHITE)
                background = GradientDrawable().apply { cornerRadius = dp(12).toFloat(); setColor(if (on()) Color.parseColor("#FFB066") else Color.parseColor("#33FFFFFF")) }
            }
            paint()
            setOnClickListener { toggle(); paint(); refreshText(); main.removeCallbacks(hidePanel); main.postDelayed(hidePanel, 15000) }
        }
        if (canBuddy) row.addView(chip("If Best Buddy", { whatIf.bestBuddy }, { whatIf = whatIf.copy(bestBuddy = !whatIf.bestBuddy) }),
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
        if (canPurify) row.addView(chip("If Purified", { whatIf.purified }, { whatIf = whatIf.copy(purified = !whatIf.purified) }))
        return row
    }

    private var leftText: TextView? = null
    private var rightText: TextView? = null

    private fun refreshText() {
        val res = current ?: return
        val (l, r) = Analyzer.format(this, res, whatIf)
        leftText?.text = l; rightText?.text = r
    }

    private fun showPanel(left: String, right: String, flag: Boolean) {
        if (!::wm.isInitialized) return
        removePanel()
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(10), dp(10))
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Color.parseColor("#E6202124")) }
        }
        val l = TextView(this).apply { text = left; setTextColor(Color.parseColor("#FFB066")); textSize = 15f; setPadding(0, 0, dp(12), 0); setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val div = View(this).apply { setBackgroundColor(Color.parseColor("#55FFFFFF")) }
        val r = TextView(this).apply { text = right; setTextColor(Color.WHITE); textSize = 13f; setPadding(dp(12), 0, dp(6), 0) }
        leftText = l; rightText = r
        val mid = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(r) }
        current?.let { chips(it) }?.let { mid.addView(it) }
        val edit = TextView(this).apply {
            text = "✎"; textSize = 20f; setTextColor(if (flag) Color.parseColor("#FFD54F") else Color.parseColor("#AAFFFFFF")); setPadding(dp(8), 0, dp(4), 0)
            setOnClickListener { removePanel(); startActivity(Intent(this@OverlayService, EditActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        card.addView(l); card.addView(div, LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT))
        card.addView(mid, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)); card.addView(edit)
        card.setOnClickListener { removePanel() }
        val lp = overlayParams(metrics.widthPixels - dp(16), WindowManager.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = dp(28) }
        wm.addView(card, lp); panel = card
        main.removeCallbacks(hidePanel); main.postDelayed(hidePanel, 15000)
    }

    private fun removePanel() { panel?.let { runCatching { wm.removeView(it) } }; panel = null; leftText = null; rightText = null }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        running = false
        removePanel(); button?.let { runCatching { wm.removeView(it) } }
        display?.release(); reader?.close(); projection?.stop(); bgThread?.quitSafely()
        super.onDestroy()
    }
}

/** Shows [LastScan.result] on the strip. */
fun Context.showOverlayResult() =
    startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_SHOW))
