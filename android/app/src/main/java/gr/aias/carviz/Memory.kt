package gr.aias.carviz

import android.content.Context
import android.util.Log
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
 * 1. ΣΗΜΕΙΩΣΕΙΣ — από διαδρομή σε διαδρομή, στο Dropbox.
 *
 *    Ο ΑΙΑΣ γράφει ελεύθερα, ο Claude στο chat φιλτράρει (απόφαση 29/09/2026).
 *    Δύο αρχεία στον φάκελο της εφαρμογής στο Dropbox ([Cloud]):
 *
 *    - `inbox.txt`: κάθε κλήση του `thymisou`, με ημερομηνία, χωρίς φίλτρο.
 *      Εδώ γράφει μόνο η εφαρμογή — και πάντα **προσθέτοντας**, ποτέ
 *      αντικαθιστώντας: αν το αρχείο άλλαξε από τότε που το διαβάσαμε,
 *      ξαναδιαβάζεται και ξαναπροσπαθούμε.
 *    - `memory.txt`: μόνο ό,τι ενέκρινε ο Claude.
 *
 *    Ο Claude στο chat **δεν μπορεί να αντικαταστήσει αρχεία** στο Dropbox,
 *    μόνο να φτιάξει καινούργια. Άρα δεν αγγίζει ποτέ αυτά τα δύο: αφήνει
 *    πρόταση `memory.new.<ΕΕΕΕΜΜΗΗ-ΩΩΛΛ>.txt`, και την εφαρμόζει η εφαρμογή
 *    ([applyProposal]). Η εφαρμογή τα ξαναδιαβάζει σε κάθε έναρξη συνεδρίας.
 *    Έτσι η μνήμη είναι ίδια σε όποιο κινητό κι αν κουμπώσει ο Γιάννης.
 *
 *    Στο κινητό μένουν αντίγραφα και των δύο, για να δουλεύει ο ΑΙΑΣ και χωρίς
 *    δίκτυο, και ένα `pending.txt` με σημειώσεις που δεν έχουν ανέβει ακόμη.
 *    Χωρίς σύνδεση με το Dropbox, όλα μένουν στο κινητό, όπως ως την 0.46.
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
 * ΤΟ ΤΑΒΑΝΙ — ΚΑΙ ΤΙ ΓΙΝΕΤΑΙ ΟΤΑΝ ΞΕΠΕΡΑΣΤΕΙ.
 *
 * Το `{{memory}}` μπαίνει στο prompt και το prompt ξαναστέλνεται σε ΚΑΘΕ
 * γύρο της κουβέντας. Το ElevenLabs γράφει ότι πάνω από 2.000 tokens
 * ανεβαίνουν η καθυστέρηση και το κόστος, και μια ερώτηση για καιρό
 * προσθέτει άλλα 300–400 (δες [Weather]).
 *
 * Όταν εγκεκριμένες και νέες δεν χωράνε μαζί, **τίποτα δεν σβήνεται από τα
 * αρχεία**. Απλώς δεν στέλνονται όλες: κόβονται πρώτα οι παλαιότερες νέες, και
 * μόνο αν δεν χωράνε ούτε οι εγκεκριμένες, οι παλαιότερες εγκεκριμένες. Η
 * οθόνη «Τι θυμάται ο ΑΙΑΣ» το λέει, για να ξέρει ο Claude ότι είναι ώρα να
 * φιλτράρει.
 */
object Memory {

    private const val TAG = "AiasMemory"

    /** Όλο το `{{memory}}`, σε χαρακτήρες. */
    const val BUDGET = 600

    /** Όσο παίρνουν τα πρόσφατα σε επανασύνδεση· οι σημειώσεις μοιράζονται τα υπόλοιπα. */
    private const val RECENT_BUDGET = 300

    /** Μια σημείωση είναι μία πρόταση. Αν ξεφύγει, κόβεται. */
    private const val NOTE_MAX = 160

