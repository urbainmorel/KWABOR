package com.kwabor.shared.presentation.detail

import com.kwabor.shared.domain.catalog.CatalogAmenity
import com.kwabor.shared.domain.catalog.CatalogCategoryReference
import com.kwabor.shared.domain.catalog.CatalogCityReference
import com.kwabor.shared.domain.catalog.CatalogDayHours
import com.kwabor.shared.domain.catalog.CatalogDetail
import com.kwabor.shared.domain.catalog.CatalogDetailCommon
import com.kwabor.shared.domain.catalog.CatalogEventOrganizer
import com.kwabor.shared.domain.catalog.CatalogEventTicketing
import com.kwabor.shared.domain.catalog.CatalogEventVenue
import com.kwabor.shared.domain.catalog.CatalogLocation
import com.kwabor.shared.domain.catalog.CatalogMedia
import com.kwabor.shared.domain.catalog.CatalogMediaKind
import com.kwabor.shared.domain.catalog.CatalogMetrics
import com.kwabor.shared.domain.catalog.CatalogOpeningDay
import com.kwabor.shared.domain.catalog.CatalogOpeningHours
import com.kwabor.shared.domain.catalog.CatalogOpeningPeriod
import com.kwabor.shared.domain.catalog.CatalogPrice
import com.kwabor.shared.domain.catalog.CatalogQueryRepository
import com.kwabor.shared.domain.catalog.CatalogRoomType
import com.kwabor.shared.domain.catalog.CatalogTicketTier
import com.kwabor.shared.domain.catalog.Category
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.catalog.GeoPoint
import com.kwabor.shared.domain.catalog.ListingClass
import com.kwabor.shared.domain.catalog.ListingContact
import com.kwabor.shared.domain.catalog.ListingFilters
import com.kwabor.shared.domain.catalog.ListingPageRequest
import com.kwabor.shared.domain.catalog.ListingSearchQuery
import com.kwabor.shared.domain.catalog.ListingSummaryPage
import com.kwabor.shared.domain.catalog.ListingType
import com.kwabor.shared.domain.catalog.PriceUnit
import com.kwabor.shared.domain.catalog.Weekday
import com.kwabor.shared.domain.core.ClockProvider
import com.kwabor.shared.domain.core.DomainError
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.i18n.AppLocale
import com.kwabor.shared.domain.money.MoneyXof
import com.kwabor.shared.i18n.CatalogDetailStrings
import com.kwabor.shared.i18n.stringsFor
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

class CatalogDetailPresenterTest {
    private val strings = stringsFor(AppLocale.French)

