package gr.aias.carviz

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Η δοκιμή του βήματος 1: ζωγραφίζει μια γραμμή που σαρώνει κατακόρυφα και
 * τυπώνει τις διαστάσεις της επιφάνειας, την ορατή περιοχή και τα καρέ ανά
 * δευτερόλεπτο.
 *
 * Δεν έχει καμία σχέση με τη σφαίρα. Απαντά σε μία μόνο ερώτηση: παίρνει η
 * εφαρμογή μας επιφάνεια στην οθόνη του αυτοκινήτου και τη ζωγραφίζει;
 *
 * Οι τρεις αριθμοί που θα δείξει είναι ακριβώς όσοι χρειάζονται για να
 * ρυθμιστεί ο πραγματικός renderer στο βήμα 2.
 */
class SurfaceRenderer(private val carContext: CarContext) : DefaultLifecycleObserver {

    companion object { private const val TAG = "AiasSurface" }

    private var container: SurfaceContainer? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /**
     * Η περιοχή που δεν σκεπάζεται από τα δικά του στοιχεία το head unit.
     * Η επιφάνεια που μας δίνεται μπορεί να είναι μεγαλύτερη από ό,τι
     * φαίνεται πραγματικά, οπότε η σφαίρα πρέπει να κεντραριστεί εδώ μέσα
     * και όχι στο σύνολο. Γι' αυτό τη ζωγραφίζουμε ήδη από τη δοκιμή.
     */
    @Volatile private var visible: Rect? = null
    @Volatile private var stable: Rect? = null

    /** Διαβάζεται από την οθόνη διαγνωστικών στο κινητό. */
    @Volatile
    var status: String = "δεν έχει δοθεί επιφάνεια ακόμη"
        private set

    /**
     * Κάθε τι που μαθαίνουμε γράφεται και μόνιμα, γιατί στο αυτοκίνητο δεν
     * υπάρχει adb για logcat — το καλώδιο πιάνει τη θύρα του κινητού.
     */
    private fun note(key: String, value: String) {
        try { Diag.put(carContext, key, value) } catch (e: Throwable) {
            Log.w(TAG, "αποτυχία εγγραφής διαγνωστικών", e)
        }
    }

