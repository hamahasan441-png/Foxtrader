package com.foxtrader.app.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.foxtrader.app.ui.theme.FoxTheme

enum class FoxButtonStyle { Primary, Secondary, Ghost, Danger }

@Composable
fun FoxButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: FoxButtonStyle = FoxButtonStyle.Primary,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    contentDescription: String? = null,
) {
    val colors = FoxTheme.colors
    val spacing = FoxTheme.spacing
    val height = Modifier.heightIn(min = spacing.touch)
    val spokenDescription = contentDescription
    val content: @Composable () -> Unit = {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(spacing.xs))
        }
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }

    when (style) {
        FoxButtonStyle.Primary -> Button(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(height).semantics {
                role = Role.Button
                if (spokenDescription != null) {
                    this.contentDescription = spokenDescription
                }
            },
            shape = RoundedCornerShape(FoxTheme.shapes.sm),
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.onAccent,
                disabledContainerColor = colors.surfaceStrong,
                disabledContentColor = colors.textMuted,
            ),
            contentPadding = PaddingValues(horizontal = spacing.lg, vertical = spacing.sm),
        ) { content() }

        FoxButtonStyle.Secondary -> FilledTonalButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(height),
            shape = RoundedCornerShape(FoxTheme.shapes.sm),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = colors.surfaceStrong,
                contentColor = colors.textPrimary,
            ),
        ) { content() }

        FoxButtonStyle.Ghost -> TextButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(height),
            colors = ButtonDefaults.textButtonColors(contentColor = colors.accent),
        ) { content() }

        FoxButtonStyle.Danger -> OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            modifier = modifier.then(height),
            shape = RoundedCornerShape(FoxTheme.shapes.sm),
            border = BorderStroke(1.dp, colors.danger.copy(alpha = 0.5f)),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.danger),
        ) { content() }
    }
}

@Composable
fun FoxIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tintActive: Boolean = false,
    enabled: Boolean = true,
) {
    val colors = FoxTheme.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.size(FoxTheme.spacing.iconButton),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = when {
                !enabled -> colors.textMuted.copy(alpha = 0.45f)
                tintActive -> colors.accent
                else -> colors.textMuted
            },
            modifier = Modifier.size(20.dp),
        )
    }
}
