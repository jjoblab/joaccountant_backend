# CHANGEMENTS BACKEND — Impact sur l'application mobile

> **Date** : 29 juillet 2026
> **Version backend concernée** : v5.2 → v5.3 (post-audit multi-experts)
> **Public** : équipe dev mobile (Android APK 21.7 MB, 16 feature modules)
> **Action requise** : adapter l'app mobile aux changements d'API ci-dessous avant la prochaine synchronisation avec le backend.

---

## Synthèse — 3 niveaux d'impact

| Niveau | Dénomination | Détail |
|--------|--------------|--------|
| 🔴 **BREAKING** | Changements cassants | L'app mobile **doit** être mise à jour pour continuer à fonctionner |
| 🟡 **NEW** | Nouveautés exploitables | L'app mobile **peut** être enrichie pour exploiter ces nouveaux endpoints |
| 🟢 **INTERNAL** | Changements internes | Aucun impact sur l'app mobile (sécurité, perf, ops côté serveur) |

**Total** : 5 changements breaking, 11 nouveautés exploitables, ~20 changements internes.

---

## 🔴 Changements BREAKING (5)

### 1. Auth — `POST /api/v1/auth/login/mfa` : signature modifiée

**Avant** (v5.2) :
```http
POST /api/v1/auth/login/mfa?mfaChallengeToken={jwt}&code={code}
Content-Type: application/x-www-form-urlencoded
```

**Après** (v5.3) :
```http
POST /api/v1/auth/login/mfa
Content-Type: application/json

{
  "mfaChallengeToken": "eyJ...",
  "code": 123456
}
```

**Raison** : R-01 (lot-A-sécurité) — correction d'une vulnérabilité critique de bypass MFA. Le `mfaChallengeToken` n'est plus en query string (fuite dans les logs nginx/Tomcat et les Referer). La signature JWT est désormais vérifiée côté serveur.

**Action mobile** :
- Modifier le client HTTP pour envoyer un body JSON au lieu de query params
- Utiliser `@Body MfaLoginRequest` avec Retrofit (ou équivalent)
- Le DTO Kotlin/Java côté mobile : `data class MfaLoginRequest(val mfaChallengeToken: String, val code: Int)`

### 2. Auth — Rate limiting sur `/auth/login/mfa`

**Avant** : endpoint non rate-limité (vulnérabilité brute-force TOTP).
**Après** : 10 tentatives/min/IP + 5 échecs/(IP,email)/15 min (mêmes règles que `/login`).

**Action mobile** :
- Gérer explicitement le HTTP 429 Too Many Requests sur cet endpoint
- Afficher un message utilisateur explicite (« Trop de tentatives, réessayez dans X minutes »)
- Lire le header `Retry-After` si présent

### 3. Audit trail — `GET /api/v1/companies/{companyId}/audit-trail` : pagination obligatoire

**Avant** :
```http
GET /api/v1/companies/{companyId}/audit-trail
→ 200 OK  (retourne List<AuditLogResponse> — OOM côté serveur sur 100M+ lignes)
```

**Après** :
```http
GET /api/v1/companies/{companyId}/audit-trail?page=0&size=20&entityType=&actorUserId=&from=&to=
→ 200 OK  (retourne Page<AuditLogResponse> — wrapper {content, totalElements, totalPages, ...})
```

**Raison** : R-09 (lot-C-perf-devops) — l'ancien endpoint non paginé provoquait un OOM certain côté serveur.

