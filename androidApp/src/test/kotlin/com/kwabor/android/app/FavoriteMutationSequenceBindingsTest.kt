package com.kwabor.android.app

import com.kwabor.android.presentation.explore.ExploreEffect
import com.kwabor.android.presentation.favorites.FavoritesEffect
import com.kwabor.shared.presentation.session.ViewerSessionScope
import kotlin.test.Test
import kotlin.test.assertEquals

class FavoriteMutationSequenceBindingsTest {
    private val scope = ViewerSessionScope(accountId = TEST_ACCOUNT_ID, epoch = 7L)

    @Test
    fun exploreChangeToFavoritesIntent_preservesExactSequenceAndScope() {
        val intent =
            ExploreEffect.FavoriteChanged(
                listingId = TEST_LISTING_ID,
                favorited = true,
                clientMutationSequence = TEST_CLIENT_MUTATION_SEQUENCE,
                scope = scope,
            ).toFavoritesIntent()

        assertEquals(TEST_LISTING_ID, intent.listingId)
        assertEquals(true, intent.favorited)
        assertEquals(TEST_CLIENT_MUTATION_SEQUENCE, intent.clientMutationSequence)
        assertEquals(scope, intent.scope)
    }

    @Test
    fun favoritesChangeToExploreIntent_preservesExactSequenceAndScope() {
        val intent =
            FavoritesEffect.FavoriteChanged(
                listingId = TEST_LISTING_ID,
                favorited = false,
                clientMutationSequence = TEST_CLIENT_MUTATION_SEQUENCE,
                scope = scope,
            ).toExploreIntent()

        assertEquals(TEST_LISTING_ID, intent.listingId)
        assertEquals(false, intent.favorited)
        assertEquals(TEST_CLIENT_MUTATION_SEQUENCE, intent.clientMutationSequence)
        assertEquals(scope, intent.scope)
    }
}

private const val TEST_ACCOUNT_ID = "00000000-0000-4000-8000-000000000001"
private const val TEST_LISTING_ID = "00000000-0000-4000-8000-000000000002"
private const val TEST_CLIENT_MUTATION_SEQUENCE = 4_294_967_297L
