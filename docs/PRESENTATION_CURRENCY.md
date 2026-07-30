# Devise de présentation des états financiers (Task v6-4-presentation-currency)

> Implémentation de la conversion des états financiers (bilan, compte de résultat, tableau de
> flux de trésorerie) vers une **devise de présentation** distincte de la devise fonctionnelle —
> typiquement pour produire la **DCR annuelle DGI Haïti en HTG** à partir d'une comptabilité tenue
> en USD (ONG haïtienne ou société en zone franche).

## 1. Contexte — pourquoi cette fonctionnalité

Les validateurs PME3 (ONG Espwa pou Ayiti) + PME4 (Caribbean Textiles SA, zone franche CODEVI)
ont identifié ce gap comme **BLOQUANT** :

- Les ONG haïtiennes tiennent leur comptabilité en **USD** (devise fonctionnelle — bailleurs en
  USD) mais doivent déclarer la **DCR annuelle en HTG** à la DGI (Code Fiscal art. 195).
- Les sociétés en zone franche (Caribbean Textiles) facturent en USD exclusivement (clients
  américains) mais déclarent en HTG.
- Avant v6-4, `FinancialStatementsService` ne supportait **aucun** paramètre `presentationCurrency` :
  bilan et compte de résultat en devise fonctionnelle uniquement, avec retraitement manuel
  obligatoire pour la DGI en HTG — non conforme DCLS annuelle.

## 2. Conformité IAS 21 — Effets des variations des cours des monnaies étrangères

La conversion applique les deux conventions de la norme IAS 21 :

| État financier            | Convention IAS 21                     | Implémentation v6-4                                          |
|---------------------------|---------------------------------------|-------------------------------------------------------------|
| Bilan                     | Taux de clôture (postes monétaires)   | `exchange_rate_snapshot` type `CLOSING` à la date du bilan  |
| Compte de résultat        | Taux moyen de la période              | `exchange_rate_snapshot` type `PERIOD_AVERAGE` mensuels + moyenne arithmétique |
| Tableau de flux (IAS 7)   | Taux moyen de la période (flux) + taux de clôture (soldes ouverture/clôture trésorerie) | **Squelette v6** — taux moyen unique appliqué à tous les postes (voir §5 Limitations) |

## 3. Procédure opérationnelle

### 3.1. Saisie mensuelle du taux BRH

Pour chaque mois de l'exercice, créer un snapshot `PERIOD_AVERAGE` à partir du taux officiel
publié par la **Banque de la République d'Haïti (BRH)** :

```sql
INSERT INTO exchange_rate_snapshot
    (company_id, from_currency, to_currency, rate, rate_date,
     source, snapshot_type, period_year, period_month)
VALUES
    ('0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd', 'USD', 'HTG', 152.30,
     DATE '2026-01-31', 'BRH', 'PERIOD_AVERAGE', 2026, 1);
```

Répéter pour chaque mois (12 enregistrements pour un exercice annuel).

### 3.2. Saisie du taux de clôture

À la date de clôture (31 décembre), créer un snapshot `CLOSING` :

```sql
INSERT INTO exchange_rate_snapshot
    (company_id, from_currency, to_currency, rate, rate_date,
     source, snapshot_type)
VALUES
    ('0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd', 'USD', 'HTG', 150.50,
     DATE '2026-12-31', 'BRH', 'CLOSING');
```

### 3.3. Génération des états financiers en HTG

Les endpoints REST acceptent désormais 2 query params optionnels :
- `presentationCurrency` (ISO 4217 — ex. `HTG`)
- `closingRate` ou `averageRate` (taux à utiliser directement, sinon lookup automatique dans
  `exchange_rate_snapshot`)

Si `presentationCurrency` est omis ou égal à la devise fonctionnelle → comportement v5.5 inchangé
(backward-compat).

## 4. Exemples d'appels API

### 4.1. Bilan au 31/12/2026 en HTG (lookup automatique du taux de clôture)

```http
GET /api/v1/companies/0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd/financial-statements/balance-sheet?asOf=2026-12-31&presentationCurrency=HTG
```

Réponse :
```json
{
  "companyId": "0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd",
  "asOf": "2026-12-31",
  "assets": [ ... ],
  "liabilities": [ ... ],
  "equity": [ ... ],
  "totalAssets": 187500000.00,
  "totalLiabilities": 112500000.00,
  "totalEquity": 75000000.00,
  "balanced": true,
  "presentationCurrency": "HTG",
  "functionalCurrency": "USD",
  "conversionRate": 150.50,
  "conversionRateDate": "2026-12-31",
  "conversionType": "CLOSING"
}
```

### 4.2. Bilan au 31/12/2026 en HTG avec taux fourni directement (sans snapshot préalable)

```http
GET /api/v1/companies/0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd/financial-statements/balance-sheet?asOf=2026-12-31&presentationCurrency=HTG&closingRate=150.50
```

