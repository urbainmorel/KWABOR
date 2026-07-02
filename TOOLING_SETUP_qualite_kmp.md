# Chaîne qualité KMP — Spotless (ktlint) + Detekt + .editorconfig + CI

Le system prompt dit à l'agent **comment écrire** le code. Ces fichiers **imposent** les règles au niveau du build et de la CI : le code non conforme est refusé automatiquement, sans dépendre de la bonne volonté de l'agent.

Dépose chaque fichier ci-dessous à l'emplacement indiqué, puis lance `./gradlew check`.

---

## Arborescence cible

```
racine-projet/
├── .editorconfig                 # style synchronisé (indentation, largeur de ligne)
├── build.gradle.kts              # applique Spotless + Detekt à TOUS les modules
├── config/
│   └── detekt/
│       └── detekt.yml            # seuils bloquants (taille, complexité, imbrication)
├── .github/
│   └── workflows/
│       └── ci.yml                # contrôles systématiques avant merge
├── shared/                       # module KMP partagé
├── androidApp/
└── iosApp/
```

---

## 1. `.editorconfig` (à la racine)

Fichier unique qui synchronise l'indentation et le style ; lu à la fois par l'IDE, par ktlint et par Spotless.

```ini
root = true

[*]
charset = utf-8
end_of_line = lf
insert_final_newline = true
trim_trailing_whitespace = true
indent_style = space
indent_size = 4
max_line_length = 120

[*.{kt,kts}]
ktlint_code_style = ktlint_official
ktlint_standard_no-wildcard-imports = enabled
ij_kotlin_allow_trailing_comma = true
ij_kotlin_allow_trailing_comma_on_call_site = true

[*.{yml,yaml,json}]
indent_size = 2

[*.md]
trim_trailing_whitespace = false
```

> `max_line_length = 120` est le point unique qui pilote la largeur de ligne. Ajuste-le (100 est courant côté Android) — ktlint et detekt s'aligneront dessus.

---

## 2. `config/detekt/detekt.yml`

S'appuie sur la config par défaut de detekt (`buildUponDefaultConfig = true` dans le build) : on ne liste donc que les **surcharges**. `warningsAsErrors: true` fait **échouer le build** dès qu'une règle est enfreinte.

```yaml
config:
  validation: true
  warningsAsErrors: true          # tout warning devient bloquant → build KO

complexity:
  LongMethod:
    threshold: 40                 # méthode trop longue → build KO
  LargeClass:
    threshold: 400                # classe / fichier trop gros → build KO
  CyclomaticComplexMethod:
    threshold: 12
  NestedBlockDepth:
    threshold: 4                  # imbrication profonde → pousse aux guard clauses
  LongParameterList:
    functionThreshold: 6
    constructorThreshold: 7
  TooManyFunctions:
    thresholdInClasses: 15
    thresholdInFiles: 20

style:
  MaxLineLength:
    maxLineLength: 120
  ReturnCount:
    max: 4
    excludeGuardClauses: true     # les guard clauses ne sont pas comptées
  WildcardImport:
    active: true
  MagicNumber:
    ignoreNumbers: ['-1', '0', '1', '2']
    ignoreHashCodeFunction: true

exceptions:
  SwallowedException:
    active: true                  # interdit les catch qui avalent l'erreur
  TooGenericExceptionCaught:
    active: true

coroutines:
  GlobalCoroutineUsage:
    active: true                  # interdit GlobalScope
```

> Les seuils (40, 400, 12, 4…) sont un point de départ senior sain. Ce sont **tes** seuils : durcis-les si besoin.

---

## 3. `build.gradle.kts` (racine) — application à tous les modules

Applique Spotless et Detekt à **chaque sous-module** (`shared`, `androidApp`, etc.) et branche les contrôles sur `check`.

