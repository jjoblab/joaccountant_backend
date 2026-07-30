# Guide Démo — JOAccountant V8.1

> Guide utilisateur pour les commerciaux et prospects utilisant les démos publiques.

## Accès aux démos

Les démos sont accessibles publiquement (sans login) sur :

- **API REST** : `https://demo.joaccountant.ht/api/v1/demos`
- **Web app** : `https://demo.joaccountant.ht` (page d'accueil démos)
- **App mobile** : onglet « Explorer les démos »

## Les 4 entreprises démos

### 1. Boutik Lakay S.A. — Commerce retail (Pétion-Ville)

**Profil** : Commerce de détail (alimentation, ménagers, cosmétiques), 4 employés, CA ~6M HTG/an.

**Points clés à montrer** :
- Multi-taxe TVA 10% + TCA 10% sur livraisons (V67)
- Stock FIFO avec COGS automatique
- 13e mois en décembre (Code Travail art. 153)
- Déclarations DGI mensuelles (TVA + TCA + RS + acompte IS 1%)

**Scénario démo** : « Saisie d'une vente avec livraison → calcul TVA+TCA → déclaration DGI mensuelle »

### 2. Moïse & Associés Conseil S.A. — Services pro (Port-au-Prince)

**Profil** : Cabinet de conseil, 8 consultants, CA ~18M HTG/an.

**Points clés à montrer** :
- Time-billing multi-niveaux (BillableRate projet + ressource)
- Auto-approbation timesheet bloquée (règle 4 yeux, v7-9)
- RS 2% retenue par clients + RS 30% non-résidents (V64)
- Multi-taxe TVA + TCA cumulatives sur même facture

**Scénario démo** : « Saisie timesheet → approbation manager → génération facture depuis WIP → RS 2% retenue »

### 3. Espwa pou Ayiti — ONG humanitaire (Port-au-Prince)

**Profil** : ONG, 35 employés, budget ~60M HTG/an (~5M USD), 4 bailleurs (USAID, EU, BM, CRS).

**Points clés à montrer** :
- 4 subventions par bailleur avec restrictions RESTRICTED/UNRESTRICTED
- Formats bailleurs structurés (USAID SF-425, EU PRAG, BM)
- Alimentation auto `donor_report_line` via tagging comptable (v7-1)
- IS 0% NGO_EXEMPT + TVA exonérée (Code Fiscal art. 195, v8-1)
- Conversion USD → HTG + CTA en capitaux propres (v7-3)

**Scénario démo** : « Saisie dépense USD ventilée par grant → refresh donor_report_line → export USAID SF-425 trimestriel »

### 4. Caribbean Textiles S.A. — Zone franche industrielle (CODEVI)

**Profil** : Industrie textile 100% export USA, 1200 employés, CA ~144M HTG/an (~12M USD), IFRS_FULL.

**Points clés à montrer** :
- IS 15% zone franche (Code Fiscal art. 195, v8-1)
- TVA 0% export + imports en franchise douanière (v8-6 VAT_EXEMPT_ZF)
- Keyset pagination 50K factures/an (v7-8)
- Spring Batch paie 1200 employés + 13e mois async (v8-7)
- IFRS_FULL complet : Bilan + CTA + CR + CF + SCE IAS 1.106 (v7-2)

**Scénario démo** : « Lancement 13e mois décembre pour 1200 employés → suivi async → bilan IFRS en USD → conversion HTG pour DCR DGI »

## FAQ prospects

**Q : Les données sont-elles réalistes ?**
R : Oui — noms, adresses, produits, taux BRH, barèmes CNSS/OFATMA/AST/ITS sont basés sur des
valeurs haïtiennes réelles 2024-2026. Les barèmes fiscaux (ITS, AST) sont marqués
« À VALIDER AVEC DGI » car ils évoluent annuellement.

**Q : Puis-je cloner une démo pour mon entreprise ?**
R : Oui via `POST /api/v1/demos/{demoCode}/clone` (rôle ADMIN) — crée une entreprise vide avec
la même configuration (framework, modules activés, paramètres fiscaux).

**Q : Les démos sont-elles sécurisées ?**
R : Les endpoints GET sont publics (lecture seule). Les entreprises démos ont `is_demo=TRUE`
dans la DB — filtrage automatique, aucune fuite vers les entreprises réelles.

**Q : Comment sont calculés les KPIs ?**
R : En V8.1, les KPIs sont des estimations conformes au profil de chaque PME. En V9, ils seront
calculés depuis les écritures comptables réelles agrégées par période.
