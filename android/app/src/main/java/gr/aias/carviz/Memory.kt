package gr.aias.carviz

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Η μνήμη του ΑΙΑΝΤΑ. Δύο στρώματα, με διαφορετικό σκοπό.
 *
 * ΤΟ ElevenLabs ΔΕΝ ΘΥΜΑΤΑΙ ΤΙΠΟΤΑ ΜΟΝΟ ΤΟΥ. Κάθε συνομιλία ξεκινά από το μηδέν·
 * ο τεκμηριωμένος δρόμος για «μνήμη» είναι να του δίνει ο πελάτης το ιστορικό
 * στην αρχή, μέσα από τη μεταβλητή `{{memory}}` του prompt.
 *
 * 1. ΣΗΜΕΙΩΣΕΙΣ — από διαδρομή σε διαδρομή, στον δίσκο.
 *
 *    Δεν κρατάμε απομαγνητοφωνημένα. Ο ίδιος ο ΑΙΑΣ διαλέγει τι αξίζει: έχει
 *    εργαλείο `thymisou` και σημειώνει μία πρόταση όταν κάτι αξίζει να το
 *    θυμάται σε ένα μήνα. Το μοντέλο κάνει τη σύνοψη, εμείς φυλάμε το
 *    σημειωματάριο.
 *
 *    Δεν αγγίζουμε το «Τετράδιο Αία» της κονσόλας (knowledge base) με τα
 *    σταθερά στοιχεία για τον Γιάννη. Εδώ μπαίνει μόνο ό,τι σημειώνει ο ίδιος.
 *
 * 2. ΠΡΟΣΦΑΤΑ — μόνο για την τρέχουσα διαδρομή, μόνο στη μνήμη.
 *
 *    Αν κοπεί το δίκτυο, η επανασύνδεση είναι καινούργια συνομιλία. Χωρίς
 *    αυτό θα έλεγε «Γεια και χαρά!» στο εικοστό λεπτό. Κρατάμε αυτολεξεί τις
 *    τελευταίες ατάκες και του τις ξαναδίνουμε.
 *
 * ΤΟ ΤΑΒΑΝΙ ΤΩΝ 600 ΧΑΡΑΚΤΗΡΩΝ — ΚΑΙ ΓΙΑΤΙ ΕΙΝΑΙ ΤΟΣΟ ΧΑΜΗΛΟ.
 *
 * Το `{{memory}}` μπαίνει στο prompt και το prompt ξαναστέλνεται σε ΚΑΘΕ
 * γύρο της κουβέντας. Το ElevenLabs γράφει ότι πάνω από 2.000 tokens
 * ανεβαίνουν η καθυστέρηση και το κόστος. Ήταν 800 ως την 0.44· με το prompt
 * v32 και το «Τετράδιο Αία», γεμάτη μνήμη 800 έφτανε ~2.080 tokens, και μια
 * ερώτηση για καιρό προσθέτει άλλα 300–400 (δες [Weather]). Στους 600
 * χωράνε επτά με εννιά σύντομες προτάσεις.
 *
 * Όταν γεμίσει, φεύγουν οι παλαιότερες. Ό,τι βλέπεις στην οθόνη «Τι θυμάται ο
 * ΑΙΑΣ» είναι ακριβώς ό,τι του δίνεται — όχι ένα μεγαλύτερο αρχείο από το
 * οποίο στέλνεται ένα κομμάτι.
 */
object Memory {

    private const val FILE = "memory.txt"

    /** Όλο το `{{memory}}`, σε χαρακτήρες. */
    const val BUDGET = 600

    /** Όσο παίρνουν τα πρόσφατα σε επανασύνδεση· οι σημειώσεις μοιράζονται τα υπόλοιπα. */
    private const val RECENT_BUDGET = 300

    /** Μια σημείωση είναι μία πρόταση. Αν ξεφύγει, κόβεται. */
    private const val NOTE_MAX = 160

    private const val EMPTY = "Τίποτα ακόμα."

    private val recent = ArrayDeque<String>()

    // ------------------------------------------------------------ σημειώσεις

