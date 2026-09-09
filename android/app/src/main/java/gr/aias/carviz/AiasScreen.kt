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
        // Η εκκίνηση της υπηρεσίας δεν είναι ακαριαία· χωρίς δεύτερη ανανέωση
        // η ετικέτα θα έδειχνε την προηγούμενη κατάσταση ως το επόμενο πάτημα.
        android.os.Handler(android.os.Looper.getMainLooper())
            .postDelayed({ invalidate() }, 600)
    }

    private fun label(): String = when {
        !micGranted() -> "Άδεια από κινητό"
        Voice.active -> "Σταμάτα"
        else -> "Μίλα"
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
