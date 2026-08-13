package com.foxtrader.app.feature.onboarding.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.foxtrader.app.ui.components.FoxButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.foxtrader.app.R
import com.foxtrader.app.ui.theme.FoxAmber50
import com.foxtrader.app.ui.theme.FoxNeutral60

/**
 * First-run educational-tool disclaimer gate.
 *
 * Surfaced full-screen before any analysis on first launch (see the gate in
 * MainActivity) and cannot be bypassed — the only way forward is to
 * acknowledge it. Acknowledgment is persisted so the screen is shown once.
 *
 * This closes ENTERPRISE_MASTER_PLAN T5.3's DoD ("disclaimer surfaced before
 * first analysis"); the same text also lives passively in Settings → Privacy.
 */
@Composable
fun DisclaimerScreen(
    onAcknowledge: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = FoxAmber50,
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.disclaimer_title),
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(R.string.disclaimer_subtitle),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = FoxAmber50,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.disclaimer_body),
            style = MaterialTheme.typography.bodyMedium,
            color = FoxNeutral60,
        )
        Spacer(Modifier.height(20.dp))

        DisclaimerPoint(stringResource(R.string.disclaimer_point_not_advice))
        DisclaimerPoint(stringResource(R.string.disclaimer_point_no_trades))
        DisclaimerPoint(stringResource(R.string.disclaimer_point_simulated))
        DisclaimerPoint(stringResource(R.string.disclaimer_point_risk))
        DisclaimerPoint(stringResource(R.string.disclaimer_point_local))

        Spacer(Modifier.height(28.dp))
        FoxButton(
            text = stringResource(R.string.disclaimer_acknowledge),
            onClick = onAcknowledge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.disclaimer_settings_note),
            style = MaterialTheme.typography.bodySmall,
            color = FoxNeutral60,
        )
    }
}

@Composable
private fun DisclaimerPoint(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "•",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = FoxAmber50,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
    }
}
