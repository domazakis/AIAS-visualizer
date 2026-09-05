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
 * Ο βρόχος σχεδίασης στην επιφάνεια που παραχωρεί ο host.
 *
 * Το βήμα 1 —«παίρνουμε επιφάνεια;»— απαντήθηκε καταφατικά στο πραγματικό
 * αυτοκίνητο: 1785×690 στα 240 dpi, 46 καρέ. Εδώ πλέον ζωγραφίζονται οι
 * τελείες του βήματος 2, μεταφερμένες από το `bars.html`.
 *
 * Τα διαγνωστικά μένουν: στο ενσύρματο Android Auto το καλώδιο πιάνει τη θύρα
 * του κινητού, οπότε δεν υπάρχει adb τη στιγμή της δοκιμής.
 */
class SurfaceRenderer(private val carContext: CarContext) : DefaultLifecycleObserver {

    companion object {
        private const val TAG = "AiasSurface"

        /**
         * Οι αριθμοί πάνω στην οθόνη του αυτοκινήτου. Κλειστοί: η εικόνα είναι
         * το ζητούμενο, και τα ίδια νούμερα γράφονται ούτως ή άλλως στα
         * διαγνωστικά που διαβάζονται από το κινητό. Άνοιξέ το αν χρειαστεί να
         * δεις κάτι επιτόπου.
         */
        private const val SHOW_HUD = false
    }

    private var container: SurfaceContainer? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    /**
     * Η περιοχή που δεν σκεπάζεται από τα δικά του στοιχεία το head unit.
     * Η **σταθερή** είναι η εγγυημένη· η ορατή μεγαλώνει όταν κρύβεται η μπάρα
     * του Android Auto (στο MG από 540 σε 636 σε ύψος) και η σύνθεση θα
     * πηδούσε αν κεντραριζόταν εκεί.
     */
    @Volatile private var visible: Rect? = null
    @Volatile private var stable: Rect? = null

    private val bars = Bars()
    private val demo = Demo()

    /** Διαβάζεται από την οθόνη διαγνωστικών στο κινητό. */
    @Volatile
    var status: String = "δεν έχει δοθεί επιφάνεια ακόμη"
        private set

    private val hud = Paint().apply {
        isAntiAlias = true
        color = Color.rgb(255, 205, 140)
        textSize = 38f
    }

    /**
     * Κάθε τι που μαθαίνουμε γράφεται και μόνιμα, γιατί στο αυτοκίνητο δεν
     * υπάρχει adb για logcat — το καλώδιο πιάνει τη θύρα του κινητού.
     */
    private fun note(key: String, value: String) {
        try { Diag.put(carContext, key, value) } catch (e: Throwable) {
            Log.w(TAG, "αποτυχία εγγραφής διαγνωστικών", e)
        }
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
        bars.release()
    }

    /**
     * Η περιοχή μέσα στην οποία κεντράρεται η σύνθεση.
     *
     * Οι εκφυλισμένες απορρίπτονται: στο MG, στη μετάβαση προς τη χωρισμένη
     * οθόνη, ήρθε στιγμιαία περιοχή μηδενικού μεγέθους. Η διάμετρος βγήκε
     * μηδέν, και μαζί της η ακτίνα της λάμψης — «ending radius must be > 0».
     */
    private fun box(w: Int, h: Int): Rect {
        val s = stable
        if (s != null && s.width() > 0 && s.height() > 0) return s
        val v = visible
        if (v != null && v.width() > 0 && v.height() > 0) return v
        return Rect(0, 0, w, h)
    }

    private fun start() {
        if (running) return
        running = true
        thread = Thread {
            var frames = 0
            var total = 0L
            var mark = System.nanoTime()
            var prev = mark
            var fps = 0.0
            var reported = false
            var lockFails = 0
            while (running) {
                val c = container
                val surface = c?.surface
                val now = System.nanoTime()
                // Πραγματικό dt: οι φάσεις ολοκληρώνονται στον χρόνο, οπότε ένα
                // σταθερό 16 ms θα παραμόρφωνε την κίνηση σε κάθε πτώση καρέ.
                var dt = (now - prev) / 1e9f
                prev = now
                if (dt > 0.05f) dt = 0.05f
                if (dt <= 0f) dt = 0.016f

                if (surface != null && surface.isValid) {
                    var canvas: Canvas? = null
                    try {
                        // Το lockCanvas αποτυγχάνει αν την επιφάνεια την κρατάει
                        // ακόμη άλλος παραγωγός — συμβαίνει όταν η εφαρμογή
                        // αντικατασταθεί ενώ τρέχει και η παλιά διεργασία δεν
                        // έχει αποσυνδεθεί. Δεν είναι δικό μας σφάλμα σχεδίασης
                        // και δεν έχει νόημα να επιμένουμε ενενήντα φορές το
                        // δευτερόλεπτο.
                        canvas = surface.lockCanvas(null)
                        lockFails = 0
                        demo.step(dt, bars)
                        bars.frame(canvas, dt, c.width, c.height, box(c.width, c.height))
                        if (SHOW_HUD) hud(canvas, c.width, c.height, fps)
                        total++
                    } catch (e: Throwable) {
                        if (canvas == null) {
                            lockFails++
                            if (lockFails == 1) {
                                Log.w(TAG, "η επιφάνεια δεν κλειδώνει", e)
                                note("σφάλμα", "η επιφάνεια δεν κλειδώνει: $e")
                            }
                        } else {
                            Log.w(TAG, "αποτυχία σχεδίασης", e)
                            // Μόνο το πρώτο σφάλμα· αλλιώς γράφουμε στον δίσκο
                            // εξήντα φορές το δευτερόλεπτο.
                            if (!reported) {
                                reported = true
                                note("σφάλμα", "στη σχεδίαση: $e")
                            }
                        }
                    } finally {
                        if (canvas != null) {
                            try { surface.unlockCanvasAndPost(canvas) } catch (e: Throwable) { }
                        }
                    }
                }
                frames++
                if (now - mark > 1_000_000_000L) {
                    fps = frames * 1e9 / (now - mark)
                    frames = 0
                    mark = now
                    // Το σύνολο των καρέ είναι η απόδειξη ότι πράγματι
                    // ζωγραφίστηκε κάτι, όχι μόνο ότι δόθηκε επιφάνεια.
                    note("καρέ", "${"%.0f".format(fps)} fps, σύνολο $total")
                    note("σκηνή", demo.label())
                }
                // Οπισθοχώρηση όταν η επιφάνεια δεν κλειδώνει: δεν κερδίζουμε
                // τίποτα επιμένοντας, και καίμε επεξεργαστή στο αυτοκίνητο.
                val nap = if (lockFails > 3) 250L else 8L
                try { Thread.sleep(nap) } catch (e: InterruptedException) { break }
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

    private fun hud(canvas: Canvas, w: Int, h: Int, fps: Double) {
        val b = box(w, h)
        canvas.drawText("${w} × ${h}", b.left + 24f, b.top + 44f, hud)
        canvas.drawText("${"%.0f".format(fps)} fps · ${demo.label()}", b.left + 24f, b.top + 90f, hud)
    }
}