### 4.3. Compte de résultat 2026 en HTG (lookup du taux moyen mensuel)

```http
GET /api/v1/companies/0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd/financial-statements/income-statement?from=2026-01-01&to=2026-12-31&presentationCurrency=HTG
```

Le service recherche les snapshots `PERIOD_AVERAGE` pour les 12 mois de 2026 et calcule la
moyenne arithmétique. Si aucun snapshot n'est trouvé → HTTP 422
`PRESENTATION_AVERAGE_RATE_REQUIRED` (saisir `averageRate` directement dans ce cas).

### 4.4. Tableau de flux de trésorerie 2026 en HTG (squelette v6 — taux unique)

```http
GET /api/v1/companies/0192a8d5-1c2d-3e4f-5a6b-7c8d9e0fabcd/financial-statements/cash-flow-statement?from=2026-01-01&to=2026-12-31&presentationCurrency=HTG
```

## 5. Limitations v6-4 — planifié v7

1. **Cumul de translation adjustment (CTA)** : la conversion v6 multiplie chaque solde par le
   taux approprié sans isoler l'écart de conversion. En IAS 21 strict, l'écart de conversion
   (différence entre le bilan converti au taux de clôture et le CR converti au taux moyen) doit
   être isolé en capitaux propres sous un compte « Cumul des écarts de conversion ». Non
   implémenté en v6 — planifié v7.

2. **Tableau de flux de trésorerie** : en v6, un taux moyen unique est appliqué à tous les flux
   (operating / investing / financing) ET aux soldes d'ouverture et de clôture de trésorerie.
   En IAS 7 strict, les soldes d'ouverture / clôture de trésorerie doivent être convertis au
   taux de clôture et les flux au taux moyen. Planifié v7.

3. **Taux moyen par sous-période** : en v6, on calcule la moyenne arithmétique simple des taux
   mensuels disponibles. Une approche plus rigoureuse pondérerait chaque taux mensuel par le
   volume d'écritures du mois (taux moyen pondéré). Planifié v7.

4. **Pas de réévaluation automatique des écritures en devise** : le snapshot de taux BRH sert
   uniquement à la conversion des états financiers. La réévaluation des soldes en devises à la
   clôture (générer une écriture comptable de gain/perte de change latent) est gérée par le
   module `:fx-operations` (type `REVALUATION`) — pas par cette tâche.

## 6. Fichiers ajoutés / modifiés

### Ajouts

- `fx-operations/src/main/resources/db/migration/V70__exchange_rate_snapshot_closing.sql` —
  table `exchange_rate_snapshot`
- `fx-operations/src/main/java/jo/accountant/fxoperations/entity/ExchangeRateSnapshot.java` —
  entité JPA
- `fx-operations/src/main/java/jo/accountant/fxoperations/repository/ExchangeRateSnapshotRepository.java` —
  repository avec `findLatestClosingRate` et `findAverageRateForPeriod`
- `financial-statements/src/main/java/jo/accountant/financialstatements/dto/PresentationCurrencyRequest.java` —
  DTO de requête (record)
- `docs/PRESENTATION_CURRENCY.md` — cette documentation

### Modifications

- `financial-statements/build.gradle.kts` — ajout des dépendances `:company` et `:fx-operations`
- `financial-statements/src/main/java/jo/accountant/financialstatements/dto/BalanceSheet.java` —
  5 nouveaux champs (presentationCurrency, functionalCurrency, conversionRate,
  conversionRateDate, conversionType) + constructeur backward-compat 9-args
- `financial-statements/src/main/java/jo/accountant/financialstatements/dto/IncomeStatement.java` —
  idem (8-args backward-compat)
- `financial-statements/src/main/java/jo/accountant/financialstatements/dto/CashFlowStatement.java` —
  idem (11-args backward-compat)
- `financial-statements/src/main/java/jo/accountant/financialstatements/service/FinancialStatementsService.java` —
  3 surcharges acceptant `PresentationCurrencyRequest` + helpers de conversion + 2 nouvelles
  dépendances (CompanyRepository, ExchangeRateSnapshotRepository)
- `financial-statements/src/main/java/jo/accountant/financialstatements/controller/FinancialStatementsController.java` —
  3 endpoints étendus avec query params optionnels `presentationCurrency` / `closingRate` /
  `averageRate`

## 7. Backward compatibility

- Les anciennes méthodes `getBalanceSheet(companyId, asOf)`,
  `getIncomeStatement(companyId, from, to)`, `getCashFlowStatement(companyId, from, to)` sont
  conservées et délèguent aux nouvelles avec `presentation = null`.
- Les constructeurs 9-args / 8-args / 11-args des records DTO sont conservés (champs de
  conversion à null).
- Les endpoints REST acceptent les query params optionnels — un appel sans `presentationCurrency`
  se comporte exactement comme en v5.5.
- Les consommateurs JSON existants voient juste 5 nouveaux champs null dans les réponses
  (pas de breaking change).
