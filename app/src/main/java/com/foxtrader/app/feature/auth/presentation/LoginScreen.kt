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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.foxtrader.app.ui.components.FoxButton
import com.foxtrader.app.ui.components.FoxButtonStyle
import com.foxtrader.app.ui.components.FoxTextField
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
            FoxTextField(
                value = state.displayName,
                onValueChange = viewModel::onDisplayNameChange,
                label = stringResource(R.string.auth_label_display_name),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )
            Spacer(Modifier.height(12.dp))
        }

        FoxTextField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            label = stringResource(R.string.auth_label_email),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Email,
                imeAction = ImeAction.Next,
            ),
        )

        Spacer(Modifier.height(12.dp))

        FoxTextField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(R.string.auth_label_password),
            visualTransformation = PasswordVisualTransformation(),
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

        if (state.isSubmitting) {
            CircularProgressIndicator(
                modifier = Modifier.height(20.dp),
                color = FoxAmber50,
                strokeWidth = 2.dp,
            )
        } else {
            FoxButton(
                text = if (state.mode == AuthMode.LOGIN) {
                    stringResource(R.string.auth_action_sign_in)
                } else {
                    stringResource(R.string.auth_action_create_account)
                },
                onClick = viewModel::submit,
                enabled = state.canSubmit,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(8.dp))

        FoxButton(
            text = if (state.mode == AuthMode.LOGIN) {
                stringResource(R.string.auth_switch_to_register)
            } else {
                stringResource(R.string.auth_switch_to_login)
            },
            onClick = viewModel::toggleMode,
            style = FoxButtonStyle.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )

        FoxButton(
            text = stringResource(R.string.auth_continue_offline),
            onClick = onDismiss,
            style = FoxButtonStyle.Ghost,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
