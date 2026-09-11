package gr.aias.carviz

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate

/**
 * Το NavigationTemplate είναι σκελετός: δεν περιγράφει τι ζωγραφίζεται.
 * Ό,τι βλέπεις έρχεται από τον SurfaceRenderer, που γράφει απευθείας στην
 * επιφάνεια που παραχωρεί ο host.
 *
 * Το μοναδικό κουμπί που επιτρέπει το πρότυπο είναι ο διακόπτης της φωνής.
 * Πριν, η φωνή άνοιγε μόνο από το κινητό, και έπρεπε να το θυμάσαι **πριν**
 * βάλεις το καλώδιο — μέσα στο αυτοκίνητο δεν υπήρχε κανένας τρόπος.
 */
class AiasScreen(carContext: CarContext) : Screen(carContext) {

    val renderer = SurfaceRenderer(carContext)

    /**
     * Δηλώνει ενεργή πλοήγηση όσο ζει η οθόνη. Χωρίς αυτό ο host μας έβλεπε
     * ως εφαρμογή που κάθεται στη θέση της πλοήγησης χωρίς να πλοηγεί.
     */
    private val nav = NavState(carContext)

    init {
        lifecycle.addObserver(renderer)
        lifecycle.addObserver(nav)
    }

    private fun micGranted(): Boolean =
        carContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Η άδεια μικροφώνου δεν ζητιέται από εδώ: μια οθόνη αυτοκινήτου δεν
     * επιτρέπεται να δείξει διάλογο αδείας. Αν λείπει, το λέμε και ο χρήστης
     * τη δίνει από το κινητό — μία φορά στη ζωή της εγκατάστασης.
     */
    private fun toggleVoice() {
        if (!micGranted()) {
            carContext.startService(Intent(carContext, VoiceService::class.java))
            invalidate()
            return
        }
        val i = Intent(carContext, VoiceService::class.java)
        if (Voice.active) carContext.stopService(i) else carContext.startService(i)
        invalidate()
        // Η εκκίνηση της υπηρεσίας δεν είναι ακαριαία, και η σύνδεση στον agent
        // ακόμη λιγότερο· χωρίς αυτές τις ανανεώσεις η ετικέτα θα έμενε στην
        // προηγούμενη κατάσταση ως το επόμενο πάτημα. Λίγες και αραιές, γιατί
        // ο host μετράει τις ανανεώσεις προτύπου.
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        for (ms in longArrayOf(600, 2500, 6000)) h.postDelayed({ invalidate() }, ms)
    }

    /**
     * Η ετικέτα λέει την **αλήθεια**, όχι την πρόθεση.
     *
     * Στο αυτοκίνητο το κουμπί έγραφε «Σταμάτα» ενώ ο agent δεν είχε συνδεθεί
     * ποτέ — το κινητό είναι χωρίς κάρτα SIM και δεν υπήρχε δίκτυο. Ο οδηγός
     * κοιτούσε μια οθόνη που δήλωνε ότι δουλεύει και δεν άκουγε τίποτα. Ό,τι
     * ξέρει η υπηρεσία πρέπει να φτάνει ως εδώ.
     */
    private fun label(): String {
        if (!micGranted()) return "Άδεια από κινητό"
        if (!Voice.active) return "Μίλα"
        val s = Voice.status
        return when {
            s.startsWith("χωρίς ίντερνετ") -> "Χωρίς ίντερνετ"
            s.startsWith("σφάλμα") -> "Σφάλμα σύνδεσης"
            s.startsWith("σύνδεση") -> "Συνδέεται…"
            else -> "Σταμάτα"
        }
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle(label())
                            .setOnClickListener { toggleVoice() }
                            .build()
                    )
                    .build()
            )
            .build()
}
