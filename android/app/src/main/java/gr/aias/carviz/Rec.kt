package gr.aias.carviz

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Η ηχογράφηση της συνομιλίας, **πριν από κάθε ηχείο**.
 *
 * Το προφανές θα ήταν να ηχογραφείται η καμπίνα: μικρόφωνο που πιάνει και τους
 * δύο. Τότε όμως η ποιότητα της φωνής του ΑΙΑΝΤΑ εξαρτάται από την ακουστική
 * του αυτοκινήτου, από τον δρόμο, και από το πόσο δυνατά παίζουν τα ηχεία —
 * δηλαδή ακριβώς από τους παράγοντες που μας ταλαιπώρησαν με την ηχώ.
 *
 * Εδώ γράφονται οι **πηγές**: η φωνή σου όπως βγαίνει από το μικρόφωνο, και η
 * φωνή του όπως ήρθε από το δίκτυο. Καμία καμπίνα, κανένας δρόμος.
 *
 * **Στερεοφωνικό, με χωρισμένα κανάλια:** αριστερά εσύ, δεξιά εκείνος. Στο
 * μοντάζ γίνονται δύο ανεξάρτητα κομμάτια με ένα κλικ, με ξεχωριστή ένταση και
 * επεξεργασία το καθένα — και είναι εγγυημένα ευθυγραμμισμένα, επειδή είναι
 * ένα αρχείο.
 *
 * ΤΟ ΡΟΛΟΪ ΕΙΝΑΙ ΤΟ ΜΙΚΡΟΦΩΝΟ. Τρέχει συνεχώς στα 16 kHz, οπότε ο δείκτης
 * δείγματος είναι ο χρόνος. Η φωνή του ΑΙΑΝΤΑ έρχεται σε ριπές από το δίκτυο,
 * πολύ πιο γρήγορα από την πραγματικότητα· μπαίνει σε κυκλικό απομονωτή και
 * αδειάζει με τον ρυθμό του μικροφώνου. Έτσι δεν υπάρχει ολίσθηση: και τα δύο
 * κανάλια είναι κλειδωμένα στο ίδιο ρολόι. Υπάρχει σταθερή μετατόπιση όσο ο
 * απομονωτής του AudioTrack — μισό δευτερόλεπτο — που στο μοντάζ διορθώνεται
 * μια φορά και ισχύει για όλο το αρχείο.
 */
object Rec {

    /** 16 kHz στερεοφωνικό, 16 bit: 64 kB το δευτερόλεπτο, 230 MB η ώρα. */
    private const val RATE = 16000

    /** Τριάντα δευτερόλεπτα ουρά για τη φωνή του ΑΙΑΝΤΑ. */
    private const val RING = RATE * 30

    private var out: RandomAccessFile? = null
    private var frames = 0L
    private var startedAt = 0L
    @Volatile var path: String? = null
        private set

    private val ring = ShortArray(RING)
    private var rHead = 0
    private var rTail = 0
    private var rCount = 0

    /** Ουρά προς τον δίσκο: 250 καρέ των 40 ms, δέκα δευτερόλεπτα περιθώριο. */
    private val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(250)
    private var writer: Thread? = null
    @Volatile private var dropped = 0

    @Synchronized
    fun start(ctx: Context): String? {
        if (out != null) return path
        return try {
            val dir = ctx.getExternalFilesDir(null) ?: return null
            val name = "aias-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".wav"
            val f = File(dir, name)
            val ra = RandomAccessFile(f, "rw")
            ra.setLength(0)
            ra.write(header(0))          // τα μεγέθη μπαίνουν στο κλείσιμο
            out = ra
            frames = 0L
            rHead = 0; rTail = 0; rCount = 0
            startedAt = System.currentTimeMillis()
            dropped = 0
            queue.clear()
            path = f.absolutePath
            writer = Thread({
                while (true) {
                    val b = try { queue.take() } catch (e: InterruptedException) { break }
                    if (b.isEmpty()) break          // το σήμα τερματισμού
                    try {
                        synchronized(this) { out?.write(b) }
                        frames += b.size / 4
                    } catch (e: Throwable) { }
                }
            }, "aias-rec").also { it.start() }
            path
        } catch (e: Throwable) { null }
    }

    fun stop() {
        if (out == null) return
        // Το σήμα τερματισμού μπορεί να μη χωρέσει σε γεμάτη ουρά· τότε το
        // νήμα σταματά με διακοπή. Χωρίς αυτό θα έγραφε πάνω σε κλεισμένο
        // αρχείο τη στιγμή που φτιάχνουμε την κεφαλίδα.
        queue.offer(ByteArray(0))
        try { writer?.join(2000) } catch (e: Throwable) { }
        if (writer?.isAlive == true) {
            writer?.interrupt()
            try { writer?.join(500) } catch (e: Throwable) { }
        }
        writer = null
        synchronized(this) {
            val ra = out ?: return
            out = null
            try {
                ra.seek(0)
                ra.write(header(frames))
                ra.close()
            } catch (e: Throwable) { }
        }
    }

