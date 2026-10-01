package gr.aias.carviz

import android.content.Context
import android.util.Log
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.media.CarAudioCallback
import androidx.car.app.media.OpenMicrophoneRequest
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ΜΕΤΡΗΣΗ, ΟΧΙ ΛΕΙΤΟΥΡΓΙΑ: το μικρόφωνο του Android Auto.
 *
 * ΓΙΑΤΙ. Η φωνή του Γιάννη φτάνει από το μικρόφωνο του MG μέσω Bluetooth
 * κλήσης, και η γραμμή αυτή είναι στενής ζώνης: μετρημένο 01/10/2026, 0,00%
 * της ενέργειας πάνω από 4 kHz, ενώ το μικρόφωνο του κινητού στο σπίτι δίνει
 * 1,68%. Γι' αυτό τα ελληνικά θολώνουν («αυτόματα» → «αγωνίσματα»).
 *
 * Το Android Auto έχει δικό του κανάλι μικροφώνου, μέσα από το καλώδιο και όχι
 * από το Bluetooth: `AppManager.openMicrophone`, 16 kHz, από car API level 5.
 * Τρία πράγματα δεν τα ξέρουμε, και τα απαντά μόνο μια ηχογράφηση:
 *
 * 1. αν το MG το υποστηρίζει (API level)·
 * 2. αν δίνει πραγματικό ευρύ ήχο ή απλώς ξαναπακετάρει τον στενό·
 * 3. αν ακυρώνει την ηχώ. Αυτό είναι το κρίσιμο: χωρίς ακύρωση ηχούς ο δρόμος
 *    δεν αξίζει, γιατί από εκεί ξεκίνησε όλη η ταλαιπωρία των 0.32–0.39.
 *
 * ΠΩΣ. Ένα κουμπί στο κινητό την οπλίζει **για μία φορά**. Στην επόμενη
 * συνομιλία στο αυτοκίνητο, μόλις ο server επιβεβαιώσει τη συνεδρία,
 * γράφονται [SECONDS] δευτερόλεπτα από το κανάλι του Android Auto σε
 * `carmic-….wav`. Η κανονική λειτουργία συνεχίζει παράλληλα και αμετάβλητη:
 * ο ΑΙΑΣ ακούει από τη γραμμή κλήσης όπως πάντα. Η ηχώ μετριέται συγκρίνοντας
 * το αρχείο με την ηχογράφηση της ίδιας στιγμής (`aias-….wav`, δεξί κανάλι ο
 * ΑΙΑΣ). Γι' αυτό το `sessions.log` γράφει σε ποιο δευτερόλεπτό της ξεκίνησε.
 */
object CarMic {

    private const val TAG = "AiasCarMic"
    private const val PREFS = "carmic"
    private const val SECONDS = 30
    private const val RATE = 16000

    /** Το CarContext της τρέχουσας σύνδεσης με το αυτοκίνητο, ή `null`. */
    @Volatile private var car: CarContext? = null

    @Volatile var status: String = "—"
        private set

    fun attach(ctx: CarContext) {
        car = ctx
        status = "Android Auto API level ${level()}"
    }

    fun detach(ctx: CarContext) { if (car === ctx) car = null }

    fun level(): Int = try { car?.carAppApiLevel ?: 0 } catch (e: Throwable) { 0 }

    fun arm(ctx: Context, on: Boolean) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("armed", on).apply()

    fun armed(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("armed", false)

    /**
     * Καλείται σε κάθε επιβεβαίωση συνεδρίας. Κάνει κάτι μόνο αν είναι οπλισμένη
     * και υπάρχει αυτοκίνητο· αλλιώς δεν αγγίζει τίποτα.
     *
     * @param log το `sessions.log` της υπηρεσίας
     * @param recAt σε ποιο δευτερόλεπτο της κανονικής ηχογράφησης βρισκόμαστε
     */
    fun maybeMeasure(ctx: Context, log: (String) -> Unit, recAt: () -> String) {
        if (!armed(ctx)) return
        val c = car ?: return                        // όχι στο αυτοκίνητο: περιμένει
        val lvl = level()
        arm(ctx, false)                              // μία φορά, ό,τι κι αν γίνει
        if (lvl < 5) {
            status = "Android Auto API level $lvl — χωρίς μικρόφωνο (θέλει 5)"
            log("μέτρηση μικροφώνου AA: δεν υποστηρίζεται, API level $lvl")
            return
        }
        val dir = ctx.getExternalFilesDir(null) ?: return
        val name = "carmic-" + SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav"
        Thread({ record(c, File(dir, name), log, recAt) }, "aias-carmic").start()
    }

    private fun record(c: CarContext, out: File, log: (String) -> Unit, recAt: () -> String) {
        var stop: (() -> Unit)? = null
        try {
            status = "ηχογραφεί…"
            val response = c.getCarService(AppManager::class.java)
                .openMicrophone(OpenMicrophoneRequest.Builder(CarAudioCallback { }).build())
                ?: run {
                    status = "το αυτοκίνητο δεν έδωσε μικρόφωνο"
                    log("μέτρηση μικροφώνου AA: το αυτοκίνητο δεν έδωσε μικρόφωνο")
                    return
                }
            stop = { try { response.carAudioCallback.onStopRecording() } catch (e: Throwable) { } }
            val input = response.carMicrophoneInputStream
            log("μέτρηση μικροφώνου AA: ξεκίνησε · στην ηχογράφηση ${recAt()}")

            val want = SECONDS * RATE * 2
            var got = 0
            RandomAccessFile(out, "rw").use { f ->
                f.setLength(0)
                f.write(ByteArray(44))
                val buf = ByteArray(4096)
                val t0 = System.currentTimeMillis()
                while (got < want && System.currentTimeMillis() - t0 < (SECONDS + 10) * 1000L) {
                    val n = input.read(buf, 0, minOf(buf.size, want - got))
                    if (n < 0) break
                    if (n > 0) { f.write(buf, 0, n); got += n }
                }
                header(f, got)
            }
            try { input.close() } catch (e: Throwable) { }
            status = "τελείωσε: ${got / (RATE * 2)}″ σε ${out.name}"
            log("μέτρηση μικροφώνου AA: τέλος · ${got / (RATE * 2)}″ · ${out.name}")
        } catch (e: Throwable) {
            Log.w(TAG, "μέτρηση", e)
            status = "σφάλμα: ${e.javaClass.simpleName} ${e.message?.take(60) ?: ""}"
            log("μέτρηση μικροφώνου AA: σφάλμα ${e.javaClass.simpleName} ${e.message?.take(80) ?: ""}")
        } finally {
            stop?.invoke()
        }
    }

    /** WAV, μονοφωνικό, 16 kHz, 16 bit — η μορφή που δηλώνει το Android Auto. */
    private fun header(f: RandomAccessFile, data: Int) {
        fun i32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
        fun i16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
        f.seek(0)
        f.write("RIFF".toByteArray()); f.write(i32(36 + data)); f.write("WAVE".toByteArray())
        f.write("fmt ".toByteArray()); f.write(i32(16)); f.write(i16(1)); f.write(i16(1))
        f.write(i32(RATE)); f.write(i32(RATE * 2)); f.write(i16(2)); f.write(i16(16))
        f.write("data".toByteArray()); f.write(i32(data))
    }
}
