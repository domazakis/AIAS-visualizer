package gr.aias.carviz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Οι τελείες, μεταφερμένες από το `bars.html` του κλαδιού `claude/bars-lab`.
 *
 * Κάθε τελεία είναι **μία γραμμή με στρογγυλό τελείωμα**, πάχους Δ. Όταν το
 * μήκος της είναι μηδέν, το τελείωμα και μόνο ζωγραφίζει τέλειο κύκλο
 * διαμέτρου Δ — ταυτότητα, όχι προσέγγιση:
 *
 *     μισό ύψος = Δ/2 + έκταση · Δ·(αναλογία−1)/2
 *
 * Πέντε τελείες, δέκα πινελιές το καρέ. Είναι από τα φθηνότερα πράγματα που
 * μπορεί να ζωγραφίσει η Skia, και ο λόγος που αυτό το σχέδιο χωράει άνετα
 * στο budget του αυτοκινήτου εκεί που η σφαίρα ήταν αμφίβολη.
 *
 * Οι τιμές των ρυθμιστικών είναι αυτές που διάλεξε ο χρήστης στο εργαστήριο
 * και παραδόθηκαν στο `LAB.md`. Δεν είναι αυθαίρετες· κάθε μία κόστισε γύρο.
 */
class Bars {

    companion object {
        private const val TAU = (PI * 2).toFloat()

        /** Οι ρυθμίσεις που παραλαμβάνονται από το εργαστήριο. */
        private const val NB = 5
        private const val THICK = 1.84f
        private const val RMAX = 9.0f
        private const val SPACING = 1.08f
        private const val RATE = 1.0f
        private const val WAMP = 0.30f
        private const val GLOW_K = 1.0f
        private const val SOFT = 0.35f

        /** Πόση φάση απλώνεται σε ΟΛΗ τη σειρά. */
        private val WSPREAD = (1.25 * PI).toFloat()

        /** Ο καμβάς λάμψης στο ένα τέταρτο, όπως και στη σφαίρα. */
        private const val GLOW_DIV = 4
    }

    // ---------------------------------------------------------------- κατάσταση

    /** 0..1, στιγμιαία ένταση φωνής. Θα έρθει από τον AnalyserNode στο βήμα 3. */
    @Volatile var level = 0f

    /** 'idle' | 'listen' | 'speak' — η ίδια διεπαφή με το `window.AIAS`. */
    @Volatile var mode = "idle"

    private var mixListen = 0f
    private var mixSpeak = 0f
    private var glowEnv = 0f
    private var burst = 0f
    private var smoothed = 0f

    /**
     * Μία και μόνη έκταση για όλες τις τελείες — **αριθμός, όχι πίνακας**.
     * Όταν ανεβαίνει, ανεβαίνουν όλες μαζί, η καθεμιά προς το δικό της ταβάνι.
     * Είναι ένα σώμα που φουσκώνει, όχι πέντε φώτα που κινούνται μόνα τους.
     */
    private var ext = 0f

    /**
     * Οι φάσεις **ολοκληρώνονται στον χρόνο**: `φάση += dt·ρυθμός`. Ποτέ
     * `χρόνος·ρυθμός` — τότε κάθε αλλαγή ρυθμού μετατοπίζει ακαριαία ολόκληρο
     * το κύμα. Είναι το ίδιο σφάλμα που πιάστηκε δύο φορές, στις κορδέλες και
     * στις τελείες.
     */
    private var wph = 0f
    private var ph1 = 0f
    private var ph2 = 0f

    private val bnc = FloatArray(NB)

    // ---------------------------------------------------------------- διάταξη

    private var lD = 8f
    private var lPitch = 8f
    private var lX0 = 0f
    private var lCy = 0f
    private var lRmax = RMAX

    // ---------------------------------------------------------------- καμβάδες

