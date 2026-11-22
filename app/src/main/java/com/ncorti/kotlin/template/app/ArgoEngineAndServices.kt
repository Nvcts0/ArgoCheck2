package com.driver.argocheck

import android.accessibilityservice.AccessibilityService
import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.*
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.*
import java.text.NumberFormat
import java.util.Locale
import kotlin.coroutines.resume

// =========================================================================
// 1. MESIN KALKULATOR & PARSER ORDERAN
// =========================================================================
object ArgoEngine {
    data class EvalResult(
        val status: String,
        val colorHex: String,
        val marginBersih: Double,
        val biayaEnergi: Double,
        val marginPerKm: Double,
        val upahPerJam: Double,
        val totalJarak: Double,
        val menit: Int
    )

    private val fareRegex = Regex("""Rp\s?([\d.,]+)""", RegexOption.IGNORE_CASE)
    private val kmRegex = Regex("""(\d+(?:[.,]\d+)?)\s?km""", RegexOption.IGNORE_CASE)
    private val minRegex = Regex("""(\d+)\s?(?:mnt|menit|min)""", RegexOption.IGNORE_CASE)

    fun parseAndEvaluate(context: Context, rawText: String): EvalResult? {
        val argo = fareRegex.find(rawText)?.groupValues?.get(1)
            ?.replace(".", "")?.replace(",", "")?.toDoubleOrNull() ?: return null
        if (argo < 3000) return null

        val kmList = kmRegex.findAll(rawText)
            .mapNotNull { it.groupValues[1].replace(",", ".").toDoubleOrNull() }
            .toList()
        if (kmList.isEmpty()) return null

        val jemput = if (kmList.size >= 2) kmList[0] else 0.0
        val antar = if (kmList.size >= 2) kmList[1] else kmList[0]
        val totalJarak = (jemput + antar).coerceAtLeast(0.1)

        val menitParsed = minRegex.findAll(rawText).mapNotNull { it.groupValues[1].toIntOrNull() }.sum()
        val menit = if (menitParsed > 0) menitParsed else ((totalJarak / 24.0) * 60).toInt().coerceAtLeast(3)

        val prefs = context.getSharedPreferences("argo_prefs", Context.MODE_PRIVATE)
        val hargaEnergi = prefs.getFloat("hargaEnergi", 10000f).toDouble()
        val efisiensi = prefs.getFloat("efisiensiEnergi", 14.0f).toDouble().coerceAtLeast(0.1)
        val targetBulanan = prefs.getFloat("targetBulanan", 7500000f).toDouble()
        val pengeluaranHarian = prefs.getFloat("pengeluaranHarian", 50000f).toDouble()
        val hariKerja = prefs.getInt("hariKerjaBulan", 25).coerceAtLeast(1)
        val jamKerja = prefs.getFloat("jamKerjaHari", 8.0f).toDouble().coerceAtLeast(1.0)
        val minMarginKm = prefs.getFloat("minMarginPerKm", 2000f).toDouble()

        val biayaEnergi = totalJarak * (hargaEnergi / efisiensi)
        val marginBersih = argo - biayaEnergi
        val marginPerKm = marginBersih / totalJarak
        val upahPerJam = marginBersih / (menit / 60.0).coerceAtLeast(0.05)

        val totalTargetHarian = (targetBulanan / hariKerja) + pengeluaranHarian
        val targetUpahJam = totalTargetHarian / jamKerja

        val (status, colorHex) = when {
            marginPerKm >= minMarginKm && upahPerJam >= targetUpahJam -> "✅ LAYAK" to "#1B5E20"
            marginPerKm >= minMarginKm * 0.7 || upahPerJam >= targetUpahJam * 0.7 -> "⚠️ PERTIMBANGKAN" to "#E65100"
            else -> "❌ BONCOS" to "#B71C1C"
        }

        return EvalResult(status, colorHex, marginBersih, biayaEnergi, marginPerKm, upahPerJam, totalJarak, menit)
    }
}

// =========================================================================
// 2. SERVICE PEMBACA LAYAR + FALLBACK ML KIT OCR
// =========================================================================
class OrderAccessibilityService : AccessibilityService() {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val ocrRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var lastScan = 0L

