package com.kwabor.shared.presentation.explore

import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingContact
import com.kwabor.shared.domain.catalog.ListingDetail
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingMedia
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingStatus
import com.kwabor.shared.domain.catalog.ListingSummary
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.ListingViewerInteraction
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.core.PageRequest
import com.kwabor.shared.domain.core.PageResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.stringsFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ExplorePresenterTest {
    private val strings = stringsFor(AppLocale.French)
    private val clockProvider = FixedClockProvider(nowEpochMilliseconds = 1_000L)

    @Test
    fun load_mapsPublishedCatalogListingsToReadOnlyExploreState() = runSuspendTest {
        val repository = FakeCatalogRepository(
            listings = listOf(
                listingSummary(
                    id = "ouidah-gate",
                    name = "Porte du non-retour",
                    cityId = "ouidah",
                    coverImageUrl = "https://example.invalid/cover.jpg",
                    ratingAverage = 4.74,
                    sponsoredUntilEpochMilliseconds = 2_000L,
                ),
            ),
        )
        val presenter = ExplorePresenter(repository, clockProvider)

        val state = presenter.load(
            request = ExploreLoadRequest(selectedTab = ExploreTab.Places, selectedChipId = "history"),
            strings = strings,
        )

        assertFalse(state.isLoading)
        assertFalse(state.hasError)
        assertEquals(strings.currentCity, state.cityLabel)
        assertEquals(ExploreTab.Places, state.selectedTab)
        assertEquals("history", state.selectedChipId)
        assertEquals(ListingType.Place, repository.lastFilters?.listingType)
        assertEquals("heritage-historique", repository.lastFilters?.categoryId)

        val listing = state.listings.single()
        assertEquals("ouidah-gate", listing.id)
        assertEquals("Porte du non-retour", listing.title)
        assertEquals("Ouidah", listing.cityLabel)
        assertEquals("https://example.invalid/cover.jpg", listing.coverImageUrl)
        assertEquals("4,7", listing.ratingLabel)
        assertTrue(listing.sponsored)
    }

    @Test
    fun load_keepsUnknownChipAsTabFilterOnly() = runSuspendTest {
        val repository = FakeCatalogRepository()
        val presenter = ExplorePresenter(repository, clockProvider)

        presenter.load(
            request = ExploreLoadRequest(selectedTab = ExploreTab.Events, selectedChipId = "concerts"),
            strings = strings,
        )

        assertEquals(ListingType.Event, repository.lastFilters?.listingType)
        assertEquals(null, repository.lastFilters?.categoryId)
    }

    @Test
    fun load_returnsOfflineErrorStateForNetworkFailure() = runSuspendTest {
        val repository = FakeCatalogRepository(listingsError = DomainError.NetworkUnavailable())
        val presenter = ExplorePresenter(repository, clockProvider)

        val state = presenter.load(request = ExploreLoadRequest(), strings = strings)

        assertTrue(state.hasError)
        assertTrue(state.isOffline)
        assertEquals(strings.offlineBanner, state.errorMessage)
        assertTrue(state.listings.isEmpty())
    }

    @Test
    fun load_marksExpiredCampaignAsNotSponsored() = runSuspendTest {
        val repository = FakeCatalogRepository(
            listings = listOf(
                listingSummary(sponsoredUntilEpochMilliseconds = 500L),
            ),
        )
        val presenter = ExplorePresenter(repository, clockProvider)

        val state = presenter.load(request = ExploreLoadRequest(), strings = strings)

        assertFalse(state.listings.single().sponsored)
    }
}

private class FakeCatalogRepository(
    private val cities: List<City> = listOf(
        City(id = "cotonou", name = "Cotonou"),
        City(id = "ouidah", name = "Ouidah"),
    ),
    private val categories: List<Category> = listOf(
        Category(
            id = "heritage-historique",
            nameKey = "category.heritage.historique",
            listingType = ListingType.Place,
            defaultListingClass = ListingClass.Heritage,
        ),
        Category(
            id = "commercial-hotel",
            nameKey = "category.commercial.hotel",
            listingType = ListingType.Establishment,
            defaultListingClass = ListingClass.Commercial,
        ),
    ),
    private val listings: List<ListingSummary> = listOf(listingSummary()),
    private val listingsError: DomainError? = null,
) : CatalogRepository {
    var lastFilters: ListingFilters? = null

    override suspend fun listCities(): DomainResult<List<City>> = DomainResult.Success(cities)

    override suspend fun listCategories(): DomainResult<List<Category>> = DomainResult.Success(categories)

    override suspend fun listListings(
        filters: ListingFilters,
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> {
        lastFilters = filters
        return listingsError?.let { error -> DomainResult.Failure(error) }
            ?: DomainResult.Success(PageResult(items = listings, nextOffset = null))
    }

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: PageRequest,
    ): DomainResult<PageResult<ListingSummary>> = DomainResult.Success(
        PageResult(items = emptyList(), nextOffset = null),
    )

    override suspend fun getListingDetail(listingId: String): DomainResult<ListingDetail> = DomainResult.Success(
        ListingDetail(
            summary = listingSummary(id = listingId),
            slug = listingId,
            description = "Description",
            contentLocale = AppLocale.French,
            district = null,
            address = null,
            geoPoint = null,
            contact = ListingContact(phone = null, whatsapp = null, externalUrl = null, email = null),
            media = listOf(
                ListingMedia(url = "https://example.invalid/image.jpg", alt = "Image", order = 0, isCover = true),
            ),
            tags = emptyList(),
            ownerId = null,
            stewardId = null,
            publishedAtEpochMilliseconds = null,
        ),
    )

    override suspend fun getListingViewerInteraction(listingId: String): DomainResult<ListingViewerInteraction> =
        DomainResult.Success(
            ListingViewerInteraction(listingId, likedByViewer = false, favoritedByViewer = false, likesCount = 0),
        )

    override suspend fun listListingViewerInteractions(
        listingIds: List<String>,
    ): DomainResult<List<ListingViewerInteraction>> = DomainResult.Success(emptyList())

    override suspend fun likeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        getListingViewerInteraction(listingId)

    override suspend fun unlikeListing(listingId: String): DomainResult<ListingViewerInteraction> =
        getListingViewerInteraction(listingId)

    override suspend fun favoriteListing(listingId: String): DomainResult<ListingViewerInteraction> =
        getListingViewerInteraction(listingId)

    override suspend fun unfavoriteListing(listingId: String): DomainResult<ListingViewerInteraction> =
        getListingViewerInteraction(listingId)
}

private class FixedClockProvider(private val nowEpochMilliseconds: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = nowEpochMilliseconds
}

private fun listingSummary(
    id: String = "listing-1",
    name: String = "Listing test",
    cityId: String = "cotonou",
    coverImageUrl: String? = null,
    ratingAverage: Double? = null,
    sponsoredUntilEpochMilliseconds: Long? = null,
): ListingSummary {
    val price = assertIs<DomainResult.Success<MoneyXof>>(MoneyXof.fromAmount(5_000)).value
    return ListingSummary(
        id = id,
        type = ListingType.Place,
        listingClass = ListingClass.Heritage,
        status = ListingStatus.Published,
        name = name,
        cityId = cityId,
        categoryId = "heritage-historique",
        coverImageUrl = coverImageUrl,
        priceFromXof = price,
        ratingAverage = ratingAverage,
        likesCount = 12,
        verified = true,
        sponsoredUntilEpochMilliseconds = sponsoredUntilEpochMilliseconds,
    )
}

private fun runSuspendTest(block: suspend () -> Unit) = kotlinx.coroutines.test.runTest {
    block()
}
