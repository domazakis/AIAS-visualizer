package gr.aias.carviz

import kotlin.math.abs
import kotlin.math.sin

/**
 * Συνθετικός οδηγός, μόνο για τη δοκιμή.
 *
 * Το μικρόφωνο και ο agent έρχονται στο βήμα 3. Ως τότε χρειάζεται κάτι που
 * να κινεί τα δύο μεγέθη —`level` και `mode`— αλλιώς η οθόνη του αυτοκινήτου
 * θα έδειχνε μόνο τη σιωπή και δεν θα μαθαίναμε αν δουλεύουν η ακρόαση και η
 * ομιλία.
 *
 * Κυκλικά: σιωπή, ακρόαση, ομιλία. Η ομιλία έχει περίγραμμα συλλαβής πάνω σε
 * πιο αργό περίγραμμα φράσης, ώστε να μοιάζει με λόγο και όχι με ημίτονο.
 * Καθαρά ντετερμινιστικό — καμία τυχαιότητα, ώστε δύο δοκιμές να συγκρίνονται.
 */
class Demo {

    private companion object {
        const val IDLE = 5f
        const val LISTEN = 5f
        const val SPEAK = 9f
        const val CYCLE = IDLE + LISTEN + SPEAK
    }

    private var t = 0f

    /** Προχωράει τον χρόνο και γράφει κατευθείαν πάνω στα δύο μεγέθη. */
    fun step(dt: Float, bars: Bars) {
        t += dt
        if (t > CYCLE) t -= CYCLE

        when {
            t < IDLE -> {
                bars.mode = "idle"
                bars.level = 0.03f + 0.025f * sin(t * 0.9f)
            }
            t < IDLE + LISTEN -> {
                bars.mode = "listen"
                bars.level = 0.055f + 0.045f * sin(t * 1.7f)
            }
            else -> {
                val s = t - IDLE - LISTEN
                bars.mode = "speak"
                // Το ταβάνι είναι 0.62 και όχι 1.0, επίτηδες. Μετρήθηκε στο
                // πρωτότυπο ότι το κέντρο της τελείας δίνει (255,175,50) στο
                // 0.45 και (255,193,55) στο 0.60 — πορτοκαλί, κοντά στο
                // --amber του έργου. Στο 1.0 δίνει (255,242,86), δηλαδή
                // κίτρινο: η προσθετική σύνθεση κορεννύει το κόκκινο και ό,τι
                // περισσεύει ανεβάζει μόνο το πράσινο. Ο πραγματικός λόγος
                // δεν κάθεται ποτέ στο ταβάνι· βουτάει σε κάθε συλλαβή.
                val syllable = Math.pow(abs(sin(s * 5.2f)).toDouble(), 1.3).toFloat()
                val phrase = 0.35f + 0.65f * (0.5f + 0.5f * sin(s * 0.62f))
                bars.level = (0.06f + 0.56f * syllable * phrase).coerceIn(0f, 1f)
            }
        }
    }

    /** Ποια κατάσταση παίζει τώρα — μπαίνει στα διαγνωστικά. */
    fun label(): String = when {
        t < IDLE -> "σιωπή"
        t < IDLE + LISTEN -> "ακρόαση"
        else -> "ομιλία"
    }
}
