package com.foxtrader.app.feature.auth.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral60
import kotlinx.coroutines.launch

/**
 * Full-screen lock gate shown when biometric App Lock is enabled and the app
 * has not yet been unlocked this session.
 *
 * On first composition it invokes [onAuthenticate] (a suspend biometric call
 * hosted by the Activity). The button lets the user retry if they cancel or
 * fail. On success the host clears the lock and shows the app.
 */
@Composable
fun LockScreen(
    onAuthenticate: suspend () -> Boolean,
    onUnlock: () -> Unit,
) {
    // Trigger the prompt automatically once when the lock screen appears.
    LaunchedEffect(Unit) {
        if (onAuthenticate()) onUnlock()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Lock,
            contentDescription = null,
            tint = FoxAmber50,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "FoxTrader is locked",
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Authenticate to continue",
            style = MaterialTheme.typography.bodyMedium,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(32.dp))
        UnlockButton(onAuthenticate = onAuthenticate, onUnlock = onUnlock)
    }
}

@Composable
private fun UnlockButton(
    onAuthenticate: suspend () -> Boolean,
    onUnlock: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Button(
        onClick = {
            scope.launch {
                if (onAuthenticate()) onUnlock()
            }
        },
        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
    ) {
        Text("Unlock", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
    }
}
