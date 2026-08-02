# Module : document-generation

> Génération de PDF par rendu Thymeleaf + openhtmltopdf, avec immuabilité des documents générés.

## Rôle du module

Le module `:document-generation` produit les documents PDF de l'application (factures,
avoirs, reçus de dons, bilan, compte de résultat, grand livre, rapport bailleur). Il est
**always-on** (activé pour tous les types métier via `BusinessTypeModuleService.alwaysOnModules()`)
et fonctionne pour les 6 référentiels — les gabarits sont par entreprise, agnostiques au
framework.

Le module ne génère pas d'écritures comptables — il produit uniquement des PDF à partir de
variables fournies par l'appelant (`:invoicing`, `:funds-grants`, `:reporting`).

## Ce qu'il fait précisément

### Entités principales

- `DocumentTemplate` — gabarit HTML Thymeleaf stocké en DB. Champs : `documentType`
  (INVOICE/CREDIT_NOTE/DONATION_RECEIPT/BALANCE_SHEET/INCOME_STATEMENT/GENERAL_LEDGER/
  DONOR_REPORT), `htmlTemplate` (contenu Thymeleaf), `active`, `default` (un seul gabarit
  par défaut par type).
- `GeneratedDocument` — PDF généré et stocké via `FileStoragePort`. Champs :
  `documentType`, `resourceId` (ID de l'entité source — ex. ID de la facture),
  `storageKey` (clé opaque dans le `FileStoragePort`), `contentHash` (SHA-256 du PDF pour
  immuabilité), `generatedAt`.
- `DocumentType` (enum) — 7 valeurs (INVOICE, CREDIT_NOTE, DONATION_RECEIPT, BALANCE_SHEET,
  INCOME_STATEMENT, GENERAL_LEDGER, DONOR_REPORT).

### Règles métier clés

1. **PDF immuable** — si un `GeneratedDocument` existe déjà pour `(companyId, documentType,
   resourceId)`, `POST /documents` sert l'existant et ne régénère pas. Garantit que le PDF
   d'une facture émise ne change pas même si le gabarit est modifié ultérieurement.
2. **Rendu Thymeleaf** — le gabarit HTML est rendu avec les variables fournies par
   l'appelant (ex. `{invoiceNumber, clientName, lines, totalAmount}` pour une facture).
3. **Conversion HTML → PDF** via `openhtmltopdf` (`PdfRendererBuilder`).
4. **Stockage via `FileStoragePort`** — le PDF est stocké avec une clé opaque (UUID), pas
   en DB (la DB ne stocke que la métadonnée `storageKey`). L'implémentation par défaut
   `FileSystemFileStorageAdapter` écrit sur le système de fichiers local.
5. **`contentHash` SHA-256** — le hash du PDF est stocké pour audit (détecter une
   modification ultérieure du fichier stocké).
6. **Logo et en-tête d'entreprise** stockés via `FileStoragePort`, avec repli sur un
   gabarit neutre si non configuré.

### Cycle de vie des objets

- `DocumentTemplate` : créé → activé/désactivé (pas d'endpoint public de désactivation —
  opération DB). Un seul gabarit `default=true` par type.
- `GeneratedDocument` : créé → immuable (pas de mise à jour, pas de suppression). Pour
  régénérer un PDF, il faut supprimer la ligne en DB et le fichier dans `FileStoragePort`
  (opération d'admin).

## Endpoints exposés

| Méthode | Chemin | Description | Codes d'erreur |
|---|---|---|---|
| POST | `/api/v1/companies/{companyId}/document-generation/templates` | Crée un gabarit HTML Thymeleaf | 422 champs invalides |
| GET | `/api/v1/companies/{companyId}/document-generation/templates` | Liste les gabarits | — |
| POST | `/api/v1/companies/{companyId}/document-generation/documents?documentType=&resourceId=` | Génère un PDF (synchrone, immuable). Corps : variables Thymeleaf. | 422 `TEMPLATE_NOT_FOUND`/`RESOURCE_ID_REQUIRED` |
| GET | `/api/v1/companies/{companyId}/document-generation/documents/{resourceId}` | Sert le PDF déjà généré (404 si inexistant) | 404 `GeneratedDocument` |

> `:invoicing` expose aussi `GET /invoices/{id}/pdf` qui délègue à ce module en passant
> `documentType=INVOICE` et `resourceId=invoiceId`.

## Relations avec les autres modules

### Dépendances (modules dont celui-ci dépend)

- `:core` — `TenantAwareEntity`, exceptions, `FileStoragePort`, `ApplicationEventPublisher`.
- Bibliothèques : `org.thymeleaf`, `com.openhtmltopdf`.

### Modules qui dépendent de celui-ci

- `:invoicing` — `GET /invoices/{id}/pdf` appelle `DocumentGenerationService.generateDocument`
  avec `documentType=INVOICE` et les variables de la facture.
- `:funds-grants` — génère les reçus de dons (`documentType=DONATION_RECEIPT`).
- `:reporting` — génère les exports PDF du bilan (`BALANCE_SHEET`), du compte de résultat
  (`INCOME_STATEMENT`), du grand livre (`GENERAL_LEDGER`) et du rapport bailleur
  (`DONOR_REPORT`).

### Événements publiés / consommés

- **Publie** : `DocumentGeneratedEvent` (à chaque génération de PDF).
- **Consomme** : aucun.

## Tables / migrations Flyway

- `src/main/resources/db/migration/V35__document_generation.sql` — tables
  `document_template` et `generated_document`. Unique `(company_id, document_type,
  resource_id)` sur `generated_document` (immuabilité). `content_hash` CHAR(64) pour
  SHA-256. CHECK sur `document_type` (7 valeurs).

## Points d'attention (hérités de l'audit)

- ⚠️ **PDF immuable — pas de régénération** — si l'utilisateur modifie le gabarit après
  avoir émis des factures, les nouvelles factures utiliseront le nouveau gabarit, mais les
  anciennes garderont leur PDF original. C'est le comportement attendu (audit), mais le
  client mobile doit informer l'utilisateur qu'il ne peut pas "régénérer" un PDF déjà émis.
- ⚠️ **Génération synchrone** — `POST /documents` génère le PDF dans le thread de la
  requête HTTP. Pour un grand nombre de lignes (grand livre sur 5 ans), la génération peut
  prendre plusieurs secondes. Le client mobile doit afficher un loader et prévoir un
  timeout long (au moins 30s).
- ⚠️ **`FileStoragePort` par défaut = système de fichiers local** — `FileSystemFileStorageAdapter`
  écrit dans un répertoire local. En production multi-instance, cela ne fonctionne pas sans
  stockage partagé (S3, NFS). L'implémentation de production doit être branchée avant
  déploiement.
- ⚠️ **Gabarit HTML par défaut non fourni** — l'entreprise doit créer ses propres gabarits
  via `POST /templates`. Aucun gabarit par défaut n'est seedé. Le client mobile doit guider
  l'utilisateur vers la création de gabarits avant de pouvoir générer des PDF.
- ⚠️ **Pas de contrôle de rôle** sur `POST /templates` (audit B5) — un `VIEWER` peut créer
  ou modifier un gabarit, ce qui pourrait permettre d'injecter du HTML malveillant. À
  restreindre à ADMIN/OWNER.

## Tests

Couvert par `DocumentGenerationIntegrationTest` dans `:app` (7 tests) — création de
gabarit, génération PDF, immuabilité (un second appel sert l'existant), extraction de texte
du PDF pour vérifier la présence du numéro de document et des montants.
