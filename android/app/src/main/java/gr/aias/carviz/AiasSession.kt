package gr.aias.carviz

import android.content.Intent
import androidx.car.app.Screen
import androidx.car.app.Session

class AiasSession : Session() {
    override fun onCreateScreen(intent: Intent): Screen = AiasScreen(carContext)
}