    private const val EMPTY = "Τίποτα ακόμα."

    /** Στο Dropbox, μέσα στον φάκελο της εφαρμογής. */
    const val REMOTE_INBOX = "/inbox.txt"
    const val REMOTE_MEMORY = "/memory.txt"

    /** Στο κινητό. */
    private const val CACHE_MEMORY = "cloud-memory.txt"
    private const val CACHE_INBOX = "cloud-inbox.txt"
    private const val PENDING = "pending.txt"
    /** Το τελευταίο φιλτράρισμα του Claude που εφαρμόστηκε, για την οθόνη μνήμης. */
    private const val FILTERED = "filtered.txt"
    /** Ως την 0.46 οι σημειώσεις ζούσαν εδώ· μεταφέρονται στο `pending`. */
    private const val LEGACY = "memory.txt"

    private val recent = ArrayDeque<String>()
    private val syncLock = Any()

    // ------------------------------------------------------------ σημειώσεις

    /**
     * Καλείται από το εργαλείο `thymisou`, που ΠΕΡΙΜΕΝΕΙ απάντηση — άρα
     * επιστρέφει αμέσως. Η σημείωση γράφεται στο κινητό· το ανέβασμα γίνεται
     * μετά, από το [flushAsync].
     */
    @Synchronized
    fun remember(ctx: Context, text: String): String {
        migrate(ctx)
        val t = text.trim().replace('\n', ' ').take(NOTE_MAX)
        if (t.isEmpty()) return "Δεν υπήρχε κάτι να κρατήσω."
        // Ίδια σημείωση δύο φορές δεν προσθέτει τίποτα· μόνο θόρυβο.
        val known = (read(ctx, CACHE_MEMORY) + read(ctx, CACHE_INBOX) + read(ctx, PENDING))
            .map { body(it) }
        if (known.any { it.equals(t, ignoreCase = true) }) return "Το είχα ήδη."
        append(ctx, PENDING, "${stamp()}: $t")
        return "Σημειώθηκε."
    }

    /** Το ανέβασμα των νέων σημειώσεων, στο παρασκήνιο. */
    fun flushAsync(ctx: Context) {
        val app = ctx.applicationContext
        Thread({ sync(app) }, "aias-memory-sync").start()
    }

    /**
     * Συγχρονισμός που δεν καθυστερεί την κουβέντα: περιμένουμε ως [ms] και
     * μετά προχωράμε με ό,τι έχει το κινητό. Ο συγχρονισμός συνεχίζει στο
     * παρασκήνιο και θα φανεί στην επόμενη σύνδεση.
     */
    fun syncWithin(ctx: Context, ms: Long) {
        val app = ctx.applicationContext
        val t = Thread({ sync(app) }, "aias-memory-sync")
        t.start()
        try { t.join(ms) } catch (e: InterruptedException) { }
    }

