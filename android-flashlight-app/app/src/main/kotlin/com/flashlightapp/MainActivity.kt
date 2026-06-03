package com.flashlightapp

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.flashlightapp.FlashlightService.ListeningMode
import com.flashlightapp.ui.theme.*

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    // Permissions we need
    private val requiredPermissions: Array<String>
        get() = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FlashlightAppTheme {
                val uiState by viewModel.uiState.collectAsStateWithLifecycle()
                val context = LocalContext.current

                // If the service shut itself down, close the Activity too
                LaunchedEffect(uiState.isShutdown) {
                    if (uiState.isShutdown) finish()
                }

                FlashlightScreen(
                    uiState = uiState,
                    onSetMode = { viewModel.setListeningMode(it) },
                    onToggleFlashlight = { viewModel.toggleFlashlight() },
                    onShutdown = { viewModel.shutdown(context) }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Bind to (and start) the service on every onStart so rotating the
        // screen re-attaches without killing background work.
        viewModel.bindToService(this)
    }

    override fun onStop() {
        // Unbind but leave the service alive so it keeps listening in the bg.
        viewModel.unbindFromService(this)
        super.onStop()
    }
}

// =============================================================================
// Root screen — permission gate → main UI
// =============================================================================

@Composable
fun FlashlightScreen(
    uiState: FlashlightService.UiState,
    onSetMode: (ListeningMode) -> Unit,
    onToggleFlashlight: () -> Unit,
    onShutdown: () -> Unit
) {
    val context = LocalContext.current

    val permissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val allGranted = permissions.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var showPermissionScreen by remember { mutableStateOf(!allGranted) }

    if (showPermissionScreen) {
        PermissionScreen(
            permissions = permissions,
            onAllGranted = { showPermissionScreen = false }
        )
    } else {
        MainContent(
            uiState = uiState,
            onSetMode = onSetMode,
            onToggleFlashlight = onToggleFlashlight,
            onShutdown = onShutdown
        )
    }
}

// =============================================================================
// Permission screen
// =============================================================================

