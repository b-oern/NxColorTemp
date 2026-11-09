package net.bsnx.colortemp

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Menu
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.ln
import kotlin.math.pow

/**
 * ColorTempSensorActivity
 *
 * Ermittelt die Farbtemperatur (in Kelvin, Skala 2000–8000 K) des mittleren 50 %
 * des Kamerabildes in Echtzeit und zeigt sie auf einer visuellen Skala an.
 *
 * Algorithmus:
 *  1. Analysiere das mittlere 50 % (Breite × Höhe) des Kamerabildes.
 *  2. Berechne den durchschnittlichen R-, G-, B-Wert (Subsampling alle 4 Pixel).
 *  3. Leite XYZ → CIE 1960 uv → Kelvin via Robertson-Algorithmus ab.
 *
 * ─── Abhängigkeiten in build.gradle (Module: app) ───────────────────────────
 *   implementation "androidx.camera:camera-core:1.4.2"
 *   implementation "androidx.camera:camera-camera2:1.4.2"
 *   implementation "androidx.camera:camera-lifecycle:1.4.2"
 *   implementation "androidx.camera:camera-view:1.4.2"
 *   implementation "com.google.android.material:material:1.12.0"
 *   implementation "androidx.constraintlayout:constraintlayout:2.1.4"
 *
 * ─── AndroidManifest.xml ────────────────────────────────────────────────────
 *   <uses-permission android:name="android.permission.CAMERA" />
 *   <uses-feature android:name="android.hardware.camera" android:required="true" />
 */
class MainActivity : AppCompatActivity() {

    // ─── Views ───────────────────────────────────────────────────────────────
    private lateinit var previewView: PreviewView
    private lateinit var tvKelvin: TextView
    private lateinit var tvUnit: TextView
    private lateinit var tvColorName: TextView
    private lateinit var indicatorBar: View
    private lateinit var scaleBar: View
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var loadingIndicator: CircularProgressIndicator
    private lateinit var bottomNav: BottomNavigationView

    // ─── CameraX ─────────────────────────────────────────────────────────────
    private lateinit var cameraExecutor: ExecutorService

    private val TEMP_MIN = 2000f
    private val TEMP_MAX = 8000f
    private val TAG = "ColorTempSensor"

