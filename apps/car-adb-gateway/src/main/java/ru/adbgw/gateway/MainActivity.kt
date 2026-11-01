package ru.adbgw.gateway

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        GatewayRepository.initialize(this)
        val initial = GatewayRepository.state.value
        if (initial.registration != null && initial.enabled) GatewayService.start(this)
        setContent { GatewayTheme { GatewayApp() } }
    }
}

/**
 * Wires the stateless [GatewayScreen] to the repository: which overlay is up, the code being
 * typed, the two full-access warnings (CAG-013) in front of enrollment and of every pairing code,
 * and the notification permission once the car is enrolled.
 */
@Composable
private fun GatewayApp() {
    val context = LocalContext.current
    val state by GatewayRepository.state.collectAsState()
    val now by currentTime()
    val scope = rememberCoroutineScope()
    var inviteCode by rememberSaveable { mutableStateOf("") }
    var enrollmentError by rememberSaveable { mutableStateOf<String?>(null) }
    var showEnrollmentWarning by remember { mutableStateOf(false) }
    var showPairingWarning by remember { mutableStateOf(false) }
    var showDisableConfirmation by remember { mutableStateOf(false) }
    var showSupport by remember { mutableStateOf(false) }
    var hiddenPairing by remember { mutableStateOf<PairingKey?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    LaunchedEffect(Unit) {
        if (!state.isEnrolled) GatewayRepository.prepareAdb()
    }
    LaunchedEffect(state.isEnrolled) {
        val notificationGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (shouldRequestNotificationPermission(
                sdkInt = Build.VERSION.SDK_INT,
                isEnrolled = state.isEnrolled,
                permissionGranted = notificationGranted,
            )
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val pairing = state.pairingWindow?.takeIf { PairingKey.of(it) != hiddenPairing }
    val overlay = when {
        showSupport -> GatewayOverlay.Support
        showDisableConfirmation -> GatewayOverlay.DisableConfirm
        showPairingWarning -> GatewayOverlay.PairWarning
        showEnrollmentWarning -> GatewayOverlay.EnrollWarning
        pairing != null -> GatewayOverlay.PairCode(pairing)
        else -> null
    }
    val actions = GatewayActions(
        onCodeChange = {
            inviteCode = normalizeCodeInput(it)
            enrollmentError = null
        },
        onRetryAdb = { scope.launch { GatewayRepository.prepareAdb() } },
        onEnroll = { showEnrollmentWarning = true },
        onConfirmEnroll = {
            showEnrollmentWarning = false
            enrollmentError = null
            scope.launch {
                val result = GatewayRepository.enroll(inviteCode)
                if (result.isFailure) {
                    enrollmentError = GatewayRepository.state.value.message
                        ?: "Не удалось подключить машину"
                }
            }
        },
        onPair = { showPairingWarning = true },
        onConfirmPair = {
            showPairingWarning = false
            // An expired code stays hidden while its replacement is fetched; anything else shows
            // again, as before.
            val current = GatewayRepository.state.value.pairingWindow
            hiddenPairing = current
                ?.takeIf { !it.isActive(System.currentTimeMillis() / 1_000) }
                ?.let(PairingKey::of)
            scope.launch { GatewayRepository.requestPairing() }
        },
        onClosePairing = { pairing?.let { hiddenPairing = PairingKey.of(it) } },
        onNewCode = {
            pairing?.let { hiddenPairing = PairingKey.of(it) }
            showPairingWarning = true
        },
        onDisable = { showDisableConfirmation = true },
        onConfirmDisable = {
            showDisableConfirmation = false
            scope.launch { GatewayRepository.disableRemoteAccess() }
        },
        onEnable = { scope.launch { GatewayRepository.enableRemoteAccess() } },
        onSupport = { showSupport = true },
        onDismiss = {
            when {
                showSupport -> showSupport = false
                showDisableConfirmation -> showDisableConfirmation = false
                showPairingWarning -> showPairingWarning = false
                showEnrollmentWarning -> showEnrollmentWarning = false
            }
        },
    )

    GatewayScreen(
        state = state,
        view = GatewayViewState(code = inviteCode, enrollmentError = enrollmentError, overlay = overlay),
        nowMillis = now,
        actions = actions,
    )
}

/**
 * Which pairing window the driver closed: its request and its expiry, so a fresh code issued for
 * the same request is shown again.
 */
private data class PairingKey(val requestId: String, val expiresAtEpochSeconds: Long) {
    companion object {
        fun of(window: PairingWindow) = PairingKey(window.requestId, window.expiresAtEpochSeconds)
    }
}

@Composable
private fun currentTime() = produceState(initialValue = System.currentTimeMillis()) {
    while (true) {
        value = System.currentTimeMillis()
        delay(1_000)
    }
}

internal fun shouldRequestNotificationPermission(
    sdkInt: Int,
    isEnrolled: Boolean,
    permissionGranted: Boolean,
): Boolean = sdkInt >= 33 && isEnrolled && !permissionGranted

internal fun normalizeCodeInput(value: String): String = value
    .filter(Char::isLetterOrDigit)
    .uppercase()
    .take(8)
