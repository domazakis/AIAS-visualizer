package gr.aias.carviz

import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Το σημείο εισόδου που ψάχνει το Android Auto. Δηλώνεται στο manifest με
 * φίλτρο androidx.car.app.category.NAVIGATION — η κατηγορία πλοήγησης
 * είναι η μόνη που δίνει ελεύθερη επιφάνεια σχεδίασης.
 */
class AiasCarAppService : CarAppService() {

    /**
     * Δέχεται οποιονδήποτε host. Αυτό είναι αποδεκτό μόνο επειδή η
     * εφαρμογή εγκαθίσταται χειροκίνητα σε δική μας συσκευή. Σε
     * οποιαδήποτε διανομή πρέπει να μπει λίστα υπογραφών.
     */
    override fun createHostValidator(): HostValidator =
        HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(): Session = AiasSession()

    /**
     * «Έκλεισε μόνη της» είναι μία από τις τρεις πιθανές εκβάσεις της
     * δοκιμής, και η μόνη που δεν αφήνει ίχνος από μόνη της. Κρατάμε το
     * ίχνος στοίβας πριν πεθάνει η διεργασία, ώστε να διαβαστεί μετά από
     * την οθόνη του κινητού — στο αυτοκίνητο δεν υπάρχει logcat.
     */
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
                Diag.putNow(this, "σφάλμα", "κατάρρευση στο «${thread.name}»\n\n$trace")
            } catch (e: Throwable) {
                Log.e("AiasCrash", "αποτυχία καταγραφής της κατάρρευσης", e)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
