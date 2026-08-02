# Convention de nommage des migrations Flyway — JOAccountant

**Dernière mise à jour** : 2026-08-02
**Convention** : **Un majeur unique par module Gradle** (29 modules, majeurs 0 à 28).

---

## Format

```
V<majeur>_<mineur>__<description_en_snake_case>.sql
```

- `<majeur>` — numéro unique du module Gradle (0 à 28). Chaque module a exactement un majeur, et toutes les migrations d'un module partagent ce majeur.
- `<mineur>` — séquence à 3 chiffres au sein du module (`001`, `002`, ..., `NNN`), triée par ordre chronologique d'ajout.
- `<description>` — description courte en snake_case.

## Règle d'incrémentation

- **Toute migration d'un module** utilise le majeur de ce module et incrémente le mineur.
- **Un module ne doit JAMAIS avoir de migrations sous un autre majeur.**
- Pour ajouter une migration dans un module existant, utiliser le prochain mineur disponible pour ce majeur.

## Table des modules et de leurs majeurs

| Majeur | Module | Nb migrations | Plage de mineurs |
|---:|---|---:|---|
| V0 | app | 3 | _001 à _003 |
| V1 | core | 4 | _001 à _004 |
| V2 | auth | 3 | _001 à _003 |
| V3 | company | 16 | _001 à _016 |
| V4 | document-numbering | 3 | _001 à _003 |
| V5 | chart-of-accounts | 4 | _001 à _004 |
| V6 | approval-workflow | 2 | _001 à _002 |
| V7 | analytics | 1 | _001 à _001 |
| V8 | accounting-engine | 8 | _001 à _008 |
| V9 | financial-statements | 2 | _001 à _002 |
| V10 | third-parties | 3 | _001 à _003 |
| V11 | fixed-assets | 3 | _001 à _003 |
| V12 | inventory | 1 | _001 à _001 |
| V13 | time-billing | 2 | _001 à _002 |
| V14 | document-generation | 7 | _001 à _007 |
| V15 | invoicing | 7 | _001 à _007 |
| V16 | bank-reconciliation | 2 | _001 à _002 |
| V17 | funds-grants | 6 | _001 à _006 |
| V18 | notifications | 1 | _001 à _001 |
| V19 | tax | 12 | _001 à _012 |
| V20 | reporting | 1 | _001 à _001 |
| V21 | purchasing | 3 | _001 à _003 |
| V22 | expenses | 3 | _001 à _003 |
| V23 | employees | 5 | _001 à _005 |
| V24 | payroll | 3 | _001 à _003 |
| V25 | fx-operations | 2 | _001 à _002 |
| V26 | purchase-orders | 1 | _001 à _001 |
| V27 | audit-trail | 2 | _001 à _002 |
| V28 | demo-data | 1 | _001 à _001 |

## Dernier couple utilisé

- **Module le plus élevé** : `V28` (demo-data)
- **Dernier mineur** : `V28_001`

## Prochaine migration

- Dans un module existant (ex. `tax` = V19) : `V19_<prochain_minor>__<desc>.sql`
- Nouveau module : `V29_001__<desc>.sql`

## Ordre d'exécution Flyway

Flyway trie GLOBALEMENT par nom de fichier. Avec cette convention, l'ordre est :

```
V0_001 (app) → V0_002 → ... → V0_N
→ V1_001 (core) → V1_002 → ... → V1_N
→ V2_001 (auth) → ... → V2_N
→ ...
→ V28_001 (demo-data) → ... → V28_N
```

Toutes les migrations d'un module s'exécutent consécutivement, puis on passe
au module suivant. L'ordre des modules (V0 à V28) préserve les dépendances
inter-modules (les modules fondamentaux comme `app`, `core`, `auth`,
`company` s'exécutent en premier).

## Exemples

```bash
# Bon — migration dans le module tax (majeur 19)
V19_013__add_new_tax_rule_template.sql

# MAUVAIS — un module ne doit pas avoir plusieurs majeurs
V20_001__core_exchange_rate.sql   # ❌ (core doit utiliser V1_xxx uniquement)
```
