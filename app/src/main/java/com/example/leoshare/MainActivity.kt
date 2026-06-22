package com.example.leoshare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.leoshare.ui.theme.LeoShareTheme
import kotlinx.coroutines.delay

/**
 * MainActivity.kt — GhostShare entry point.
 *
 * Two-step setup:
 *  1. SYSTEM_ALERT_WINDOW  → "Display over other apps"
 *  2. AccessibilityService → "Settings → Accessibility → GhostShare"
 *
 * Once both are granted, starts FloatingTriggerService.
 * No MediaProjection dialog — ever.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LeoShareTheme {
                GhostShareHomeScreen(
                    onStartFloatingService = {
                        startForegroundService(
                            Intent(this, FloatingTriggerService::class.java)
                        )
                    },
                    onStopFloatingService = {
                        stopService(Intent(this, FloatingTriggerService::class.java))
                    }
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Helper: check if our AccessibilityService is enabled
// ─────────────────────────────────────────────────────────────────────────────

fun isAccessibilityServiceEnabled(context: Context): Boolean {
    val expectedComponent = "${context.packageName}/.SnippetAccessibilityService"
    val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabledServices.split(":").any { it.equals(expectedComponent, ignoreCase = true) }
}

// ─────────────────────────────────────────────────────────────────────────────
// Main Compose UI
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun GhostShareHomeScreen(
    onStartFloatingService: () -> Unit,
    onStopFloatingService: () -> Unit
) {
    val activity = androidx.compose.ui.platform.LocalContext.current as Activity

    // ── Permission states (re-checked every time we return to this screen) ────
    var hasOverlayPermission      by remember { mutableStateOf(false) }
    var hasAccessibilityPermission by remember { mutableStateOf(false) }
    var isActive                   by remember { mutableStateOf(false) }

    // Poll permissions continuously while screen is visible so cards update
    // when the user comes back from Settings without needing a button press.
    LaunchedEffect(Unit) {
        while (true) {
            hasOverlayPermission       = Settings.canDrawOverlays(activity)
            hasAccessibilityPermission = isAccessibilityServiceEnabled(activity)

            // Auto-start when both are granted and not already active
            if (hasOverlayPermission && hasAccessibilityPermission && !isActive) {
                isActive = true
                onStartFloatingService()
            }
            delay(500)
        }
    }

    // ── Activity result launchers ─────────────────────────────────────────────

    // (A) Overlay permission — opens system settings, checks on return
    val overlayLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasOverlayPermission = Settings.canDrawOverlays(activity)
    }

    // (B) Accessibility settings — opens the global Accessibility page
    val accessibilityLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        hasAccessibilityPermission = isAccessibilityServiceEnabled(activity)
    }

    // ── Background ────────────────────────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0D0D1A), Color(0xFF12122A), Color(0xFF0D0D1A))
                )
            )
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {

            // ── Logo ──────────────────────────────────────────────────────────
            AppLogoSection()
            Spacer(modifier = Modifier.height(28.dp))

            // ── Title ─────────────────────────────────────────────────────────
            Text(
                "GhostShare",
                color      = Color.White,
                fontSize   = 32.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )
            Text(
                "Capture  •  Crop  •  Share",
                color    = Color(0xFF8888AA),
                fontSize = 13.sp,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(40.dp))

            // ── Step 1: Overlay permission ────────────────────────────────────
            AnimatedVisibility(
                visible = true,
                enter   = fadeIn(tween(400)) + slideInVertically(tween(400)) { it }
            ) {
                PermissionCard(
                    step        = "1",
                    emoji       = "🪟",
                    title       = "Display Over Other Apps",
                    description = "Shows the floating ✂️ button while you use other apps.",
                    isGranted   = hasOverlayPermission,
                    actionLabel = "Grant",
                    onAction    = {
                        overlayLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${activity.packageName}")
                            )
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // ── Step 2: Accessibility permission ─────────────────────────────
            AnimatedVisibility(
                visible = true,
                enter   = fadeIn(tween(600)) + slideInVertically(tween(600)) { it }
            ) {
                PermissionCard(
                    step        = "2",
                    emoji       = "♿",
                    title       = "Accessibility Access",
                    description = "Lets GhostShare silently capture the screen — no pop-up every time.",
                    isGranted   = hasAccessibilityPermission,
                    actionLabel = if (hasOverlayPermission) "Enable" else "Step 1 first",
                    onAction    = {
                        if (hasOverlayPermission) {
                            accessibilityLauncher.launch(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(30.dp))

            // ── Active banner / Stop button ───────────────────────────────────
            if (isActive) {
                ActiveStatusBanner(onStop = {
                    isActive = false
                    onStopFloatingService()
                })
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Reusable composables
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun AppLogoSection() {
    Box(contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .clip(CircleShape)
                .background(
                    Brush.radialGradient(listOf(Color(0x554FC3F7), Color.Transparent))
                )
        )
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(listOf(Color(0xFF2979FF), Color(0xFF00BCD4)))
                ),
            contentAlignment = Alignment.Center
        ) {
            Text("✂️", fontSize = 28.sp)
        }
    }
}

@Composable
private fun PermissionCard(
    step: String,
    emoji: String,
    title: String,
    description: String,
    isGranted: Boolean,
    actionLabel: String,
    onAction: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(
            containerColor = if (isGranted) Color(0xFF0E2D1A) else Color(0xFF1A1A2E)
        ),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Step badge
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (isGranted) Color(0xFF2E7D32) else Color(0xFF2979FF)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (isGranted) "✓" else emoji,
                    color      = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize   = 16.sp
                )
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                Text(title,       color = Color.White,        fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                Spacer(Modifier.height(2.dp))
                Text(description, color = Color(0xFF8888AA),  fontSize = 12.sp)
            }

            if (!isGranted) {
                Button(
                    onClick = onAction,
                    shape   = RoundedCornerShape(50),
                    colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF2979FF)),
                    modifier = Modifier.height(36.dp)
                ) {
                    Text(actionLabel, fontSize = 11.sp, color = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ActiveStatusBanner(onStop: () -> Unit) {
    val scale by animateFloatAsState(1f, tween(600), label = "scale")

    Card(
        modifier = Modifier.fillMaxWidth().scale(scale),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = Color(0xFF0E2D1A))
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF66BB6A))
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    "GhostShare is active",
                    color      = Color(0xFF66BB6A),
                    fontWeight = FontWeight.Bold,
                    fontSize   = 16.sp
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "The floating ✂️ button is visible over other apps.\nTap it anywhere to capture and crop your screen.",
                color    = Color(0xFF8888AA),
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = onStop,
                shape   = RoundedCornerShape(50),
                colors  = ButtonDefaults.buttonColors(containerColor = Color(0xFF4A1010))
            ) {
                Text("Stop GhostShare", color = Color(0xFFFF6E6E))
            }
        }
    }
}
