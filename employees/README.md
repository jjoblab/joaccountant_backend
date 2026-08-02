# Module : employees

> Fiches employés (RH) rattachées à un `ThirdParty` de type EMPLOYEE — ne génère **aucune**
> écriture comptable.

## Rôle du module

Le module `:employees` porte les fiches employés (RH) — salaire de base, contrat, statut,
numéro de compte bancaire pour virement. Il est **toujours-actif** (always-on — voir
`BusinessTypeModuleService.alwaysOnModules`) : toute entreprise a des employés.

Le module **ne génère aucune écriture comptable** (comme `:third-parties`). Les écritures
de paie sont générées par `:payroll` qui consomme cette entité en lecture.

## Ce qu'il fait précisément

### Entités principales

- `Employee` — fiche employé. Champs : `thirdPartyId` (FK vers `ThirdParty` de type
  `EMPLOYEE`), `employeeNumber` (unique par entreprise), `position`, `department`,
  `hireDate`, `terminationDate` (nullable), `baseSalary` (BigDecimal), `salaryCurrency`,
  `contractType` (PERMANENT/FIXED_TERM/CONSULTANT), `status` (ACTIVE/ON_LEAVE/TERMINATED),
  `bankAccountNumber` (nullable, pour virement paie).
  **V60 (audit v4.7 §4.1 Finding #18)** : `overtimeHours25` (heures sup majorées +25 %),
  `overtimeHours50` (heures sup majorées +50 %), `absenceDays` (jours d'absence non
  rémunérés), `paidLeaveDays` (jours de congés payés pris sur la période). Tous NOT NULL
  DEFAULT 0 (intégrité des calculs `PayrollCalculator`). Ces 4 champs sont consommés par le
  moteur de paie V51 pour calculer le brut ajusté.

### Règles métier clés

1. **`employeeNumber` unique par entreprise** — contrainte `uc_emp_company_number`.
   Lève `409 EMPLOYEE_NUMBER_ALREADY_EXISTS` si violation.
2. **Tiers de type EMPLOYEE obligatoire** — la fiche employé doit référencer un `ThirdParty`
   de type `EMPLOYEE`. Deux variantes de création :
   - L'employeur a déjà créé le tiers via `:third-parties` → passer `thirdPartyId`.
   - L'employeur n'a pas encore créé le tiers → passer `thirdPartyName` +
     `collectiveAccountId` (compte collectif employés, classe 42 en SYSCOHADA) ; le tiers
     est créé en même temps que l'employé.
3. **Statut `ACTIVE` par défaut** — `ON_LEAVE` pour un congé, `TERMINATED` pour un départ.
   Si `TERMINATED` sans `terminationDate` explicite, la date du jour est utilisée.
4. **Filtre `status=ACTIVE`** — utilisé par `:payroll` pour lister les salariés à payer
   sur une période.
5. **Aucune écriture comptable** — le module ne poste rien dans `:accounting-engine`.

### Cycle de vie des objets

- `Employee` : créé à `ACTIVE` → peut passer à `ON_LEAVE` → revient à `ACTIVE` →
  éventuellement `TERMINATED` (irréversible au MVP — pas de endpoint de réactivation).
- Pas de suppression physique (audit).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/employees` | Crée un employé (+ optionnellement le tiers EMPLOYEE) | 409 `EMPLOYEE_NUMBER_ALREADY_EXISTS`, 422 `THIRD_PARTY_REQUIRED`/`THIRD_PARTY_NOT_EMPLOYEE`/`COLLECTIVE_ACCOUNT_REQUIRED` |
| GET | `/api/v1/companies/{companyId}/employees` | Liste les employés (filtrable par `?status=ACTIVE`) | — |
| GET | `/api/v1/companies/{companyId}/employees/{id}` | Détail d'un employé | 404 `Employee` |
| POST | `/api/v1/companies/{companyId}/employees/{id}/status` | Change le statut (`?status=TERMINATED`) | — |

> Pas de `403 MODULE_NOT_ENABLED` — le module est **toujours-actif** (always-on).

## Relations avec les autres modules

### Dépendances

- `:core` — `TenantAwareEntity`, exceptions.
- `:audit-trail` — auditing.
- `:third-parties` — `ThirdParty`, `ThirdPartyRepository`, `ThirdPartiesService` (pour la
  création composite du tiers en même temps que l'employé).
- `:company` — `BusinessTypeModuleService.alwaysOnModules` référence `EMPLOYEES`.

### Modules qui dépendent de celui-ci

- `:payroll` — consomme `Employee` en lecture pour lister les salariés `ACTIVE` à payer
  sur une période (`EmployeeRepository.findByCompanyIdAndStatusOrderByIdAsc`).

### Événements publiés / consommés

- **Publie** : aucun au MVP (une `EmployeeTerminatedEvent` serait à ajouter si un
  consommateur est identifié — ex. bloquer les futurs paiements automatiquement).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V37__employees.sql` — table `employee`. CHECK sur
  `contract_type` et `status`. Index sur `(company_id, status)`, `third_party_id`.
  Contrainte unique `(company_id, employee_number)`.
- `src/main/resources/db/migration/V60__employees_overtime_absences.sql` — **V60 — Finding #18**.
  Ajoute 4 colonnes NOT NULL DEFAULT 0 sur `employee` :
  `overtime_hours_25` (NUMERIC 19,4 — heures sup majorées +25 %, taux horaire × 1.25),
  `overtime_hours_50` (NUMERIC 19,4 — heures sup majorées +50 %, taux horaire × 1.50),
  `absence_days` (NUMERIC 19,4 — jours d'absence non rémunérés, déduits du `baseSalary` au
  prorata), `paid_leave_days` (NUMERIC 19,4 — jours de congés payés pris sur la période,
  déduits du `baseSalary` au prorata — indemnité CP séparée prévue en v4.8).

## Points d'attention

- ⚠️ **Pas de PATCH implémenté** — la modification d'une fiche employé (changement de
  salaire, de poste) n'est pas implémentée au MVP. Seul le changement de statut est
  exposé. Pour les autres modifications, l'utilisateur doit récréer la fiche (ou attendre
  l'implémentation d'un endpoint PATCH dédié).
- ⚠️ **Pas de gestion de congés détaillée** — `ON_LEAVE` est un statut simple sans date
  de début/fin. La gestion fine des congés payés et calculs d'indemnités est hors scope.
- ⚠️ **Pas d'historique des salaires** — `baseSalary` est unique sur la fiche. Un
  historique des salaires (avec dates d'effet) serait à ajouter si besoin (couplé à
  `:payroll` pour appliquer le bon salaire sur une période passée).

## Tests

Couvert par `EmployeesIntegrationTest` dans `:app` — création d'employé avec tiers
préexistant, création composite (tiers + employé en une fois), unicité de
`employeeNumber`, filtrage par statut.
