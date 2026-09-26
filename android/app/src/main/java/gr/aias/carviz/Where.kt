package gr.aias.carviz

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * Το GPS του ΑΙΑΝΤΑ.
 *
 * ΓΙΑΤΙ ΕΡΓΑΛΕΙΟ ΚΑΙ ΟΧΙ ΣΥΝΕΧΗΣ ΤΡΟΦΟΔΟΣΙΑ.
 *
 * Θα μπορούσαμε να του σπρώχνουμε «τώρα περνάτε από Χ» κάθε λίγο. Δύο λόγοι
 * που δεν το κάνουμε: η πληροφορία θα έφτανε πάντα καθυστερημένη, και —το
 * σοβαρό— **θα το έλεγε**. Ο ΑΙΑΣ θα γινόταν πλοηγός, που είναι ακριβώς αυτό
 * που ο κανόνας «δεν βλέπεις και δεν φαντάζεσαι» υπάρχει για να αποτρέψει.
 *
 * Εδώ η θέση δίνεται **μόνο όταν τη ζητήσει**, μέσα από εργαλείο πελάτη του
 * ElevenLabs. Και η διάκριση είναι σωστή για τον χαρακτήρα: ένα αυτοκίνητο
 * δεν βλέπει τον δρόμο — έχει **όργανα**. Κοιτάζει το GPS του όπως κοιτάζει
 * το κοντέρ.
 *
 * ΚΑΙ ΟΤΑΝ ΔΕΝ ΥΠΑΡΧΕΙ ΣΗΜΑ, ΤΟ ΛΕΕΙ. Δεν πετάει εξαίρεση, δεν επιστρέφει
 * κενό, δεν αφήνει τον agent να μαντέψει: απαντά με ολόκληρη πρόταση που
 * εξηγεί τι λείπει. Ο ΑΙΑΣ μπορεί να πει «δεν βλέπω πού είμαστε» και να
 * συνεχίσει τη συζήτηση, που είναι η μόνη αποδεκτή συμπεριφορά μέσα σε
 * ηχογράφηση.
 */
object Where {

    private const val TAG = "AiasWhere"

    /** Κάθε δέκα δευτερόλεπτα ή εκατό μέτρα· αρκεί για κουβέντα, όχι για πλοήγηση. */
    private const val MIN_MS = 10_000L
    private const val MIN_M = 100f

    /** Πάνω από δύο λεπτά, το στίγμα δεν το λέμε «τωρινό». */
    private const val STALE_MS = 120_000L

    /** Κάτω από αυτό, θεωρούμαστε σταματημένοι. */
    private const val STOPPED_KMH = 4f

    private var manager: LocationManager? = null
    private var listener: LocationListener? = null

    @Volatile private var last: Location? = null

    /** Το όνομα του τόπου, από το παρασκήνιο· ποτέ δεν το ψάχνουμε την ώρα της ερώτησης. */
    @Volatile private var place: String? = null
    @Volatile private var placeAt: Location? = null

    /** Για τη γραμμή διαγνωστικών. */
    @Volatile var status: String = "δεν ξεκίνησε"
        private set

    // ------------------------------------------------------------------ έναρξη

