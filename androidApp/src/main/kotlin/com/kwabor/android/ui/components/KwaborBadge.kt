package com.kwabor.android.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.android.design.KwaborColors
import com.kwabor.android.design.KwaborRadius
import com.kwabor.android.design.KwaborSpacing
import com.kwabor.shared.i18n.KwaborStrings

@Composable
fun SponsoredBadge(strings: KwaborStrings, modifier: Modifier = Modifier) {
    Text(
        text = strings.sponsored,
        modifier = modifier
            .background(
                color = KwaborColors.Sponsored,
                shape = RoundedCornerShape(KwaborRadius.Pill),
            )
            .padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Xs),
        color = KwaborColors.Ink950,
        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
    )
}