    /**
     * Ένας συγχρονισμός τη φορά, με αυτή τη σειρά:
     *
     * 1. Αν ο Claude άφησε πρόταση φιλτραρίσματος, εφαρμόζεται ([applyProposal]).
     * 2. `memory.txt` → αντίγραφο στο κινητό.
     * 3. Οι νέες σημειώσεις του κινητού → `inbox.txt`, με προσθήκη.
     *
     * **Αρχείο που λείπει από το Dropbox δεν σημαίνει άδεια μνήμη.** Κρατάμε το
     * αντίγραφο του κινητού και το ξανανεβάζουμε· μια διαγραφή δεν
     * συγχρονίζεται ποτέ προς τα κινητά.
     *
     * Αργό — μόνο από νήμα παρασκηνίου.
     */
    fun sync(ctx: Context) = synchronized(syncLock) {
        migrate(ctx)
        if (!Cloud.linked(ctx)) {
            Cloud.status = if (Cloud.configured()) "δεν συνδέθηκε — μόνο στο κινητό"
                           else "δεν έχει ρυθμιστεί — μόνο στο κινητό"
            return@synchronized
        }

        val names = Cloud.list(ctx) ?: return@synchronized
        val proposals = names.filter { PROPOSAL.matches(it) }.sorted()
        if (proposals.isNotEmpty() && !applyProposal(ctx, proposals)) return@synchronized

        val cachedMemory = read(ctx, CACHE_MEMORY)
        val m = rewrite(ctx, REMOTE_MEMORY) { remote ->
            if (remote == null && cachedMemory.isNotEmpty()) cachedMemory else null
        }
        if (!m.ok) return@synchronized
        m.lines?.let { write(ctx, CACHE_MEMORY, it) }
        val approved = read(ctx, CACHE_MEMORY).map { body(it).lowercase() }.toSet()

        val pend = read(ctx, PENDING)
        val cachedInbox = read(ctx, CACHE_INBOX)
        var added = 0
        val i = rewrite(ctx, REMOTE_INBOX) { remote ->
            val base = remote ?: cachedInbox
            val there = base.map { body(it).lowercase() }.toSet()
            val add = pend.filter { body(it).lowercase() !in there && body(it).lowercase() !in approved }
            added = add.size
            if (remote != null && add.isEmpty()) null else (base + add).ifEmpty { null }
        }
        if (!i.ok) return@synchronized
        i.lines?.let { write(ctx, CACHE_INBOX, it) }
        drop(ctx, pend)
        Cloud.status = "συγχρονίστηκε ${clock()}" + if (added > 0) " · ανέβηκαν $added" else ""
    }

    /** `memory.new.20260929-2215.txt` — το όνομα ταξινομείται όπως ο χρόνος. */
    private val PROPOSAL = Regex("^memory\\.new\\..+\\.txt$", RegexOption.IGNORE_CASE)

    internal class Proposal(val memory: List<String>, val processed: List<String>)

    /**
     * Η πρόταση του Claude: `## memory` με ολόκληρο το νέο `memory.txt`, και
     * `## processed` με αυτολεξεί τις γραμμές του inbox που επεξεργάστηκε.
     * Χωρίς ενότητα `## memory` δεν είναι πρόταση — δεν εφαρμόζεται, για να
     * μη σβηστεί η μνήμη από ένα μισογραμμένο αρχείο. Άδεια ενότητα όμως είναι
     * έγκυρη: ο Claude απέρριψε τα πάντα.
     */
    internal fun parseProposal(text: String): Proposal? {
        var section: String? = null
        var hasMemory = false
        val memory = ArrayList<String>()
        val processed = ArrayList<String>()
        for (raw in text.lines()) {
            val l = raw.trim()
            if (l.startsWith("##")) {
                section = l.removePrefix("##").trim().lowercase()
                if (section == "memory") hasMemory = true
                continue
            }
            if (l.isEmpty()) continue
            when (section) {
                "memory" -> memory += l
                "processed" -> processed += l
            }
        }
        return if (hasMemory) Proposal(memory, processed) else null
    }

