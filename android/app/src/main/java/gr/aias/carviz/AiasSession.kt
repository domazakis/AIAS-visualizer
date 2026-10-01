package gr.aias.carviz

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

class AiasSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen {
        // Για τη μέτρηση του μικροφώνου του Android Auto ([CarMic]): η υπηρεσία
        // φωνής δεν έχει CarContext, και το μικρόφωνο του αυτοκινήτου ζητιέται
        // μόνο μέσα από αυτό. Γράφεται και η έκδοση του Android Auto στα διαγνωστικά.
        CarMic.attach(carContext)
        try { Diag.put(carContext, "αυτοκίνητο", CarMic.status) } catch (e: Throwable) { }
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) = CarMic.detach(carContext)
        })
        return AiasScreen(carContext)
    }
}
