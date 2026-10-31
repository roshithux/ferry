package app.ferry

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.IntentCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * "Share → Ferry". Stays open (as a small card) until the transfer finishes, because the
 * read permission for shared files only lasts while this activity is alive.
 */
class ShareActivity : ComponentActivity() {
    private sealed interface Outcome {
        data object Sending : Outcome
        data object Sent : Outcome
        data class Failed(val message: String) : Outcome
    }

    private var outcome by mutableStateOf<Outcome>(Outcome.Sending)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mac = Ferry.paired.value
        if (mac == null) {
            Ferry.toast("Open Ferry and pair with your Mac first.")
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        val uris = sharedUris(intent)
        val text = if (uris.isEmpty()) intent.getStringExtra(Intent.EXTRA_TEXT) else null
        if (uris.isEmpty() && text.isNullOrEmpty()) {
            Ferry.toast("There was nothing to send.")
            finish()
            return
        }

        Ferry.startService(this)
        setContent {
            FerryTheme {
                ShareCard(macName = mac.name, outcome = outcome, onClose = ::finish)
            }
        }

        if (savedInstanceState != null) return
        lifecycleScope.launch {
            outcome = try {
                if (text != null) Ferry.sendText(text) else uris.forEach { Ferry.sendUri(it) }
                Outcome.Sent
            } catch (e: Exception) {
                Outcome.Failed(e.message ?: "Couldn't send. Please try again.")
            }
            if (outcome == Outcome.Sent) {
                delay(900)
                finish()
            }
        }
    }

    private fun sharedUris(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
        Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
        else -> emptyList()
    }

    @Composable
    private fun ShareCard(macName: String, outcome: Outcome, onClose: () -> Unit) {
        val busy by Ferry.busy.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        when (outcome) {
                            Outcome.Sending -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
                            Outcome.Sent -> Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            is Outcome.Failed -> Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(28.dp))
                        }
                        Column {
                            Text(
                                when (outcome) {
                                    Outcome.Sending -> "Sending to $macName"
                                    Outcome.Sent -> "Sent to $macName"
                                    is Outcome.Failed -> "Couldn't send"
                                },
                                style = MaterialTheme.typography.titleMedium,
                            )
                            val detail = when (outcome) {
                                Outcome.Sending -> busy
                                Outcome.Sent -> "Saved in the Mac's Downloads folder."
                                is Outcome.Failed -> outcome.message
                            }
                            if (detail != null) {
                                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    if (outcome is Outcome.Failed) {
                        TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("Close") }
                    }
                }
            }
        }
    }
}