    /**
     * Ο Claude στο chat δεν μπορεί να αντικαταστήσει αρχεία στο Dropbox, μόνο
     * να φτιάξει καινούργια. Γι' αυτό αφήνει πρόταση, και την εφαρμόζει η
     * εφαρμογή:
     *
     * - `memory.txt` ← η ενότητα `memory`, ολόκληρη.
     * - `inbox.txt` ← ό,τι είχε, **μείον μόνο** τις γραμμές της `processed`.
     *   Ό,τι σημειώθηκε ενώ ο Claude φιλτράριζε μένει.
     * - Σβήνονται όλες οι προτάσεις. Αν ήταν πολλές, ισχύει η νεότερη: ο Claude
     *   τη βάσισε σε ό,τι έβλεπε τότε, που περιλάμβανε ό,τι δεν είχαν
     *   προλάβει να εφαρμόσουν οι παλαιότερες.
     *
     * Αν κοπεί στη μέση, ξαναγίνεται στον επόμενο συγχρονισμό με το ίδιο
     * αποτέλεσμα· γι' αυτό η πρόταση σβήνεται τελευταία. Το ίδιο κι αν δύο
     * κινητά την εφαρμόσουν μαζί.
     */
    private fun applyProposal(ctx: Context, proposals: List<String>): Boolean {
        val newest = proposals.last()
        val text = when (val g = Cloud.get(ctx, "/$newest")) {
            is Cloud.Got.Ok -> g.text
            is Cloud.Got.Missing -> return true   // την εφάρμοσε ήδη άλλο κινητό
            is Cloud.Got.Fail -> { Cloud.status = "σφάλμα: ${g.why} — από το κινητό"; return false }
        }
        val p = parseProposal(text)
        if (p == null) {
            write(ctx, FILTERED, listOf("${clock()} · $newest ΔΕΝ εφαρμόστηκε: λείπει η ενότητα ## memory"))
            return true
        }
        if (!rewrite(ctx, REMOTE_MEMORY) { p.memory }.ok) return false
        write(ctx, CACHE_MEMORY, p.memory)

        val processed = p.processed.toSet()
        val cachedInbox = read(ctx, CACHE_INBOX)
        var removed = 0
        val i = rewrite(ctx, REMOTE_INBOX) { remote ->
            val base = remote ?: cachedInbox
            val next = base.filter { it !in processed }
            removed = base.size - next.size
            if (remote == null && next.isEmpty()) null else next
        }
        if (!i.ok) return false
        write(ctx, CACHE_INBOX, i.lines ?: cachedInbox.filter { it !in processed })

        for (n in proposals) Cloud.delete(ctx, "/$n")
        write(ctx, FILTERED, listOf(
            "${clock()} · $newest · εγκεκριμένες ${p.memory.size} · βγήκαν $removed από το inbox" +
                if (proposals.size > 1) " · αγνοήθηκαν ${proposals.size - 1} παλαιότερες προτάσεις" else ""))
        return true
    }

    private class Outcome(val ok: Boolean, val lines: List<String>?)

    /**
     * Διάβασε, άλλαξε, γράψε — με έλεγχο έκδοσης. Αν στο μεταξύ το άλλαξε κάποιος
     * άλλος (άλλο κινητό), ξαναδιαβάζεται και η αλλαγή γίνεται πάνω στο νέο.
     *
     * Το [change] παίρνει το περιεχόμενο (`null` αν λείπει) και δίνει το νέο, ή
     * `null` για «καμία εγγραφή». Το [Outcome.lines] είναι ό,τι ισχύει τελικά
     * στο Dropbox· `null` αν το αρχείο λείπει και δεν γράφτηκε.
     */
    private fun rewrite(ctx: Context, path: String, change: (List<String>?) -> List<String>?): Outcome {
        repeat(3) {
            val remote: List<String>?
            val rev: String?
            when (val g = Cloud.get(ctx, path)) {
                is Cloud.Got.Ok -> { remote = lines(g.text); rev = g.rev }
                is Cloud.Got.Missing -> { remote = null; rev = null }
                is Cloud.Got.Fail -> { Cloud.status = "σφάλμα: ${g.why} — από το κινητό"; return Outcome(false, null) }
            }
            val next = change(remote)
            if (next == null || next == remote) return Outcome(true, remote)
            when (val p = Cloud.put(ctx, path, next.joinToString("\n") + "\n", rev)) {
                is Cloud.Put.Ok -> return Outcome(true, next)
                is Cloud.Put.Conflict -> Log.i(TAG, "το $path άλλαξε στο μεταξύ — ξανά")
                is Cloud.Put.Fail -> { Cloud.status = "σφάλμα: ${p.why} — από το κινητό"; return Outcome(false, null) }
            }
        }
        Cloud.status = "το $path άλλαζε συνέχεια — ξανά στην επόμενη σύνδεση"
        return Outcome(false, null)
    }

