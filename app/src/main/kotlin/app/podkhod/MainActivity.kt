package app.podkhod

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.podkhod.data.workout.WorkoutRepository
import app.podkhod.service.WorkoutService
import app.podkhod.ui.PodkhodRoot
import app.podkhod.ui.theme.PodkhodTheme
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val workouts: WorkoutRepository by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            PodkhodTheme {
                PodkhodRoot()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // After process death the service is gone; bring the rest timer back as soon as the user returns.
        lifecycleScope.launch {
            if (workouts.activeSessionNow() != null) WorkoutService.start(this@MainActivity)
        }
    }
}
