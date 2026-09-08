package gr.aias.carviz

import android.graphics.Canvas
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Οι τελείες στην οθόνη του **κινητού**.
 *
 * Έλειπε, και το λάθος φάνηκε μόλις δούλεψε η φωνή: η υπηρεσία μιλούσε
 * κανονικά αλλά δεν υπήρχε πουθενά να δεις τις τελείες να αντιδρούν, γιατί
 * ζωγραφίζονταν μόνο στην επιφάνεια που παραχωρεί το αυτοκίνητο.
 *
 * Τρία που κερδίζουμε:
 *
 * 1. Δοκιμάζεται ολόκληρη η αλυσίδα φωνή → στάθμη → σχεδίαση **χωρίς
 *    αυτοκίνητο και χωρίς εξομοιωτή**.
 * 2. Γίνεται εφεδρεία: κινητό σε βάση, ίδια εικόνα, όταν το Android Auto
 *    δυστροπεί — ακριβώς το «χάνεις μόνο την οθόνη του αυτοκινήτου» που
 *    προέβλεπε το PLAN.
 * 3. Ίδιος κώδικας [Bars], άρα ό,τι διορθώνεται εδώ ισχύει και εκεί.
 *
 * Οριζόντια, γιατί η διάταξη σχεδιάστηκε για φαρδιά οθόνη: σε κατακόρυφη ο
 * περιορισμός πλάτους θα σμίκρυνε τις τελείες σε κουκκίδες.
 */
class VizActivity : AppCompatActivity(), SurfaceHolder.Callback {

    private companion object { const val TAG = "AiasViz" }

    private val bars = Bars()
    private val demo = Demo()
    private var thread: Thread? = null
    @Volatile private var running = false
    @Volatile private var holder: SurfaceHolder? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Ζωγραφίζουμε ΠΑΝΩ από την εγκοπή της κάμερας.
        //
        // Χωρίς αυτό, το παράθυρο ξεκινούσε στο x=77 αντί για 0 — το περιθώριο
        // της τρύπας, που σε οριζόντια θέση πέφτει στο πλάι. Μετρημένο με
        // dumpsys: mBounds ήταν [0,0..1600,720] αλλά το frame [77,0..1600,720].
        // Οι τελείες κεντράρονταν σωστά μέσα στην επιφάνεια, στο 838,5 — και
        // ακριβώς 38 εικονοστοιχεία δεξιά από το κέντρο της οθόνης, όσο μισό
        // το περιθώριο. Το μάτι το έβλεπε αμέσως· η αριθμητική επιβεβαίωσε ότι
        // δεν έφταιγε ο renderer αλλά το παράθυρο.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                else
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        val sv = SurfaceView(this)
        sv.holder.addCallback(this)
        setContentView(sv)
        hideBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Οι μπάρες επιστρέφουν μετά από κάθε αλληλεπίδραση· τις ξανακρύβουμε.
        if (hasFocus) hideBars()
    }

    /**
     * Πλήρης οθόνη, με το σύγχρονο API.
     *
     * Η πρώτη εκδοχή χρησιμοποιούσε τις σημαίες `systemUiVisibility`, που είναι
     * παρωχημένες από το API 30 και **αγνοούνται** στο Android 15. Αποτέλεσμα:
     * η επιφάνεια σχεδίασης έμενε μέσα στα περιθώρια των μπαρών — γκρίζα
     * λωρίδα επάνω και δεξιά — και οι τελείες, ενώ ήταν κεντραρισμένες μέσα
     * στην επιφάνεια, φαίνονταν μετατοπισμένες μέσα στην οθόνη.
     */
    private fun hideBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun surfaceCreated(h: SurfaceHolder) {
        holder = h
        start()
    }

    override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {
        holder = h
    }

    override fun surfaceDestroyed(h: SurfaceHolder) {
        stop()
        holder = null
    }

    override fun onDestroy() {
        stop()
        bars.release()
        super.onDestroy()
    }

    private fun start() {
        if (running) return
        running = true
        thread = Thread({
            var prev = System.nanoTime()
            while (running) {
                val h = holder
                val now = System.nanoTime()
                var dt = (now - prev) / 1e9f
                prev = now
                if (dt > 0.05f) dt = 0.05f
                if (dt <= 0f) dt = 0.016f

                if (h != null && h.surface.isValid) {
                    var c: Canvas? = null
                    try {
                        c = h.lockCanvas()
                        if (c != null) {
                            if (Voice.active) {
                                bars.level = Voice.level
                                bars.mode = Voice.mode
                            } else {
                                demo.step(dt, bars)
                            }
                            val w = c.width
                            val ht = c.height
                            bars.frame(c, dt, w, ht, Rect(0, 0, w, ht))
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "αποτυχία σχεδίασης", e)
                    } finally {
                        if (c != null) {
                            try { h.unlockCanvasAndPost(c) } catch (e: Throwable) { }
                        }
                    }
                }
                try { Thread.sleep(8) } catch (e: InterruptedException) { break }
            }
        }, "aias-viz").also { it.start() }
    }

    private fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }
}