    /** Βγάζει από το `pending` ό,τι ανέβηκε — όχι ό,τι σημειώθηκε στο μεταξύ. */
    @Synchronized
    private fun drop(ctx: Context, done: List<String>) {
        if (done.isEmpty()) return
        write(ctx, PENDING, read(ctx, PENDING).filter { it !in done })
    }

    /** Τοπικά μόνο: χωρίς Dropbox, αυτό είναι όλη η μνήμη. */
    @Synchronized
    fun forget(ctx: Context) {
        for (f in listOf(PENDING, CACHE_INBOX, CACHE_MEMORY, FILTERED, LEGACY))
            try { File(ctx.filesDir, f).delete() } catch (e: Throwable) { }
    }

    // ------------------------------------------------------------- προβολή

    /** «3 εγκεκριμένες + 2 νέες · 412/600» — για τα διαγνωστικά και την οθόνη μνήμης. */
    @Synchronized
    fun size(ctx: Context): String {
        migrate(ctx)
        val a = read(ctx, CACHE_MEMORY)
        val i = inbox(ctx)
        val c = compose(a, i, "", BUDGET)
        val all = chars(a + i)
        return "${a.size} εγκεκριμένες + ${i.size} νέες · $all/$BUDGET" +
            if (c.dropped > 0) " · δεν χωράνε ${c.dropped}" else ""
    }

