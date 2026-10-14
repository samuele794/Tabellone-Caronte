package departureboard.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** Se il dispositivo è collegato all'alimentazione; segue collegamenti e scollegamenti. */
@Composable
fun rememberIsCharging(): Boolean {
    val context = LocalContext.current
    var charging by remember { mutableStateOf(isCharging(context)) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                charging = intent.action == Intent.ACTION_POWER_CONNECTED
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        // I broadcast di sistema arrivano anche a un receiver non esportato.
        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        // Il cavo può essere stato collegato o staccato tra remember e la registrazione.
        charging = isCharging(context)
        onDispose { context.unregisterReceiver(receiver) }
    }
    return charging
}

/** Stato attuale, dall'ultimo ACTION_BATTERY_CHANGED (sticky: nessun receiver da registrare). */
private fun isCharging(context: Context): Boolean {
    val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
    return (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
}