```kotlin
// build.gradle.kts (racine du projet)
// Applique Spotless (ktlint) + Detekt à TOUS les modules.
// Complète le bloc plugins avec tes plugins KMP/Android existants.

plugins {
    id("com.diffplug.spotless") version "6.25.0" apply false
    id("io.gitlab.arturbosch.detekt") version "1.23.7" apply false
}

subprojects {
    apply(plugin = "com.diffplug.spotless")
    apply(plugin = "io.gitlab.arturbosch.detekt")

    // ---- Spotless : formatage auto + largeur de ligne (via .editorconfig) ----
    extensions.configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        kotlin {
            target("src/**/*.kt")
            targetExclude("**/build/**/*.kt")
            ktlint() // lit .editorconfig : style ktlint_official + max_line_length
            trimTrailingWhitespace()
            endWithNewline()
        }
        kotlinGradle {
            target("*.gradle.kts")
            ktlint()
        }
    }

    // ---- Detekt : analyse statique + seuils bloquants ----
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        parallel = true
    }

    tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
        jvmTarget = "17"
        reports {
            html.required.set(true)
            sarif.required.set(true)
            txt.required.set(false)
        }
    }

    // `./gradlew check` (et donc la CI) lance formatage + analyse statique
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("spotlessCheck", "detekt")
    }
}
```

> **Versions** : `6.25.0` (Spotless) et `1.23.7` (Detekt) sont stables et fonctionnelles ; vérifie les dernières sur le Gradle Plugin Portal et bumpe si tu veux. La version de ktlint est celle par défaut de Spotless — inutile de la figer.

---

## 4. `.github/workflows/ci.yml` — CI bloquante avant merge

```yaml
name: CI

on:
  pull_request:
  push:
    branches: [ main, develop ]

jobs:
  quality:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: gradle/actions/setup-gradle@v4

      - name: Formatage (Spotless)
        run: ./gradlew spotlessCheck

      - name: Analyse statique (Detekt)
        run: ./gradlew detekt

      - name: Tests
        run: ./gradlew allTests
```

> **Important — pour *réellement* bloquer un merge** : le workflow seul ne suffit pas. Va dans **Settings → Branches → Branch protection rules**, protège `main`/`develop`, coche **« Require status checks to pass before merging »** et sélectionne le job `quality`. Sans ça, la CI signale mais n'empêche pas le merge.
>
> **iOS** : `ubuntu-latest` suffit pour ktlint, detekt et les tests du code commun. Pour compiler/tester la cible iOS complète, ajoute un job séparé sur `macos-latest`.
>
> **GitLab CI** : l'équivalent est un `.gitlab-ci.yml` avec un job qui lance les mêmes commandes ; le blocage se fait via *merge request pipelines* + « Pipelines must succeed ».

---

## 5. Commandes locales

| Commande | Effet |
|---|---|
| `./gradlew spotlessApply` | **Corrige** le formatage automatiquement |
| `./gradlew spotlessCheck` | Vérifie le formatage (échoue si non conforme) |
| `./gradlew detekt` | Analyse statique (échoue si un seuil est dépassé) |
| `./gradlew check` | Lance `spotlessCheck` + `detekt` (+ tests) sur tous les modules |

> Sur **Windows 11**, utilise `gradlew.bat …` (ou `./gradlew …` sous Git Bash / PowerShell).
>
> **Optionnel** : un hook git `pre-commit` qui lance `./gradlew spotlessApply` évite de committer du code mal formaté.

---

## Résultat — les 5 points, imposés automatiquement

1. **KtLint via Spotless** → `spotlessApply` formate, `spotlessCheck` bloque ; largeur de ligne pilotée par `.editorconfig`.
2. **Detekt avec seuils bloquants** → méthode/classe/complexité/imbrication au-delà des seuils = **build en échec**.
3. **.editorconfig unique** → indentation et style synchronisés IDE + ktlint + detekt.
4. **CI/CD systématique** → `spotlessCheck` et `detekt` à chaque PR ; blocage via protection de branche.
5. **Application globale** → `subprojects { … }` couvre tous les modules partagés **et** spécifiques.
