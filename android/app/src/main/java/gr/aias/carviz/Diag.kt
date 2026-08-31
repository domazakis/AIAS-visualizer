package gr.aias.carviz

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Μόνιμα διαγνωστικά, γραμμένα στις ρυθμίσεις της εφαρμογής.
 *
 * Λόγος ύπαρξης: στο αυτοκίνητο το καλώδιο του Android Auto πιάνει τη θύρα
 * του κινητού, άρα δεν υπάρχει adb και δεν διαβάζεται logcat εκείνη τη
 * στιγμή. Ό,τι μαθαίνει ο renderer πρέπει να επιβιώσει μέχρι να γυρίσουμε
 * και να ανοίξουμε την εφαρμογή στο κινητό.
 *
 * Ο ίδιος φάκελος ρυθμίσεων διαβάζεται από τη [MainActivity]· η υπηρεσία
 * του αυτοκινήτου και η οθόνη του κινητού είναι στην ίδια διεργασία.
 */
object Diag {

    private const val FILE = "aias-diag"

    /** Η σειρά εμφάνισης στην οθόνη — όχι αλφαβητική, αλλά κατά σημασία. */
    private val ORDER = listOf(
        "κατάσταση", "επιφάνεια", "ορατή", "σταθερή", "καρέ", "σφάλμα", "ενημέρωση"
    )

    fun put(context: Context, key: String, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .putString("ενημέρωση", stamp())
            .apply()
    }

    /**
     * Σαν την [put], αλλά περιμένει να γραφτεί στον δίσκο. Χρειάζεται μόνο
     * στον χειριστή κατάρρευσης: το `apply()` γράφει ασύγχρονα και η
     * διεργασία μπορεί να πεθάνει πριν προλάβει.
     */
    fun putNow(context: Context, key: String, value: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(key, value)
            .putString("ενημέρωση", stamp())
            .commit()
    }

    /** Μόνο τα κλειδιά που έχουν γραφτεί, με τη σειρά του [ORDER]. */
    fun read(context: Context): List<Pair<String, String>> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return ORDER.mapNotNull { key -> prefs.getString(key, null)?.let { key to it } }
    }

    fun clear(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().apply()
    }

    private fun stamp(): String =
        SimpleDateFormat("dd/MM HH:mm:ss", Locale.US).format(Date())
}
