# Glossaire — JOAccountant Backend

> R-45 (lot-F-ops-docs) — Glossaire complet des acronymes techniques et fiscaux utilisés dans le projet, avec focus sur le contexte fiscal haïtien.

## Fiscalité Haïti (DGI)

| Acronyme | Signification | Contexte |
|----------|---------------|----------|
| **AST** | Ajustement Social Temporaire | Cotisation sociale haïtienne, barème progressif par tranches mensuelles (Décret 2017 modifié). S'applique sur le salaire brut. |
| **CNSS** | Caisse Nationale de Sécurité Sociale | Cotisation 6% employeur + 6% salarié, plafonnée à 6 PMT (≈ 150 000 HTG/mois en 2024). Loi CNSS art. 17. |
| **CSPDP** | Commission Sur la Protection des Données Personnelles | Autorité de contrôle du Décret 23 mai 2018 sur les données personnelles en Haïti. |
| **DCLS** | États financiers (bilan, compte de résultat, notes annexes) | Annexe de la DCR soumise à la DGI. Équivalent des états financiers SYSCOHADA. |
| **DCR** | Déclaration Comptable et Fiscale Résumée | Déclaration annuelle DGI Haïti, échéance 31 mars N+1. Récapitulatif des opérations comptables de l'exercice. |
| **DCRf** | DCR feuillet 1 (résumé) | Feuillet résumé de la DCR. |
| **DCRG** | DCR Grand livre | Annexe de la DCR contenant le grand livre comptable. |
| **DGI** | Direction Générale des Impôts | Administration fiscale haïtienne. Équivalent de la DGFiP française. |
| **HTG** | Gourde haïtienne (HTG = ISO 4217) | Devise officielle d'Haïti. Code ISO : HTG. Souvent combinée avec USD (pays dollarisé). |
| **IS** | Impôt sur Sociétés | Haïti : 30% sur bénéfice net (Code Fiscal art. 4). 15% en zones franches. Acompte 1% sur encaissements (art. 5). |
| **NIF** | Numéro d'Identification Fiscale | Identifiant fiscal haïtien (10 chiffres + 2 lettres : `^[0-9]{10}[A-Z]{2}$`). Obligatoire sur factures depuis arrêté DGI 4 oct 2017. |
| **OFATMA** | Office d'Assurance Accidents du Travail, Maladie et Maternité | Cotisation sociale haïtienne : Santé 3% employeur + 1% salarié, Accidents 2% (default, variable 0.5-6% selon secteur). |
| **PCN** | Plan Comptable National (Haïti) | Référentiel comptable haïtien, 8 classes (1 Capitaux, 2 Immo, 3 Stocks, 4 Tiers, 5 Financiers, 6 Charges, 7 Produits, 8 Comptes spéciaux). |
| **PMT** | Plafond Mensuel des Traitements | Plafond CNSS Haïti ≈ 6 × SMG ≈ 150 000 HTG/mois en 2024. |
| **RS** | Retenue à la Source | Code Fiscal Haïti art. 156 — 2% sur prestations locales, 10% sur royalties, 30% sur services non-résidents. |
| **RAS** | Retenue à l'Avance sur les Sommes | Acompte IS 1% sur encaissements (Code Fiscal art. 5). |
| **SMG** | Salaire Minimum Général | SMIC haïtien, base de calcul PMT. En 2024 : ~25 000 HTG/mois (varie par secteur). |
| **TCA** | Taxe sur le Chiffre d'Affaires | Code Fiscal Haïti art. 196 — 2% banques (art. 197), 5% télécommunications, 10% autres services. Distincte de la TVA. |
| **TVA** | Taxe sur la Valeur Ajoutée | Haïti : 10% (loi du 24 juillet 2002), sur débits (Code Fiscal art. 191). Différente de la TVA française (20%, 10%, 5.5%, 2.1%). |

## Fiscalité française (CGI — référence initiale du projet)

