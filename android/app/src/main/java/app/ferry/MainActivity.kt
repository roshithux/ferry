package app.ferry

import android.Manifest
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon as GraphicsIcon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Ferry.startService(this)
        setContent { FerryTheme { FerryScreen() } }
    }
}

@Composable
fun FerryTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FerryScreen() {
    val context = LocalContext.current
    val paired by Ferry.paired.collectAsStateWithLifecycle()
    val macs by Ferry.macs.collectAsStateWithLifecycle()

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Ferry") }) }) { padding ->
        Box(Modifier.padding(padding)) {
            val mac = paired
            if (mac == null) PairingScreen(macs.values.sortedBy { it.name }) else PairedScreen(mac, Ferry.isMacOnline(mac, macs))
        }
    }
}

// Pairing

@Composable
private fun PairingScreen(macs: List<MacService>) {
    var pairing by remember { mutableStateOf<Pair<MacService, String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Pair with your Mac", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.size(4.dp))
            Text(
                "Start Ferry on your Mac. Both devices need to be on the same Wi-Fi.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        error?.let { message -> item { NoticeCard(message, isError = true) } }
        if (macs.isEmpty()) {
            item {
                Row(Modifier.padding(vertical = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Looking for Macs on this Wi-Fi…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(macs, key = { it.id }) { mac ->
            Card(onClick = {
                error = null
                pairing = mac to (1000..9999).random().toString()
            }) {
                ListItem(
                    headlineContent = { Text(mac.name, fontWeight = FontWeight.SemiBold) },
                    supportingContent = { Text("Tap to pair") },
                    leadingContent = { FerryBadge() },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                )
            }
        }
    }

    pairing?.let { (mac, code) ->
        LaunchedEffect(mac.id, code) {
            try {
                Ferry.pair(mac, code)
            } catch (e: Exception) {
                error = e.message ?: "Pairing didn't finish. Please try again."
            }
            pairing = null
        }
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Confirm on your Mac") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Click Allow on ${mac.name}. It shows this code:")
                    Text(code, fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = 8.sp)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { pairing = null }) { Text("Cancel") } },
        )
    }
}

// Paired

@Composable
private fun PairedScreen(mac: PairedMac, online: Boolean) {
    val context = LocalContext.current
    val activity by Ferry.activity.collectAsStateWithLifecycle()
    val busy by Ferry.busy.collectAsStateWithLifecycle()
    var batteryRestricted by remember { mutableStateOf(isBatteryRestricted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { batteryRestricted = isBatteryRestricted(context) }

    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) {
            val label = if (uris.size == 1) "File sent to ${mac.name}" else "${uris.size} files sent to ${mac.name}"
            Ferry.sendInBackground(label) { uris.forEach { Ferry.sendUri(it) } }
        }
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FerryBadge()
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(mac.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    if (online) Icons.Filled.CheckCircle else Icons.Filled.Info,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                )
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    if (online) "On this Wi-Fi" else "Not found on this Wi-Fi",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { Ferry.sendClipboard(context) }, modifier = Modifier.weight(1f)) {
                            Text("Send clipboard")
                        }
                        OutlinedButton(onClick = { pickFiles.launch(arrayOf("*/*")) }, modifier = Modifier.weight(1f)) {
                            Text("Send files")
                        }
                    }
                    busy?.let { label ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }

        if (batteryRestricted) {
            item {
                NoticeCard(
                    "Android may stop Ferry in the background, so the Mac can't send to this phone.",
                    action = "Allow background" to { requestBatteryExemption(context) },
                )
            }
        }

        item { ShortcutsCard() }

        if (activity.isNotEmpty()) {
            item { Text("Recent", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
            items(activity, key = { it.time.toString() + it.title }) { item ->
                val tapLabel = when {
                    item.uri != null -> "Open"
                    item.text != null -> "Copy again"
                    else -> null
                }
                ListItem(
                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = item.detail.takeIf { it.isNotEmpty() }?.let { { Text(it, maxLines = 1, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = {
                        Icon(
                            if (item.incoming) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            contentDescription = if (item.incoming) "Received" else "Sent",
                        )
                    },
                    modifier = if (tapLabel != null) {
                        Modifier.clickable(onClickLabel = tapLabel) { Ferry.open(context, item) }
                    } else {
                        Modifier
                    },
                )
            }
        }

        item {
            TextButton(onClick = { Ferry.unpair() }) { Text("Unpair ${mac.name}") }
        }
    }
}

@Composable
private fun ShortcutsCard() {
    val context = LocalContext.current
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Quicker ways to send", style = MaterialTheme.typography.titleSmall)
            Shortcut(Icons.Filled.Share, "Share any file or link, then pick Ferry.")
            Shortcut(Icons.Filled.Send, "Tap “Send clipboard” in Ferry's notification.")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Shortcut(painterResource(R.drawable.ic_clipboard_send), "Add the “Send clipboard” Quick Settings tile.")
                }
                if (Build.VERSION.SDK_INT >= 33) {
                    TextButton(onClick = { requestTile(context) }) { Text("Add tile") }
                }
            }
        }
    }
}

@Composable
private fun Shortcut(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Shortcut(icon: androidx.compose.ui.graphics.painter.Painter, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NoticeCard(message: String, isError: Boolean = false, action: Pair<String, () -> Unit>? = null) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Row(Modifier.padding(16.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Warning, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            action?.let { (label, onClick) -> TextButton(onClick = onClick) { Text(label) } }
        }
    }
}

@Composable
private fun FerryBadge() {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_ferry), contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
        }
    }
}

// Actions

private fun isBatteryRestricted(context: Context) =
    !context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

@Suppress("BatteryLife")
private fun requestBatteryExemption(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))
    )
}

private fun requestTile(context: Context) {
    if (Build.VERSION.SDK_INT < 33) return
    context.getSystemService(StatusBarManager::class.java).requestAddTileService(
        ComponentName(context, ClipboardTileService::class.java),
        "Send clipboard",
        GraphicsIcon.createWithResource(context, R.drawable.ic_clipboard_send),
        context.mainExecutor,
    ) { result ->
        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) Ferry.toast("The tile is already in Quick Settings.")
    }
}
