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
 * στην αρχή, μέσα από μεταβλητή του prompt. Αυτό κάνουμε εδώ.
 *
 * 1. ΣΗΜΕΙΩΣΕΙΣ — μακροπρόθεσμες, στον δίσκο, από διαδρομή σε διαδρομή.
 *
 *    Δεν κρατάμε ολόκληρα τα απομαγνητοφωνημένα. Θα μεγάλωναν χωρίς όριο, και
 *    για να τα συμπυκνώσουμε θα χρειαζόμασταν γλωσσικό μοντέλο μέσα στην
 *    εφαρμογή. Αντ' αυτού **ο ίδιος ο ΑΙΑΣ διαλέγει τι αξίζει**: του δίνουμε
 *    εργαλείο «θυμήσου» και γράφει μόνος του μία πρόταση όταν κάτι αξίζει να
 *    το θυμάται σε ένα μήνα. Το μοντέλο κάνει τη σύνοψη· εμείς απλώς
 *    φυλάμε το σημειωματάριο.
 *
 *    Και αυτό υπηρετεί έναν κανόνα που μέχρι τώρα δεν μπορούσε να τηρηθεί: *«Οι
 *    θέσεις σου δεν αλλάζουν από κουβέντα σε κουβέντα.»* Χωρίς μνήμη, κάθε
 *    κουβέντα ήταν η πρώτη.
 *
 * 2. ΠΡΟΣΦΑΤΑ — βραχυπρόθεσμα, μόνο στη μνήμη, μόνο για αυτή τη διαδρομή.
 *
 *    Αν κοπεί το δίκτυο, η επανασύνδεση είναι ΚΑΙΝΟΥΡΓΙΑ συνομιλία. Χωρίς
 *    αυτό, στο εικοστό λεπτό του επεισοδίου θα έλεγε «Γεια και χαρά!» σαν να
 *    μην είχατε μιλήσει ποτέ. Εδώ κρατάμε αυτολεξεί τις τελευταίες ατάκες και
 *    του τις ξαναδίνουμε — συνεχίζει από εκεί που έμεινε.
 */
object Memory {

    private const val FILE = "memory.txt"

    /** Όριο σημειώσεων σε χαρακτήρες· τις παλαιότερες τις ξεχνάει πρώτες. */
    private const val NOTES_MAX = 6000

    /** Όριο των πρόσφατων ατάκων για την επανασύνδεση. */
    private const val RECENT_MAX = 2500

    private val recent = ArrayDeque<String>()
    private var recentChars = 0

    // ------------------------------------------------------------ σημειώσεις

    /** Καλείται από το εργαλείο `thymisou`. Επιστρέφει την απάντηση προς τον agent. */
    @Synchronized
    fun remember(ctx: Context, text: String): String {
        val t = text.trim().replace('\n', ' ')
        if (t.isEmpty()) return "Δεν υπήρχε κάτι να κρατήσω."
        val lines = read(ctx).toMutableList()
        // Ίδια σημείωση δύο φορές δεν προσθέτει τίποτα· μόνο θόρυβο.
        if (lines.any { it.substringAfter(": ", it).equals(t, ignoreCase = true) })
            return "Το είχα ήδη."
        lines += "${stamp()}: $t"
        // Κόβουμε από την αρχή ώσπου να χωρέσει.
        while (lines.sumOf { it.length + 1 } > NOTES_MAX && lines.size > 1) lines.removeAt(0)
        write(ctx, lines)
        return "Το κράτησα."
    }

    @Synchronized
    fun notes(ctx: Context): List<String> = read(ctx)

    @Synchronized
    fun forget(ctx: Context) {
        try { File(ctx.filesDir, FILE).delete() } catch (e: Throwable) { }
    }

    // --------------------------------------------------------------- πρόσφατα

    /** Κάθε ατάκα που ειπώθηκε, από όποια μεριά. */
    @Synchronized
    fun heard(who: String, text: String) {
        val t = text.trim()
        if (t.isEmpty() || t == "...") return
        val line = "$who: $t"
        recent.addLast(line)
        recentChars += line.length + 1
        while (recentChars > RECENT_MAX && recent.size > 1) {
            recentChars -= recent.removeFirst().length + 1
        }
    }

    /** Μία διαδρομή τελείωσε· τα πρόσφατα δεν έχουν θέση στην επόμενη. */
    @Synchronized
    fun endDrive() {
        recent.clear()
        recentChars = 0
    }

    @Synchronized
    fun hasRecent(): Boolean = recent.isNotEmpty()

    // --------------------------------------------------- ό,τι φεύγει στον agent

    /**
     * Το κείμενο της μεταβλητής `{{memory}}`. **Ποτέ κενό**: η τεκμηρίωση του
     * ElevenLabs δεν λέει τι γίνεται αν το prompt ζητά μεταβλητή που λείπει,
     * και δεν θέλουμε να το μάθουμε μέσα σε λήψη.
     */
    @Synchronized
    fun payload(ctx: Context, reconnect: Boolean): String {
        val sb = StringBuilder()
        val n = read(ctx)
        if (n.isEmpty()) sb.append("Δεν έχεις σημειώσεις από προηγούμενες διαδρομές.")
        else {
            sb.append("Σημειώσεις σου από προηγούμενες διαδρομές:\n")
            n.forEach { sb.append("- ").append(it).append('\n') }
        }
        if (reconnect && recent.isNotEmpty()) {
            sb.append("\nΗ γραμμή κόπηκε πριν από λίγο, μέσα στην ίδια διαδρομή. ")
                .append("Οι τελευταίες ατάκες ήταν:\n")
            recent.forEach { sb.append(it).append('\n') }
            sb.append("Συνέχισε από εκεί. Μη χαιρετήσεις ξανά σαν να ξεκινάτε.")
        }
        return sb.toString().trim()
    }

    /** Το πρώτο που λέει. Στην επανασύνδεση, κάτι που ταιριάζει σε επανασύνδεση. */
    fun greeting(reconnect: Boolean): String =
        if (reconnect) "Κόπηκε η γραμμή. Λέγε." else "Γεια και χαρά!"

    // --------------------------------------------------------------- δίσκος

    private fun read(ctx: Context): List<String> = try {
        val f = File(ctx.filesDir, FILE)
        if (!f.exists()) emptyList() else f.readLines().filter { it.isNotBlank() }
    } catch (e: Throwable) { emptyList() }

    private fun write(ctx: Context, lines: List<String>) {
        try { File(ctx.filesDir, FILE).writeText(lines.joinToString("\n") + "\n") }
        catch (e: Throwable) { }
    }

    private fun stamp(): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date())
}