    @Test
    fun load_mapsTheSixClosedDetailVariantsExhaustively() = runTest {
        DetailFixtureVariant.entries.forEach { variant ->
            val source = catalogDetailFixture(variant = variant)
            val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }
            val presenter = CatalogDetailPresenter(repository, FixedDetailClock(DETAIL_TEST_NOW))

            val state = presenter.load("  ${source.common.id}  ", strings)

            val content = assertIs<CatalogDetailUiState.Content>(state)
            assertEquals(listOf(source.common.id), repository.requestedListingIds)
            assertEquals(source.common.id, content.model.id)
            assertEquals(source.common.name, content.model.title)
            assertEquals(source.common.isClaimable, content.model.isClaimable)
            assertVariantMapping(variant, content.model.content, strings.detail)
        }
    }

    @Test
    fun load_exposesOnlyOfficialImagesAndSelectsTheCover() = runTest {
        val source = catalogDetailFixture()
        val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }

        val state = CatalogDetailPresenter(repository, FixedDetailClock(DETAIL_TEST_NOW)).load(
            listingId = source.common.id,
            strings = strings,
        )

        val content = assertIs<CatalogDetailUiState.Content>(state)
        assertEquals(
            listOf(DETAIL_COVER_URL, DETAIL_GALLERY_URL),
            content.model.media.map(CatalogDetailMediaUiModel::url),
        )
        assertFalse(content.model.media.any { media -> media.url == DETAIL_VIDEO_URL })
        assertTrue(content.model.media.all { media -> media.alt.isNotBlank() })
        assertEquals(0, content.selectedMediaIndex)
    }

    @Test
    fun load_exposesTypedDirectionsContactAndMenuTargets() = runTest {
        val source = catalogDetailFixture(variant = DetailFixtureVariant.Food)
        val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }

        val state = CatalogDetailPresenter(repository, FixedDetailClock(DETAIL_TEST_NOW)).load(
            listingId = source.common.id,
            strings = strings,
        )

        val model = assertIs<CatalogDetailUiState.Content>(state).model
        assertEquals(
            CatalogDetailDirectionsUiModel(
                latitude = 6.370293,
                longitude = 2.391236,
                label = "Restaurant Kwabor",
            ),
            model.directions,
        )
        assertEquals(
            CatalogDetailContactUiModel(
                phoneNumber = "+2290100000000",
                whatsappNumber = "+2290100000000",
                websiteUrl = "https://kwabor.test/contact",
                emailAddress = "contact@kwabor.test",
            ),
            model.contact,
        )
        val food = assertIs<CatalogDetailContentUiModel.Food>(model.content)
        assertEquals("https://kwabor.test/menu", food.menuUrl)
    }

    @Test
    fun load_omitsDirectionsAndContactWhenNoTargetIsAvailable() = runTest {
        val base = assertIs<CatalogDetail.Establishment.Food>(
            catalogDetailFixture(variant = DetailFixtureVariant.Food),
        )
        val source = base.copy(
            common = base.common.copy(
                location = base.common.location.copy(geoPoint = null),
                contact = ListingContact(
                    phone = null,
                    whatsapp = null,
                    externalUrl = null,
                    email = null,
                ),
            ),
        )
        val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }

        val state = CatalogDetailPresenter(repository, FixedDetailClock(DETAIL_TEST_NOW)).load(
            listingId = source.common.id,
            strings = strings,
        )

        val model = assertIs<CatalogDetailUiState.Content>(state).model
        assertNull(model.directions)
        assertNull(model.contact)
    }

    @Test
    fun load_omitsContactForPlaceAndEventEvenWhenTheirPayloadContainsContactData() = runTest {
        listOf(DetailFixtureVariant.Place, DetailFixtureVariant.Event).forEach { variant ->
            val source = catalogDetailFixture(variant = variant)
            val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }

            val state = CatalogDetailPresenter(repository, FixedDetailClock(DETAIL_TEST_NOW)).load(
                listingId = source.common.id,
                strings = strings,
            )

            assertNull(assertIs<CatalogDetailUiState.Content>(state).model.contact)
        }
    }

    @Test
    fun load_preservesFreeEventRegistrationUrl() = runTest {
        val base = assertIs<CatalogDetail.Event>(catalogDetailFixture(variant = DetailFixtureVariant.Event))
        val source = base.copy(
            ticketing = CatalogEventTicketing.Free(externalUrl = "https://tickets.kwabor.test/register"),
        )
        val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }

        val state = CatalogDetailPresenter(repository, FixedDetailClock(DETAIL_TEST_NOW)).load(
            listingId = source.common.id,
            strings = strings,
        )

        val event = assertIs<CatalogDetailContentUiModel.Event>(
            assertIs<CatalogDetailUiState.Content>(state).model.content,
        )
        val ticketing = assertIs<CatalogDetailTicketingUiModel.Free>(event.ticketing)
        assertEquals("https://tickets.kwabor.test/register", ticketing.externalUrl)
    }

    @Test
    fun load_turnsTaxonomyAndAmenityKeysIntoHumanLabels() = runTest {
        val base = assertIs<CatalogDetail.Place>(catalogDetailFixture())
        val source = base.copy(
            common = base.common.copy(
                category = CatalogCategoryReference(
                    id = "heritage-historique",
                    labelKey = "category.heritage.historique",
                ),
                amenities = listOf(
                    CatalogAmenity(
                        id = "accessible-pmr",
                        labelKey = "amenity.accessible_pmr",
                        order = 0,
                    ),
                ),
                tags = listOf("family_friendly", "family-friendly", "patrimoine-culturel"),
            ),
            placeCategory = "heritage-historique",
        )
        val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }

        val state = CatalogDetailPresenter(repository, FixedDetailClock(DETAIL_TEST_NOW)).load(
            source.common.id,
            strings,
        )

        val model = assertIs<CatalogDetailUiState.Content>(state).model
        val place = assertIs<CatalogDetailContentUiModel.Place>(model.content)
        assertEquals("Cotonou · Historique", model.contextLabel)
        assertEquals(listOf("Accessible aux personnes à mobilité réduite"), model.amenities)
        assertEquals(listOf("Family friendly", "Patrimoine culturel"), model.tags)
        assertEquals("Historique", place.placeCategoryLabel)
        val visibleLabels = listOf(model.contextLabel, place.placeCategoryLabel) + model.amenities + model.tags
        assertTrue(
            visibleLabels.none { label ->
                label.contains("category.") || label.contains("amenity.") || '_' in label
            },
        )
    }

    @Test
    fun load_deduplicatesLabelsAfterHumanReadableProjection() = runTest {
        val foodBase = assertIs<CatalogDetail.Establishment.Food>(
            catalogDetailFixture(variant = DetailFixtureVariant.Food),
        )
        val guideBase = assertIs<CatalogDetail.Establishment.Guide>(
            catalogDetailFixture(variant = DetailFixtureVariant.Guide),
        )
        val food = foodBase.copy(
            cuisines = listOf("afro-fusion", "afro_fusion"),
            meals = listOf("petit-dejeuner", "petit_dejeuner"),
        )
        val guide = guideBase.copy(
            languages = listOf("fr", "fra", "fon"),
            zones = listOf("Cotonou", "cotonou"),
            specialties = listOf("patrimoine-culturel", "patrimoine_culturel"),
        )

        val foodState = CatalogDetailPresenter(
            FakeDetailCatalogQueryRepository { DomainResult.Success(food) },
            FixedDetailClock(DETAIL_TEST_NOW),
        ).load(food.common.id, strings)
        val guideState = CatalogDetailPresenter(
            FakeDetailCatalogQueryRepository { DomainResult.Success(guide) },
            FixedDetailClock(DETAIL_TEST_NOW),
        ).load(guide.common.id, strings)

        val foodContent = assertIs<CatalogDetailContentUiModel.Food>(
            assertIs<CatalogDetailUiState.Content>(foodState).model.content,
        )
        val guideContent = assertIs<CatalogDetailContentUiModel.Guide>(
            assertIs<CatalogDetailUiState.Content>(guideState).model.content,
        )
        assertEquals(listOf("Afro fusion"), foodContent.cuisines)
        assertEquals(listOf("Petit dejeuner"), foodContent.meals)
        assertEquals(listOf("Français", "Fon"), guideContent.languages)
        assertEquals(listOf("Cotonou"), guideContent.zones)
        assertEquals(listOf("Patrimoine culturel"), guideContent.specialties)
    }

    @Test
    fun load_usesFrenchSingularFormsForGuideExperienceAndEventCapacity() = runTest {
        val guideSource = assertIs<CatalogDetail.Establishment.Guide>(
            catalogDetailFixture(variant = DetailFixtureVariant.Guide),
        ).copy(experienceYears = 1)
        val eventSource = assertIs<CatalogDetail.Event>(
            catalogDetailFixture(variant = DetailFixtureVariant.Event),
        ).copy(capacity = 1)

        val guideState = CatalogDetailPresenter(
            FakeDetailCatalogQueryRepository { DomainResult.Success(guideSource) },
            FixedDetailClock(DETAIL_TEST_NOW),
        ).load(guideSource.common.id, strings)
        val eventState = CatalogDetailPresenter(
            FakeDetailCatalogQueryRepository { DomainResult.Success(eventSource) },
            FixedDetailClock(DETAIL_TEST_NOW),
        ).load(eventSource.common.id, strings)

        val guide = assertIs<CatalogDetailContentUiModel.Guide>(
            assertIs<CatalogDetailUiState.Content>(guideState).model.content,
        )
        val event = assertIs<CatalogDetailContentUiModel.Event>(
            assertIs<CatalogDetailUiState.Content>(eventState).model.content,
        )
        assertEquals("1 an", guide.facts.single { fact -> fact.label == strings.detail.experience }.value)
        assertEquals("1 personne", event.capacityLabel)
    }

    @Test
    fun load_evaluatesOvernightOpeningHoursInAfricaPortoNovo() = runTest {
        val mondayOvernight = CatalogOpeningHours.Weekly(
            days = Weekday.entries.map { weekday ->
                CatalogOpeningDay(
                    weekday = weekday,
                    hours = if (weekday == Weekday.Monday) {
                        CatalogDayHours.Periods(
                            listOf(
                                CatalogOpeningPeriod(
                                    opensMinute = 22 * MINUTES_PER_HOUR,
                                    closesMinute = 2 * MINUTES_PER_HOUR,
                                    closesNextDay = true,
                                ),
                            ),
                        )
                    } else {
                        CatalogDayHours.Closed
                    },
                )
            },
        )
        val source = catalogDetailFixture(openingHours = mondayOvernight)
        val now = Instant.parse("2026-08-03T23:30:00Z").toEpochMilliseconds()
        val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }

        val state = CatalogDetailPresenter(repository, FixedDetailClock(now)).load(source.common.id, strings)

        val model = assertIs<CatalogDetailUiState.Content>(state).model
        assertEquals(strings.detail.openNow, model.openingStatusLabel)
        assertEquals("22:00–02:00 (lendemain)", model.openingHours.first().hoursLabel)
    }

    @Test
    fun load_marksPastEventAndFormatsItsScheduleInAfricaPortoNovo() = runTest {
        val startsAt = Instant.parse("2026-08-10T18:00:00Z").toEpochMilliseconds()
        val endsAt = Instant.parse("2026-08-10T22:00:00Z").toEpochMilliseconds()
        val now = Instant.parse("2026-08-10T23:00:00Z").toEpochMilliseconds()
        val source = catalogDetailFixture(
            variant = DetailFixtureVariant.Event,
            eventSchedule = DetailEventSchedule(startsAt, endsAt),
        )
        val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }

        val state = CatalogDetailPresenter(repository, FixedDetailClock(now)).load(source.common.id, strings)

        val model = assertIs<CatalogDetailUiState.Content>(state).model
        val event = assertIs<CatalogDetailContentUiModel.Event>(model.content)
        assertTrue(event.isEnded)
        assertTrue(model.contextLabel.endsWith(strings.detail.eventEnded))
        assertEquals("10/08/2026 · 19:00", event.startsAtLabel)
        assertEquals("10/08/2026 · 23:00", event.endsAtLabel)
    }

    @Test
    fun load_usesTheLinkedEventVenueLocationWhenTheEventLocationIsEmpty() = runTest {
        val base = assertIs<CatalogDetail.Event>(catalogDetailFixture(variant = DetailFixtureVariant.Event))
        val source = base.copy(
            common = base.common.copy(
                location = CatalogLocation(district = null, address = null, geoPoint = null),
            ),
        )
        val repository = FakeDetailCatalogQueryRepository { DomainResult.Success(source) }

        val state = CatalogDetailPresenter(repository, FixedDetailClock(DETAIL_TEST_NOW)).load(
            source.common.id,
            strings,
        )

        val location = assertIs<CatalogDetailUiState.Content>(state).model.location
        assertEquals("Cotonou", location.cityLabel)
        assertEquals("Cadjèhoun", location.districtLabel)
        assertEquals("Cotonou", location.addressLabel)
        assertEquals(6.38, location.latitude)
        assertEquals(2.39, location.longitude)
        assertEquals(
            CatalogDetailDirectionsUiModel(
                latitude = 6.38,
                longitude = 2.39,
                label = "Place de l’Étoile Rouge",
            ),
            assertIs<CatalogDetailUiState.Content>(state).model.directions,
        )
    }

    @Test
    fun load_mapsNotFoundNetworkAndUnexpectedErrorsWithoutLeakingTechnicalKeys() = runTest {
        val fixtures = listOf(
            FailureFixture(DomainError.NotFound("error.catalog.detail_missing")) { state ->
                val failure = assertIs<CatalogDetailUiState.NotFound>(state)
                assertEquals(strings.detail.unavailable, failure.message)
            },
            FailureFixture(DomainError.NetworkUnavailable("error.network.raw")) { state ->
                val failure = assertIs<CatalogDetailUiState.OfflineFailure>(state)
                assertEquals(strings.detail.offlineUnavailable, failure.message)
            },
            FailureFixture(DomainError.Unexpected("postgres.raw.failure")) { state ->
                val failure = assertIs<CatalogDetailUiState.Failure>(state)
                assertEquals(strings.detail.loadFailed, failure.message)
            },
        )

        fixtures.forEachIndexed { index, fixture ->
            val repository = FakeDetailCatalogQueryRepository { DomainResult.Failure(fixture.error) }
            val listingId = "detail-$index"

            val state = CatalogDetailPresenter(repository, FixedDetailClock(DETAIL_TEST_NOW)).load(
                " $listingId ",
                strings,
            )

            fixture.assertion(state)
            assertEquals(listOf(listingId), repository.requestedListingIds)
        }
    }
}

