package com.foxtrader.app.feature.mt4.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * MT4 Login screen for connecting to a broker account via MetaApi.
 *
 * Follows the same composition pattern as [com.foxtrader.app.feature.auth.presentation.LoginScreen]:
 * OutlinedTextFields for credentials, a primary Button with loading indicator, and error display.
 *
 * @param onConnected Callback invoked when connection succeeds (navigate to account screen).
 * @param onDismiss Callback to navigate back without connecting.
 */
@Composable
fun Mt4LoginScreen(
    onConnected: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: Mt4ViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Navigate to account screen when connected
    if (state is Mt4UiState.Connected) {
        onConnected()
        return
    }

    val disconnected = state as? Mt4UiState.Disconnected
    val isConnecting = state is Mt4UiState.Connecting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "MT4 Connection",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = FoxAmber50,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Connect your MT4 account via MetaApi",
            style = MaterialTheme.typography.bodyMedium,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Requires a MetaApi token configured in Settings",
            style = MaterialTheme.typography.bodySmall,
            color = FoxNeutral60,
        )

        Spacer(Modifier.height(32.dp))

        OutlinedTextField(
            value = disconnected?.login.orEmpty(),
            onValueChange = viewModel::onLoginChange,
            label = { Text("MT4 Login") },
            singleLine = true,
            enabled = !isConnecting,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Next,
            ),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = disconnected?.password.orEmpty(),
            onValueChange = viewModel::onPasswordChange,
            label = { Text("Password") },
            singleLine = true,
            enabled = !isConnecting,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Next,
            ),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = disconnected?.server.orEmpty(),
            onValueChange = viewModel::onServerChange,
            label = { Text("Server") },
            singleLine = true,
            enabled = !isConnecting,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
        )

        if (disconnected?.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = disconnected.error,
                color = FoxBearishText,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = viewModel::connect,
            enabled = disconnected?.canSubmit == true && !isConnecting,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = "Connect",
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = onDismiss) {
            Text("Cancel", color = FoxNeutral60)
        }
    }
}