    private val bg = Paint().apply { color = Color.BLACK }
    private val stroke = Paint().apply {
        isAntiAlias = true
        color = Color.rgb(255, 170, 60)
        strokeWidth = 5f
    }
    private val frame = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        color = Color.rgb(120, 80, 30)
        strokeWidth = 2f
    }
    private val label = Paint().apply {
        isAntiAlias = true
        color = Color.rgb(255, 205, 140)
        textSize = 38f
    }

    /**
     * Υλοποιούνται και οι τέσσερις αρχικές μέθοδοι της διεπαφής, όχι μόνο οι
     * δύο που χρειάζονται. Οι μεταγενέστερες —κύλιση, χειρονομίες, άγγιγμα—
     * έχουν κατ' ανάγκη προεπιλεγμένες υλοποιήσεις, αλλιώς η προσθήκη τους θα
     * είχε σπάσει κάθε υπάρχουσα εφαρμογή, οπότε παραλείπονται με ασφάλεια.
     */
    private val callback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            container = surfaceContainer
            status = "επιφάνεια ${surfaceContainer.width}×${surfaceContainer.height}, " +
                     "${surfaceContainer.dpi} dpi"
            Log.i(TAG, "onSurfaceAvailable: $status")
            // Αυτή η μία γραμμή είναι η απάντηση στο ερώτημα του βήματος 1:
            // ο host μας παραχώρησε επιφάνεια.
            note("κατάσταση", "ΝΑΙ — ο host έδωσε επιφάνεια")
            note("επιφάνεια", "${surfaceContainer.width} × ${surfaceContainer.height}, " +
                              "${surfaceContainer.dpi} dpi")
            start()
        }

        override fun onVisibleAreaChanged(area: Rect) {
            visible = Rect(area)
            Log.i(TAG, "onVisibleAreaChanged: $area")
            note("ορατή", "${area.width()} × ${area.height()} @ ${area.left},${area.top}")
        }

        override fun onStableAreaChanged(area: Rect) {
            stable = Rect(area)
            Log.i(TAG, "onStableAreaChanged: $area")
            note("σταθερή", "${area.width()} × ${area.height()} @ ${area.left},${area.top}")
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            Log.i(TAG, "onSurfaceDestroyed")
            stop()
            container = null
            status = "η επιφάνεια αποσύρθηκε"
            note("κατάσταση", "η επιφάνεια αποσύρθηκε (φυσιολογικό στην αποσύνδεση)")
        }
    }

    override fun onCreate(owner: LifecycleOwner) {
        Log.i(TAG, "εγγραφή SurfaceCallback")
        // Καθαρίζουμε ό,τι έμεινε από προηγούμενη σύνδεση, αλλιώς δεν
        // ξεχωρίζει η χθεσινή δοκιμή από τη σημερινή.
        Diag.clear(carContext)
        note("κατάσταση", "η υπηρεσία ξεκίνησε, αναμονή επιφάνειας")
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(callback)
        status = "ο SurfaceCallback καταχωρήθηκε, αναμονή επιφάνειας"
    }

    override fun onDestroy(owner: LifecycleOwner) {
        stop()
    }

    private fun start() {
        if (running) return
        running = true
        thread = Thread {
            var frames = 0
            var total = 0L
            var mark = System.nanoTime()
            var fps = 0.0
            var phase = 0f
            var reported = false
            while (running) {
                val c = container
                val surface = c?.surface
                if (surface != null && surface.isValid) {
                    var canvas: Canvas? = null
                    try {
                        canvas = surface.lockCanvas(null)
                        phase += 0.010f
                        if (phase > 1f) phase -= 1f
                        draw(canvas, c.width, c.height, phase, fps)
                        total++
                    } catch (e: Throwable) {
                        Log.w(TAG, "αποτυχία σχεδίασης", e)
                        // Μόνο το πρώτο σφάλμα· αλλιώς γράφουμε στον δίσκο
                        // εξήντα φορές το δευτερόλεπτο.
                        if (!reported) {
                            reported = true
                            note("σφάλμα", "στη σχεδίαση: $e")
                        }
                    } finally {
                        if (canvas != null) {
                            try { surface.unlockCanvasAndPost(canvas) } catch (e: Throwable) { }
                        }
                    }
                }
                frames++
                val now = System.nanoTime()
                if (now - mark > 1_000_000_000L) {
                    fps = frames * 1e9 / (now - mark)
                    frames = 0
                    mark = now
                    // Το σύνολο των καρέ είναι η απόδειξη ότι πράγματι
                    // ζωγραφίστηκε κάτι, όχι μόνο ότι δόθηκε επιφάνεια.
                    note("καρέ", "${"%.0f".format(fps)} fps, σύνολο $total")
                }
                try { Thread.sleep(16) } catch (e: InterruptedException) { break }
            }
        }.also {
            it.name = "aias-render"
            it.setUncaughtExceptionHandler { _, e ->
                Log.e(TAG, "το νήμα σχεδίασης τερματίστηκε από εξαίρεση", e)
                note("σφάλμα", "το νήμα σχεδίασης πέθανε: $e")
            }
            it.start()
        }
    }

    private fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    private fun draw(canvas: Canvas, w: Int, h: Int, phase: Float, fps: Double) {
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

        // Η σαρωτική γραμμή: αν κινείται, η επιφάνεια ζει.
        val y = phase * h
        canvas.drawLine(0f, y, w.toFloat(), y, stroke)

        // Το περίγραμμα της ορατής περιοχής, για να φανεί πόσο από την
        // επιφάνεια σκεπάζει το head unit με τα δικά του στοιχεία.
        visible?.let { canvas.drawRect(it, frame) }

        val vis = visible?.let { "${it.width()}×${it.height()} @ ${it.left},${it.top}" } ?: "άγνωστη"
        canvas.drawText("ΑΙΑΣ — δοκιμή επιφάνειας", 44f, 70f, label)
        canvas.drawText("επιφάνεια  ${w} × ${h}", 44f, 120f, label)
        canvas.drawText("ορατή      $vis", 44f, 168f, label)
        canvas.drawText("καρέ       ${"%.0f".format(fps)} fps", 44f, 216f, label)
    }
}