@Composable
fun PermissionScreen(
    permissions: List<String>,
    onAllGranted: () -> Unit
) {
    val context = LocalContext.current

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.all { it }) onAllGranted()
    }

    val denied = permissions.filter {
        ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DeepCharcoal),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = SurfaceCharcoal)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Mic,
                    contentDescription = null,
                    tint = YellowTorch,
                    modifier = Modifier.size(56.dp)
                )
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.headlineLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "This app needs the following permissions to listen for voice commands and control the flashlight.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(4.dp))
                denied.forEach { perm ->
                    PermissionRow(permission = perm)
                }
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = { launcher.launch(denied.toTypedArray()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = YellowTorch)
                ) {
                    Text(
                        "Grant Permissions",
                        color = DeepCharcoal,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionRow(permission: String) {
    val label = when {
        permission.contains("RECORD_AUDIO")     -> "Microphone — for voice commands"
        permission.contains("CAMERA")           -> "Camera — for flashlight control"
        permission.contains("POST_NOTIFICATIONS") -> "Notifications — for background service"
        else -> permission.substringAfterLast(".")
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(CardSurface)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(YellowTorch)
        )
        Text(text = label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
    }
}

// =============================================================================
// Main content
// =============================================================================

@Composable
fun MainContent(
    uiState: FlashlightService.UiState,
    onSetMode: (ListeningMode) -> Unit,
    onToggleFlashlight: () -> Unit,
    onShutdown: () -> Unit
) {
    val bgColor by animateColorAsState(
        targetValue = if (uiState.flashlightOn) Color(0xFF2C2100) else DeepCharcoal,
        animationSpec = tween(600),
        label = "bg"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(bgColor, SurfaceCharcoal)
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Title bar
            AppHeader()

            // Flashlight orb
            FlashlightOrb(
                isOn = uiState.flashlightOn,
                onClick = onToggleFlashlight
            )

            // Status label
            StatusLabel(uiState = uiState)

            // Mode selector
            ModeSelector(
                currentMode = uiState.mode,
                onSetMode = onSetMode
            )

            // Shutdown button
            ShutdownButton(onClick = onShutdown)
        }
    }
}

// =============================================================================
// Composable sub-components
// =============================================================================

@Composable
private fun AppHeader() {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.FlashOn,
            contentDescription = null,
            tint = YellowTorch,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "Flashlight Voice",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun FlashlightOrb(isOn: Boolean, onClick: () -> Unit) {
    val scale by animateFloatAsState(
        targetValue = if (isOn) 1.08f else 1f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "scale"
    )
    val orbColor by animateColorAsState(
        targetValue = if (isOn) YellowTorch else CardSurface,
        animationSpec = tween(500),
        label = "orbColor"
    )
    val glowColor by animateColorAsState(
        targetValue = if (isOn) YellowTorchDim.copy(alpha = 0.35f) else Color.Transparent,
        animationSpec = tween(600),
        label = "glowColor"
    )
    val iconTint by animateColorAsState(
        targetValue = if (isOn) DeepCharcoal else Color.White.copy(alpha = 0.4f),
        animationSpec = tween(400),
        label = "iconTint"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .scale(scale)
            .size(200.dp)
            .clip(CircleShape)
            .background(
                Brush.radialGradient(
                    colors = listOf(glowColor, Color.Transparent)
                )
            )
            .border(
                width = 2.dp,
                color = if (isOn) YellowTorch else Color.White.copy(alpha = 0.12f),
                shape = CircleShape
            )
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(160.dp)
                .clip(CircleShape)
                .background(orbColor)
        ) {
            Icon(
                imageVector = if (isOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = if (isOn) "Turn flashlight off" else "Turn flashlight on",
                tint = iconTint,
                modifier = Modifier.size(72.dp)
            )
        }
    }
}

@Composable
private fun StatusLabel(uiState: FlashlightService.UiState) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val (label, color) = when {
            uiState.flashlightOn -> "FLASHLIGHT ON" to OnGreen
            else                 -> "FLASHLIGHT OFF" to OffRed
        }
        Text(
            text = label,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color,
            letterSpacing = 3.sp
        )
        Spacer(Modifier.height(6.dp))

        val (micLabel, micColor) = when (uiState.mode) {
            ListeningMode.ACTIVE      -> "Active Listening" to Passive
            ListeningMode.PASSIVE     -> "Passive Monitoring" to YellowTorchDim
            ListeningMode.DEACTIVATED -> "Voice Control Off" to Color.White.copy(alpha = 0.4f)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (uiState.mode == ListeningMode.DEACTIVATED)
                    Icons.Default.MicOff else Icons.Default.Mic,
                contentDescription = null,
                tint = micColor,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = micLabel,
                color = micColor,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun ModeSelector(
    currentMode: ListeningMode,
    onSetMode: (ListeningMode) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "LISTENING MODE",
            color = Color.White.copy(alpha = 0.5f),
            style = MaterialTheme.typography.labelLarge,
            letterSpacing = 2.sp
        )
        Spacer(Modifier.height(12.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = CardSurface)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ModeTab(
                    label = "Active",
                    description = "Always on",
                    selected = currentMode == ListeningMode.ACTIVE,
                    selectedColor = Passive,
                    onClick = { onSetMode(ListeningMode.ACTIVE) },
                    modifier = Modifier.weight(1f)
                )
                ModeTab(
                    label = "Passive",
                    description = "Save battery",
                    selected = currentMode == ListeningMode.PASSIVE,
                    selectedColor = YellowTorch,
                    onClick = { onSetMode(ListeningMode.PASSIVE) },
                    modifier = Modifier.weight(1f)
                )
                ModeTab(
                    label = "Off",
                    description = "Mic released",
                    selected = currentMode == ListeningMode.DEACTIVATED,
                    selectedColor = OffRed.copy(alpha = 0.8f),
                    onClick = { onSetMode(ListeningMode.DEACTIVATED) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        val hint = when (currentMode) {
            ListeningMode.ACTIVE  ->
                "Say \"turn on\", \"turn off\", or \"shut down\""
            ListeningMode.PASSIVE ->
                "Wakes on sound — then checks for commands"
            ListeningMode.DEACTIVATED ->
                "Microphone is fully released"
        }
        Text(
            text = hint,
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ModeTab(
    label: String,
    description: String,
    selected: Boolean,
    selectedColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) selectedColor else Color.Transparent,
        animationSpec = tween(300),
        label = "tabBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) DeepCharcoal else Color.White.copy(alpha = 0.55f),
        animationSpec = tween(300),
        label = "tabText"
    )

    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = bgColor,
        contentColor = textColor
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                color = textColor
            )
            Text(
                text = description,
                fontSize = 10.sp,
                color = textColor.copy(alpha = 0.75f)
            )
        }
    }
}

@Composable
private fun ShutdownButton(onClick: () -> Unit) {
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = SurfaceCharcoal,
            icon = {
                Icon(Icons.Default.PowerOff, contentDescription = null, tint = OffRed)
            },
            title = {
                Text("Shut Down?", color = Color.White, fontWeight = FontWeight.Bold)
            },
            text = {
                Text(
                    "This will stop the background service, release the microphone, " +
                    "turn off the flashlight, and close the app.",
                    color = Color.White.copy(alpha = 0.7f)
                )
            },
            confirmButton = {
                Button(
                    onClick = { showConfirm = false; onClick() },
                    colors = ButtonDefaults.buttonColors(containerColor = OffRed)
                ) { Text("Shut Down", fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }

    OutlinedButton(
        onClick = { showConfirm = true },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, OffRed.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = OffRed)
    ) {
        Icon(Icons.Default.PowerOff, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            "Shut Down App",
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(vertical = 6.dp)
        )
    }
}
