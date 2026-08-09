package com.kwabor.shared.i18n

data class FavoritesStrings(
    val title: String,
    val allFilter: String,
    val placesFilter: String,
    val eventsFilter: String,
    val hotelsRestaurantsFilter: String,
    val emptyTitle: String,
    val emptyMessage: String,
    val loadFailed: String,
    val refreshFailed: String,
    val loadMoreFailed: String,
    val removeFavorite: String,
    val removeFailed: String,
    val eventEnded: String,
    val eventEndedAccessibility: String,
    val openListing: String,
)

internal val frenchFavoritesStrings = FavoritesStrings(
    title = "Favoris",
    allFilter = "Tous",
    placesFilter = "Lieux",
    eventsFilter = "Événements",
    hotelsRestaurantsFilter = "Hôtels & Restaurants",
    emptyTitle = "Aucun favori",
    emptyMessage = "Touchez le marque-page pour sauvegarder un lieu ou un événement.",
    loadFailed = "Impossible de charger vos favoris.",
    refreshFailed = "Actualisation impossible. Vos favoris précédents restent affichés.",
    loadMoreFailed = "Impossible de charger plus de favoris.",
    removeFavorite = "Retirer des favoris",
    removeFailed = "Impossible de retirer ce favori pour le moment.",
    eventEnded = "Terminé",
    eventEndedAccessibility = "Événement terminé",
    openListing = "Voir la fiche",
)
