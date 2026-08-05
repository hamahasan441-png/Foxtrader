package com.foxtrader.app.feature.auth.presentation

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.foxtrader.app.R
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxBearishText
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * Login / Register screen.
 *
 * Reachable from Settings → Account. Authentication is OPTIONAL (offline-first);
 * this screen only enables cloud sync/backup. Toggles between LOGIN and REGISTER
 * modes. On success, invokes [onAuthenticated] so the caller can navigate back.
 */
@Composable
fun LoginScreen(
    onAuthenticated: () -> Unit,
    onDismiss: () -> Unit,
    viewModel: AuthViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.success) {
        if (state.success) {
            viewModel.consumeSuccess()
            onAuthenticated()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.app_name),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = FoxAmber50,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (state.mode == AuthMode.LOGIN) stringResource(R.string.auth_sign_in_subtitle) else stringResource(R.string.auth_create_account_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.auth_offline_note),
            style = MaterialTheme.typography.bodySmall,
            color = FoxNeutral60,
        )

        Spacer(Modifier.height(32.dp))

        if (state.mode == AuthMode.REGISTER) {
            OutlinedTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = { Text(stringResource(R.string.auth_label_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = { Text(stringResource(R.string.auth_label_email)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = { Text(stringResource(R.string.auth_label_password)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done,
            ),
        )

        if (state.error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = state.error ?: "",
                color = FoxBearishText,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = viewModel::submit,
            enabled = state.canSubmit,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
        ) {
            if (state.isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp,
                )
            } else {
                Text(
                    text = if (state.mode == AuthMode.LOGIN) stringResource(R.string.auth_action_sign_in) else stringResource(R.string.auth_action_create_account),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        TextButton(onClick = viewModel::toggleMode) {
            Text(
                text = if (state.mode == AuthMode.LOGIN) {
                    stringResource(R.string.auth_switch_to_register)
                } else {
                    stringResource(R.string.auth_switch_to_login)
                },
                color = FoxAmber50,
            )
        }

        TextButton(onClick = onDismiss) {
            Text(stringResource(R.string.auth_continue_offline), color = FoxNeutral60)
        }
    }
}
