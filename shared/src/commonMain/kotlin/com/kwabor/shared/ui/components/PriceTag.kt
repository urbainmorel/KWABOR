package com.kwabor.shared.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.KwaborStrings

@Composable
fun PriceTag(price: MoneyXof?, strings: KwaborStrings, currency: KwaborCurrency = KwaborCurrency.Xof) {
    val label = when {
        price == null -> strings.free
        currency == KwaborCurrency.Xof -> "${price.amount} ${currency.symbol}"
        else -> "≈ ${price.amount} ${currency.symbol}"
    }

    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
    )
}
