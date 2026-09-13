package gr.aias.carviz

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.io.File

/**
 * Τα γραφικά του Google Play, ζωγραφισμένα από τον ίδιο τον renderer.
 *
 * Θα μπορούσαν να φτιαχτούν σε πρόγραμμα σχεδίασης. Δεν φτιάχνονται, για έναν
 * λόγο: τότε θα έδειχναν κάτι που **μοιάζει** με την εφαρμογή. Εδώ τρέχει ο
 * πραγματικός [Bars] για εξήντα καρέ και αποτυπώνεται ό,τι ακριβώς βλέπει ο
 * οδηγός — ίδια γεωμετρία, ίδια λάμψη, ίδια χρώματα. Αν αλλάξει κάποτε το
 * σχέδιο, ξανατρέχει η ίδια εντολή και τα γραφικά ακολουθούν από μόνα τους.
 *
 * Γράφει στον ιδιωτικό φάκελο της εφαρμογής, οπότε δεν χρειάζεται καμία άδεια
 * αποθήκευσης· τα αρχεία βγαίνουν με `run-as`.
 */
object Store {

    /** Το μαύρο του σχεδίου. Ίδιο με το φόντο του εικονιδίου. */
    private const val BG = 0xFF0B0B0C.toInt()

    fun writeAll(ctx: Context): String {
        val out = StringBuilder()
        val dir = ctx.filesDir

        save(icon(512), File(dir, "play-icon-512.png"), out)
        save(scene(1024, 500, "speak", 0.62f), File(dir, "play-feature-1024x500.png"), out)
        save(scene(1920, 1080, "speak", 0.72f), File(dir, "play-shot-1-speak.png"), out)
        save(scene(1920, 1080, "listen", 0f), File(dir, "play-shot-2-listen.png"), out)
        save(scene(1785, 690, "speak", 0.45f), File(dir, "play-shot-3-car.png"), out)

        return out.toString()
    }

    private fun save(bmp: Bitmap, f: File, out: StringBuilder) {
        try {
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            out.append(f.name).append(": ").append(f.length()).append(" bytes\n")
        } catch (e: Throwable) {
            out.append(f.name).append(": ΣΦΑΛΜΑ ").append(e).append('\n')
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Το εικονίδιο, με την ίδια γεωμετρία που έχει το `ic_aias.xml`.
     *
     * Ο κύκλος του διανύσματος **δεν** ζωγραφίζεται: το Play θέλει τετράγωνο
     * γεμάτο ως τις άκρες και βάζει το δικό του στρογγύλεμα. Αν αφήναμε τον
     * κύκλο, οι γωνίες θα έμεναν διαφανείς και θα τις γέμιζε άσπρο.
     */
    private fun icon(n: Int): Bitmap {
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(BG)
        val k = n / 108f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeWidth = 12f * k
        }
        // x, μισό ύψος, χρώμα — αυτούσια από το διάνυσμα.
        val bars = arrayOf(
            floatArrayOf(22f, 2f), floatArrayOf(38f, 10f), floatArrayOf(54f, 18f),
            floatArrayOf(70f, 10f), floatArrayOf(86f, 2f)
        )
        for ((i, b) in bars.withIndex()) {
            p.color = if (i == 2) Color.rgb(255, 198, 95) else Color.rgb(255, 182, 63)
            cv.drawLine(b[0] * k, (54f - b[1]) * k, b[0] * k, (54f + b[1]) * k, p)
        }
        return bmp
    }

    /**
     * Μια σκηνή του renderer σε δοσμένο μέγεθος.
     *
     * Τα εξήντα καρέ δεν είναι αυθαίρετα: η λάμψη χτίζεται σε πυραμίδα και η
     * έκταση φτάνει στην τιμή της με σταθερά χρόνου· λιγότερα καρέ θα
     * αποτύπωναν τη μεταβατική κατάσταση, που δεν είναι αυτό που βλέπει ο
     * χρήστης.
     */
    private fun scene(w: Int, h: Int, mode: String, level: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        cv.drawColor(BG)
        val bars = Bars()
        bars.mode = mode
        bars.level = level
        repeat(60) { bars.frame(cv, 0.016f, w, h, Rect(0, 0, w, h)) }
        bars.release()
        return bmp
    }
}