    private var cw = 0
    private var ch = 0
    private var bk = 0.25f
    private val bl = arrayOfNulls<Bitmap>(4)
    private val bc = arrayOfNulls<Canvas>(4)

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val addStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val addBitmap = Paint().apply {
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }
    private val srcBitmap = Paint().apply {
        isFilterBitmap = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
    }
    /** Χωρίς xfermode: η γρήγορη διαδρομή της Skia. Δες το στάδιο 4. */
    private val overBitmap = Paint().apply { isFilterBitmap = true }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.ADD)
    }

    private fun ensureCanvases(w: Int, h: Int) {
        if (w == cw && h == ch && bl[0] != null) return
        cw = w; ch = h
        var d = GLOW_DIV
        for (i in 0..2) {
            val bw = max(1, w / d)
            val bh = max(1, h / d)
            bl[i] = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
            bc[i] = Canvas(bl[i]!!)
            d *= 2
        }
        bl[3] = Bitmap.createBitmap(bl[0]!!.width, bl[0]!!.height, Bitmap.Config.ARGB_8888)
        bc[3] = Canvas(bl[3]!!)
        bk = bl[0]!!.width.toFloat() / w
    }

    // ---------------------------------------------------------------- βήματα

    private fun wrap(a: Float): Float {
        var x = a % TAU
        if (x < 0) x += TAU
        return x
    }

    /**
     * Το βάρος κάθε θέσης. Με ταβάνι 3.4 διαμέτρους δίνει 3.40 στη μέση,
     * 2.50 στις πλαϊνές και 1.60 στις ακριανές. Στα μισά πέφτει ακριβώς στο
     * 0.625, γιατί συν(π/4)² = 1/2.
     */
    private fun barW(i: Int, n: Int): Float {
        if (n < 2) return 1f
        val u = (i - (n - 1) / 2f) / ((n - 1) / 2f)
        val c = cos(u * PI.toFloat() * 0.5f)
        return 0.25f + 0.75f * c * c
    }

    private fun barRatio(i: Int): Float = 1f + (lRmax - 1f) * barW(i, NB)

    /** Η ομαλοποίηση της έντασης και των καταστάσεων — ίδια με τον βρόχο του HTML. */
    private fun stepState(dt: Float) {
        val target = level.coerceIn(0f, 1f)
        smoothed += (target - smoothed) * (if (target > smoothed) 0.17f else 0.048f)
        val d = target - smoothed
        if (d > 0.24f) burst = min(1f, burst + d * 0.70f)
        burst *= Math.pow(0.45, dt.toDouble()).toFloat()
        mixListen += ((if (mode == "listen") 1f else 0f) - mixListen) * min(1f, dt * 1.5f)
        mixSpeak += ((if (mode == "speak") 1f else 0f) - mixSpeak) * min(1f, dt * 1.9f)
        val gt = smoothed * 0.85f + mixSpeak * 0.22f
        glowEnv += (gt - glowEnv) * min(1f, dt * (if (gt > glowEnv) 2.0f else 0.8f))
    }

    private fun stepBars(dt: Float, lv: Float, br: Float) {
        // ΤΟ ΚΥΜΑ. Ένα ημίτονο τρέχει μέσα από όλη τη σειρά· κάθε τελεία έχει
        // δική της ΦΑΣΗ, καθυστερημένη κατά σταθερό βήμα. Κινούνται όλες
        // συνέχεια — ταξιδεύει η κορυφή, όχι το χτύπημα.
        val w0 = TAU * (0.55f * RATE) * (1f + 0.45f * mixListen)
        wph = wrap(wph + dt * w0)
        val kstep = WSPREAD / max(1, NB - 1)
        for (i in 0 until NB) bnc[i] = sin(wph - kstep * i)

        val sr = (0.85f + 2.10f * lv) * RATE
        ph1 = wrap(ph1 + dt * 1.55f * sr)
        ph2 = wrap(ph2 + dt * 2.60f * sr)
        val wob = 0.72f + 0.28f * (0.62f * sin(ph1) + 0.38f * sin(ph2))

        // Έκταση μόνο στην ομιλία. Στη σιωπή σβήνει και μένουν οι κύκλοι.
        var sp = 0.14f + lv * 1.35f * wob + 0.20f * br
        sp = sp.coerceIn(0f, 1f)
        val tg = sp * mixSpeak
        val tau = if (tg > ext) 0.035f else 0.115f
        ext += (tg - ext) * (1f - exp(-dt / tau))

        // Το κύμα σβήνει μόλις αρχίσει να μιλάει.
        for (i in 0 until NB) bnc[i] *= (1f - mixSpeak)
    }

    /**
     * Ο κανόνας του περιορισμού, αυτούσιος από το εργαστήριο: ο περιορισμός
     * **πλάτους** σμικραίνει τα πάντα, ο περιορισμός **ύψους** κόβει το ταβάνι
     * και ποτέ τη διάμετρο.
     *
     * Το [box] είναι η **σταθερή** περιοχή του head unit, όχι ολόκληρη η
     * επιφάνεια: εκεί μέσα είναι εγγυημένο ότι δεν σκεπάζει τίποτα. Επίτηδες
     * δεν χρησιμοποιείται η ορατή περιοχή — στο MG μεγαλώνει από 540 σε 636
     * όταν κρύβεται η μπάρα, και η σύνθεση θα πηδούσε.
     */
    private fun layout(box: Rect) {
        val vw = box.width().toFloat()
        val vh = box.height().toFloat()
        var dd = min(vw, vh) * 0.190f * THICK
        var pitch = dd * SPACING
        val span = pitch * (NB - 1) + dd
        if (span > vw * 0.94f) {
            val s = vw * 0.94f / span
            dd *= s; pitch *= s
        }
        lD = dd
        lPitch = pitch
        lRmax = min(RMAX, vh * 0.90f / dd)
        lX0 = box.left + vw * 0.5f - pitch * (NB - 1) * 0.5f
        lCy = box.top + vh * 0.5f
    }

    /**
     * Δύο πινελιές ανά τελεία: μια φαρδιά και αχνή για το περίβλημα του φωτός,
     * και ο πυρήνας από πάνω. Καλείται δύο φορές ανά καρέ — στον ορατό καμβά
     * και στον καμβά λάμψης.
     */
    private fun paintBars(g: Canvas, lv: Float, bright: Float) {
        val d = lD
        val bam = WAMP * d
        for (i in 0 until NB) {
            val x = lX0 + lPitch * i
            val sw = bnc[i]              // −1 ως 1: το ίδιο το ημίτονο
            val w = 0.5f * (1f + sw)     //  0 ως 1: για φως και φούσκωμα

            // Το φούσκωμα μένει κάτω από το βήμα ανάμεσα στις τελείες. Αν το
            // ξεπερνούσε, οι πυρήνες θα αλληλοκαλύπτονταν και η προσθετική
            // σύνθεση θα άναβε φωτεινή ραφή στη μέση.
            val di = d * (1f + 0.07f * w)
            val halfH = di * 0.5f + ext * d * (barRatio(i) - 1f) * 0.5f
            val yc = lCy - sw * bam
            val y0 = yc - halfH + di * 0.5f
            // Το +0.01 κρατάει τη διαδρομή μη μηδενική: κάποιες μηχανές
            // παραλείπουν εντελώς γραμμή μηδενικού μήκους και η τελεία θα
            // εξαφανιζόταν αντί να ζωγραφιστεί από το τελείωμα.
            val y1 = yc + halfH - di * 0.5f + 0.01f

            var hot = lv * 0.75f + w * 0.42f
            if (hot > 1f) hot = 1f
            val gg = (88 + 26 * hot).toInt()
            val bb = (12 + 18 * hot).toInt()

            var a = (0.026f + 0.044f * glowEnv + 0.022f * w) * bright
            if (a > 0.38f) a = 0.38f
            addStroke.color = Color.argb((a * 255).toInt(), 255, gg, bb)
            addStroke.strokeWidth = di * 1.15f
            g.drawLine(x, y0, x, y1, addStroke)

            a = (0.54f + 0.10f * hot) * bright * (1f - (1f - mixSpeak) * 0.26f * (1f - w))
            if (a > 1f) a = 1f
            addStroke.color = Color.argb((a * 255).toInt(), 255, min(255, gg + 12), min(255, bb + 10))
            addStroke.strokeWidth = di
            g.drawLine(x, y0, x, y1, addStroke)
        }
    }

    /**
     * Ένα καρέ. Το [box] είναι η σταθερή περιοχή· ο [canvas] ολόκληρη η
     * επιφάνεια που παραχώρησε ο host.
     */
    /** Χρονομέτρηση σταδίων, μόνο για τη μέτρηση. Σε νανοδευτερόλεπτα. */
    @Volatile var profile = false
    val stage = LongArray(5)     // καθάρισμα, πινελιές, λάμψη, πυραμίδα, επίστρωση
    fun resetStages() { for (i in stage.indices) stage[i] = 0 }
    private inline fun timed(i: Int, body: () -> Unit) {
        if (!profile) { body(); return }
        val t0 = System.nanoTime(); body(); stage[i] += System.nanoTime() - t0
    }

    fun frame(canvas: Canvas, dt: Float, w: Int, h: Int, box: Rect) {
        if (w <= 0 || h <= 0 || box.width() <= 0 || box.height() <= 0) return
        ensureCanvases(w, h)
        stepState(dt)
        stepBars(dt, smoothed, burst)
        layout(box)
        // Δεύτερη γραμμή άμυνας: αν για οποιονδήποτε λόγο η διάταξη βγει
        // εκφυλισμένη, δεν ζωγραφίζουμε παρά να σκάσουμε στην ακτίνα.
        if (lD <= 0f) return

        val bright = (0.55f + 0.42f * glowEnv + 0.20f * mixListen + 0.12f * burst) * GLOW_K
        val trailFade = (0.95f - 0.72f * SOFT) * (1f - 0.12f * glowEnv)

        // 1. ορατός καμβάς — αδιαφανές μαύρο. Οι τελείες μπαίνουν ΤΕΛΕΥΤΑΙΕΣ,
        //    μετά τη λάμψη· ο λόγος είναι στο στάδιο 4.
        timed(0) { canvas.drawColor(Color.BLACK, PorterDuff.Mode.SRC) }

        // 2. καμβάς λάμψης στο ένα τέταρτο, με ουρά
        val g2 = bc[0]!!
        val t2 = if (profile) System.nanoTime() else 0L
        g2.drawColor(Color.argb((trailFade * 255).toInt(), 0, 0, 0), PorterDuff.Mode.SRC_OVER)
        val save = g2.save()
        g2.scale(bk, bk)
        paintBars(g2, smoothed, bright * trailFade)

        // η λάμψη της ομάδας: φουσκώνει όσο μιλάει
        val cx = box.exactCenterX()
        val hr = max(lPitch * (NB - 1) * 0.85f, lD * RMAX * 0.6f) * (1.55f + 0.75f * glowEnv)
        halo.shader = RadialGradient(
            cx, lCy, hr,
            intArrayOf(
                Color.argb((((0.014f + 0.086f * glowEnv + 0.012f * mixListen) * GLOW_K) * 255).toInt(), 255, 164, 72),
                Color.argb((((0.006f + 0.036f * glowEnv) * GLOW_K) * 255).toInt(), 255, 150, 52),
                Color.argb((((0.002f + 0.010f * glowEnv) * GLOW_K) * 255).toInt(), 255, 128, 30),
                Color.argb(0, 255, 116, 20)
            ),
            floatArrayOf(0f, 0.38f, 0.70f, 1f),
            Shader.TileMode.CLAMP
        )
        g2.drawCircle(cx, lCy, hr, halo)
        g2.restoreToCount(save)
        if (profile) stage[2] += System.nanoTime() - t2

        // 3. η πυραμίδα: δύο ακόμη σμικρύνσεις για πλατύτερο θόλωμα.
        //    Η σύνθεση γίνεται σε ΞΕΧΩΡΙΣΤΟ καμβά — αν την ξαναγράφαμε πάνω
        //    στον συσσωρευτή, το φως θα τροφοδοτούσε τον εαυτό του και θα
        //    έφτανε στο λευκό.
        timed(3) {
            drawScaled(bc[1]!!, bl[0]!!, bl[1]!!, srcBitmap, 255)
            drawScaled(bc[2]!!, bl[1]!!, bl[2]!!, srcBitmap, 255)
            drawScaled(bc[3]!!, bl[0]!!, bl[3]!!, srcBitmap, 255)
            drawScaled(bc[3]!!, bl[1]!!, bl[3]!!, addBitmap, 230)
            drawScaled(bc[3]!!, bl[2]!!, bl[3]!!, addBitmap, 242)
        }

        // 4. επίστρωση στον ορατό καμβά — και μετά οι τελείες από πάνω.
        //
        //    Η σειρά έχει σημασία, και είναι η μοναδική σοβαρή απόκλιση από το
        //    πρωτότυπο. Εκεί η λάμψη προστίθεται ΠΑΝΩ στις τελείες. Εδώ
        //    ζωγραφίζεται ΠΡΙΝ, με απλή επικάλυψη αντί για προσθετική.
        //
        //    Το αποτέλεσμα είναι πανομοιότυπο: πάνω σε μαύρο, η επικάλυψη με
        //    άλφα α δίνει χρώμα·α, ακριβώς όσο και η πρόσθεση. Και επειδή η
        //    πρόσθεση είναι αντιμεταθετική, «λάμψη μετά τελείες» ισούται με
        //    «τελείες μετά λάμψη».
        //
        //    Το κέρδος δεν είναι μικρό. Μετρημένο στο κινητό, μεγέθυνση
        //    446×172 → 1785×690: προσθετικά 15,21 ms, απλά 7,73 ms. Δηλαδή
        //    το ένα τρίτο ολόκληρου του καρέ, δωρεάν.
        timed(4) {
            val a = min(1f, (0.42f + 0.17f * glowEnv) * GLOW_K)
            overBitmap.alpha = (a * 255).toInt()
            canvas.drawBitmap(bl[3]!!, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), overBitmap)
            overBitmap.alpha = 255
        }
        timed(1) { paintBars(canvas, smoothed, bright) }
    }

    private fun drawScaled(dst: Canvas, src: Bitmap, into: Bitmap, paint: Paint, alpha: Int) {
        paint.alpha = alpha
        dst.drawBitmap(src, null, RectF(0f, 0f, into.width.toFloat(), into.height.toFloat()), paint)
        paint.alpha = 255
    }

    fun release() {
        for (i in 0..3) { bl[i]?.recycle(); bl[i] = null; bc[i] = null }
        cw = 0; ch = 0
    }
}
