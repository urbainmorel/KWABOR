package com.kwabor.shared.presentation.money

import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.money.MoneyXof

private const val COMPACT_AMOUNT_THRESHOLD = 10_000L
private const val ONE_THOUSAND = 1_000L
private const val ONE_MILLION = 1_000_000L
private const val ONE_TENTH_MILLION = 100_000L
private const val HALF_TENTH_MILLION = 50_000L
private const val DECIMAL_BASE = 10L
private const val WHOLE_NUMBER_GROUP_SIZE = 3

object PriceLabelFormatter {
    fun compactXof(price: MoneyXof?, freeLabel: String): String {
        val amount = price?.amount ?: return freeLabel
        if (amount == 0L) return freeLabel
        if (amount < COMPACT_AMOUNT_THRESHOLD) return fullXof(price)
        if (amount < ONE_MILLION) return "${amount / ONE_THOUSAND} k ${KwaborCurrency.Xof.symbol}"
        if (amount % ONE_MILLION == 0L) {
            return "${amount / ONE_MILLION} M ${KwaborCurrency.Xof.symbol}"
        }

        val unroundedTenths = amount / ONE_TENTH_MILLION
        val remainder = amount % ONE_TENTH_MILLION
        val roundsUp = remainder > HALF_TENTH_MILLION ||
            (remainder == HALF_TENTH_MILLION && unroundedTenths % 2L != 0L)
        val roundedTenths = unroundedTenths + if (roundsUp) 1L else 0L
        return "${roundedTenths / DECIMAL_BASE},${roundedTenths % DECIMAL_BASE} M " +
            KwaborCurrency.Xof.symbol
    }

    fun fullXof(price: MoneyXof): String = "${price.amount.formatWholeNumber()} ${KwaborCurrency.Xof.symbol}"
}

private fun Long.formatWholeNumber(): String = toString()
    .reversed()
    .chunked(size = WHOLE_NUMBER_GROUP_SIZE)
    .joinToString(separator = " ")
    .reversed()
