package gr.aias.carviz

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * Δηλώνει στο Android Auto ότι είμαστε **ενεργή** εφαρμογή πλοήγησης.
 *
 * Ως τώρα δηλώναμε μόνο την κατηγορία. Το logcat του αυτοκινήτου το έλεγε
 * ξεκάθαρα:
 *
 *     GH.NavClientManager: connectToComponent(gr.aias.carviz/AiasCarAppService)
 *     GH.NavClientManager: No Navigation Client Source for gr.aias.carviz
 *     GH.DefaultAppManager: component not validated, was null, category=NAVIGATION
 *
 * Δηλαδή καθόμασταν στη θέση της πλοήγησης χωρίς ποτέ να πλοηγούμε. Μια τέτοια
 * εφαρμογή δεν αποθηκεύεται ως προεπιλεγμένη, και ο host δεν έχει λόγο να την
 * επαναφέρει όταν ο χρήστης πάει κάπου αλλού.
 *
 * Εδώ το διορθώνουμε: `navigationStarted()` όσο η οθόνη είναι ζωντανή,
 * `navigationEnded()` όταν φεύγει. Ο βοηθός δεν πλοηγεί πραγματικά — αλλά για
 * το πρωτόκολλο «πλοήγηση» σημαίνει «αυτή η εφαρμογή κατέχει τώρα την οθόνη
 * και έχει κάτι ζωντανό να δείξει», που είναι ακριβώς η αλήθεια.
 *
 * **Δεν είναι βεβαιότητα.** Είναι ο τελευταίος μοχλός που ελέγχουμε εμείς για
 * το πρόβλημα της επαναφοράς· ο άλλος περνάει από το Play Console.
 */
class NavState(private val carContext: CarContext) : DefaultLifecycleObserver {

    private companion object { const val TAG = "AiasNav" }

    private var started = false

    private val callback = object : NavigationManagerCallback {
        /**
         * Ο host ζητάει να σταματήσουμε — π.χ. όταν άλλη εφαρμογή πλοήγησης
         * παίρνει τη σκυτάλη. Υποχρεωτικά υπακούμε· αν δεν το κάνουμε, το
         * Android Auto μπορεί να μας τερματίσει.
         */
        override fun onStopNavigation() {
            Log.i(TAG, "ο host ζήτησε τερματισμό πλοήγησης")
            started = false
            note("πλοήγηση", "ο host ζήτησε τερματισμό")
        }

        override fun onAutoDriveEnabled() {
            Log.i(TAG, "onAutoDriveEnabled (δοκιμαστική λειτουργία του host)")
        }
    }

    private fun note(key: String, value: String) {
        try { Diag.put(carContext, key, value) } catch (e: Throwable) {
            Log.w(TAG, "αποτυχία εγγραφής διαγνωστικών", e)
        }
    }

    private fun manager(): NavigationManager? = try {
        carContext.getCarService(NavigationManager::class.java)
    } catch (e: Throwable) {
        Log.w(TAG, "δεν υπάρχει NavigationManager", e)
        null
    }

    override fun onCreate(owner: LifecycleOwner) {
        // Ο callback πρέπει να μπει ΠΡΙΝ το navigationStarted, αλλιώς πετάει.
        manager()?.let {
            try {
                it.setNavigationManagerCallback(callback)
                note("πλοήγηση", "ο callback καταχωρήθηκε")
            } catch (e: Throwable) {
                Log.w(TAG, "αποτυχία καταχώρησης callback", e)
                note("πλοήγηση", "αποτυχία callback: $e")
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        if (started) return
        manager()?.let {
            try {
                it.navigationStarted()
                started = true
                Log.i(TAG, "navigationStarted")
                note("πλοήγηση", "ενεργή")
            } catch (e: Throwable) {
                Log.w(TAG, "αποτυχία navigationStarted", e)
                note("πλοήγηση", "αποτυχία έναρξης: $e")
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) = end()

    override fun onDestroy(owner: LifecycleOwner) {
        end()
        try { manager()?.clearNavigationManagerCallback() } catch (e: Throwable) { }
    }

    private fun end() {
        if (!started) return
        started = false
        try {
            manager()?.navigationEnded()
            Log.i(TAG, "navigationEnded")
        } catch (e: Throwable) {
            Log.w(TAG, "αποτυχία navigationEnded", e)
        }
    }
}