    fun start(ctx: Context) {
        if (manager != null) return
        if (!allowed(ctx)) { status = "χωρίς άδεια"; return }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        manager = lm

        // Το τελευταίο γνωστό στίγμα δίνει απάντηση από το πρώτο δευτερόλεπτο,
        // πριν προλάβει να κλειδώσει το GPS. Σημειώνεται ως παλιό αν είναι.
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (last == null || l.time > last!!.time) last = l
            }
        } catch (e: SecurityException) { }

        val cb = LocationListener { loc ->
            last = loc
            maybeGeocode(ctx, loc)
        }
        listener = cb
        var any = false
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            try {
                if (!lm.isProviderEnabled(p)) continue
                lm.requestLocationUpdates(p, MIN_MS, MIN_M, cb, Looper.getMainLooper())
                any = true
            } catch (e: Throwable) {
                Log.w(TAG, "δεν άνοιξε ο πάροχος $p", e)
            }
        }
        status = if (any) "ενεργό" else "κλειστό"
        last?.let { maybeGeocode(ctx, it) }
    }

    fun stop() {
        try { listener?.let { manager?.removeUpdates(it) } } catch (e: Throwable) { }
        listener = null
        manager = null
        last = null
        place = null
        placeAt = null
        status = "σταμάτησε"
    }

    // ------------------------------------------------------------- η απάντηση

    /**
     * Η πρόταση που παίρνει ο agent. **Πάντα** πρόταση, ποτέ κενό.
     *
     * Καλείται από το νήμα του WebSocket, άρα δεν κάνει τίποτα αργό: το όνομα
     * του τόπου έχει ήδη βρεθεί στο παρασκήνιο.
     */
    fun describe(ctx: Context): String {
        if (!allowed(ctx)) {
            status = "χωρίς άδεια"
            return "Δεν έχω άδεια για την τοποθεσία, οπότε δεν ξέρω πού είμαστε."
        }
        if (!enabled(ctx)) {
            status = "κλειστό"
            return "Το GPS του κινητού είναι κλειστό, οπότε δεν ξέρω πού είμαστε."
        }
        val l = last
        if (l == null) {
            status = "χωρίς στίγμα"
            return "Το GPS είναι ανοιχτό αλλά δεν έχει πιάσει ακόμη στίγμα."
        }

        val age = System.currentTimeMillis() - l.time
        val km = if (l.hasSpeed()) l.speed * 3.6f else -1f
        val name = place

        val sb = StringBuilder()
        if (name != null) sb.append("Βρισκόμαστε: ").append(name).append(". ")
        else sb.append("Δεν έχω όνομα περιοχής, μόνο στίγμα. ")

        when {
            km < 0f -> sb.append("Δεν έχω ταχύτητα.")
            km < STOPPED_KMH -> sb.append("Είμαστε σταματημένοι.")
            else -> sb.append("Ταχύτητα ").append(km.toInt()).append(" χιλιόμετρα την ώρα.")
        }

        if (age > STALE_MS) {
            sb.append(" Προσοχή: το στίγμα είναι ")
                .append(age / 60_000).append(" λεπτών, μπορεί να έχουμε προχωρήσει.")
            status = "παλιό στίγμα"
        } else {
            status = "εντάξει"
        }
        return sb.toString()
    }

    /** Σύντομη μορφή για τα διαγνωστικά. */
    fun line(): String {
        val l = last ?: return status
        val km = if (l.hasSpeed()) "%.0f χλμ/ω".format(l.speed * 3.6f) else "—"
        val age = (System.currentTimeMillis() - l.time) / 1000
        return "$status · ${place ?: "χωρίς όνομα"} · $km · πριν ${age}s"
    }

    // ------------------------------------------------------------- βοηθητικά

    private fun allowed(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Ανοιχτό σημαίνει ότι το λειτουργικό δίνει τοποθεσία — όχι ότι έχουμε
     * στίγμα. Ο χρήστης μπορεί να το κλείσει από τις γρήγορες ρυθμίσεις
     * οποιαδήποτε στιγμή, και μέσα στη διαδρομή.
     */
    private fun enabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) lm.isLocationEnabled
            else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                 lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        } catch (e: Throwable) { false }
    }

    /**
     * Το όνομα του τόπου βρίσκεται ΠΡΙΝ ρωτήσει ο agent.
     *
     * Το `Geocoder` χτυπάει δίκτυο. Αν το καλούσαμε μέσα στην απάντηση, θα
     * κρατούσε το νήμα που διαβάζει τον ήχο του ΑΙΑΝΤΑ — για να πούμε σε ποια
     * λεωφόρο είμαστε. Εδώ τρέχει σε δικό του νήμα, και μόνο όταν έχουμε
     * απομακρυνθεί αρκετά ώστε να αλλάξει η απάντηση.
     */
    private fun maybeGeocode(ctx: Context, loc: Location) {
        val prev = placeAt
        if (prev != null && prev.distanceTo(loc) < 150f && place != null) return
        placeAt = loc
        Thread({
            try {
                if (!Geocoder.isPresent()) return@Thread
                @Suppress("DEPRECATION")
                val list = Geocoder(ctx, Locale("el", "GR"))
                    .getFromLocation(loc.latitude, loc.longitude, 1)
                val a = list?.firstOrNull() ?: return@Thread
                // Δρόμος και περιοχή, χωρίς αριθμό: «Λεωφόρος Κηφισίας, Μαρούσι».
                // Ο αριθμός θα ήταν και άχρηστος στην κουβέντα και πιο κοντά
                // στη διεύθυνση κάποιου απ' όσο χρειάζεται μια εκπομπή.
                val road = a.thoroughfare
                val area = a.subLocality ?: a.locality ?: a.subAdminArea
                place = listOfNotNull(road, area).distinct().joinToString(", ")
                    .ifEmpty { a.locality ?: a.adminArea }
            } catch (e: Throwable) {
                Log.w(TAG, "δεν βρέθηκε όνομα τόπου", e)
            }
        }, "aias-geo").start()
    }
}