private data class FailureFixture(
    val error: DomainError,
    val assertion: (CatalogDetailUiState) -> Unit,
)

private fun assertVariantMapping(
    variant: DetailFixtureVariant,
    content: CatalogDetailContentUiModel,
    strings: CatalogDetailStrings,
) {
    when (variant) {
        DetailFixtureVariant.Place -> {
            val place = assertIs<CatalogDetailContentUiModel.Place>(content)
            assertEquals(strings.place, place.heading)
            assertEquals(strings.history, place.placeCategoryLabel)
            assertEquals("Accès libre", place.feeNote)
        }

        DetailFixtureVariant.Lodging -> {
            val lodging = assertIs<CatalogDetailContentUiModel.Lodging>(content)
            assertEquals(strings.lodging, lodging.heading)
            assertEquals(
                listOf(strings.starRating, strings.roomCount, strings.checkIn, strings.checkOut),
                lodging.facts.map { it.label },
            )
            assertEquals(listOf("Standard"), lodging.roomTypes.map { it.label })
        }

        DetailFixtureVariant.Food -> {
            val food = assertIs<CatalogDetailContentUiModel.Food>(content)
            assertEquals(strings.food, food.heading)
            assertEquals(listOf("Béninoise"), food.cuisines)
            assertEquals(strings.reservationsAccepted, food.reservationLabel)
            assertEquals("https://kwabor.test/menu", food.menuUrl)
        }

        DetailFixtureVariant.Nightlife -> {
            val nightlife = assertIs<CatalogDetailContentUiModel.Nightlife>(content)
            assertEquals(strings.nightlife, nightlife.heading)
            assertEquals(listOf(strings.venueKind, strings.minimumAge), nightlife.facts.map { it.label })
        }

        DetailFixtureVariant.Guide -> {
            val guide = assertIs<CatalogDetailContentUiModel.Guide>(content)
            assertEquals(strings.guide, guide.heading)
            assertEquals(listOf("Français", "Fon"), guide.languages)
            assertEquals(listOf("Patrimoine culturel"), guide.specialties)
            assertEquals(listOf(strings.accreditation, strings.experience), guide.facts.map { it.label })
        }

        DetailFixtureVariant.Event -> {
            val event = assertIs<CatalogDetailContentUiModel.Event>(content)
            assertEquals(strings.event, event.heading)
            assertEquals("Place de l’Étoile Rouge · Cotonou", event.venueLabel)
            val ticketing = assertIs<CatalogDetailTicketingUiModel.Paid>(event.ticketing)
            assertEquals("https://tickets.kwabor.test/event", ticketing.externalUrl)
        }
    }
    assertFalse(content.heading.contains('.'))
    assertFalse(content.heading.contains('_'))
}

