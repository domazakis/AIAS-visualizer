package gr.aias.carviz

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
 */
class AiasScreen(carContext: CarContext) : Screen(carContext) {

    val renderer = SurfaceRenderer(carContext)

    init {
        lifecycle.addObserver(renderer)
    }

    override fun onGetTemplate(): Template =
        NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(
                        Action.Builder()
                            .setTitle("ΑΙΑΣ")
                            .setOnClickListener { invalidate() }
                            .build()
                    )
                    .build()
            )
            .build()
}