    private val MENU_ABOUT = 1

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
            else {
                tvKelvin.text = "---"
                tvColorName.text = "Kamera-Berechtigung verweigert"
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildLayout())
        cameraExecutor = Executors.newSingleThreadExecutor()
        checkCameraPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    private fun checkCameraPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else requestPermissionLauncher.launch(Manifest.permission.CAMERA)
    }


    private fun buildLayout(): View {
        rootLayout = ConstraintLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            id = View.generateViewId()
        }

        rootLayout.addView(buildCameraPreview())
        bottomNav = BottomNavigationView(this).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID
            }
            setBackgroundColor(Color.parseColor("#F0000000"))
            menu.add(Menu.NONE, MENU_ABOUT, Menu.NONE, "Info")
                .setIcon(android.R.drawable.ic_dialog_info)
            itemIconTintList = android.content.res.ColorStateList.valueOf(Color.WHITE)
            itemTextColor = android.content.res.ColorStateList.valueOf(Color.WHITE)

            setOnItemSelectedListener { item ->
                when (item.itemId) {
                    MENU_ABOUT -> { showAboutDialog(); true }
                    else -> false
                }
            }
        }
        rootLayout.addView(bottomNav)

        // ── Info-Panel (über der BottomNav, halbtransparent) ──────────────────
        val panelHeight = dpToPx(215)
        val panel = View(this).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT, panelHeight
            ).also { lp ->
                lp.bottomToTop = bottomNav.id   // Panel endet dort, wo BottomNav beginnt
            }
            setBackgroundColor(Color.parseColor("#E0000000"))
        }
        rootLayout.addView(panel)

        // ── Kelvin-Anzeige ────────────────────────────────────────────────────
        tvKelvin = TextView(this).apply {
            id = View.generateViewId()
            text = "----"
            textSize = 72f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD
            )
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.bottomToTop = bottomNav.id
                lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomMargin = dpToPx(140)
                lp.marginStart = dpToPx(20)
            }
        }
        rootLayout.addView(tvKelvin)

        // ── "K" Einheit ───────────────────────────────────────────────────────
        tvUnit = TextView(this).apply {
            id = View.generateViewId()
            text = "K"
            textSize = 32f
            setTextColor(Color.parseColor("#AAFFFFFF"))
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.bottomToTop = bottomNav.id
                lp.startToEnd = tvKelvin.id
                lp.bottomMargin = dpToPx(158)
                lp.marginStart = dpToPx(6)
            }
        }
        rootLayout.addView(tvUnit)

        // ── Farbname ──────────────────────────────────────────────────────────
        tvColorName = TextView(this).apply {
            id = View.generateViewId()
            text = "Initialisiere Kamera …"
            textSize = 16f
            setTextColor(Color.parseColor("#CCFFFFFF"))
            letterSpacing = 0.08f
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.WRAP_CONTENT,
                ConstraintLayout.LayoutParams.WRAP_CONTENT
            ).also { lp ->
                lp.bottomToTop = bottomNav.id
                lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomMargin = dpToPx(116)
                lp.marginStart = dpToPx(22)
            }
        }
        rootLayout.addView(tvColorName)

        // ── Skalenbalken (Farbverlauf 2000 K – 8000 K) ────────────────────────
        scaleBar = View(this).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT, dpToPx(18)
            ).also { lp ->
                lp.bottomToTop = bottomNav.id
                lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomMargin = dpToPx(62)
                lp.marginStart = dpToPx(20)
                lp.marginEnd = dpToPx(20)
            }
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(
                    Color.parseColor("#FF9B4A"),  // ~2000 K Kerzenlicht
                    Color.parseColor("#FFBC6D"),  // ~3000 K Glühlampe
                    Color.parseColor("#FFD9A8"),  // ~4000 K Warmweiß
                    Color.parseColor("#FFF4E8"),  // ~5000 K Neutralweiß
                    Color.parseColor("#FFFFFF"),  // ~5500 K Tageslicht
                    Color.parseColor("#D6E9FF"),  // ~6500 K Kaltlicht
                    Color.parseColor("#A3CBFF"),  // ~7500 K Bedeckter Himmel
                    Color.parseColor("#7BB5FF"),  // ~8000 K Blauer Himmel
                )
            ).also { it.cornerRadius = dpToPx(9).toFloat() }
        }
        rootLayout.addView(scaleBar)

        // ── Beschriftungen Skala ──────────────────────────────────────────────
        listOf(
            Triple("2000 K", ConstraintLayout.LayoutParams.PARENT_ID, true),
            Triple("8000 K", ConstraintLayout.LayoutParams.PARENT_ID, false)
        ).forEach { (label, _, isStart) ->
            val tv = TextView(this).apply {
                id = View.generateViewId()
                text = label
                textSize = 10f
                setTextColor(Color.parseColor("#88FFFFFF"))
                layoutParams = ConstraintLayout.LayoutParams(
                    ConstraintLayout.LayoutParams.WRAP_CONTENT,
                    ConstraintLayout.LayoutParams.WRAP_CONTENT
                ).also { lp ->
                    lp.bottomToTop = bottomNav.id
                    lp.bottomMargin = dpToPx(42)
                    if (isStart) {
                        lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                        lp.marginStart = dpToPx(20)
                    } else {
                        lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                        lp.marginEnd = dpToPx(20)
                    }
                }
            }
            rootLayout.addView(tv)
        }

        // ── Indikator-Nadel ───────────────────────────────────────────────────
        indicatorBar = View(this).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(dpToPx(3), dpToPx(26)).also { lp ->
                lp.bottomToTop = bottomNav.id
                lp.startToStart = ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomMargin = dpToPx(56)
                lp.marginStart = dpToPx(20)
            }
            setBackgroundColor(Color.WHITE)
        }
        rootLayout.addView(indicatorBar)

        // ── Lade-Spinner ──────────────────────────────────────────────────────
        loadingIndicator = CircularProgressIndicator(this).apply {
            id = View.generateViewId()
            isIndeterminate = true
            setIndicatorColor(Color.WHITE)
            layoutParams = ConstraintLayout.LayoutParams(dpToPx(40), dpToPx(40)).also { lp ->
                lp.bottomToTop = bottomNav.id
                lp.endToEnd = ConstraintLayout.LayoutParams.PARENT_ID
                lp.bottomMargin = dpToPx(148)
                lp.marginEnd = dpToPx(22)
            }
        }
        rootLayout.addView(loadingIndicator)

        return rootLayout
    }

    private fun buildCameraPreview() : PreviewView {
        previewView = PreviewView(this).apply {
            id = View.generateViewId()
            layoutParams = ConstraintLayout.LayoutParams(
                ConstraintLayout.LayoutParams.MATCH_PARENT,
                ConstraintLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        return previewView
    }

    private fun showAboutDialog() {
        AlertDialog.Builder(this)
            .setTitle("Über diese App")
            .setMessage(
                "ColorTemp Sensor\n\n" +
                        "Misst die Farbtemperatur des Lichts anhand des mittleren 50 % " +
                        "des Kamerabildes.\n\n" +
                        "Algorithmus:\n" +
                        "  sRGB → CIE XYZ → CIE 1960 uv\n" +
                        "  → Robertson CCT (Kelvin)\n\n" +
                        "Skala: 2 000 K (Kerzenlicht) bis 8 000 K (blauer Himmel)"
            )
            .setPositiveButton("OK", null)
            .show()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(cameraExecutor, ColorTempAnalyzer { kelvin ->
                        runOnUiThread { updateUI(kelvin) }
                    })
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis
                )
            } catch (e: Exception) {
                Log.e(TAG, "Kamera konnte nicht gebunden werden", e)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateUI(kelvin: Float) {
        loadingIndicator.visibility = View.GONE

        val clamped = kelvin.coerceIn(TEMP_MIN, TEMP_MAX)

        tvKelvin.text = clamped.toInt().toString()
        tvKelvin.setTextColor(kelvinToDisplayColor(clamped))
        tvColorName.text = kelvinToName(clamped)

        positionIndicator(clamped)
    }

    /** Positioniert die weiße Nadel auf dem Skalenbalken. */
    private fun positionIndicator(kelvin: Float) {
        scaleBar.post {
            val ratio = (kelvin - TEMP_MIN) / (TEMP_MAX - TEMP_MIN)
            val offsetPx = (ratio * scaleBar.width).toInt()
            val leftMargin = (scaleBar.left + offsetPx - dpToPx(1))
                .coerceIn(scaleBar.left, scaleBar.right - dpToPx(3))

            val lp = indicatorBar.layoutParams as ConstraintLayout.LayoutParams
            lp.marginStart = leftMargin
            indicatorBar.layoutParams = lp
        }
    }

    /** Beschreibender Name für eine Kelvin-Temperatur. */
    private fun kelvinToName(k: Float): String = when {
        k < 2500 -> "Kerzenlicht / Glut"
        k < 3000 -> "Glühlampe (warm)"
        k < 3500 -> "Warmweiß"
        k < 4000 -> "Neutralweiß"
        k < 5000 -> "Frühmorgen / Sonnenaufgang"
        k < 5500 -> "Tageslicht (sonnig)"
        k < 6500 -> "Direktes Sonnenlicht"
        k < 7000 -> "Leicht bewölkt"
        k < 7500 -> "Bedeckter Himmel"
        else     -> "Blauer Himmel / Tiefschatten"
    }

    /**
     * Tanner-Helland-Approximation: Kelvin → sRGB-Anzeigefarbe.
     * Quelle: https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html
     */
    private fun kelvinToDisplayColor(k: Float): Int {
        val temp = k / 100.0
        val r = when {
            temp <= 66 -> 255
            else -> (329.698727446 * (temp - 60).pow(-0.1332047592)).toInt().coerceIn(0, 255)
        }
        val g = when {
            temp <= 66 -> (99.4708025861 * ln(temp) - 161.1195681661).toInt().coerceIn(0, 255)
            else -> (288.1221695283 * (temp - 60).pow(-0.0755148492)).toInt().coerceIn(0, 255)
        }
        val b = when {
            temp >= 66 -> 255
            temp <= 19 -> 0
            else -> (138.5177312231 * ln(temp - 10) - 305.0447927307).toInt().coerceIn(0, 255)
        }
        return Color.rgb(r, g, b)
    }

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density + 0.5f).toInt()

    /**
     * Analysiert jeden Frame und berechnet die Farbtemperatur des zentralen
     * 50 % (25 %–75 % beider Achsen) des Kamerabildes.
     *
     * Pipeline:
     *   RGBA-Pixel  →  mittlere RGB  →  XYZ (sRGB-Matrix)
     *              →  CIE 1960 uv   →  Robertson CCT (Kelvin)
     */
    private class ColorTempAnalyzer(
        private val onResult: (Float) -> Unit
    ) : ImageAnalysis.Analyzer {

        private var frameSkip = 0

        @androidx.camera.core.ExperimentalGetImage
        override fun analyze(image: ImageProxy) {
            // Nur jeden 6. Frame auswerten → ~5 fps
            if (++frameSkip % 6 != 0) { image.close(); return }

            try {
                val bitmap = image.toBitmap()
                onResult(computeCCT(bitmap))
            } catch (e: Exception) {
                Log.e("ColorTempAnalyzer", "Analysefehler", e)
            } finally {
                image.close()
            }
        }

        /** Mittlere Farbtemperatur des zentralen 50 %-Bereichs. */
        private fun computeCCT(bitmap: Bitmap): Float {
            val w = bitmap.width
            val h = bitmap.height

            // ROI: mittlere 50 % beider Achsen
            val x0 = w / 4;  val x1 = w * 3 / 4
            val y0 = h / 4;  val y1 = h * 3 / 4
            val roiW = x1 - x0
            val roiH = y1 - y0

            val pixels = IntArray(roiW * roiH)
            bitmap.getPixels(pixels, 0, roiW, x0, y0, roiW, roiH)

            // Subsampling: jedes 4. Pixel für bessere Performance
            var rSum = 0L; var gSum = 0L; var bSum = 0L; var n = 0L
            for (i in pixels.indices step 4) {
                rSum += Color.red(pixels[i])
                gSum += Color.green(pixels[i])
                bSum += Color.blue(pixels[i])
                n++
            }
            if (n == 0L) return 5500f

            return rgbToCCT(rSum / n.toFloat(), gSum / n.toFloat(), bSum / n.toFloat())
        }

        /**
         * RGB (0–255, linearisiert) → Kelvin via Robertson-Algorithmus.
         *
         * Schritt 1: sRGB → CIE XYZ  (D65, IEC 61966-2-1 Matrix)
         * Schritt 2: XYZ → CIE 1960 u, v
         * Schritt 3: Robertson CCT-Interpolation auf dem Plankian Locus
         */
        private fun rgbToCCT(r: Float, g: Float, b: Float): Float {
            // Normierung auf [0..1]
            val rn = r / 255f; val gn = g / 255f; val bn = b / 255f

            // sRGB → linearisiert (Gamma-Näherung; für Echtzeit-Schätzung ausreichend)
            fun lin(c: Float): Float = if (c <= 0.04045f) c / 12.92f
            else ((c + 0.055f) / 1.055f).pow(2.4f)
            val rl = lin(rn); val gl = lin(gn); val bl = lin(bn)

            // Lineare sRGB → CIE XYZ (D65)
            val X = rl * 0.4124564f + gl * 0.3575761f + bl * 0.1804375f
            val Y = rl * 0.2126729f + gl * 0.7151522f + bl * 0.0721750f
            val Z = rl * 0.0193339f + gl * 0.1191920f + bl * 0.9503041f

            val denom = X + 15f * Y + 3f * Z
            if (denom == 0f) return 5500f

            // CIE 1960 UCS
            val u = 4f * X / denom
            val v = 6f * Y / denom

            return robertsonCCT(u.toDouble(), v.toDouble())
        }

        /**
         * Robertson CCT-Algorithmus.
         * Tabelle: Wyszecki & Stiles, "Color Science", 2nd ed., Table 1(3.11)
         * Gibt Kelvin im Bereich [2000, 8000] zurück.
         */
        private fun robertsonCCT(u: Double, v: Double): Float {
            // (Reziprokwert mired, u, v, Steigung t)
            val table = arrayOf(
                doubleArrayOf(0.0,   0.18006, 0.26352, -0.24341),
                doubleArrayOf(10.0,  0.18066, 0.26589, -0.25479),
                doubleArrayOf(20.0,  0.18133, 0.26846, -0.26876),
                doubleArrayOf(30.0,  0.18208, 0.27119, -0.28539),
                doubleArrayOf(40.0,  0.18293, 0.27407, -0.30470),
                doubleArrayOf(50.0,  0.18388, 0.27709, -0.32675),
                doubleArrayOf(60.0,  0.18494, 0.28021, -0.35156),
                doubleArrayOf(70.0,  0.18611, 0.28342, -0.37915),
                doubleArrayOf(80.0,  0.18740, 0.28668, -0.40955),
                doubleArrayOf(90.0,  0.18880, 0.28997, -0.44278),
                doubleArrayOf(100.0, 0.19032, 0.29326, -0.47888),
                doubleArrayOf(125.0, 0.19462, 0.30141, -0.58204),
                doubleArrayOf(150.0, 0.19962, 0.30921, -0.70471),
                doubleArrayOf(175.0, 0.20525, 0.31647, -0.84901),
                doubleArrayOf(200.0, 0.21142, 0.32312, -1.0182),
                doubleArrayOf(225.0, 0.21807, 0.32909, -1.2168),
                doubleArrayOf(250.0, 0.22511, 0.33439, -1.4512),
                doubleArrayOf(275.0, 0.23247, 0.33904, -1.7298),
                doubleArrayOf(300.0, 0.24010, 0.34308, -2.0637),
                doubleArrayOf(325.0, 0.24792, 0.34655, -2.4681),
                doubleArrayOf(350.0, 0.25591, 0.34951, -2.9641),
                doubleArrayOf(375.0, 0.26400, 0.35200, -3.5814),
                doubleArrayOf(400.0, 0.27218, 0.35407, -4.3633),
                doubleArrayOf(425.0, 0.28039, 0.35577, -5.3762),
                doubleArrayOf(450.0, 0.28863, 0.35714, -6.7262),
                doubleArrayOf(475.0, 0.29685, 0.35823, -8.5955),
                doubleArrayOf(500.0, 0.30505, 0.35907, -11.324),
                doubleArrayOf(525.0, 0.31320, 0.35968, -15.628),
                doubleArrayOf(550.0, 0.32129, 0.36011, -23.325),
                doubleArrayOf(575.0, 0.32931, 0.36038, -40.770),
                doubleArrayOf(600.0, 0.33724, 0.36051, -116.45)
            )

            var last = 0.0
            for (i in 0 until table.size - 1) {
                val (r, tu, tv, t) = table[i].let {
                    arrayOf(it[0], it[1], it[2], it[3])
                }
                val di = (v - tv) - t * (u - tu)
                if (i > 0 && last * di < 0) {
                    val f = last / (last - di)
                    val reci = table[i - 1][0] + f * (r - table[i - 1][0])
                    return if (reci == 0.0) 2000f
                    else (1_000_000.0 / reci).toFloat().coerceIn(2000f, 8000f)
                }
                last = di
            }
            return 5500f  // Fallback: Tageslicht
        }
    }
}