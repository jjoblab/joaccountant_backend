# Signature électronique — Cadre légal et intégration

**R-36 — lot-F3-security** · Framework extensible de signature électronique pour JOAccountant Backend.

Ce document détaille le cadre légal haïtien, les autorités de certification reconnues, les étapes d'intégration technique et l'estimation des coûts pour activer une signature électronique conforme sur les factures, avoirs, bulletins de paie et états financiers.

---

## 1. Cadre légal

### 1.1 Haïti

#### Décret du 12 février 2002 sur la signature électronique

Le **Décret du 12 février 2002** (publié au journal officiel *Le Moniteur*) reconnaît la signature électronique comme ayant la **même valeur juridique que la signature manuscrite** (article 5), sous deux conditions :

1. **Identification du signataire** : la signature doit permettre d'identifier le signataire de manière univoque (lien entre le certificat X.509 et la personne physique ou morale).
2. **Intégrité du document** : toute modification ultérieure du document signé doit être détectable (digest cryptographique SHA-256 + chiffrement asymétrique RSA/ECDSA).

Le décret s'inspire directement de la **directive européenne 1999/93/CE** (devenue règlement eIDAS 910/2014). Le standard technique de référence est **XAdES** (XML Advanced Electronic Signature, ETSI EN 319 132) pour XML, et **PAdES** (PDF Advanced Electronic Signature, ETSI EN 319 142) pour PDF.

#### Arrêté DGI du 4 octobre 2017

L'**arrêté ministériel du 4 octobre 2017** de la Direction Générale des Impôts (DGI) impose la signature électronique pour les **factures électroniques transmises à la DGI** (article 3). Toute entreprise haïtienne qui dématérialise ses factures doit :

- Apposer une signature électronique conforme au Décret 2002 (certificat qualifié).
- Conserver les factures signées pendant **10 ans** (Code Fiscal art. 196 — obligation de rétention comptable).
- Pouvoir produire les factures signées en cas de contrôle fiscal (procédure de vérification générale).

#### Code Fiscal Haïtien (art. 196)

Les mentions obligatoires sur les factures (quelle que soit la forme, papier ou électronique) :

- Numéro de facture (séquentiel, sans rupture)
- Date d'émission
- Identification du vendeur (NIF, raison sociale, adresse)
- Identification de l'acheteur (NIF si assujetti)
- Désignation des biens/services
- Montant HT, TVA (10% ou 2% selon produits), TCA le cas échéant, montant TTC

### 1.2 Reconnaissance internationale

Les signatures électroniques avec **certificat qualifié eIDAS** (règlement UE 910/2014) sont reconnues comme équivalentes aux signatures manuscrites dans tous les États membres de l'UE. Pour Haïti :

