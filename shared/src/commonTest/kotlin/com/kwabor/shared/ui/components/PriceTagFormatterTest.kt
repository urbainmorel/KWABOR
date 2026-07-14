package com.kwabor.shared.ui.components

import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.KwaborCurrency
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals

class PriceTagFormatterTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun formatPriceTag_formatsFullXofWithGroupedThousands() {
        val label = formatPriceTag(price = money(150_000), strings = strings)

        assertEquals("150 000 FCFA", label)
    }

    @Test
    fun formatPriceTag_formatsCompactXofForDenseSurfaces() {
        val label = formatPriceTag(
            price = money(25_000),
            strings = strings,
            options = PriceTagOptions(mode = PriceTagMode.Compact),
        )

        assertEquals("25 k FCFA", label)
    }

    @Test
    fun formatPriceTag_keepsSmallAmountsReadableInCompactMode() {
        val label = formatPriceTag(
            price = money(5_000),
            strings = strings,
            options = PriceTagOptions(mode = PriceTagMode.Compact),
        )

        assertEquals("5 000 FCFA", label)
    }

    @Test
    fun formatPriceTag_formatsConvertedTransactionWithIndicativePrefix() {
        val label = formatPriceTag(
            price = money(5_000),
            strings = strings,
            options = PriceTagOptions(
                currency = KwaborCurrency.Eur,
                convertedAmount = 7.62,
                transactional = true,
            ),
        )

        assertEquals("≈ 7,62 €", label)
    }

    @Test
    fun formatPriceTag_neverCompactsTransactionalAmounts() {
        val label = formatPriceTag(
            price = money(30_000),
            strings = strings,
            options = PriceTagOptions(
                mode = PriceTagMode.Compact,
                transactional = true,
            ),
        )

        assertEquals("30 000 FCFA", label)
    }

    @Test
    fun formatPriceTag_usesFreeLabelForNullOrZero() {
        assertEquals(strings.free, formatPriceTag(price = null, strings = strings))
        assertEquals(strings.free, formatPriceTag(price = money(0), strings = strings))
    }

    private fun money(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
        is DomainResult.Success -> result.value
        is DomainResult.Failure -> error("Invalid test money")
    }
}