    /**
     * Καλείται από το εργαλείο `thymisou`, που ΠΕΡΙΜΕΝΕΙ απάντηση — άρα
     * επιστρέφει αμέσως κάτι σύντομο. Ο δίσκος εδώ είναι ένα μικρό αρχείο.
     */
    @Synchronized
    fun remember(ctx: Context, text: String): String {
        val t = text.trim().replace('\n', ' ').take(NOTE_MAX)
        if (t.isEmpty()) return "Δεν υπήρχε κάτι να κρατήσω."
        val lines = read(ctx).toMutableList()
        // Ίδια σημείωση δύο φορές δεν προσθέτει τίποτα· μόνο θόρυβο.
        if (lines.any { it.substringAfter(": ", it).equals(t, ignoreCase = true) })
            return "Το είχα ήδη."
        lines += "${stamp()}: $t"
        while (chars(lines) > BUDGET && lines.size > 1) lines.removeAt(0)
        write(ctx, lines)
        return "Σημειώθηκε."
    }

    @Synchronized
    fun notes(ctx: Context): List<String> = read(ctx)

    /** «4 σημειώσεις · 312/600» — για τα διαγνωστικά και την οθόνη μνήμης. */
    @Synchronized
    fun size(ctx: Context): String {
        val n = read(ctx)
        return "${n.size} σημειώσεις · ${chars(n)}/$BUDGET"
    }

    @Synchronized
    fun forget(ctx: Context) {
        try { File(ctx.filesDir, FILE).delete() } catch (e: Throwable) { }
    }

    // --------------------------------------------------------------- πρόσφατα

    @Synchronized
    fun heard(who: String, text: String) {
        val t = text.trim()
        if (t.isEmpty() || t == "...") return
        recent.addLast("$who: $t")
        // Κρατάμε λίγο παραπάνω από όσα θα στείλουμε· το κόψιμο γίνεται στο payload.
        while (recent.sumOf { it.length + 1 } > RECENT_BUDGET * 2 && recent.size > 1)
            recent.removeFirst()
    }

    @Synchronized
    fun endDrive() = recent.clear()

    @Synchronized
    fun hasRecent(): Boolean = recent.isNotEmpty()

    // --------------------------------------------------- ό,τι φεύγει στον agent

    /**
     * Το κείμενο του `{{memory}}`. Χωρίς επικεφαλίδα — την έχει ήδη το prompt
     * («Όσα έχεις σημειώσει ο ίδιος από προηγούμενες κουβέντες:»). Ποτέ κενό.
     * Ποτέ πάνω από [BUDGET].
     */
    @Synchronized
    fun payload(ctx: Context, reconnect: Boolean): String {
        val tail = if (reconnect && recent.isNotEmpty()) recentBlock() else ""
        val room = BUDGET - tail.length - 1
        // Οι νεότερες σημειώσεις έχουν προτεραιότητα αν δεν χωράνε όλες.
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in read(ctx).asReversed()) {
            if (used + line.length + 1 > room) break
            kept.addFirst(line); used += line.length + 1
        }
        val head = if (kept.isEmpty()) EMPTY else kept.joinToString("\n")
        return if (tail.isEmpty()) head else "$head\n$tail"
    }

    /** Οι τελευταίες ατάκες, όσες χωράνε, με μια οδηγία να συνεχίσει. */
    private fun recentBlock(): String {
        val intro = "Κόπηκε η γραμμή μέσα στην ίδια διαδρομή. Τελευταίες ατάκες:"
        val outro = "Συνέχισε από εκεί, χωρίς να ξαναχαιρετήσεις."
        val room = RECENT_BUDGET - intro.length - outro.length - 2
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in recent.reversed()) {
            if (used + line.length + 1 > room) break
            kept.addFirst(line); used += line.length + 1
        }
        return (listOf(intro) + kept + outro).joinToString("\n")
    }

    /** Το πρώτο που λέει. Στην επανασύνδεση, κάτι που ταιριάζει σε επανασύνδεση. */
    fun greeting(reconnect: Boolean): String =
        if (reconnect) "Κόπηκε η γραμμή. Λέγε." else "Γεια και χαρά!"

    // --------------------------------------------------------------- δίσκος

    private fun chars(lines: List<String>) = lines.sumOf { it.length + 1 }

    private fun read(ctx: Context): List<String> = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }
    } catch (e: Throwable) { emptyList() }

    private fun write(ctx: Context, lines: List<String>) {
        try { File(ctx.filesDir, FILE).writeText(lines.joinToString("\n") + "\n") }
        catch (e: Throwable) { }
    }

    /** Σύντομη ημερομηνία: κάθε χαρακτήρας εδώ κοστίζει σε κάθε γύρο. */
    private fun stamp(): String = SimpleDateFormat("dd/MM", Locale.US).format(Date())
}
