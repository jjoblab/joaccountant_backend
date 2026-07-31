# KNOWN_ISSUES — JOAccountant Backend v2.5.0

Ce fichier documente les sujets connus qui n'ont pas été traités dans la tâche
en cours (Task ID: `v2.5.0-task1`) et qui nécessitent un traitement ultérieur.

---

## 1. `CompleteWizardAtomicIT.java` — même bug que Phase1IntegrationTest (créé company sur ancien API)

**Emplacement** : `app/src/test/java/jo/accountant/app/CompleteWizardAtomicIT.java`
**Lignes** : 80, 247, 262

**Description** : Le test appelle `Company company = companyService.createCompany(...)`
mais `createCompany` retourne désormais un `CreateCompanyResponse` (record V8.3 —
refonte JWT claim `companies`), pas une `Company`. 3 erreurs de compilation :

```
CompleteWizardAtomicIT.java:80:  error: incompatible types: CreateCompanyResponse cannot be converted to Company
CompleteWizardAtomicIT.java:247: error: incompatible types: CreateCompanyResponse cannot be converted to Company
CompleteWizardAtomicIT.java:262: error: incompatible types: CreateCompanyResponse cannot be converted to Company
```

**Cause racine** : Identique à `Phase1IntegrationTest` (Task v2.5.0-task1) — le
DTO `CreateCompanyResponse` a été introduit en V8.3 mais ces 3 tests
`CompleteWizardAtomicIT` n'ont pas été mis à jour pour utiliser le nouveau
contrat. C'est le même pattern de fix : remplacer
`Company company = companyService.createCompany(...)`
par
```java
var created = companyService.createCompany(...);
Company company = companyRepository.findById(created.company().id()).orElseThrow();
```
(`companyRepository` est déjà `@Autowired` dans la classe, ligne 60.)

**Priorité** : Haute — bloque `:app:compileTestJava` (et donc `:app:test`).

**Action recommandée** : Appliquer le même fix que `Phase1IntegrationTest`
(3 occurrences, pattern identique — voir le diff de la Task v2.5.0-task1).

**Statut v2.5.0-task1** : 🔴 Non traité — hors scope de la Task v2.5.0-task1
(qui ciblait uniquement `Phase1IntegrationTest.java`).

---

## 2. `ChartOfAccountsCsvIntegrationTest.java` — `contentType(Matcher)` n'existe pas

**Emplacement** : `app/src/test/java/jo/accountant/chartofaccounts/ChartOfAccountsCsvIntegrationTest.java`
**Lignes** : 110, 145

**Description** : Le test appelle `content().contentType(org.hamcrest.Matchers.startsWith("text/csv"))`
mais la méthode `ContentResultMatchers.contentType(...)` n'a que 2 surcharges
acceptant `String` ou `MediaType` — pas de surcharge `Matcher<String>`. 2 erreurs
de compilation :

```
ChartOfAccountsCsvIntegrationTest.java:110: error: no suitable method found for contentType(Matcher<String>)
ChartOfAccountsCsvIntegrationTest.java:145: error: no suitable method found for contentType(Matcher<String>)
```

**Cause racine** : API misuse. Probablement un copier-coller depuis un projet
qui utilisait une version custom de `ContentResultMatchers` ou une lib
d'extension. L'intent (vérifier que la réponse est `text/csv` ou
`text/csv;charset=UTF-8`) est correct, mais l'API Spring n'expose pas cette
surcharge.

**Action recommandée** : Remplacer
`content().contentType(org.hamcrest.Matchers.startsWith("text/csv"))`
par
`content().contentTypeCompatibleWith(org.springframework.http.MediaType.parseMediaType("text/csv"))`
qui accomplit le même intent (vérifie `text/csv` ou `text/csv;charset=UTF-8`)
via l'API Spring standard.

**Priorité** : Haute — bloque `:app:compileTestJava`.

**Statut v2.5.0-task1** : 🔴 Non traité — hors scope (bug d'API différent, pas
lié au refactor `CreateCompanyResponse`).

---

## 3. `LettrageAndCsvIntegrationTest.java` — même bug `contentType(Matcher)`

**Emplacement** : `app/src/test/java/jo/accountant/thirdparties/LettrageAndCsvIntegrationTest.java`
**Ligne** : 277

**Description** : Identique à l'issue #2 ci-dessus — même API misuse, 1 occurrence.

```
LettrageAndCsvIntegrationTest.java:277: error: no suitable method found for contentType(Matcher<String>)
```

**Action recommandée** : Même fix que l'issue #2 — remplacer par
`content().contentTypeCompatibleWith(org.springframework.http.MediaType.parseMediaType("text/csv"))`.

**Priorité** : Haute — bloque `:app:compileTestJava`.

**Statut v2.5.0-task1** : 🔴 Non traité — hors scope.

---

## Résumé — impact sur la vérification

Les 3 issues ci-dessus totalisent **6 erreurs de compilation** qui empêchent
`:app:compileTestJava` de réussir (et donc `:app:test` de s'exécuter).

Pour vérifier que `Phase1IntegrationTest` (Task v2.5.0-task1) compile et passe
correctement, les 3 fichiers ci-dessus ont été **temporairement** déplacés hors
du source set, puis `:app:compileTestJava` + `:app:test --tests "*Phase1IntegrationTest"`
ont été lancés avec succès. Les fichiers ont ensuite été restaurés à leur
emplacement d'origine.

Résultat : **Phase1IntegrationTest compile proprement ET les 21 tests passent**
(0 failures, 0 errors, 0 skipped — voir worklog Task v2.5.0-task1 pour le
détail).
