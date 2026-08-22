package com.foxtrader.app.feature.mt4.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.foxtrader.app.domain.model.Mt4AccountProfile
import com.foxtrader.app.domain.model.Mt4Broker
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
            text = "Broker Connection",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = FoxAmber50,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Connect MT4 / MT5 accounts via MetaApi",
            style = MaterialTheme.typography.bodyMedium,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Requires a MetaApi token configured in Settings",
            style = MaterialTheme.typography.bodySmall,
            color = FoxNeutral60,
        )

        Spacer(Modifier.height(20.dp))

        PlatformSelector(
            platform = disconnected?.platform ?: "mt4",
            enabled = !isConnecting,
            onPlatformChange = viewModel::onPlatformChange,
        )

        if (!disconnected?.savedAccounts.isNullOrEmpty()) {
            Spacer(Modifier.height(16.dp))
            SavedAccountSection(
                accounts = disconnected?.savedAccounts.orEmpty(),
                enabled = !isConnecting,
                onSelect = viewModel::onSavedAccountSelected,
                onRemove = viewModel::removeSavedAccount,
            )
        }

        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = disconnected?.login.orEmpty(),
            onValueChange = viewModel::onLoginChange,
            label = { Text("Trading Login") },
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

        Spacer(Modifier.height(12.dp))

        // Broker search — auto-fills the exact server string MetaApi needs.
        BrokerSearchSection(
            query = disconnected?.brokerQuery.orEmpty(),
            results = disconnected?.brokerResults.orEmpty(),
            enabled = !isConnecting,
            onQueryChange = viewModel::searchBrokers,
            onBrokerSelected = viewModel::onBrokerSelected,
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

/**
 * Searchable directory of known MT4 brokers. Typing in the field filters the
 * list by broker name or server; tapping a result fills the server field with
 * that broker's exact server string.
 */
@Composable
private fun PlatformSelector(
    platform: String,
    enabled: Boolean,
    onPlatformChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Platform", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            listOf("mt4" to "MT4", "mt5" to "MT5 / Deriv").forEach { (value, label) ->
                val selected = platform.equals(value, ignoreCase = true)
                if (selected) {
                    Button(
                        onClick = { onPlatformChange(value) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = FoxAmber50),
                    ) { Text(label, color = MaterialTheme.colorScheme.onPrimary) }
                } else {
                    OutlinedButton(
                        onClick = { onPlatformChange(value) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    ) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun SavedAccountSection(
    accounts: List<Mt4AccountProfile>,
    enabled: Boolean,
    onSelect: (Mt4AccountProfile) -> Unit,
    onRemove: (Mt4AccountProfile) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Saved accounts", style = MaterialTheme.typography.labelLarge)
        Text(
            "Passwords are never saved. Select a profile, then enter the password.",
            style = MaterialTheme.typography.bodySmall,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(6.dp))
        accounts.forEach { profile ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 3.dp)
                    .clickable(enabled = enabled) { onSelect(profile) },
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            profile.displayName.ifBlank { "Account ${profile.login}" },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            "${profile.platform.uppercase()} · ${profile.login} · ${profile.server}",
                            style = MaterialTheme.typography.bodySmall,
                            color = FoxNeutral60,
                        )
                    }
                    TextButton(onClick = { onRemove(profile) }, enabled = enabled) { Text("Remove") }
                }
            }
        }
    }
}

@Composable
private fun BrokerSearchSection(
    query: String,
    results: List<Mt4Broker>,
    enabled: Boolean,
    onQueryChange: (String) -> Unit,
    onBrokerSelected: (Mt4Broker) -> Unit,
) {
    // Run a search on first composition so the directory is populated even when
    // the field is untouched.
    LaunchedEffect(Unit) { onQueryChange(query) }

    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        label = { Text("Search broker") },
        placeholder = { Text("e.g. Deriv, IC Markets, Exness") },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Done,
        ),
    )

    if (results.isNotEmpty()) {
        Spacer(Modifier.height(4.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                items(results, key = { it.name }) { broker ->
                    BrokerRow(
                        broker = broker,
                        onClick = { onBrokerSelected(broker) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }
}

@Composable
private fun BrokerRow(
    broker: Mt4Broker,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = broker.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            if (broker.description.isNotBlank()) {
                Text(
                    text = broker.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = FoxNeutral60,
                )
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = broker.servers.firstOrNull() ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = FoxAmber50,
                fontWeight = FontWeight.Medium,
            )
            if (broker.country.isNotBlank()) {
                Text(
                    text = broker.country,
                    style = MaterialTheme.typography.labelSmall,
                    color = FoxNeutral60,
                )
            }
        }
    }
}
