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
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.KwaborStrings

private const val COMPACT_AMOUNT_THRESHOLD = 10_000L
private const val ONE_THOUSAND = 1_000L
private const val ONE_MILLION = 1_000_000L
private const val ONE_MILLION_DECIMAL = 1_000_000.0
private const val ONE_DECIMAL_SCALE = 10.0
private const val ONE_DECIMAL_DIVISOR = 10L
private const val TWO_DECIMAL_SCALE = 100.0
private const val TWO_DECIMAL_DIVISOR = 100L
private const val DECIMAL_WIDTH = 2
private const val WHOLE_NUMBER_GROUP_SIZE = 3

@Composable
fun PriceTag(price: MoneyXof?, strings: KwaborStrings, options: PriceTagOptions = PriceTagOptions()) {
    val label = formatPriceTag(
        price = price,
        strings = strings,
        options = options,
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

data class PriceTagOptions(
    val currency: KwaborCurrency = KwaborCurrency.Xof,
    val mode: PriceTagMode = PriceTagMode.Full,
    val convertedAmount: Double? = null,
    val transactional: Boolean = false,
)

internal fun formatPriceTag(
    price: MoneyXof?,
    strings: KwaborStrings,
    options: PriceTagOptions = PriceTagOptions(),
): String {
    if (price == null || price.amount == 0L) {
        return strings.free
    }

    if (options.currency != KwaborCurrency.Xof && options.convertedAmount != null) {
        return "≈ ${formatConvertedAmount(options.convertedAmount, options.currency, options.transactional)} " +
            options.currency.symbol
    }

    val effectiveMode = if (options.transactional) PriceTagMode.Full else options.mode
    return when (effectiveMode) {
        PriceTagMode.Compact -> formatCompactXof(price.amount)
        PriceTagMode.Full -> "${price.amount.formatWholeNumber()} ${KwaborCurrency.Xof.symbol}"
    }
}

private fun formatCompactXof(amount: Long): String {
    if (amount < COMPACT_AMOUNT_THRESHOLD) {
        return "${amount.formatWholeNumber()} ${KwaborCurrency.Xof.symbol}"
    }

    if (amount < ONE_MILLION) {
        return "${amount / ONE_THOUSAND} k ${KwaborCurrency.Xof.symbol}"
    }

    val millions = amount.toDouble() / ONE_MILLION_DECIMAL
    val formatted = if (amount % ONE_MILLION == 0L) {
        (amount / ONE_MILLION).toString()
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
    .chunked(size = WHOLE_NUMBER_GROUP_SIZE)
    .joinToString(separator = " ")
    .reversed()

private fun Double.formatOneDecimal(): String {
    val value = (this * ONE_DECIMAL_SCALE).roundToLong()
    return "${value / ONE_DECIMAL_DIVISOR},${value % ONE_DECIMAL_DIVISOR}"
}

private fun Double.formatTwoDecimals(): String {
    val value = (this * TWO_DECIMAL_SCALE).roundToLong()
    val cents = (value % TWO_DECIMAL_DIVISOR).toString().padStart(length = DECIMAL_WIDTH, padChar = '0')
    return "${value / TWO_DECIMAL_DIVISOR},$cents"
}

private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()