    private val driverApps = setOf(
        "com.gojek.partner",
        "com.grabtaxi.driver2",
        "com.taxsee.driver",
        "sinet.startup.inDriver"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (pkg !in driverApps) return

        val now = System.currentTimeMillis()
        if (now - lastScan < 900) return
        lastScan = now

        val root = rootInActiveWindow ?: return
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        val uiText = texts.joinToString(" | ")

        val directResult = ArgoEngine.parseAndEvaluate(this, uiText)
        if (directResult != null) {
            triggerOverlay(directResult)
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    val bmp = Bitmap.wrapHardwareBuffer(res.hardwareBuffer, res.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                    res.hardwareBuffer.close()
                    if (bmp != null) {
                        scope.launch {
                            val ocrStr = runOcr(bmp)
                            bmp.recycle()
                            ArgoEngine.parseAndEvaluate(this@OrderAccessibilityService, ocrStr)?.let {
                                triggerOverlay(it)
                            }
                        }
                    }
                }
                override fun onFailure(errorCode: Int) {}
            })
        }
    }

    private suspend fun runOcr(bitmap: Bitmap): String = suspendCancellableCoroutine { cont ->
        ocrRecognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { cont.resume(it.text) }
            .addOnFailureListener { cont.resume("") }
    }

    private fun collectTexts(node: AccessibilityNodeInfo?, out: MutableList<String>) {
        if (node == null) return
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) collectTexts(node.getChild(i), out)
    }

    private fun triggerOverlay(res: ArgoEngine.EvalResult) {
        val intent = Intent(this, FloatingOverlayService::class.java).apply {
            action = "SHOW_ARGO_BADGE"
            putExtra("STATUS", res.status)
            putExtra("COLOR", res.colorHex)
            putExtra("BERSIH", res.marginBersih)
            putExtra("ENERGI", res.biayaEnergi)
            putExtra("PER_KM", res.marginPerKm)
            putExtra("PER_JAM", res.upahPerJam)
            putExtra("JARAK", res.totalJarak)
            putExtra("MENIT", res.menit)
        }
        startService(intent)
    }

    override fun onInterrupt() {}
    override fun onDestroy() { super.onDestroy(); scope.cancel() }
}

// =========================================================================
// 3. FLOATING WIDGET OVERLAY SERVICE
// =========================================================================
class FloatingOverlayService : Service() {
    private lateinit var wm: WindowManager
    private lateinit var badgeLayout: LinearLayout
    private lateinit var tvTitle: TextView
    private lateinit var tvMetrics: TextView
    private lateinit var tvSub: TextView
    private val handler = Handler(Looper.getMainLooper())
    private val hideTask = Runnable { badgeLayout.visibility = View.GONE }

    override fun onCreate() {
        super.onCreate()
        val channelId = "argocheck_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(channelId, "ArgoCheck", NotificationManager.IMPORTANCE_LOW))
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("ArgoCheck Siaga")
            .setContentText("Membaca kelayakan order masuk secara otomatis")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()
        startForeground(1, notif)

        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        badgeLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 24, 36, 24)
            visibility = View.GONE
            setOnClickListener { visibility = View.GONE }
        }
        tvTitle = TextView(this).apply { setTextColor(Color.WHITE); textSize = 15f; setTypeface(null, Typeface.BOLD) }
        tvMetrics = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        tvSub = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 11f }

        badgeLayout.addView(tvTitle)
        badgeLayout.addView(tvMetrics)
        badgeLayout.addView(tvSub)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 100
        }
        wm.addView(badgeLayout, params)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "SHOW_ARGO_BADGE") {
            val fmt = NumberFormat.getInstance(Locale("id", "ID")).apply { maximumFractionDigits = 0 }
            val status = intent.getStringExtra("STATUS") ?: ""
            val color = intent.getStringExtra("COLOR") ?: "#1B5E20"
            val bersih = intent.getDoubleExtra("BERSIH", 0.0)
            val energi = intent.getDoubleExtra("ENERGI", 0.0)
            val perKm = intent.getDoubleExtra("PER_KM", 0.0)
            val perJam = intent.getDoubleExtra("PER_JAM", 0.0)
            val jarak = intent.getDoubleExtra("JARAK", 0.0)
            val menit = intent.getIntExtra("MENIT", 0)

            badgeLayout.setBackgroundColor(Color.parseColor(color))
            tvTitle.text = "$status • Bersih Rp${fmt.format(bersih)}"
            tvMetrics.text = "Rp${fmt.format(perKm)}/km  |  Rp${fmt.format(perJam)}/jam"
            tvSub.text = "Jarak ${String.format("%.1f", jarak)} km (~$menit mnt) • Energi Rp${fmt.format(energi)}"

            badgeLayout.visibility = View.VISIBLE
            handler.removeCallbacks(hideTask)
            handler.postDelayed(hideTask, 10_000)
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(hideTask)
        wm.removeView(badgeLayout)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