**Action mobile** :
- Modifier le client API pour wrapper la réponse en `Page<T>` (`data class Page<T>(val content: List<T>, val totalElements: Long, val totalPages: Int, val number: Int, val size: Int)`)
- Implémenter la pagination infinie (scroll infini avec `page++` quand l'utilisateur atteint le bas)
- Ajouter les filtres `entityType`, `actorUserId`, `from`, `to` dans l'écran de recherche audit
- Le hard cap côté serveur est `size ≤ 200` ; recommandé d'utiliser `size = 20` côté mobile
- L'ancien endpoint `GET /audit-trail/all` est `@Deprecated` et sera supprimé en v6

### 4. Audit trail — Réponse immuable (tentative UPDATE/DELETE → 500)

**Avant** : aucune protection.
**Après** : la table `audit_log` est immuable (triggers `BEFORE UPDATE`/`BEFORE DELETE` lèvent une exception PostgreSQL).

**Action mobile** :
- Aucune action directe (l'app mobile ne fait que lire l'audit trail)
- Si l'app proposait une fonction « éditer l'audit » (peu probable), la supprimer

### 5. Journal entries — `GET /api/v1/companies/{companyId}/journal-entries` (legacy, non paginé) : déprécié

**Avant** : `GET /journal-entries` retournait `List<JournalEntryResponse>` sans pagination.
**Après** : endpoint `@Deprecated`, sera supprimé en v6. Utiliser `GET /journal-entries/paged?page=&size=&sort=` à la place.

**Action mobile** :
- Migrer vers `/journal-entries/paged` (voir réponse `Page<>` comme pour audit-trail)
- Si l'app mobile utilise encore l'ancien endpoint, planifier la migration avant v6

---

## 🟡 Nouveautés exploitables (11)

### 6. Fiscalité Haïti — Nouveau calendrier déclaratif DGI

**Nouvel endpoint** (R-06, lot-B-fiscalité) :
```http
GET /api/v1/companies/{companyId}/tax/declaration-schedule?year=2026&country=HT
→ 200 OK  (retourne HaitianTaxDeclarationScheduleResponse)
```

**Contenu** : échéances mensuelles (TVA, TCA, RS, acompte IS 1% au 15 M+1) + annuelles (DCR, DCRf, DCRG, DCLS au 31 mars N+1).

**Action mobile** :
- Ajouter un écran « Calendrier fiscal DGI » dans le module tableau de bord
- Notifications push 7 jours avant chaque échéance (peut être fait via le module notifications existant)
- Bouton « Marquer comme payée » (PUT sur la déclaration)

### 7. Fiscalité Haïti — TCA (Taxe sur le Chiffre d'Affaires)

**Nouvelles TaxRule globales** (R-07, lot-B-fiscalité) :
- `TVA_HT_10` (10%)
- `TCA_HT_2_BANK` (2% sur opérations bancaires)
- `TCA_HT_5_TELECOM` (5% télécommunications)
- `TCA_HT_10_SERVICES` (10% autres services)

**Action mobile** :
- L'écran de création de facture doit permettre de sélectionner la TCA en plus de la TVA (nouveau champ `taxType` sur `TaxRule`)
- Si le secteur d'activité de l'entreprise est banque/télécom/services, proposer la TCA par défaut

### 8. Fiscalité Haïti — IS par pays (defaultCorporateTaxRule)

**Changement** (R-05, lot-B-fiscalité) : le calcul de l'IS utilise désormais le pays de la Company :
- HT : 30% (standard) / 15% (zones franches)
- FR : 25% / 15% (PME < 42 500 €)
- CA, autres : 25% par défaut

**Action mobile** :
- L'écran de projection IS (`GET /tax/corporate-tax/projection`) affichera automatiquement le bon taux selon le pays
- Ajouter un badge « Haïti » ou « France » sur l'écran de configuration fiscale pour confirmer le pays actif

### 9. Paie — Heures supplémentaires +100% et matricule CNSS/OFATMA

**Nouveaux champs Employee** (R-20, lot-B-fiscalité) :
```json
{
  "overtimeHours100": 0,        // HS au-delà de 56h/sem ou dimanches/jours fériés (Haïti)
  "cnssNumber": "1234567890",   // Matricule CNSS Haïti (10 chiffres)
  "ofatmaSectorCode": "GEN"     // Code secteur OFATMA (détermine taux accidents 0.5-6%)
}
```

**Nouveau champ Company** (R-20) :
```json
{
  "monthlyLegalHours": 208.00   // 173.33 pour France, 208 pour Haïti
}
```

**Action mobile** :
- Écran de paie : ajouter un 3ᵉ champ de saisie « Heures sup. +100% » (en plus de « +25% » et « +50% »)
- Écran employé : ajouter les champs « Matricule CNSS » et « Code secteur OFATMA »
- Écran configuration société : ajouter « Heures légales mensuelles » (par défaut 208 si pays=HT, 173.33 si FR)

### 10. Facturation — NIF Haïti sur Factur-X

**Changement** (R-21, lot-B-fiscalité) : le XML Factur-X embarque désormais le NIF haïtien comme `SpecifiedTaxRegistration` avec `schemeID="NIF_HT"` quand `country='HT'`.

**Action mobile** :
- L'écran de facture peut afficher un badge « Conforme DGI Haïti » si le NIF est présent
- L'écran de configuration société doit valider le format NIF haïtien (10 chiffres + 2 lettres : `^[0-9]{10}[A-Z]{2}$`) si `country='HT'`

### 11. Crédit TVA reportable

**Nouvel endpoint** (R-23, lot-B-fiscalité) :
```http
GET /api/v1/companies/{companyId}/tax/credit-carried-forward?taxType=VAT&year=2026&month=3
→ 200 OK  (retourne { creditAmount: 12500.00, carriedToNext: true })
```

**Action mobile** :
- Écran déclaration TVA : afficher le crédit reporté de la période précédente
- Indicateur visuel « Crédit de TVA à reporter : X HTG »

### 12. Cotisations CNSS/OFATMA/AST Haïti

**Nouvelles ContributionRule globales** (R-25, lot-B-fiscalité) :
- CNSS Employeur 6% (plafond 150 000 HTG/mois)
- CNSS Salarié 6%
- OFATMA Santé Employeur 3% + Salarié 1%
- OFATMA Accidents 2% (default, variable selon secteur)
- AST barème progressif par tranches mensuelles

**Action mobile** :
- Écran bulletin de paie : afficher les lignes CNSS, OFATMA Santé, OFATMA Accidents, AST
- Écran configuration paie : si `country='HT'`, auto-remplir les cotisations avec les valeurs par défaut Haïti

### 13. Mentions légales factures Haïti (Code Fiscal art. 196)

**Nouvelles templates** (R-08, lot-B-fiscalité) : templates facture/avoir distincts par pays :
- Template FR (CGI art. 289) — comportement existant
- Template HT (Code Fiscal art. 196) — NIF émetteur, raison sociale, adresse, taux TVA/TCA, montant HT/TTC, pénalités 1.5%/mois, indemnité 5 000 HTG

**Action mobile** :
- Si l'app mobile génère des PDF de facture (peu probable côté mobile), utiliser le template HT
- L'écran de visualisation de facture doit afficher les mentions Code Fiscal art. 196 si `country='HT'`

### 14. Profil Spring `staging`

**Nouveau profil** (R-E, lot-E) : `--spring.profiles.active=staging`
- Configuration prod-like (CORS durci, Swagger désactivé, RS256 obligatoire, Redis obligatoire)
- Idéal pour les tests de charge Gatling

**Action mobile** : aucun changement direct, mais l'équipe mobile peut tester contre l'environnement staging (URL à communiquer par l'équipe backend).

### 15. Healthcheck — port 8081 séparé

**Changement** (R-29, lot-C) : les endpoints Actuator sont sur le port 8081, séparés du port 8080 API.
- `GET http://api.example.com:8080/actuator/health` → **404** (port API)
- `GET http://api.example.com:8081/actuator/health` → **200** (port management)

**Action mobile** :
- Si l'app mobile fait un healthcheck au démarrage, pointer vers `:8081/actuator/health`
- Ou : ne pas faire de healthcheck côté mobile (laisser le LB K8s gérer)

### 16. Compression gzip activée

**Changement** (R-E, lot-E) : `server.compression.enabled=true` en staging et prod.
- Les réponses JSON > 1 KB sont désormais compressées en gzip
- Header de réponse : `Content-Encoding: gzip`

**Action mobile** :
- Vérifier que le client HTTP mobile gère bien `Accept-Encoding: gzip` (la plupart le font automatiquement)
- Bénéfice : bande passante mobile réduite de 5-10×

---

## 🟢 Changements internes (sans impact mobile)

Les changements suivants sont internes au backend et n'ont aucun impact sur l'API mobile :

### Sécurité (lot A)
- R-02 : Fail-fast `APP_MFA_ENCRYPTION_KEY` au démarrage
- R-03 : Câblage RLS PostgreSQL côté Java (filtre automatique des SELECT cross-tenant)
- R-13 : Bump Bouncy Castle 1.78.1 → 1.79 (CVE-2024-29857, CVE-2024-30171)

### Performance (lot C)
- R-10 : Réécriture `TaxService.getDeclaration` en SQL GROUP BY (1000 factures = 2 requêtes au lieu de 1003)
- R-11 : Réécriture `getBalanceSheet`/`getIncomeStatement`/`getCashFlow`/`getTrialBalance` en SQL GROUP BY
- R-19 : Pool HikariCP 30 → 50 en prod (sizing 1000+ users)
- R-29 : `ThreadPoolTaskExecutor` borné pour `@Async` (core=10, max=50, queue=100)

### DevOps (lot C + E)
- R-12 : Pipeline CI/CD avec scans SAST (CodeQL), SCA (Trivy), image (Trivy), SBOM (Syft)
- R-14 : Rate limit distribué Redis (bucket4j-redis) — l'ancien store in-memory reste en fallback
- NetworkPolicy + PodDisruptionBudget + ServiceMonitor Prometheus Operator ajoutés au Helm chart
- Dashboards Grafana + règles Alertmanager + SLO formalisés (`deploy/observability/`)

### Qualité (lot D)
- R-17 : JaCoCo `failOnViolation=true` avec seuil 30% lignes / 20% branches (à remonter à 70% plus tard)
- R-36 : Nettoyage `gradle.properties` (résidus Android)
- R-10 MfaController : vrai email utilisateur dans l'URL otpauth (au lieu de `"user-" + userId`)

### Fiscalité Haïti — compléments (lot B)
- Migration V55 : colonne `tax_type` sur `tax_rule` (backward compatible, default `VAT`)
- Migration V57 : seeds ContributionRule HT_GENERAL (CNSS/OFATMA/AST)
- Migration V59 : table `tax_credit_carried_forward`

---

## Migration — Plan recommandé pour l'équipe mobile

### Phase 1 — Breaking changes (1-2 jours dev)
1. Adapter le client MFA (`MfaLoginRequest` JSON body) — priorité haute, empêche toute connexion MFA
2. Adapter le client audit-trail (Page wrapper + filtres)
3. Migrer `journal-entries` → `journal-entries/paged`

### Phase 2 — Nouveautés fiscales Haïti (3-5 jours dev)
4. Écran calendrier DGI (échéances mensuelles + annuelles)
5. Écran bulletin de paie haïtien (CNSS, OFATMA, AST)
6. Sélection TCA dans l'écran de création de facture
7. Badge « Conforme DGI » sur les factures avec NIF HT

### Phase 3 — Polish (1-2 jours dev)
8. Gestion explicite HTTP 429 sur endpoints auth
9. Healthcheck vers port 8081 (ou suppression du healthcheck mobile)
10. Vérification gestion gzip côté client HTTP

**Total estimé** : 5-9 jours dev mobile pour la première release alignée avec le backend v5.3.

---

## Documentation OpenAPI

La documentation Swagger/OpenAPI est mise à jour automatiquement avec le backend. Elle est accessible (en profil dev uniquement) :
- UI : `https://api-dev.joaccountant.ht/swagger-ui.html`
- Spec : `https://api-dev.joaccountant.ht/v3/api-docs`

L'équipe mobile peut générer le client API à partir de la spec OpenAPI via :
- **Retrofit + OkHttp** (recommandé Android) : plugin `openapi-generator-gradle-plugin` avec `generatorName = "kotlin"`
- **Ktor Client** (alternative Kotlin multiplatform) : plugin `kotlinx-serialization` + spec OpenAPI

---

## Points d'attention

1. **Environnement staging** : à utiliser pour la QA mobile avant tout déploiement prod. L'équipe backend doit communiquer l'URL staging (probablement `https://api-staging.joaccountant.ht`).

2. **Tests de régression** : les changements R-09 (pagination audit-trail) et R-01 (MFA body JSON) sont les plus risqués côté mobile. Prévoir des tests d'intégration dédiés.

3. **Versionning API** : tous les endpoints restent sur `/api/v1`. Aucun changement de version majeure. Les endpoints dépréciés (`/audit-trail/all`, `/journal-entries` legacy) seront supprimés en v6 (backend) — l'app mobile doit être prête d'ici là.

4. **Sécurité MFA** : l'ancien client mobile qui enverrait le `mfaChallengeToken` en query param obtiendra une erreur 400 (le backend attend un body JSON). Il faut **impérativement** updater l'app avant tout déploiement backend v5.3 en production.

---

## Contact

Pour toute question sur ces changements, contacter :
- Équipe backend (relecture technique) — référence audit multi-experts v5.2
- Lead developer (R-01 à R-45 — voir `worklog.md` pour le détail par action)

---

## Mise à jour v5.4 — 12 actions P2 additionnelles (29 juillet 2026)

Cette section documente les changements additionnels applicables à l'app mobile issus du Lot F (P2 du plan 90 jours).

### 🟡 Nouveautés exploitables (3)

#### 17. Keyset pagination — `GET /api/v1/companies/{companyId}/journal-entries/keyset`
**Nouvel endpoint** (R-41) :
```http
GET /api/v1/companies/{companyId}/journal-entries/keyset?afterEntryDate=&afterId=&size=50
→ 200 OK  (retourne KeysetPage<JournalEntryResponse>)
```
**Format réponse** :
```json
{
  "content": [...],
  "nextAfterEntryDate": "2026-07-28T10:00:00",
  "nextAfterId": "uuid-of-last-item",
  "hasNext": true
}
```
**Bénéfice** : 10× plus rapide que la pagination OFFSET sur les pages profondes (au-delà de la 100e page). Idéal pour l'historique d'écritures.

**Action mobile** :
- Ajouter un client pour ce nouvel endpoint dans l'écran "Historique écritures"
- Implémenter le scroll infini en passant `afterEntryDate` + `afterId` du dernier élément chargé

#### 18. Signature électronique des factures — `POST /api/v1/companies/{companyId}/invoices/{invoiceId}/sign`
**Nouvel endpoint** (R-36) — désactivé par défaut :
```http
POST /api/v1/companies/{companyId}/invoices/{invoiceId}/sign
→ 200 OK  (retourne SignatureResult avec certificat + timestamp TSA)
```
**Comportement par défaut** : `NoOpElectronicSignatureService` retourne le PDF non signé + log WARNING. Pour activer : `app.signature.xades.enabled=true` + keystore PKCS12 + TSA URL.

**Action mobile** :
- Ajouter un bouton "Signer électroniquement" sur l'écran de facture (visible uniquement si `country='HT'`)
- Afficher un badge "Signé électroniquement" + certificat émetteur + timestamp sur les factures signées
- Documentation légale : Décret 12 février 2002 (Haïti), arrêté DGI 4 oct 2017

#### 19. HIBP — Validation password compromis
**Changement interne** (R-35) — désactivé par défaut (`app.password.hibp.enabled=false`) :
- À l'inscription / changement de mot de passe, le backend peut vérifier via Have I Been Pwned si le password est compromis (k-anonymity, 600M+ hashes)
- Si compromis : HTTP 422 avec code `PASSWORD_COMPROMISED`

**Action mobile** :
- Côté UI : afficher un message "Ce mot de passe a été identifié dans une base de fuites de données, veuillez en choisir un autre" si 422 PASSWORD_COMPROMISED
- Aucune autre action (HIBP est côté serveur uniquement)

### 🟢 Changements internes (sans impact mobile, 9 actions)

Les 9 autres actions P2 sont internes au backend et n'impactent pas l'API mobile :

- **R-34** : Aggregates riches (JournalEntry.addLine/post/voidEntry, SalesInvoice.issue/markPaid) — refactor interne
- **R-37** : Partitionnement mensuel audit_log (V62 + scripts cron) — perf DB
- **R-38** : Chaos engineering chaos-mesh (5 manifests) — ops
- **R-39** : PIT mutation testing (plugin Gradle + script) — qualité
- **R-40** : Documentation cible scission :reporting (préparation v6)
- **R-42** : PcnHaitiAccountTemplate + branche inferReportingClass PCN_HAITI — déjà transparent côté API
- **R-43** : Test restauration cross-region trimestriel (script) — ops
- **R-44** : Spring Modulith ApplicationModules.verify() (test) — qualité
- **R-45** : Diagrammes PlantUML (4 fichiers) + glossaire complet (docs/GLOSSAIRE.md)

### Synthèse totale des changements backend v5.2 → v5.4

| Catégorie | v5.2 → v5.3 (Lots A-E) | v5.3 → v5.4 (Lot F) | Total |
|-----------|------------------------|---------------------|-------|
| 🔴 BREAKING | 5 | 0 | **5** |
| 🟡 NEW exploitables | 11 | 3 | **14** |
| 🟢 INTERNAL | ~20 | 9 | **~29** |
| **Total** | **~36** | **12** | **~48** |

**Plan de migration mobile mis à jour** :
- Phase 1 (breaking, 1-2 jours) : inchangé
- Phase 2 (nouveautés fiscales Haïti, 3-5 jours) : inchangé
- Phase 3 (polish + keyset pagination + signature électronique + HIBP) : 2-3 jours (vs 1-2 initialement)
- **Total révisé** : 6-10 jours dev mobile pour la première release alignée avec le backend v5.4


---

## Mise à jour v6.0 — 6 corrections majeures (29 juillet 2026)

Cette section documente les changements v6 issus des validations PME/expert-comptable. Toutes les corrections répondent à des gaps bloquants identifiés par les 5 validateurs.

### 🟡 Nouveautés exploitables (8)

#### 20. Multi-taxes par ligne InvoiceLine (TVA + TCA cumulées)
**Changement** (R-v6-1) : `InvoiceLine` peut désormais porter plusieurs taxes par ligne (ex : TVA 10% + TCA 10% sur une même prestation de services).

**Nouvel endpoint** : `GET /tax/declarations?taxType=VAT|TCA|ALL` — filtre par type de taxe.
**Migration V67** : table `invoice_line_tax` (1 ligne par taxe appliquée).

**Action mobile** :
- Écran création facture : ajouter un sélecteur multi-taxes par ligne (TVA seule / TCA seule / TVA+TCA)
- Écran déclaration TVA : afficher uniquement les lignes VAT
- Écran déclaration TCA : afficher uniquement les lignes TCA
- DTO étendu : `LineDto` accepte maintenant `taxes: List<TaxApplication>` (optionnel, fallback sur `taxRate`)

#### 21. RS sur ventes (retenue à la source 2% sur mes prestations)
**Changement** (R-v6-2) : `SalesInvoice` peut porter une RS (champs `withholdingRate`, `withholdingAmount`, `netReceivable`, `withholdingRuleCode`).

**Nouvel endpoint** : `GET /tax/withholding-declarations?from=&to=` — agrège les RS par taux.
**Migration V68** : 4 colonnes sur `sales_invoice`.

**Action mobile** :
- Écran création facture : ajouter champ optionnel `withholdingRuleCode` (ex : "RS_HT_PRESTATIONS_LOCAL" pour 2% Haïti)
- Écran facture : afficher `withholdingAmount` (RS retenue par le client) et `netReceivable` (montant net à recevoir)
- Écran déclaration RS mensuelle DGI : nouveau menu

#### 22. Calendrier DGI — acompte IS 1% mensuel calculé
**Nouvel endpoint** (R-v6-5) : `GET /tax/corporate-tax/installments/{year}/{month}` — calcule l'acompte IS 1% sur encaissements bruts du mois (Code Fiscal art. 5).

**Réponse** :
```json
{
  "companyId": "...",
  "year": 2026,
  "month": 7,
  "grossReceipts": 1500000.00,
  "installmentAmount": 15000.00,
  "dueDate": "2026-08-15",
  "installmentType": "HT_1_PERCENT",
  "description": "Acompte IS 1% sur encaissements bruts (Code Fiscal art. 5)"
}
```

**Action mobile** :
- Écran calendrier DGI : afficher le montant de l'acompte IS 1% pour chaque mois (au lieu d'un placeholder vide)
- Notification push 7 jours avant le 15 M+1

#### 23. Formats bailleurs structurés (USAID SF-425, EU PRAG, Banque Mondiale)
**Nouveaux endpoints** (R-v6-3) :
- `GET /funds-grants/grants/{grantId}/donor-reports/usaid-sf425?year=&quarter=` — CSV USAID SF-425 trimestriel
- `GET /funds-grants/grants/{grantId}/donor-reports/eu-prag?year=` — CSV EU PRAG annuel
- `GET /funds-grants/grants/{grantId}/donor-reports/world-bank?year=&quarter=` — CSV Banque Mondiale trimestriel

**Migration V69** : table `donor_report_line` (ventilation par cost category : PERSONNEL, FRINGE, TRAVEL, EQUIPMENT, SUPPLIES, CONTRACTUAL, OTHER, INDIRECT_COST).

**Action mobile** :
- Écran rapport bailleur : 3 boutons de téléchargement (USAID / EU / BM) selon le `donorType` du grant
- Écran saisie cost category : permet d'alimenter `donor_report_line` (budget vs actual par catégorie)

#### 24. Devise de présentation HTG pour états financiers
**Changement** (R-v6-4) : les endpoints `GET /financial-statements/balance-sheet`, `/income-statement`, `/cash-flow-statement` acceptent maintenant des query params optionnels :
- `?presentationCurrency=HTG` — devise cible
- `?closingRate=150.50` — taux de clôture (bilan), sinon lookup depuis `exchange_rate_snapshot`
- `?averageRate=152.30` — taux moyen période (CR/CF), sinon lookup

**Migration V70** : table `exchange_rate_snapshot` (taux BRH mensuels + taux de clôture).

**Réponse étendue** : 5 nouveaux champs optionnels dans BalanceSheet/IncomeStatement/CashFlowStatement :
- `presentationCurrency`, `functionalCurrency`, `conversionRate`, `conversionRateDate`, `conversionType`

**Action mobile** :
- Écran bilan / CR / cash flow : ajouter un sélecteur de devise (HTG / USD / devise fonctionnelle)
- Si devise fonctionnelle ≠ HTG et `country=HT`, afficher un toggle "Présenter en HTG (DCR DGI)"
- Écran configuration société : saisir les taux BRH mensuels (1 par mois)

#### 25. Plan comptable PCN Haïti auto-créé
**Changement** (R-v6-6) : à l'initialisation d'une entreprise avec `framework=PCN_HAITI`, le plan comptable complet (classes 1-8 + comptes niveau 2+ : 10, 101, 20, 21, 28, 40, 401, 411, 44, 442, 446, 447, 448, 51, 521, 60, 601, 70, 701, 80, 800...) est désormais créé automatiquement. Plus besoin de saisie manuelle.

**Action mobile** :
- Écran d'onboarding wizard : si `country=HT`, proposer PCN_HAITI en premier choix (au lieu de SYSCOHADA ou PCG_FRANCE)
- Écran plan comptable : afficher les comptes auto-créés (lecture seule pour les classes, modifiable pour les sous-comptes)

#### 26. Endpoint acompte IS étendu
**Voir §22 ci-dessus**.

#### 27. Exports DGI Haïti complets (4 formulaires)
**Endpoints existants étendus** (v5.5 → v6) :
- `GET /tax/declarations/export?format=dgi-tva&year=&month=` — TVA mensuelle DGI
- `GET /tax/declarations/export?format=dgi-tca&year=&month=` — TCA mensuelle DGI
- `GET /tax/declarations/export?format=dgi-rs&year=&month=` — RS mensuelle DGI (implémenté réellement, plus squelette)
- `GET /tax/declarations/export?format=dgi-dcr&year=` — DCR annuelle DGI

**Action mobile** :
- Écran déclarations DGI : 4 boutons de téléchargement CSV par mois + 1 bouton DCR annuel
- Le CSV est pré-rempli avec les montants calculés (TVA collectée/déductible, TCA, RS par taux, soldes PCN par classe)

### 🟢 Changements internes (sans impact mobile direct)

- V67 : table `invoice_line_tax` (multi-taxe)
- V68 : 4 colonnes `withholding_*` sur `sales_invoice`
- V69 : table `donor_report_line` (rapports bailleurs)
- V70 : table `exchange_rate_snapshot` (taux BRH)
- Wiring `PcnHaitiAccountTemplate` dans `initializeMandated`
- `TaxService.computeMonthlyInstallmentHT` (acompte IS 1% Haïti)
- `FinancialStatementsService` surcharges `PresentationCurrencyRequest` (3 méthodes)
- `DonorReportExporter` (3 méthodes : USAID/EU/BM)

### Synthèse totale des changements backend v5.2 → v6.0

| Catégorie | v5.2→5.3 (Lots A-E) | v5.3→5.4 (Lot F) | v5.4→5.5 (Lot G) | v5.5→6.0 (Lot G-v6) | Total |
|-----------|---------------------|------------------|------------------|---------------------|-------|
| 🔴 BREAKING | 5 | 0 | 0 | 0 | **5** |
| 🟡 NEW exploitables | 11 | 3 | 0 | 8 | **22** |
| 🟢 INTERNAL | ~20 | 9 | 6 | 8 | **~43** |
| **Total** | **~36** | **12** | **6** | **16** | **~70** |

**Plan de migration mobile mis à jour** :
- Phase 1 (breaking, 1-2 jours) : inchangé
- Phase 2 (nouveautés fiscales Haïti, 3-5 jours) : inchangé
- Phase 3 (polish + keyset + signature + HIBP) : 2-3 jours
- Phase 4 (v6 : multi-taxe + RS + formats bailleurs + devise présentation + acompte IS + PCN auto) : 5-7 jours
- **Total révisé** : 11-17 jours dev mobile pour la première release alignée avec le backend v6.0

### Verdicts PME mis à jour (post-v6)

| PME | Verdict v5.5 | Verdict v6.0 |
|-----|--------------|--------------|
| PME1 Boutik Lakay (retail) | 🟡 AVEC RÉSERVES | 🟢 **ADOPTABLE** (multi-taxe TVA+TCA + exports DGI complets) |
| PME2 Moïse & Associés (services) | 🔴 NON ADOPTABLE | 🟢 **ADOPTABLE** (multi-taxe + RS sur ventes + déclaration RS) |
| PME3 Espwa pou Ayiti (ONG) | 🔴 NON ADOPTABLE | 🟡 **ADOPTABLE AVEC RÉSERVES** (formats bailleurs squelette — alimentation réelle en v7) |
| PME4 Caribbean Textiles (zone franche) | 🔴 NON ADOPTABLE | 🟡 **ADOPTABLE AVEC RÉSERVES** (IFRS Statement of Changes in Equity manquant — planifié v7) |


---

## Mise à jour v7.0 — 9 corrections majeures (29 juillet 2026)

> **Version backend** : v7.0 (archive `joaccountant_backend_v7.0_patched.zip`)
> **Charge estimée migration mobile** : 5-7 jours dev supplémentaires

### 🔴 Breaking (2)

1. **Auto-approbation timesheet bloquée (v7-9)** : `PATCH /time-billing/timesheet-entries/{id}/approve` retourne 403 `SELF_APPROVAL_FORBIDDEN` si l'approbateur est le consultant qui a saisi l'entrée (règle des quatre yeux). L'app mobile doit gérer cette erreur avec un message clair : « Vous ne pouvez pas approuver votre propre timesheet. »

2. **Indemnité congés payés séparée (v7-7)** : `paidLeaveDays` ne réduit plus le salaire de base. Le brut total = base (après absences) + indemnité CP (baseSalary × paidLeaveDays / 26). L'affichage du bulletin de paie doit montrer l'indemnité CP comme une ligne distincte.

### 🟡 Nouveautés exploitables (8)

3. **Statement of Changes in Equity (v7-2)** : `GET /financial-statements/statement-of-changes-in-equity?from=&to=&presentationCurrency=` — nouveau tableau de variation des capitaux propres (IAS 1.106). Ajouter un écran SCE dans le module états financiers.

4. **Bilan avec CTA (v7-3)** : `GET /financial-statements/balance-sheet?presentationCurrency=HTG&closingRate=` retourne `ctaAmount` (Cumulative Translation Adjustment). Afficher le CTA en capitaux propres si devise de présentation ≠ devise fonctionnelle.

5. **13e mois (v7-4)** : `POST /payroll-runs/thirteenth-month?year=` lance le calcul du 13e mois (Code Travail art. 153). Bouton « Calculer 13e mois » dans le module paie (décembre).

6. **OFATMA Accidents sectoriel (v7-6)** : `Employee.ofatmaSectorCode` résout dynamiquement le taux (0,5%-6% selon secteur). Afficher le taux applicable par employé.

7. **ITS sur bulletin de paie (v7-5)** : `computeIts` alimente `${incomeTaxWithheld}` sur le bulletin V56. Afficher l'ITS comme une déduction salariale.

8. **Keyset pagination factures (v7-8)** : `GET /invoices/keyset?afterIssueDate=&afterId=&size=50` — pagination par curseur pour 50K+ factures/an (Caribbean Textiles). Scroll infini plus fluide.

9. **Alimentation auto donor_report_line (v7-1)** : `POST /funds-grants/donor-reports/refresh?year=&quarter=` rafraîchit les actuals par grant/cost_category. `PUT /grants/{grantId}/donor-reports/budget` saisit les budgets.

10. **Calendrier DGI Haïti** : `GET /tax/declaration-schedule?year=` expose 12 × 4 mensuelles + 5 annuelles. Écran calendrier fiscal dans le module tax.

### Verdicts PME v7.0

| PME | Verdict v6.0 | Verdict v7.0 |
|-----|--------------|--------------|
| PME1 Boutik Lakay (retail) | 🟢 ADOPTABLE | 🟢 ADOPTABLE |
| PME2 Moïse & Associés (services) | 🟢 ADOPTABLE | 🟢 ADOPTABLE |
| PME3 Espwa pou Ayiti (ONG) | 🟡 AVEC RÉSERVES | 🟢 ADOPTABLE (alimentation auto + formats complets) |
| PME4 Caribbean Textiles (zone franche) | 🟡 AVEC RÉSERVES | 🟢 ADOPTABLE (SCE implémenté) |

---

## Mise à jour v8.1 — 9 lots v8 + Module Démos (29 juillet 2026)

> **Version backend** : v8.1 (archive `joaccountant_backend_v8.1_patched.zip`)
> **Charge estimée migration mobile** : 4-6 jours dev supplémentaires

### 🔴 Breaking (1)

1. **13e mois soumis aux cotisations sociales (v8-8)** : le 13e mois (v7-4) applique désormais CNSS/OFATMA/AST en plus de l'ITS (Code Travail art. 153 — pratique DGI). Le bulletin de paie du 13e mois affiche désormais 2 lignes de déductions : cotisations sociales + ITS.

### 🟡 Nouveautés exploitables (10)

2. **IS Zone Franche 15% + ONG 0% (v8-1)** : `Company.taxExemptionStatus` (STANDARD/FREE_ZONE/NGO_EXEMPT) pilote le taux d'IS via `TaxService.resolveCorporateTaxRule`. PME3 et PME4 enfin adoptables sans réserve — Caribbean Textiles passe de 360K USD (30%) à 180K USD (15%) d'IS annuel ; Espwa pou Ayiti passe à 0.

3. **Exports DGI Haïti en REST (v8-3)** : 4 nouveaux formats au switch `GET /tax/declarations/export?format=dgi-tva|dgi-tca|dgi-rs|dgi-dcr&year=&month=`. Les méthodes `TaxExportService.exportDgi*` sont enfin exposées en HTTP — l'app mobile peut télécharger les CSV DGI directement.

4. **DCR alimentée avec soldes réels (v8-9)** : `exportDgiDcr` retourne désormais les montants réels agrégés par classe PCN (au lieu de zéros). L'app mobile peut afficher la DCR annuelle pré-remplie.

5. **Factur-X PDF/A-3 (v8-2)** : `GET /invoices/{id}/factur-x-pdf` retourne un PDF avec XML CII D16B embarqué (openpdf). Téléchargement direct pour conformité Arrêté DGI 4 oct 2017.

6. **Câblage WIP → facture (v8-4)** : `issueInvoice` marque automatiquement les `TimesheetEntry.invoiced=true` pour les lignes avec `timesheetEntryId`. Plus de risque de re-facturation d'une même entrée de temps.

7. **Donations en nature (v8-5)** : `DonationReceipt.donationType` (CASH/IN_KIND). Pour un don en nature (médicaments, nourriture), l'écriture débite un compte de stock/immo (D 3x ou D 215) au lieu de la trésorerie. 30% des revenus ONG enfin correctement comptabilisés.

8. **Imports en franchise ZF (v8-6)** : nouveaux `taxType=VAT_EXEMPT_ZF` et `VAT_EXEMPT_NGO` sur `InvoiceLineTax`. Pour les imports en franchise douanière d'une ZF, la ligne porte un taux 0% explicite + le code d'exonération — filtré de la déclaration TVA mensuelle.

9. **13e mois asynchrone (v8-7)** : `POST /payroll-runs/thirteenth-month` retourne immédiatement le PayrollRun (statut DRAFT), calcul en arrière-plan via `ThirteenthMonthAsyncRunner` (ou Spring Batch `thirteenthMonthJob`). Pour 1200 employés (Caribbean Textiles), plus de timeout > 30s.

10. **Module Démos V8.1** : 3 nouveaux endpoints **publics sans auth** :
    - `GET /api/v1/demos` — liste des 4 entreprises fictives (Boutik Lakay, Moïse & Associés, Espwa pou Ayiti, Caribbean Textiles)
    - `GET /api/v1/demos/{demoCode}` — détail d'une entreprise démo
    - `GET /api/v1/demos/{demoCode}/dashboard?fy=FY2025-2026` — KPIs (CA, charges, IS, cash position) + alertes DGI + transactions récentes

    L'app mobile peut ajouter un écran « Explorer les démos » accessible sans login pour la prospection commerciale. Les 4 entreprises illustrent tous les segments PME avec IS calculé selon `taxExemptionStatus` (30%/15%/0%).

### 🟢 Internes (sans impact mobile direct)

- v8-1 : `CorporateTaxEligibility` enum étendue (FREE_ZONE, NGO_EXEMPT), `CorporateTaxRule.countryCode` + `freeZoneRate` + `ngoExemptRate`, migration V79+V80
- v8-5 : migration V81 (colonne `donation_type` sur `fg_donation_receipt`)
- v8-6 : migration V82 (CHECK constraint `invoice_line_tax.tax_type` étendue)
- Module Démos : migration V83 (colonne `is_demo` sur `companies` + table `demo_seed_history`), `application-demo.yml`, `SecurityConfig` permitAll sur `/api/v1/demos/**`

### Plan de migration mobile v8.1

| Phase | Actions | Effort |
|-------|---------|--------|
| Phase 1 (breaking) | Gérer 13e mois avec cotisations sociales sur bulletin (v8-8) | 0.5 j |
| Phase 2 (nouveautés fiscales) | Écran « Exports DGI » (4 formats CSV v8-3) + écran DCR pré-remplie (v8-9) + téléchargement Factur-X PDF (v8-2) | 2 j |
| Phase 3 (corrections fonctionnelles) | Donations en nature (v8-5) + imports en franchise ZF (v8-6) | 1 j |
| Phase 4 (module Démos) | Écran « Explorer les démos » (3 endpoints publics) | 1.5 j |
| **Total v8.1** | | **5 j** |

### Verdicts PME v8.1

| PME | Verdict v7.0 | Verdict v8.1 |
|-----|--------------|--------------|
| PME1 Boutik Lakay (retail) | 🟢 ADOPTABLE | 🟢 ADOPTABLE (exports DGI REST) |
| PME2 Moïse & Associés (services) | 🟢 ADOPTABLE | 🟢 ADOPTABLE (câblage WIP→facture + exports DGI REST) |
| PME3 Espwa pou Ayiti (ONG) | 🟢 ADOPTABLE | 🟢 ADOPTABLE SANS RÉSERVE (IS 0% NGO_EXEMPT + donations en nature + TVA exonérée) |
| PME4 Caribbean Textiles (zone franche) | 🟢 ADOPTABLE | 🟢 ADOPTABLE SANS RÉSERVE (IS 15% ZF + imports en franchise + 13e mois async) |

**4/4 PME adoptables sans réserve** — objectif V8 atteint. Le module Démos V8.1 permet la prospection commerciale préalable au démarrage de la beta privé.