- Le Décret 2002 ne précise pas de liste de CA qualifiées haïtiennes (à ce jour, **aucune CA haïtienne n'est reconnue internationalement** pour les certificats qualifiés).
- Les CA eIDAS qualifiées (Universign, DocuSign, Chronodoc, FedICT) sont reconnues en Haïti par application du principe de **non-discrimination technologique** (Décret 2002 art. 3) — une signature électronique techniquement équivalente à une signature eIDAS qualifiée ne peut pas être refusée au seul motif qu'elle a été émise par une CA non haïtienne.
- En cas de litige, la **preuve de l'authenticité** incombe à la partie qui produit le document signé. Une signature eIDAS qualifiée est présumée fiable (charge de la preuve inversée).

---

## 2. Autorités de certification

### 2.1 CA haïtiennes potentielles (à valider avec un cabinet juridique)

> ⚠️ À ce jour (2026), il n'existe pas d'autorité de certification haïtienne publiquement reconnue pour les certificats qualifiés. Les pistes ci-dessous doivent être validées par un cabinet juridique haïtien spécialisé en droit du numérique.

| CA potentielle | Statut | Contact | Remarques |
|----------------|--------|---------|-----------|
| **SGS Haïti** (Société Générale de Surveillance) | À valider | sgs.ht@sgs.com | SGS opère des services de certification dans d'autres pays — vérifier si une offre existe en Haïti. |
| **National Certification Authority** (à créer) | Inexistante | — | Une initiative gouvernementale pourrait émerger via le Conseil National des Télécommunications (CONATEL). |
| **BRH** (Banque de la République d'Haïti) | À valider | direction.informatique@brh.ht | Pour le secteur bancaire, la BRH pourrait émettre des certificats pour les institutions financières. |

### 2.2 CA eIDAS qualifiées reconnues (recommandées pour démarrage)

En attendant l'émergence d'une CA haïtienne qualifiée, utiliser une **CA eIDAS qualifiée européenne** :

| CA | Pays | Type | Coût certificat | API | Remarques |
|----|------|------|-----------------|-----|-----------|
| **Universign** | France | eIDAS qualifiée | ~300 EUR/an | REST + SDK Java | Offre Entreprise avec timestamping inclus. Recommandé pour JOAccountant (proximité géographique Haïti-France, support FR). |
| **DocuSign** | EU (Irlande) | eIDAS qualifiée | ~500 EUR/an | REST | Leader du marché signature, mais facture à la signature (0.30-0.50 EUR/doc) — cher pour 1000+ factures/mois. |
| **Chronodoc** | France | eIDAS qualifiée | ~250 EUR/an | REST + iText plugin | Offre packagée avec iText pour PAdES. |
| **FedICT** | Belgique | eIDAS qualifiée | ~400 EUR/an | REST | Utilisé par les institutions belges. |
| **D-Trust** | Allemagne | eIDAS qualifiée | ~350 EUR/an | PKCS#11 | CA allemande, support technique EN/DE. |

**Recommandation** : pour JOAccountant, démarrer avec **Universign** (300 EUR/an + 0.10 EUR/signature). Voir §4 pour le détail.

### 2.3 TSA (Time Stamp Authorities) qualifiés

Le timestamp RFC 3161 (XAdES-T) est optionnel mais recommandé — il prouve que le document existait à un instant T, même si le certificat est révoqué ultérieurement.

| TSA | Type | Coût | URL |
|-----|------|------|-----|
| **DigiCert** | Gratuit | 0 EUR | `https://timestamp.digicert.com` |
| **Universign** | Payant (inclus dans offre signature) | 0 EUR | `https://tsa.universign.com` |
| **FreeTSA** | Gratuit | 0 EUR | `https://freetsa.org/tsr` |
| **Sectigo** | Gratuit | 0 EUR | `http://timestamp.sectigo.com` |

---

## 3. Architecture technique

### 3.1 Framework extensible (implémenté R-36)

Le framework se compose de 7 fichiers dans `invoicing/src/main/java/jo/accountant/invoicing/signature/` :

| Fichier | Rôle |
|---------|------|
| `ElectronicSignatureService.java` | Interface — `sign(byte[], DocumentType, UUID)` et `verify(byte[])`. |
| `SignableDocumentType.java` | Enum : INVOICE, CREDIT_NOTE, PAYSLIP, FINANCIAL_STATEMENT. |
| `SignatureResult.java` | Record : signedBytes, certificateSerialNumber, certificateIssuer, signedAt, tsaTimestamp, signatureAlgorithm. |
| `NoOpElectronicSignatureService.java` | Implémentation par défaut (dev/test) — retourne le document non signé. |
| `XAdESSignatureService.java` | Squelette XAdES (désactivé par défaut) — TODO intégration xades4j. |
| `SignatureProperties.java` | `@ConfigurationProperties(prefix="app.signature.xades")`. |
| `SignatureConfig.java` | `@Configuration` — enregistre NoOp ou XAdES selon `app.signature.xades.enabled`. |

### 3.2 Endpoint

`POST /api/v1/companies/{companyId}/invoicing/invoices/{invoiceId}/sign`

- Rôle requis : `BOOKKEEPER`
- Flow : génère le PDF de la facture → appelle `ElectronicSignatureService.sign()` → retourne le PDF signé avec métadonnées en headers.
- Réponse : PDF binaire (`application/pdf`) avec headers `X-Signature-*`.
- Codes de retour : 200 (signé ou NoOp), 403 (rôle), 404 (facture introuvable), 409 (DRAFT), 501 (XAdES squelette non intégré).

### 3.3 Configuration

```yaml
app:
  signature:
    xades:
      enabled: ${XADES_ENABLED:false}            # false = NoOp par défaut
      keystore-path: ${XADES_KEYSTORE_PATH:}     # /etc/joaccountant/keystore/cert.p12
      keystore-password: ${XADES_KEYSTORE_PASSWORD:}  # secret K8s
      keystore-type: ${XADES_KEYSTORE_TYPE:PKCS12}
      tsa-url: ${XADES_TSA_URL:}                 # https://timestamp.digicert.com
```

### 3.4 Backward compatibility

- Par défaut (`XADES_ENABLED=false` ou absent), c'est `NoOpElectronicSignatureService` qui est actif.
- L'application démarre sans aucune configuration de signature.
- Le endpoint `/sign` répond 200 OK mais retourne le PDF non signé (avec `X-Signature-Algorithm=noop`).
- Aucune dépendance externe ajoutée (pas de xades4j, pas de BouncyCastle supplémentaire — Caffeine déjà présent).

---

## 4. Étapes d'intégration réelle (TODO v4.9+)

Pour activer une signature électronique réelle :

### Étape 1 — Obtenir un certificat qualifié

1. Souscrire une offre Universign Entreprise (https://www.universign.com/entreprise/).
2. Fournir les documents KYC (KBIS Haïti ou équivalent, pièce d'identité du représentant légal).
3. Universign émet un certificat X.509 qualifié eIDAS (validité 1-3 ans).
4. Télécharger le keystore PKCS12 (fichier `.p12`).

### Étape 2 — Intégrer la lib xades4j

Ajouter dans `invoicing/build.gradle.kts` :

```kotlin
implementation("com.github.luisgoncalves.xades4j:xades4j:2.4.0")
```

Vérifier la dernière version sur https://github.com/luisgoncalves/xades4j/releases.

### Étape 3 — Implémenter XAdESSignatureService.sign() et verify()

Voir le squelette dans `XAdESSignatureService.java` — les TODO détaillent les étapes :

1. Charger le keystore PKCS12 (`KeyStore.getInstance("PKCS12")`).
2. Extraire la clé privée + certificat X.509 qualifié.
3. Calculer le digest SHA-256 du document (canonicalization C14N pour XML).
4. Signer le digest avec la clé privée (RSA-SHA256).
5. Embarquer la signature XAdES-BES dans le document.
6. Si `tsaUrl` configuré : demander un timestamp RFC 3161 → niveau XAdES-T.
7. Retourner `SignatureResult(signedBytes, certSerial, certIssuer, signedAt, tsa, algo)`.

### Étape 4 — Configurer le keystore en production

- Stocker le `.p12` dans un **secret Kubernetes** (pas dans une ConfigMap — c'est un secret).
- Monter le secret en volume read-only dans le pod : `/etc/joaccountant/keystore/cert.p12`.
- Variables d'environnement :
  - `XADES_ENABLED=true`
  - `XADES_KEYSTORE_PATH=/etc/joaccountant/keystore/cert.p12`
  - `XADES_KEYSTORE_PASSWORD` (depuis un secret K8s)
  - `XADES_TSA_URL=https://timestamp.digicert.com`

### Étape 5 — Tests

- Test unitaire : mocker la lib xades4j, vérifier que `sign()` produit une `SignatureResult` valide.
- Test d'intégration : avec un certificat de test (Universign fournit des certificats de test), signer une facture de test et vérifier la signature avec `verify()`.
- Test de non-régression : vérifier que `NoOpElectronicSignatureService` reste actif si `XADES_ENABLED=false`.

### Étape 6 — Audit et conformité

- Faire valider l'implémentation par un cabinet juridique haïtien (conformité Décret 2002).
- Documenter la procédure de renouvellement du certificat (alerte 60 jours avant expiration).
- Mettre en place une procédure de révocation en cas de compromission du certificat.

---

## 5. Estimation des coûts

### 5.1 Coût d'obtention du certificat (annuel)

| Poste | Coût annuel |
|-------|-------------|
| Certificat qualifié Universign Entreprise | 300 EUR/an |
| Renouvellement (1×/an ou 1×/3 ans) | Inclus |
| Support technique | Inclus |

### 5.2 Coût par signature (variable)

| Poste | Coût unitaire | Volume estimé | Total mensuel |
|-------|---------------|---------------|---------------|
| Timestamp TSA Universign | 0.05 EUR | 1000 factures | 50 EUR |
| Calcul signature (CPU) | ~0 EUR | — | 0 EUR |
| **Total par signature** | **0.05-0.50 EUR** | **1000 factures/mois** | **50-500 EUR/mois** |

Le coût varie selon :
- **Type de signature** : XAdES-BES (0.05 EUR) vs XAdES-LT (0.20 EUR, inclut archive LTC) vs XAdES-A (0.50 EUR, archive longue durée).
- **TSA** : gratuit (DigiCert) vs payant (Universign, 0.05 EUR).
- **CA** : Universign (0.10 EUR) vs DocuSign (0.30-0.50 EUR).

### 5.3 Budget total annuel (estimation)

| Scénario | Coût annuel |
|----------|-------------|
| **Minimal** : 1000 factures/mois, Universign XAdES-BES + DigiCert TSA gratuit | 300 EUR (certificat) + 50 EUR (timestamp Universign) = **350 EUR/an** |
| **Standard** : 5000 factures/mois, Universign XAdES-T + Universign TSA | 300 EUR (certificat) + 250 EUR (timestamp) = **550 EUR/an** |
| **Premium** : 10000 factures/mois, DocuSign XAdES-A | 500 EUR (certificat) + 6000 EUR (signatures) = **6500 EUR/an** |

**Recommandation** : démarrer avec le scénario **Standard** (~550 EUR/an) pour 5000 factures/mois.

---

## 6. Références

- **Décret du 12 février 2002** (Haïti) — *Le Moniteur*, journal officiel.
- **Arrêté DGI du 4 octobre 2017** — Direction Générale des Impôts, Port-au-Prince.
- **Code Fiscal Haïtien** — article 196 (mentions obligatoires factures).
- **Règlement eIDAS 910/2014** (UE) — règlement sur l'identification électronique.
- **ETSI EN 319 132** (XAdES) — XML Advanced Electronic Signature.
- **ETSI EN 319 142** (PAdES) — PDF Advanced Electronic Signature.
- **RFC 3161** — Internet X.509 PKI Time-Stamp Protocol (TSP).
- **Troy Hunt, "I've just launched Pwned Passwords V2"** (2018) — protocole k-anonymity HIBP.

---

## 7. Historique des révisions

| Date | Lot | Auteur | Description |
|------|-----|--------|-------------|
| 2026-08 | R-36 — lot-F3-security | Lot-F3-Security | Création du framework extensible (interface, NoOp, XAdES squelette, endpoint `/sign`). |
| 2026-08 | R-35 — lot-F3-security | Lot-F3-Security | Intégration HIBP pour renforcer la sécurité des mots de passe (pré-requis pour la signature : un compte compromis ne peut pas signer légalement). |
