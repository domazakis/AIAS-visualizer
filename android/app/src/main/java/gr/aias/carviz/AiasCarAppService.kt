package gr.aias.carviz

import androidx.car.app.CarAppService
import androidx.car.app.Session
import androidx.car.app.validation.HostValidator

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
}