| Acronyme | Signification | Contexte |
|----------|---------------|----------|
| **CA3** | Déclaration de TVA (formulaire français) | Déclaration mensuelle/trimestrielle TVA en France. Échéance 19 du mois M+1. |
| **CGI** | Code Général des Impôts (français) | Référentiel fiscal français. Utilisé par erreur dans le code initial de JOAccountant. |
| **C. trav.** | Code du travail (français) | Référentiel social français. Articles R3243-1 (mentions bulletin de paie). |
| **DCRf FR** | Déclaration de revenus (France) | Différente de la DCR haïtienne. |
| **DES** | Déclaration d'Échange de Services (intra-UE) | Déclaration mensuelle française pour prestations intra-UE. Article 289 B CGI. |
| **EFI** | Échange de Formulaires Informatisés | Format de télédéclaration français. |
| **eIDAS** | Electronic IDentification, Authentication and trust Services | Règlement européen sur les signatures électroniques qualifiées. |
| **Factur-X** | Format de facturation électronique française | Cross Industry Invoice D16B, profil BASICWL, EN 16931. Conforme Loi 2023-314. |
| **LPF** | Livre des Procédures Fiscales (France) | Article L102B : conservation pièces comptables 10 ans. |
| **PCG** | Plan Comptable Général (France) | Référentiel comptable français, 7 classes. |
| **PMSS** | Plafond Mensuel de la Sécurité Sociale (France) | Plafond cotisations sociales françaises (3 864 €/mois en 2024). |
| **PAdES** | PDF Advanced Electronic Signature | Standard européen de signature électronique de PDF. Profil B-LT qualifié eIDAS. |
| **SYSCOHADA** | Système Comptable OHADA | Référentiel comptable africain francophone (17 pays), 8 classes + TAFIRE. |
| **TAFIRE** | Tableau d'Analyse Financière des Résultats et Emplois | État financier SYSCOHADA équivalent du cash flow statement. |
| **URSSAF** | Union de Recouvrement de la Sécurité Sociale et des Allocations Familiales | Organisme français de recouvrement cotisations sociales. |

## Comptabilité IFRS / Internationale

| Acronyme | Signification | Contexte |
|----------|---------------|----------|
| **BS** | Balance Sheet (Bilan) | État financier IFRS. |
| **CF** | Cash Flow (Tableau de flux de trésorerie) | État financier IAS 7. |
| **CR** | Income Statement (Compte de résultat) | État financier IFRS. |
| **FIFO** | First In, First Out | Méthode de valorisation des stocks. |
| **IAS 7** | International Accounting Standard 7 | Norme IFRS sur le tableau de flux de trésorerie. |
| **IAS 16** | Immobilisations corporelles | Norme IFRS — amortissement par composants. |
| **IAS 21** | Effets des variations des cours des monnaies étrangères | Norme IFRS — réévaluation des comptes en devises. |
| **IAS 36** | Dépréciation d'actifs | Norme IFRS — test de dépréciation (impairment). |
| **IFRS** | International Financial Reporting Standards | Référentiel comptable international. |
| **PCGR** | Plan Comptable Général Révisé (Canada) | Référentiel comptable canadien francophone. |
| **SCE** | Statement of Comprehensive Income | État financier IFRS. |
| **WORM** | Write Once Read Many | Mécanisme d'immutabilité (audit_log, archives). |

## Sécurité & Authentification

| Acronyme | Signification | Contexte |
|----------|---------------|----------|
| **AAL** | Authenticator Assurance Level | NIST 800-63B — AAL2 = MFA requise. |
| **AES-256-GCM** | Advanced Encryption Standard, 256-bit, Galois/Counter Mode | Algorithme de chiffrement symétrique utilisé pour les secrets MFA. |
| **Argon2id** | Algorithme de hash de mots de passe | Vainqueur Password Hashing Competition 2015. Paramètres OWASP 2024 : 19 MiB, t=2, p=1. |
| **CVE** | Common Vulnerabilities and Exposures | Base publique de vulnérabilités. |
| **HIBP** | Have I Been Pwned | Base de 600M+ mots de passe compromis. Intégré via k-anonymity (R-35). |
| **HS256** | HMAC-SHA256 (JWT signature) | Algorithme JWT symétrique. Défaut dans JOAccountant. |
| **IDOR** | Insecure Direct Object Reference | Vulnérabilité accès non autorisé via énumération d'ID. |
| **JWT** | JSON Web Token | Standard RFC 7519 pour tokens d'authentification stateless. |
| **JWKS** | JSON Web Key Set | Standard RFC 7517 pour exposer les clés publiques de vérification JWT. |
| **MFA** | Multi-Factor Authentication | Authentification multi-facteurs. Implémentée via TOTP RFC 6238. |
| **NIST 800-63B** | Standard NIST sur l'authentification | Référence pour AAL1/AAL2/AAL3. |
| **OWASP Top 10** | Open Web Application Security Project — Top 10 vulnérabilités | Référence sécurité applicative. |
| **PII** | Personally Identifiable Information | Données personnelles — masquées par PiiMasker dans audit_log. |
| **RBAC** | Role-Based Access Control | Contrôle d'accès basé sur les rôles (OWNER/ADMIN/ACCOUNTANT/BOOKKEEPER/VIEWER/AUDITOR). |
| **RLS** | Row Level Security | Mécanisme PostgreSQL d'isolation des lignes par tenant. |
| **RS256** | RSA-SHA256 (JWT signature) | Algorithme JWT asymétrique. Recommandé en prod (clé privée + JWKS). |
| **SAST** | Static Application Security Testing | Analyse statique de code (CodeQL, SonarQube). |
| **SBOM** | Software Bill of Materials | Inventaire des dépendances (CycloneDX, Syft). |
| **SCA** | Software Composition Analysis | Scan de vulnérabilités des dépendances (Trivy, Snyk). |
| **TSA** | Time Stamp Authority | Autorité d'horodatage qualifié pour signatures électroniques. |
| **TOTP** | Time-based One-Time Password | Standard RFC 6238 pour codes MFA. |
| **XAdES** | XML Advanced Electronic Signature | Standard de signature électronique XML. |

