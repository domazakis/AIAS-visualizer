package gr.aias.carviz

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

/**
 * Μέτρηση κόστους στην **ανάλυση του αυτοκινήτου, χωρίς αυτοκίνητο**.
 *
 * Ζωγραφίζει εκτός οθόνης, σε bitmap ίδιου μεγέθους με την επιφάνεια που
 * παραχωρεί το MG, και χρονομετρεί κάθε στάδιο ξεχωριστά. Τρέχει στο κινητό
 * στο τραπέζι, οπότε η βελτιστοποίηση δεν χρειάζεται πια βόλτα για κάθε
 * δοκιμή — μόνο η τελική επιβεβαίωση.
 *
 * Δεν είναι ταυτόσημο με τη σχεδίαση σε πραγματική επιφάνεια: λείπει η
 * κωδικοποίηση βίντεο και η μεταφορά στο head unit. Αλλά τα **σχετικά**
 * μεγέθη των σταδίων μεταφέρονται, και αυτά ψάχνουμε.
 */
object Bench {

    /** Τα μεγέθη που μετρήθηκαν στο MG στις 3–5 Σεπτεμβρίου 2026. */
    private const val SURF_W = 1785
    private const val SURF_H = 690
    private val FULL = Rect(36, 132, 36 + 1713, 132 + 540)
    private val SPLIT = Rect(36, 132, 36 + 1083, 132 + 540)

    private val NAMES = arrayOf("καθάρισμα", "πινελιές", "λάμψη", "πυραμίδα", "επίστρωση")

    fun runAll(frames: Int = 120): String {
        val sb = StringBuilder()
        sb.append("ΜΕΤΡΗΣΗ ΚΟΣΤΟΥΣ — επιφάνεια ${SURF_W}×${SURF_H}\n")
        sb.append("$frames καρέ ανά διάταξη, εκτός οθόνης\n")
        sb.append(one("πλήρης  ${FULL.width()}×${FULL.height()}", FULL, frames))
        sb.append(one("χωρισμένη ${SPLIT.width()}×${SPLIT.height()}", SPLIT, frames))
        sb.append(upscale(frames))
        return sb.toString()
    }

    /**
     * Η επίστρωση απομονωμένη. Είναι το ακριβότερο στάδιο με διαφορά, οπότε
     * αξίζει να μετρηθεί τι από τα δύο κοστίζει: το διγραμμικό φιλτράρισμα
     * της μεγέθυνσης, ή η προσθετική ανάμειξη.
     */
    private fun upscale(frames: Int): String {
        val src = Bitmap.createBitmap(SURF_W / 4, SURF_H / 4, Bitmap.Config.ARGB_8888)
        Canvas(src).drawColor(android.graphics.Color.argb(120, 255, 170, 60))
        val dst = Bitmap.createBitmap(SURF_W, SURF_H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(dst)
        val r = android.graphics.RectF(0f, 0f, SURF_W.toFloat(), SURF_H.toFloat())
        val sb = StringBuilder("\nΕΠΙΣΤΡΩΣΗ ξεχωριστά (${src.width}×${src.height} → ${SURF_W}×${SURF_H})\n")

        for (filter in booleanArrayOf(true, false)) {
            for (add in booleanArrayOf(true, false)) {
                val p = android.graphics.Paint().apply {
                    isFilterBitmap = filter
                    alpha = 140
                    if (add) xfermode = android.graphics.PorterDuffXfermode(
                        android.graphics.PorterDuff.Mode.ADD)
                }
                repeat(10) { cv.drawBitmap(src, null, r, p) }
                val t0 = System.nanoTime()
                repeat(frames) { cv.drawBitmap(src, null, r, p) }
                val ms = (System.nanoTime() - t0) / 1e6 / frames
                sb.append("  %-9s %-9s %6.2f ms\n".format(
                    if (filter) "διγραμμικό" else "πλησιέστερο",
                    if (add) "προσθετικό" else "απλό", ms))
            }
        }
        src.recycle(); dst.recycle()
        return sb.toString()
    }

    /**
     * Ένα καρέ στην ανάλυση του αυτοκινήτου, για να επαληθεύεται με το μάτι
     * ότι μια αλλαγή απόδοσης δεν άλλαξε την εικόνα.
     */
    fun preview(split: Boolean = false): Bitmap {
        val box = if (split) SPLIT else FULL
        val bmp = Bitmap.createBitmap(SURF_W, SURF_H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val bars = Bars()
        bars.mode = "speak"
        bars.level = 0.55f
        // Αρκετά καρέ ώστε να γεμίσει η ουρά της λάμψης και να σταθεροποιηθεί
        // η έκταση· αλλιώς βλέπουμε τη μεταβατική κατάσταση.
        repeat(60) { bars.frame(cv, 0.016f, SURF_W, SURF_H, box) }
        bars.release()
        return bmp
    }

    private fun one(title: String, box: Rect, frames: Int): String {
        val bmp = Bitmap.createBitmap(SURF_W, SURF_H, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val bars = Bars()
        // Η ομιλία είναι η ακριβότερη κατάσταση: οι τελείες είναι στο μέγιστο
        // ύψος, άρα οι πινελιές καλύπτουν τη μεγαλύτερη επιφάνεια.
        bars.mode = "speak"
        bars.level = 0.55f

        // Ζέσταμα: η πρώτη κλήση δεσμεύει τα bitmap της πυραμίδας και η Skia
        // δεν έχει ακόμη ζεστούς τους δρόμους της.
        repeat(15) { bars.frame(cv, 0.016f, SURF_W, SURF_H, box) }

        bars.profile = true
        bars.resetStages()
        val t0 = System.nanoTime()
        repeat(frames) { bars.frame(cv, 0.016f, SURF_W, SURF_H, box) }
        val total = (System.nanoTime() - t0) / 1e6 / frames

        val sb = StringBuilder("\n$title\n")
        var acc = 0.0
        for (i in 0..4) {
            val ms = bars.stage[i] / 1e6 / frames
            acc += ms
            sb.append("  %-11s %6.2f ms\n".format(NAMES[i], ms))
        }
        sb.append("  %-11s %6.2f ms\n".format("λοιπά", (total - acc).coerceAtLeast(0.0)))
        sb.append("  %-11s %6.2f ms  →  %.0f fps θεωρητικά\n"
            .format("ΣΥΝΟΛΟ", total, if (total > 0) 1000.0 / total else 0.0))

        bars.release()
        bmp.recycle()
        return sb.toString()
    }
}
