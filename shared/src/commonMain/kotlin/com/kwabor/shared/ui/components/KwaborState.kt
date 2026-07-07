package com.kwabor.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.shared.design.KwaborColors
import com.kwabor.shared.design.KwaborRadius
import com.kwabor.shared.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings

@Composable
fun KwaborStateMessage(
    title: String,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(KwaborRadius.Card),
            )
            .padding(KwaborSpacing.Xxl),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        supportingText?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
        if (actionLabel != null && onAction != null) {
            Button(onClick = onAction) {
                Text(text = actionLabel)
            }
        }
    }
}

@Composable
fun KwaborLoadingState(strings: KwaborStrings, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(KwaborSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        Text(text = strings.loading, style = MaterialTheme.typography.bodyLarge)
        KwaborSkeletonLine(widthFraction = 1f)
        KwaborSkeletonLine(widthFraction = 0.72f)
        KwaborSkeletonLine(widthFraction = 0.48f)
    }
}

@Composable
fun KwaborSkeletonLine(widthFraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth(widthFraction.coerceIn(minimumValue = 0.1f, maximumValue = 1f))
            .height(KwaborSpacing.Xxl)
            .background(
                color = KwaborColors.Ink100,
                shape = RoundedCornerShape(KwaborRadius.Control),
            ),
    )
}

@Composable
fun KwaborSkeletonCard(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(KwaborRadius.Card),
            )
            .padding(KwaborSpacing.Lg),
        verticalArrangement = Arrangement.spacedBy(KwaborSpacing.Md),
    ) {
        KwaborSkeletonLine(widthFraction = 1f)
        KwaborSkeletonLine(widthFraction = 0.86f)
        KwaborSkeletonLine(widthFraction = 0.58f)
    }
}

@Composable
fun OfflineBanner(strings: KwaborStrings, modifier: Modifier = Modifier) {
    Text(
        text = strings.offlineBanner,
        modifier = modifier
            .fillMaxWidth()
            .background(color = KwaborColors.Ink900)
            .padding(horizontal = KwaborSpacing.Lg, vertical = KwaborSpacing.Md),
        color = KwaborColors.Surface0,
        style = MaterialTheme.typography.labelLarge,
    )
}

@Composable
fun KwaborInlineBanner(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .background(color = MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = KwaborSpacing.Lg, vertical = KwaborSpacing.Md),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelLarge,
    )
}
