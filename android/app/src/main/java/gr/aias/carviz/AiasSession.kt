package gr.aias.carviz

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class AiasSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // Η έκδοση Android Auto του αυτοκινήτου, στα διαγνωστικά. Από αυτήν
        // εξαρτάται τι επιτρέπεται να ζητήσει η εφαρμογή (το MG δίνει ≥ 5).
        try {
            Diag.put(carContext, "αυτοκίνητο", "Android Auto API level ${carContext.carAppApiLevel}")
        } catch (e: Throwable) { }
        return AiasScreen(carContext)
    }
}
