package com.kwabor.shared.presentation.profile

import com.kwabor.shared.domain.catalog.CatalogRepository
import com.kwabor.shared.domain.catalog.City
import com.kwabor.shared.domain.core.DomainResult
import com.kwabor.shared.domain.profile.ProfileDetails
import com.kwabor.shared.domain.profile.ProfileDetailsRepository

data class ProfileDetailsForm(val details: ProfileDetails, val cities: List<City>)

class ProfileDetailsPresenter(
    private val repository: ProfileDetailsRepository,
    private val catalogRepository: CatalogRepository,
) {
    suspend fun load(ownerId: String): ProfileDetailsForm? {
        val details = (repository.get(ownerId) as? DomainResult.Success)?.value ?: return null
        val cities = (catalogRepository.listCities() as? DomainResult.Success)?.value ?: return null
        return ProfileDetailsForm(details, cities)
    }

    suspend fun save(ownerId: String, details: ProfileDetails): ProfileDetails? =
        (repository.update(ownerId, details) as? DomainResult.Success)?.value
}