    /** Ό,τι δείχνει η οθόνη «Τι θυμάται ο ΑΙΑΣ». */
    @Synchronized
    fun view(ctx: Context): String {
        migrate(ctx)
        val a = read(ctx, CACHE_MEMORY)
        val cloudInbox = read(ctx, CACHE_INBOX)
        val pend = read(ctx, PENDING)
        val c = compose(a, cloudInbox + pend, "", BUDGET)
        val filtered = read(ctx, FILTERED).lastOrNull()
        return buildString {
            if (filtered != null) append("✓ Εφαρμόστηκε φιλτράρισμα: ").append(filtered).append("\n\n")
            if (c.dropped > 0) {
                append("⚠ ΠΑΝΩ ΑΠΟ ΤΟ ΟΡΙΟ: ").append(chars(a + cloudInbox + pend))
                    .append(" χαρακτήρες, το όριο είναι ").append(BUDGET).append(". ")
                append("Ο ΑΙΑΣ δεν παίρνει ").append(c.dropped)
                    .append(if (c.dropped == 1) " σημείωση" else " σημειώσεις")
                    .append(", πρώτα τις παλαιότερες νέες. Από τα αρχεία δεν σβήστηκε τίποτα — ")
                    .append("είναι ώρα να τις φιλτράρει ο Claude.\n\n")
            }
            append("ΕΓΚΕΚΡΙΜΕΝΕΣ (memory.txt)\n")
            append(if (a.isEmpty()) "—" else a.joinToString("\n"))
            append("\n\nΝΕΕΣ, ΠΕΡΙΜΕΝΟΥΝ ΤΟΝ CLAUDE (inbox.txt)\n")
            append(if (cloudInbox.isEmpty()) "—" else cloudInbox.joinToString("\n"))
            if (pend.isNotEmpty()) {
                append("\n\nΔΕΝ ΑΝΕΒΗΚΑΝ ΑΚΟΜΗ\n").append(pend.joinToString("\n"))
            }
            append("\n\nDropbox: ").append(Cloud.status)
        }
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
     * Ποτέ πάνω από [BUDGET]. Μόνο από τα αρχεία του κινητού: καλείται από το
     * νήμα του WebSocket και δεν περιμένει δίκτυο.
     */
    @Synchronized
    fun payload(ctx: Context, reconnect: Boolean): String {
        migrate(ctx)
        val tail = if (reconnect && recent.isNotEmpty()) recentBlock() else ""
        return compose(read(ctx, CACHE_MEMORY), inbox(ctx), tail, BUDGET).text
    }

    internal class Composed(val text: String, val dropped: Int)

    /**
     * Η σύνθεση, χωρίς αρχεία, για να δοκιμάζεται. Πρώτα οι εγκεκριμένες,
     * μετά οι νέες· όπου δεν χωράνε, μένουν έξω οι παλαιότερες νέες, και μόνο
     * μετά οι παλαιότερες εγκεκριμένες.
     */
    internal fun compose(approved: List<String>, inbox: List<String>, tail: String, budget: Int): Composed {
        var room = budget - if (tail.isEmpty()) 0 else tail.length + 1
        val a = newestThatFit(approved, room)
        room -= chars(a)
        // Αν έμεινε έξω έστω και μία εγκεκριμένη, καμία νέα δεν μπαίνει στη
        // θέση της — ακόμη κι αν μια μικρή θα χωρούσε στο κενό που περίσσεψε.
        val i = if (a.size < approved.size) emptyList() else newestThatFit(inbox, room)
        val lines = a + i
        val head = if (lines.isEmpty()) EMPTY else lines.joinToString("\n")
        return Composed(
            if (tail.isEmpty()) head else "$head\n$tail",
            approved.size - a.size + inbox.size - i.size)
    }

    private fun newestThatFit(lines: List<String>, room: Int): List<String> {
        val kept = ArrayDeque<String>()
        var used = 0
        for (line in lines.asReversed()) {
            if (used + line.length + 1 > room) break
            kept.addFirst(line); used += line.length + 1
        }
        return kept
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

    /** Οι νέες: όσες είναι ήδη στο Dropbox και όσες περιμένουν να ανέβουν. */
    private fun inbox(ctx: Context) = read(ctx, CACHE_INBOX) + read(ctx, PENDING)

    /**
     * Το κείμενο μιας σημείωσης χωρίς την ημερομηνία, για τη σύγκριση. Ο
     * Claude μπορεί να γράψει τις γραμμές όπως θέλει· αν δεν ξεκινούν με
     * «ηη/μμ:», συγκρίνεται ολόκληρη η γραμμή.
     */
    internal fun body(line: String): String =
        line.replaceFirst(Regex("^\\d{1,2}/\\d{1,2}(\\s+\\d{1,2}:\\d{2})?:\\s*"), "").trim()

    internal fun lines(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun chars(lines: List<String>) = lines.sumOf { it.length + 1 }

    @Synchronized
    private fun read(ctx: Context, name: String): List<String> = try {
        val f = File(ctx.filesDir, name)
        if (!f.exists()) emptyList() else lines(f.readText())
    } catch (e: Throwable) { emptyList() }

    @Synchronized
    private fun write(ctx: Context, name: String, lines: List<String>) {
        try {
            File(ctx.filesDir, name).writeText(
                if (lines.isEmpty()) "" else lines.joinToString("\n") + "\n")
        } catch (e: Throwable) { Log.w(TAG, "εγγραφή $name", e) }
    }

    @Synchronized
    private fun append(ctx: Context, name: String, line: String) {
        try { File(ctx.filesDir, name).appendText("$line\n") }
        catch (e: Throwable) { Log.w(TAG, "προσθήκη $name", e) }
    }

    /** Οι σημειώσεις ως την 0.46 πάνε στο `pending`, για να ανέβουν στο inbox. */
    @Synchronized
    private fun migrate(ctx: Context) {
        val old = File(ctx.filesDir, LEGACY)
        if (!old.exists()) return
        val have = read(ctx, PENDING)
        for (line in read(ctx, LEGACY)) if (line !in have) append(ctx, PENDING, line)
        old.delete()
    }

    /** Σύντομη ημερομηνία: κάθε χαρακτήρας εδώ κοστίζει σε κάθε γύρο. */
    private fun stamp(): String = SimpleDateFormat("dd/MM", Locale.US).format(Date())

    private fun clock(): String = SimpleDateFormat("dd/MM HH:mm", Locale.US).format(Date())
}
