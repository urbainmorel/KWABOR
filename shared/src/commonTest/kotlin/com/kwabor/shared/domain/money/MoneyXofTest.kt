package com.kwabor.shared.domain.money

import com.kwabor.shared.domain.core.DomainResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MoneyXofTest {
    @Test
    fun fromAmount_acceptsZero() {
        val result = MoneyXof.fromAmount(amount = 0)

        val success = assertIs<DomainResult.Success<MoneyXof>>(result)
        assertEquals(0, success.value.amount)
    }

    @Test
    fun fromAmount_rejectsNegativeAmounts() {
        val result = MoneyXof.fromAmount(amount = -1)

        assertIs<DomainResult.Failure>(result)
    }
}
