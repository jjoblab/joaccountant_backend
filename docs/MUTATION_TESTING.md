# Mutation Testing avec PIT

**Task ID** : R-39 (lot-F2-tests-qa)
**Outil** : [PIT (pitest.org)](https://pitest.org/) — mutation testing for Java
**Plugin Gradle** : [`info.solidsoft.pitest` 1.15.0](https://github.com/szpak/gradle-pitest-plugin)
**PIT engine** : 1.17.x (default fournie par le plugin)
**Date d'introduction** : 2026-07-28

---

## 1. Pourquoi PIT ?

La couverture de code classique (JaCoCo) mesure **quelles lignes sont exécutées par les
tests** — mais ne dit pas si les tests **vérifient réellement** le comportement. Un test
qui appelle une méthode sans assertion fait monter la couverture JaCoCo sans rien tester.

**PIT inverse le problème** : il modifie (mute) le bytecode de production puis rejoue les
tests. Si un test échoue → le mutant est **tué** (le test sert à quelque chose). Si aucun
test n'échoue → le mutant **survit** (le test ne vérifie pas cette logique).

### Exemple concret

```java
// Production
public BigDecimal add(BigDecimal a, BigDecimal b) {
    return a.add(b);
}

// Test (couverture JaCoCo = 100% !)
@Test
void shouldAdd() {
    var result = calculator.add(BigDecimal.ONE, BigDecimal.TEN);
    // aucune assertion → JaCoCo considère la ligne couverte
}

// PIT mute → return a.subtract(b);
// Le test ne FAIL pas → mutant survived → le test ne teste rien.
```

PIT détecte donc les **tests qui ne testent rien** — bien plus pertinent que JaCoCo seul.

---

## 2. Modules couverts

PIT est activé uniquement sur les **4 modules critiques** du backend (scope volontairement
restreint pour démarrer — voir §5 pour la stratégie d'expansion) :

| Module               | Package Gradle           | Pourquoi critique |
|----------------------|--------------------------|-------------------|
| `:core`              | `jo.accountant.core`     | Utilitaires transverses (PiiMasker, CurrencyRoundingService, TenantContext, framework) — impacte toute l'app |
| `:accounting-engine` | `jo.accountant.accountingengine` | Cœur comptable : écritures, journal, grand livre, balance, périodes fiscales |
| `:payroll`           | `jo.accountant.payroll`  | Calcul brut→net (CNSS/OFATMA/AST Haïti, prélèvements France) — erreur = salaires nets erronés |
| `:tax`               | `jo.accountant.tax`      | TVA, retenues à la source, IS, déclarations DGI/DGFIP — erreur = déclarations fiscales erronées |

**Pourquoi pas les 27 autres modules ?** La majorité n'a pas (ou peu) de tests unitaires
aujourd'hui. Lancer PIT dessus ferait échouer le build (`mutationThreshold=50%` non
atteignable) sans valeur ajoutée. La stratégie est de :

1. Démarrer sur les 4 modules critiques qui ont déjà des tests.
2. Mesurer le mutation score initial sur ces 4 modules.
3. Étendre PIT aux autres modules au fur et à mesure que leur dette de tests est résorbée
   (par ordre de criticité métier : `invoicing`, `bank-reconciliation`, `approval-workflow`,
   `auth`, `company`, puis le reste).

---

## 3. Comment exécuter

### 3.1 Script tout-en-un

```bash
./scripts/run-mutation-tests.sh
```

Le script :
1. Exécute `./gradlew :core:pitest :accounting-engine:pitest :payroll:pitest :tax:pitest --continue`
2. Génère un rapport HTML agrégé dans `build/reports/pitest-aggregated/index.html`
   (sommaire + liens vers les rapports par module)

Options :
- `--skip-run` : ne pas rejouer PIT, juste régénérer le rapport agrégé à partir des rapports existants.
- `-h` / `--help` : aide.

### 3.2 Module individuel

```bash
./gradlew :core:pitest
./gradlew :accounting-engine:pitest
./gradlew :payroll:pitest
./gradlew :tax:pitest
```

Rapports générés :
- `<module>/build/reports/pitest/index.html` — rapport HTML navigable
- `<module>/build/reports/pitest/mutations.xml` — rapport XML (intégration CI)

### 3.3 Tous les modules en une commande

```bash
./gradlew :core:pitest :accounting-engine:pitest :payroll:pitest :tax:pitest --continue
```

`--continue` permet à Gradle d'exécuter les autres tâches même si l'une échoue (utile pour
avoir tous les rapports même si un module casse le seuil).

---

## 4. Configuration appliquée

Chaque module critique a sa propre config PIT dans son `build.gradle.kts`. Exemple
(`core/build.gradle.kts`) :

```kotlin
apply(plugin = "info.solidsoft.pitest")
configure<info.solidsoft.gradle.pitest.PitestPluginExtension> {
    targetClasses.set(listOf("jo.accountant.core.*"))
    targetTests.set(listOf("jo.accountant.core.*Test"))
    mutators.set(listOf("STRONGER"))
    outputFormats.set(listOf("XML", "HTML"))
    timeoutConstInMillis.set(5000)
    timeoutFactor.set(1.5)
    jvmArgs.set(listOf("-Xmx1024m"))
    mutationThreshold.set(50)
    coverageThreshold.set(70)
}
```

### 4.1 Paramètres

| Paramètre              | Valeur                    | Explication |
|------------------------|---------------------------|-------------|
| `targetClasses`        | `jo.accountant.<mod>.*`   | Limite les mutations au package du module (sinon PIT mute aussi les classes des dépendances transitives → bruit). |
| `targetTests`          | `jo.accountant.<mod>.*Test` | Limite les tests exécutés à ceux du module. |
| `mutators`             | `STRONGER`                | Mutateurs agressifs (voir §5 ci-dessous). |
| `outputFormats`        | `XML, HTML`               | HTML pour consultation locale, XML pour CI (SonarQube, GitHub Annotations). |
| `timeoutConstInMillis` | `5000`                    | Timeout absolu par test (ms). Au-delà, le mutant est marqué `TIMED_OUT` (équivalent à `KILLED` — le test boucle à cause de la mutation). |
| `timeoutFactor`        | `1.5`                     | Timeout relatif = 1.5× le temps d'exécution du test non muté (permet de tuer les mutants qui ralentissent le test sans le faire boucler). |
| `jvmArgs`              | `-Xmx1024m`               | Heap 1 Go (PIT forks une JVM par classe de test). |
| `mutationThreshold`    | `50`                      | **Build casse si < 50% des mutants sont tués.** Pragmatique pour démarrer — à remonter à 70%. |
| `coverageThreshold`    | `70`                      | **Build casse si < 70% de couverture de ligne.** (PIT double-classe JaCoCo — redondance volontaire pour empêcher de désactiver JaCoCo.) |

### 4.2 Seuils cibles

| Seuil                  | Valeur initiale | Cible 6 mois | Cible 12 mois |
|------------------------|-----------------|--------------|---------------|
| `mutationThreshold`    | 50%             | 70%          | 85%           |
| `coverageThreshold`    | 70%             | 80%          | 90%           |

Les seuils augmentent au fur et à mesure que la dette de tests est résorbée. À chaque
hausse, créer un ticket de suivi listant les mutants survived à corriger avant la prochaine
hausse.

---

## 5. Mutateurs utilisés (`STRONGER`)

Le set `STRONGER` active les groupes de mutateurs suivants (tous décrits dans la
[doc PIT](https://pitest.org/quickstart/mutators/)) :

### 5.1 Mutateurs de méthodes (conditionnelles)
- **`NEGATE_CONDITIONALS`** — inverse les conditions (`<` → `>=`, `==` → `!=`)
- **`CONDITIONALS_BOUNDARY`** — déplace les bornes (`<` → `<=`, `>` → `>=`)
- **`INVERT_NEGS`** — inverse les négations (`-x` → `x`)

### 5.2 Mutateurs de retours
- **`RETURN_VALS`** — modifie les valeurs de retour (retourne `0`, `null`, `true`, `false`)
- **`VOID_METHOD_CALLS`** — supprime les appels de méthodes `void` (effet de bord perdu)

### 5.3 Mutateurs mathématiques
- **`MATH`** — remplace les opérateurs (`+` → `-`, `*` → `/`, `%` → `*`)
- **`INCREMENTS`** — inverse les incrémentations (`i++` → `i--`)

### 5.4 Mutateurs d'invariants
- **`INVERT_NEGS`** — inverse les négations arithmétiques
- **`ABS`** — remplace `Math.abs(x)` par `x` (et inversement)

`STRONGER` est plus agressif que le set par défaut `DEFAULT` (qui n'inclut que les 7
mutateurs conditionnels + retours). Il détecte plus de tests qui ne testent rien, au prix
d'un temps d'exécution plus long (~2-3× plus de mutants générés).

### Mutateurs non activés (pistes futures)

- **`EXPERIMENTAL_BIG_DECIMAL`** — mutateur spécifique aux `BigDecimal` (très pertinent
  pour un backend comptable, mais marqué expérimental → à évaluer quand stable).
- **`REMOVE_INCREMENTS`** — supprime les `++`/`--` (souvent bruité par les boucles `for`).
- **`EXPERIMENTAL_ARGUMENT_PROPAGATION`** — remplace les arguments par `null` (très
  agressif, beaucoup de faux positifs sur une codebase existante).

---

## 6. Comment lire les rapports

### 6.1 Rapport HTML par module

Ouvrir `<module>/build/reports/pitest/index.html`. Trois vues :

1. **Package summary** — mutation coverage + line coverage par package.
2. **Class summary** — par classe, nombre de mutants `KILLED` / `SURVIVED` / `NO_COVERAGE` / `TIMED_OUT`.
3. **Line view** (cliquer sur une classe) — pour chaque ligne mutée, liste des mutants
   et leur statut.

### 6.2 Lecture d'un mutant

| Statut          | Signification                                            | Action |
|-----------------|----------------------------------------------------------|--------|
| `KILLED`        | Au moins un test a échoué → mutant détecté.              | Rien. |
| `SURVIVED`      | Aucun test n'a échoué → le test ne vérifie pas cette logique. | Ajouter une assertion ciblée. |
| `NO_COVERAGE`   | Aucun test n'exécute cette ligne.                        | Ajouter un test qui exécute la ligne. |
| `TIMED_OUT`     | Le test boucle à cause de la mutation.                   | Compté comme `KILLED` (équivalent). |
| `MEMORY_ERROR`  | OOM pendant le test muté.                                | Augmenter `jvmArgs` ou simplifier le test. |

### 6.3 Métriques principales

- **Mutation Coverage** = `KILLED / (KILLED + SURVIVED)` — la métrique la plus importante.
- **Line Coverage** = lignes exécutées / lignes totales (équivalent JaCoCo).
- **Test Strength** = `KILLED / (KILLED + SURVIVED)` restreint aux lignes avec coverage
  non nul → mesure la qualité des tests existants, en ignorant la dette de coverage.

### 6.4 Cibles pragmatiques par module

| Module               | Mutation Coverage initial (estimé) | Cible court terme | Cible moyen terme |
|----------------------|-----------------------------------|-------------------|-------------------|
| `:core`              | 40-60% (PiiMasker testé, reste non couvert) | 60% | 80% |
| `:accounting-engine` | 30-50% (AccountingEngineServiceTest partiel) | 50% | 75% |
| `:payroll`           | 50-70% (PayrollCalculatorTest sur règles HT — R-25) | 60% | 80% |
| `:tax`               | 40-60% (TaxServiceTest sur TVA + R-23 crédit) | 60% | 80% |

Le seuil `mutationThreshold=50%` est calibré pour ne pas casser le build initial — à
ajuster après la première exécution réelle (voir §7).

---

## 7. Stratégie d'expansion

### Phase 1 (actuelle — lot-F2)
- ✅ PIT configuré sur 4 modules critiques.
- ✅ Seuils pragmatiques : 50% mutation / 70% coverage.
- ✅ Script `scripts/run-mutation-tests.sh` + rapport agrégé.
- ⏳ **Première exécution réelle** : mesurer le mutation score par module et ajuster les
  seuils pour qu'ils soient enforce sans casser la CI (le seuil doit être inférieur de
  ~5 points au score mesuré, pour absorber les variations).

### Phase 2 (3 mois)
- Étendre PIT à `invoicing`, `bank-reconciliation`, `approval-workflow`, `auth` (modules
  avec tests existants).
- Ajouter des tests ciblés pour tuer les mutants `SURVIVED` identifiés en Phase 1.
- Remonter `mutationThreshold` à 60% sur les 4 modules initiaux.

### Phase 3 (6 mois)
- Étendre PIT à tous les modules.
- Remonter `mutationThreshold` à 70% partout.
- Activer `EXPERIMENTAL_BIG_DECIMAL` (mutateur très pertinent pour un backend comptable).

### Phase 4 (12 mois)
- `mutationThreshold` à 85%, `coverageThreshold` à 90%.
- Intégration SonarQube (rapport XML déjà généré).
- Pre-commit hook local (`./gradlew :<module>:pitest` sur les modules touchés par le commit).

---

## 8. Intégration CI

Le rapport XML (`<module>/build/reports/pitest/mutations.xml`) est compatible avec :

- **SonarQube** via le plugin `sonar-pitest` (mutations remontées en quality gate).
- **GitHub Annotations** via `pitest-mutation-testing-actions` (les mutants survived
  apparaissent comme annotations sur la PR).
- **GitLab Code Quality** via conversion en Code Climate JSON.

Configuration recommandée pour la CI :
- Exécuter PIT uniquement sur les modules touchés par la PR (script à écrire).
- Cache Gradle `~/.gradle/caches/build-output-*` pour accélérer.
- Pas de cache PIT (les mutations doivent être régénérées à chaque build).

---

## 9. FAQ

### 9.1 PIT met trop de temps — comment accélérer ?

- Réduire le scope : ne muter que les classes modifiées par la PR (paramètre
  `targetClasses` dynamique).
- Augmenter le parallélisme : `mutableCodeParsingParallelism` et `testPluginParallelism`
  (default = 1).
- Désactiver les mutateurs coûteux : passer de `STRONGER` à `DEFAULT` (3× plus rapide,
  mais 30% moins de mutants).

### 9.2 Un mutant survived est-il toujours un bug ?

**Non.** Un mutant survived signifie que le test ne détecte pas la mutation — pas que la
mutation est un bug. Certaines mutations sont sémantiquement équivalentes (ex: `i++` →
`++i` dans un contexte où la valeur n'est pas lue). PIT signale ces cas comme
`SURVIVED` — il faut les analyser manuellement et éventuellement les ignorer via
`@SuppressFBWarnings` équivalent (PIT supporte les `pitest-annotations` pour exclure).

### 9.3 Pourquoi ne pas activer PIT sur tous les modules ?

Voir §2. La majorité des 27 modules métier n'ont pas de tests unitaires (0% de coverage).
Lancer PIT dessus génère 100% de mutants `NO_COVERAGE` → build cassé → aucune valeur.

### 9.4 Quelle différence avec ArchUnit / Spring Modulith (R-44) ?

- **ArchUnit** (R-44 côté compile-time) — vérifie les dépendances entre packages **à la
  compilation** (un module ne dépend pas d'un package interdit).
- **Spring Modulith `verify()`** (R-44 côté runtime) — vérifie les boundaries **au
  runtime** (un module ne peut pas accéder aux `internal` packages d'un autre, même via
  reflection ou events non déclarés).
- **PIT (R-39)** — vérifie la **qualité des tests** (mutation testing), pas
  l'architecture.

Ces 3 outils sont **complémentaires** :
- ArchUnit + Modulith : garantissent que l'architecture est respectée.
- PIT : garantit que les tests vérifient effectivement le comportement.

---

## 10. Références

- [PIT — site officiel](https://pitest.org/)
- [gradle-pitest-plugin](https://github.com/szpak/gradle-pitest-plugin) (1.15.0)
- [Liste complète des mutateurs](https://pitest.org/quickstart/mutators/)
- [Best practices PIT](https://pitest.org/quickstart/best-practices/)
- Article de référence : Fraser & Zeller, "Mutation Testing Ensures Completeness of
  Unit Tests" —ça explique le rationnel académique.
