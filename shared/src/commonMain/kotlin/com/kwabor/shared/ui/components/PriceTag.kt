package com.kwabor.shared.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.kwabor.shared.design.KwaborColors
import com.kwabor.shared.design.KwaborRadius
import com.kwabor.shared.design.KwaborSpacing
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.KwaborStrings

@Composable
fun PriceTag(
    price: MoneyXof?,
    strings: KwaborStrings,
    currency: KwaborCurrency = KwaborCurrency.Xof,
    mode: PriceTagMode = PriceTagMode.Full,
    convertedAmount: Double? = null,
    transactional: Boolean = false,
) {
    val label = formatPriceTag(
        price = price,
        strings = strings,
        currency = currency,
        mode = mode,
        convertedAmount = convertedAmount,
        transactional = transactional,
    )

    Text(
        text = label,
        modifier = Modifier
            .background(
                color = KwaborColors.Ink100,
                shape = RoundedCornerShape(KwaborRadius.Pill),
            )
            .padding(horizontal = KwaborSpacing.Md, vertical = KwaborSpacing.Sm),
        style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

enum class PriceTagMode {
    Compact,
    Full,
}

internal fun formatPriceTag(
    price: MoneyXof?,
    strings: KwaborStrings,
    currency: KwaborCurrency = KwaborCurrency.Xof,
    mode: PriceTagMode = PriceTagMode.Full,
    convertedAmount: Double? = null,
    transactional: Boolean = false,
): String {
    if (price == null || price.amount == 0L) {
        return strings.free
    }

    if (currency != KwaborCurrency.Xof && convertedAmount != null) {
        return "≈ ${formatConvertedAmount(convertedAmount, currency, transactional)} ${currency.symbol}"
    }

    val effectiveMode = if (transactional) PriceTagMode.Full else mode
    return when (effectiveMode) {
        PriceTagMode.Compact -> formatCompactXof(price.amount)
        PriceTagMode.Full -> "${price.amount.formatWholeNumber()} ${KwaborCurrency.Xof.symbol}"
    }
}

private fun formatCompactXof(amount: Long): String {
    if (amount < 10_000L) {
        return "${amount.formatWholeNumber()} ${KwaborCurrency.Xof.symbol}"
    }

    if (amount < 1_000_000L) {
        return "${amount / 1_000L} k ${KwaborCurrency.Xof.symbol}"
    }

    val millions = amount.toDouble() / 1_000_000.0
    val formatted = if (amount % 1_000_000L == 0L) {
        (amount / 1_000_000L).toString()
    } else {
        millions.formatOneDecimal()
    }
    return "$formatted M ${KwaborCurrency.Xof.symbol}"
}

private fun formatConvertedAmount(amount: Double, currency: KwaborCurrency, transactional: Boolean): String = when {
    currency == KwaborCurrency.Usd || currency == KwaborCurrency.Eur -> {
        if (transactional) amount.formatTwoDecimals() else amount.roundToLong().formatWholeNumber()
    }
    else -> amount.roundToLong().formatWholeNumber()
}

private fun Long.formatWholeNumber(): String = toString()
    .reversed()
    .chunked(size = 3)
    .joinToString(separator = " ")
    .reversed()

private fun Double.formatOneDecimal(): String {
    val value = (this * 10).roundToLong()
    return "${value / 10},${value % 10}"
}

private fun Double.formatTwoDecimals(): String {
    val value = (this * 100).roundToLong()
    val cents = (value % 100).toString().padStart(length = 2, padChar = '0')
    return "${value / 100},$cents"
}

private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()
