package com.kwabor.shared.presentation.notification

data class NotificationSectionStrings(
    val today: String,
    val thisWeek: String,
    val earlier: String,
)

data class NotificationActionStrings(
    val markAllRead: String,
    val openPreferences: String,
    val hide: String,
    val openDetail: String,
)

data class NotificationScreenStrings(
    val title: String,
    val sections: NotificationSectionStrings,
    val actions: NotificationActionStrings,
)

data class NotificationEmptyStrings(
    val title: String,
    val message: String,
)

data class NotificationErrorStrings(
    val loadFailed: String,
    val refreshFailed: String,
    val loadMoreFailed: String,
    val mutationFailed: String,
    val offline: String,
    val localCacheUnavailable: String,
)

data class NotificationTemplateCopy(
    val title: String,
    val body: String,
)

data class NotificationTemplateStrings(
    val suggestion: NotificationTemplateCopy,
    val sponsored: NotificationTemplateCopy,
    val newListing: NotificationTemplateCopy,
    val eventAlert: NotificationTemplateCopy,
    val sponsoredBadge: String,
)

data class NotificationRelativeTimeStrings(
    val now: String,
    val oneMinuteAgo: String,
    val minutesAgo: String,
    val oneHourAgo: String,
    val hoursAgo: String,
    val yesterday: String,
    val daysAgo: String,
)

data class NotificationPreferenceStrings(
    val suggestion: String,
    val sponsored: String,
    val newListing: String,
    val eventAlert: String,
)

data class NotificationStrings(
    val screen: NotificationScreenStrings,
    val empty: NotificationEmptyStrings,
    val errors: NotificationErrorStrings,
    val templates: NotificationTemplateStrings,
    val relativeTime: NotificationRelativeTimeStrings,
    val preferences: NotificationPreferenceStrings,
    val abbreviatedMonthNames: List<String>,
) {
    init {
        require(abbreviatedMonthNames.size == MONTHS_PER_YEAR) {
            "Notification strings require exactly twelve abbreviated month names."
        }
    }
}

internal val frenchNotificationStrings =
    NotificationStrings(
        screen =
            NotificationScreenStrings(
                title = "Notifications",
                sections =
                    NotificationSectionStrings(
                        today = "Aujourd’hui",
                        thisWeek = "Cette semaine",
                        earlier = "Plus tôt",
                    ),
                actions =
                    NotificationActionStrings(
                        markAllRead = "Tout marquer comme lu",
                        openPreferences = "Préférences de notifications",
                        hide = "Masquer",
                        openDetail = "Voir la fiche",
                    ),
            ),
        empty =
            NotificationEmptyStrings(
                title = "Aucune notification",
                message = "Vos recommandations et alertes apparaîtront ici.",
            ),
        errors =
            NotificationErrorStrings(
                loadFailed = "Impossible de charger vos notifications.",
                refreshFailed = "Actualisation impossible. Vos notifications précédentes restent affichées.",
                loadMoreFailed = "Impossible de charger plus de notifications.",
                mutationFailed = "Cette action n’a pas pu être enregistrée pour le moment.",
                offline = "Vous êtes hors ligne. Les notifications enregistrées restent disponibles.",
                localCacheUnavailable = "Le stockage local est indisponible sur cet appareil.",
            ),
        templates =
            NotificationTemplateStrings(
                suggestion =
                    NotificationTemplateCopy(
                        title = "Pour vous",
                        body = "Découvrez {listingName}.",
                    ),
                sponsored =
                    NotificationTemplateCopy(
                        title = "À découvrir",
                        body = "Découvrez {listingName}.",
                    ),
                newListing =
                    NotificationTemplateCopy(
                        title = "Nouveau près de {cityName}",
                        body = "{listingName} vient d’être ajouté à Kwabor.",
                    ),
                eventAlert =
                    NotificationTemplateCopy(
                        title = "Événement à venir",
                        body = "{listingName} approche.",
                    ),
                sponsoredBadge = "Sponsorisé",
            ),
        relativeTime =
            NotificationRelativeTimeStrings(
                now = "À l’instant",
                oneMinuteAgo = "Il y a 1 min",
                minutesAgo = "Il y a {count} min",
                oneHourAgo = "Il y a 1 h",
                hoursAgo = "Il y a {count} h",
                yesterday = "Hier",
                daysAgo = "Il y a {count} j",
            ),
        preferences =
            NotificationPreferenceStrings(
                suggestion = "Suggestions pour vous",
                sponsored = "Contenus sponsorisés",
                newListing = "Nouveautés près de vous",
                eventAlert = "Alertes d’événements",
            ),
        abbreviatedMonthNames =
            listOf(
                "janv.",
                "févr.",
                "mars",
                "avr.",
                "mai",
                "juin",
                "juil.",
                "août",
                "sept.",
                "oct.",
                "nov.",
                "déc.",
            ),
    )

private const val MONTHS_PER_YEAR = 12