## Architecture & DevOps

| Acronyme | Signification | Contexte |
|----------|---------------|----------|
| **ArchUnit** | Architecture testing library for Java | 42 règles vérifiant l'isolation compile-time des modules. |
| **CQRS** | Command Query Responsibility Segregation | Pattern séparation lecture/écriture. Non implémenté (choix assumé). |
| **DDD** | Domain-Driven Design | Méthodologie de conception. Stratégique (bounded contexts = modules) ; tactique (aggregates, R-34) partiellement implémenté. |
| **DR** | Disaster Recovery | Reprise après sinistre. RPO < 5 min, RTO < 1h (PITR + test mensuel). |
| **HPA** | Horizontal Pod Autoscaler | K8s — scale 2→10 replicas sur CPU 70%. |
| **OTLP** | OpenTelemetry Protocol | Protocole d'export traces/metrics/logs. |
| **PDB** | PodDisruptionBudget | K8s — minAvailable=1 pour garantir qu'au moins 1 pod reste UP. |
| **PITR** | Point-In-Time Recovery | Restauration PostgreSQL à un instant T via WAL archiving. |
| **Prometheus Operator** | Operator K8s pour Prometheus | Exposé via ServiceMonitor (Lot E). |
| **RPO** | Recovery Point Objective | Perte de données maximale acceptable. < 5 min (WAL streaming). |
| **RTO** | Recovery Time Objective | Temps de récupération maximal acceptable. < 1h (pgBackRest). |
| **SLO** | Service Level Objective | Objectif de qualité de service. 99.9% dispo, P99 < 1s. |
| **Spring Batch** | Framework Spring pour batch jobs | PayrollRun + FiscalYearClosing (chunk size 50, retry 3×). |
| **Spring Modulith** | Framework Spring pour modules logiques | Vérification runtime boundaries (R-44). |
| **TPS** | Transactions Per Second | Métrique de charge. Cible : 1000+ users concurrents. |
| **WAL** | Write-Ahead Log | Journal PostgreSQL pour PITR. |

## Patterns & Méthodologies

| Acronyme | Signification | Contexte |
|----------|---------------|----------|
| **BOM** | Bill of Materials | Spring Boot BOM pour aligner les versions de dépendances. |
| **DTO** | Data Transfer Object | Objets de transfert API (Records Java 17, 146 DTOs dans le projet). |
| **DRY** | Don't Repeat Yourself | Principe anti-duplication. |
| **FK** | Foreign Key | Clé étrangère PostgreSQL. |
| **KDF** | Key Derivation Function | Dérivation de clé (PBKDF2, HKDF). Manquant pour app.mfa.encryption-key (cf audit). |
| **PIT** | Mutation Testing | Tests de mutation (info.solidsoft.pitest, R-39). |
| **RLS** | Row Level Security | Voir section Sécurité. |
| **SRP** | Single Responsibility Principle | Principe SOLID — une classe = une responsabilité. Violé par god classes (FixedAssetsService 1366 LOC). |

## Références réglementaires clés

### Haïti
- **Code Fiscal Haïtien** : art. 4 (IS 30%), 5 (acompte IS 1%), 156 (RS 2%), 191 (TVA sur débits), 196 (mentions factures), 197 (TCA banques 2%), 240 (pénalités 10-50%)
- **Code du Travail Haïtien** : art. 4 (apprentissage), 32 (durée légale 48h/sem), 36 (HS 50%/100%), 153 (13ᵉ mois), 156 (congés 15 jours ouvrables)
- **Décret 23 mai 2018** : protection des données personnelles (CSPDP)
- **Décret 12 février 2002** : signature électronique (certificat qualifié)
- **Arrêté DGI 4 octobre 2017** : facturation électronique (NIF obligatoire)

### France (référence initiale du projet — à corriger)
- **CGI** : art. 219 (IS 25%/15% PME), 289 (TVA), 283 2 nonies (reverse charge UE), 289 B (DES), 1668 (acomptes IS)
- **LPF** : L102B (conservation 10 ans)
- **Loi 2023-314** : facturation électronique B2B France (Factur-X)

### International
- **OWASP Top 10 2021** : référence sécurité applicative
- **NIST 800-63B** : authentification (AAL2 = MFA)
- **RFC 7807** : Problem Details for HTTP APIs
- **RFC 6238** : TOTP
- **RFC 7517** : JWKS
- **EN 16931** : Factur-X
- **IAS 7 / 16 / 21 / 36** : normes IFRS

---

*Document mis à jour le 29 juillet 2026 — R-45 (lot-F-ops-docs). Pour toute modification, mettre à jour également le rapport d'audit PDF correspondant.*