    /** Πόση ώρα γράφει, σε λεπτά και δευτερόλεπτα. */
    fun elapsed(): String {
        if (out == null) return "—"
        val s = ((System.currentTimeMillis() - startedAt) / 1000).toInt()
        val d = if (dropped > 0) " · χάθηκαν $dropped" else ""
        return "%d:%02d%s".format(s / 60, s % 60, d)
    }

    /** Η φωνή του ΑΙΑΝΤΑ, όπως ήρθε από το δίκτυο. Καλείται από το νήμα ήχου. */
    @Synchronized
    fun pushAgent(pcm: ShortArray) {
        if (out == null) return
        for (s in pcm) {
            if (rCount == RING) { rHead = (rHead + 1) % RING; rCount-- }
            ring[rTail] = s
            rTail = (rTail + 1) % RING
            rCount++
        }
    }

    /**
     * Ένα καρέ μικροφώνου. Γράφει και τα δύο κανάλια μαζί.
     *
     * Καλείται από το νήμα του μικροφώνου, που είναι και το ρολόι: ό,τι φωνή
     * του ΑΙΑΝΤΑ έχει μαζευτεί ως τώρα μπαίνει δίπλα, και αν δεν υπάρχει
     * μπαίνει σιωπή. Έτσι το δεξί κανάλι έχει πάντα το ίδιο μήκος με το
     * αριστερό, ό,τι κι αν κάνει το δίκτυο.
     */
    fun writeMic(mic: ShortArray, len: Int) {
        if (out == null) return
        val buf = ByteArray(len * 4)
        synchronized(this) {
            var j = 0
            for (i in 0 until len) {
                val l = mic[i].toInt()
                val r = if (rCount > 0) {
                    val v = ring[rHead].toInt()
                    rHead = (rHead + 1) % RING
                    rCount--
                    v
                } else 0
                buf[j++] = (l and 0xFF).toByte()
                buf[j++] = ((l shr 8) and 0xFF).toByte()
                buf[j++] = (r and 0xFF).toByte()
                buf[j++] = ((r shr 8) and 0xFF).toByte()
            }
        }
        // Ο ΔΙΣΚΟΣ ΔΕΝ ΑΓΓΙΖΕΙ ΤΟ ΝΗΜΑ ΤΟΥ ΜΙΚΡΟΦΩΝΟΥ.
        //
        // Η γραφή φεύγει σε δική της ουρά. Αν το σύστημα αρχείων κολλήσει για
        // μισό δευτερόλεπτο —συμβαίνει— το νήμα του μικροφώνου δεν περιμένει:
        // συνεχίζει να διαβάζει και να στέλνει. Η ηχογράφηση είναι το δεύτερο
        // σε σειρά προτεραιότητας· η συνομιλία είναι το πρώτο.
        //
        // Και αν η ουρά γεμίσει, το κομμάτι πέφτει αντί να μπλοκάρει. Κενό στην
        // ηχογράφηση είναι ενόχληση· κενό στη συνομιλία είναι αποτυχία. Το
        // έχουμε πληρώσει: εκατόν τριάντα τέσσερα δευτερόλεπτα σιωπής στο S24,
        // επειδή κάτι σταμάτησε να στέλνει και κανείς δεν το μετρούσε.
        if (!queue.offer(buf)) dropped++
    }

    /** Κεφαλίδα WAV, 44 bytes. Με μηδενικά μεγέθη στην αρχή, σωστά στο τέλος. */
    private fun header(n: Long): ByteArray {
        val data = n * 4                     // δύο κανάλια × δύο bytes
        val b = ByteArray(44)
        fun str(at: Int, s: String) { for (i in s.indices) b[at + i] = s[i].code.toByte() }
        fun i32(at: Int, v: Long) {
            b[at] = (v and 0xFF).toByte()
            b[at + 1] = ((v shr 8) and 0xFF).toByte()
            b[at + 2] = ((v shr 16) and 0xFF).toByte()
            b[at + 3] = ((v shr 24) and 0xFF).toByte()
        }
        fun i16(at: Int, v: Int) {
            b[at] = (v and 0xFF).toByte()
            b[at + 1] = ((v shr 8) and 0xFF).toByte()
        }
        str(0, "RIFF");  i32(4, 36 + data); str(8, "WAVE")
        str(12, "fmt "); i32(16, 16);       i16(20, 1)
        i16(22, 2)                                   // κανάλια
        i32(24, RATE.toLong())
        i32(28, RATE.toLong() * 4)                   // bytes ανά δευτερόλεπτο
        i16(32, 4)                                   // bytes ανά καρέ
        i16(34, 16)                                  // bits ανά δείγμα
        str(36, "data"); i32(40, data)
        return b
    }
}