internal enum class DetailFixtureVariant {
    Place,
    Lodging,
    Food,
    Nightlife,
    Guide,
    Event,
}

internal class FakeDetailCatalogQueryRepository(
    private val detailLoader: suspend (String) -> DomainResult<CatalogDetail>,
) : CatalogQueryRepository {
    val requestedListingIds = mutableListOf<String>()

    override suspend fun getListingDetail(listingId: String): DomainResult<CatalogDetail> {
        requestedListingIds += listingId
        return detailLoader(listingId)
    }

    override suspend fun listCities(): DomainResult<List<City>> = unexpectedCatalogCall()

    override suspend fun listCategories(): DomainResult<List<Category>> = unexpectedCatalogCall()

    override suspend fun listListings(
        filters: ListingFilters,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = unexpectedCatalogCall()

    override suspend fun searchListings(
        query: ListingSearchQuery,
        page: ListingPageRequest,
    ): DomainResult<ListingSummaryPage> = unexpectedCatalogCall()
}

internal class FixedDetailClock(private val nowEpochMilliseconds: Long) : ClockProvider {
    override fun nowEpochMilliseconds(): Long = nowEpochMilliseconds
}

internal fun catalogDetailFixture(
    variant: DetailFixtureVariant = DetailFixtureVariant.Place,
    id: String = "detail-${variant.name.lowercase()}",
    openingHours: CatalogOpeningHours = defaultDetailOpeningHours(),
    media: List<CatalogMedia> = defaultDetailMedia(),
    eventSchedule: DetailEventSchedule = DetailEventSchedule(DETAIL_EVENT_START, DETAIL_EVENT_END),
): CatalogDetail {
    val common = detailCommonFixture(
        variant = variant,
        id = id,
        openingHours = openingHours,
        media = media,
    )
    return DetailVariantFixtureFactory.create(variant, common, eventSchedule)
}

internal data class DetailEventSchedule(
    val startsAtEpochMilliseconds: Long,
    val endsAtEpochMilliseconds: Long?,
)

private object DetailVariantFixtureFactory {
    fun create(
        variant: DetailFixtureVariant,
        common: CatalogDetailCommon,
        eventSchedule: DetailEventSchedule,
    ): CatalogDetail = when (variant) {
        DetailFixtureVariant.Place -> place(common)
        DetailFixtureVariant.Lodging -> lodging(common)
        DetailFixtureVariant.Food -> food(common)
        DetailFixtureVariant.Nightlife -> nightlife(common)
        DetailFixtureVariant.Guide -> guide(common)
        DetailFixtureVariant.Event -> event(common, eventSchedule)
    }

    private fun place(common: CatalogDetailCommon): CatalogDetail.Place = CatalogDetail.Place(
        common = common,
        placeCategory = "heritage-historique",
        isFree = true,
        entryFee = null,
        feeNote = "Accès libre",
    )

    private fun lodging(common: CatalogDetailCommon): CatalogDetail.Establishment.Lodging =
        CatalogDetail.Establishment.Lodging(
            common = common,
            starRating = 4,
            roomCount = 24,
            checkInMinute = 14 * MINUTES_PER_HOUR,
            checkOutMinute = 11 * MINUTES_PER_HOUR,
            roomTypes = listOf(CatalogRoomType("Standard", detailMoney(15_000), order = 0)),
        )

    private fun food(common: CatalogDetailCommon): CatalogDetail.Establishment.Food = CatalogDetail.Establishment.Food(
        common = common,
        cuisines = listOf("béninoise"),
        meals = listOf("déjeuner", "dîner"),
        acceptsReservations = true,
        menuUrl = "https://kwabor.test/menu",
    )

    private fun nightlife(common: CatalogDetailCommon): CatalogDetail.Establishment.Nightlife =
        CatalogDetail.Establishment.Nightlife(
            common = common,
            venueKind = "club-culturel",
            minimumAge = 18,
        )

    private fun guide(common: CatalogDetailCommon): CatalogDetail.Establishment.Guide =
        CatalogDetail.Establishment.Guide(
            common = common,
            languages = listOf("fr", "fon"),
            zones = listOf("Ouidah"),
            specialties = listOf("patrimoine-culturel"),
            indicativePrice = detailMoney(20_000),
            accreditation = "GUIDE-BJ-001",
            experienceYears = 8,
        )

    private fun event(common: CatalogDetailCommon, schedule: DetailEventSchedule): CatalogDetail.Event =
        CatalogDetail.Event(
            common = common,
            category = "culture",
            startsAtEpochMilliseconds = schedule.startsAtEpochMilliseconds,
            endsAtEpochMilliseconds = schedule.endsAtEpochMilliseconds,
            venue = eventVenue(),
            organizer = CatalogEventOrganizer(
                name = "Kwabor Culture",
                contact = "+2290100000000",
            ),
            ticketing = CatalogEventTicketing.Paid(
                externalUrl = "https://tickets.kwabor.test/event",
                tiers = listOf(CatalogTicketTier("Standard", detailMoney(5_000), order = 0)),
            ),
            capacity = 500,
        )

    private fun eventVenue(): CatalogEventVenue = CatalogEventVenue(
        id = "venue-1",
        type = ListingType.Place,
        subtype = "heritage-historique",
        name = "Place de l’Étoile Rouge",
        city = CatalogCityReference(id = "cotonou", name = "Cotonou"),
        location = CatalogLocation(
            district = "Cadjèhoun",
            address = "Cotonou",
            geoPoint = GeoPoint(latitude = 6.38, longitude = 2.39),
        ),
    )
}

private fun detailCommonFixture(
    variant: DetailFixtureVariant,
    id: String,
    openingHours: CatalogOpeningHours,
    media: List<CatalogMedia>,
): CatalogDetailCommon {
    val properties = variant.commonProperties()
    return CatalogDetailCommon(
        id = id,
        type = properties.type,
        subtype = properties.subtype,
        listingClass = properties.listingClass,
        name = properties.name,
        slug = id,
        description = "Une description officielle suffisamment détaillée pour la fiche Kwabor.",
        contentLocale = AppLocale.French,
        city = CatalogCityReference(id = "cotonou", name = "Cotonou"),
        category = CatalogCategoryReference(
            id = properties.categoryId,
            labelKey = properties.categoryLabelKey,
        ),
        location = CatalogLocation(
            district = "Ganhi",
            address = "Rue de test, Cotonou",
            geoPoint = GeoPoint(latitude = 6.370293, longitude = 2.391236),
        ),
        price = properties.price,
        openingHours = openingHours,
        contact = ListingContact(
            phone = "+2290100000000",
            whatsapp = "+2290100000000",
            externalUrl = "https://kwabor.test/contact",
            email = "contact@kwabor.test",
        ),
        socialLinks = emptyList(),
        tags = listOf("benin-culture"),
        verified = true,
        isClaimable = properties.listingClass != ListingClass.Heritage,
        metrics = CatalogMetrics(
            ratingAverage = 4.5,
            ratingCount = 24,
            viewsCount = 1_200,
            likesCount = 12,
        ),
        publishedAtEpochMilliseconds = Instant.parse("2026-07-03T10:15:30Z").toEpochMilliseconds(),
        media = media,
        amenities = listOf(CatalogAmenity(id = "wifi", labelKey = "amenity.wifi", order = 0)),
    )
}

private data class DetailCommonProperties(
    val type: ListingType,
    val subtype: String,
    val listingClass: ListingClass,
    val name: String,
    val categoryId: String,
    val categoryLabelKey: String,
    val price: CatalogPrice,
)

private fun DetailFixtureVariant.commonProperties(): DetailCommonProperties = when (this) {
    DetailFixtureVariant.Place -> DetailCommonProperties(
        type = ListingType.Place,
        subtype = "heritage-historique",
        listingClass = ListingClass.Heritage,
        name = "Porte du non-retour",
        categoryId = "heritage-historique",
        categoryLabelKey = "category.heritage.historique",
        price = CatalogPrice(from = null, unit = PriceUnit.None, tier = null),
    )

    DetailFixtureVariant.Lodging -> establishmentProperties(
        subtype = "hotel",
        name = "Hôtel Kwabor",
        categoryId = "hotel",
        categoryLabelKey = "category.commercial.hotel",
        price = detailCatalogPrice(amount = 15_000, unit = PriceUnit.PerNight),
    )

    DetailFixtureVariant.Food -> establishmentProperties(
        subtype = "restaurant",
        name = "Restaurant Kwabor",
        categoryId = "restaurant",
        categoryLabelKey = "category.commercial.restaurant",
        price = detailCatalogPrice(amount = 5_000, unit = PriceUnit.PerPerson),
    )

    DetailFixtureVariant.Nightlife -> establishmentProperties(
        subtype = "club",
        name = "Club Kwabor",
        categoryId = "vie-nocturne",
        categoryLabelKey = "category.commercial.vie_nocturne",
        price = detailCatalogPrice(amount = 3_000, unit = PriceUnit.Consumption),
    )

    DetailFixtureVariant.Guide -> establishmentProperties(
        subtype = "guide-touristique",
        name = "Guide Kwabor",
        categoryId = "guide-touristique",
        categoryLabelKey = "category.commercial.guide",
        price = detailCatalogPrice(amount = 20_000, unit = PriceUnit.PerPerson),
    )

    DetailFixtureVariant.Event -> DetailCommonProperties(
        type = ListingType.Event,
        subtype = "event-culture",
        listingClass = ListingClass.Event,
        name = "Festival Kwabor",
        categoryId = "event-culture",
        categoryLabelKey = "category.event.culture",
        price = CatalogPrice(from = detailMoney(5_000), unit = PriceUnit.PerEntry, tier = null),
    )
}

private fun establishmentProperties(
    subtype: String,
    name: String,
    categoryId: String,
    categoryLabelKey: String,
    price: CatalogPrice,
): DetailCommonProperties = DetailCommonProperties(
    type = ListingType.Establishment,
    subtype = subtype,
    listingClass = ListingClass.Commercial,
    name = name,
    categoryId = categoryId,
    categoryLabelKey = categoryLabelKey,
    price = price,
)

private fun detailCatalogPrice(amount: Long, unit: PriceUnit): CatalogPrice =
    CatalogPrice(from = detailMoney(amount), unit = unit, tier = 2)

private fun defaultDetailOpeningHours(): CatalogOpeningHours = CatalogOpeningHours.Weekly(
    days = Weekday.entries.map { weekday ->
        CatalogOpeningDay(
            weekday = weekday,
            hours = CatalogDayHours.Periods(
                periods = listOf(
                    CatalogOpeningPeriod(
                        opensMinute = 8 * MINUTES_PER_HOUR,
                        closesMinute = 18 * MINUTES_PER_HOUR,
                        closesNextDay = false,
                    ),
                ),
            ),
        )
    },
)

private fun defaultDetailMedia(): List<CatalogMedia> = listOf(
    CatalogMedia(
        kind = CatalogMediaKind.Image,
        url = DETAIL_COVER_URL,
        alt = "Photo principale officielle",
        order = 0,
        isCover = true,
    ),
    CatalogMedia(
        kind = CatalogMediaKind.Image,
        url = DETAIL_GALLERY_URL,
        alt = "Seconde photo officielle",
        order = 1,
        isCover = false,
    ),
    CatalogMedia(
        kind = CatalogMediaKind.Video,
        url = DETAIL_VIDEO_URL,
        alt = "Vidéo officielle",
        order = 2,
        isCover = false,
    ),
)

private fun detailMoney(amount: Long): MoneyXof = when (val result = MoneyXof.fromAmount(amount)) {
    is DomainResult.Success -> result.value
    is DomainResult.Failure -> error("Invalid detail test amount: ${result.error}")
}

private fun unexpectedCatalogCall(): Nothing = error("Unexpected catalog query in detail test")

internal val DETAIL_TEST_NOW: Long = Instant.parse("2026-08-03T10:00:00Z").toEpochMilliseconds()
private val DETAIL_EVENT_START: Long = Instant.parse("2026-08-10T18:00:00Z").toEpochMilliseconds()
private val DETAIL_EVENT_END: Long = Instant.parse("2026-08-10T22:00:00Z").toEpochMilliseconds()
internal const val DETAIL_COVER_URL = "https://cdn.kwabor.test/cover.jpg"
internal const val DETAIL_GALLERY_URL = "https://cdn.kwabor.test/gallery.jpg"
private const val DETAIL_VIDEO_URL = "https://cdn.kwabor.test/official.mp4"
private const val MINUTES_PER_HOUR = 60
