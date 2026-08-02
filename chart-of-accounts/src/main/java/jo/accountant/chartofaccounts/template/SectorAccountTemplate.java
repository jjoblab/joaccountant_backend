package jo.accountant.chartofaccounts.template;

import java.util.List;
import jo.accountant.core.framework.ReportingClass;

/**
 * Templates sectoriels pour le plan comptable — génère des comptes niveau 2 et 3
 * context-aware selon le type métier de l'entreprise.
 *
 * <p>(suite — feature plan comptable context-aware) : jusqu'ici
 * l'initialisation ne générait que les comptes de niveau 1 (les "classes" du référentiel :
 * 1, 2, 3, ... en SYSCOHADA). Les comptes niveau 2+ (rubriques comme "40 Tiers fournisseurs",
 * "411 Clients locaux", "521 Banque", "601 Achats", "701 Ventes", etc.) étaient créés
 * manuellement par l'utilisateur ou par le script de seed.
 *
 * <p>Cette classe centralise les seeds sectoriels : pour chaque type métier (RETAIL_COMMERCE,
 * PROFESSIONAL_SERVICES, NGO_HUMANITARIAN, ACCOUNTING_FIRM, SCHOOL, HOSPITAL, CUSTOM), on
 * fournit une liste de comptes niveau 2 et 3 typiques que toute entreprise de ce type
 * devrait avoir. Le mobile ou l'interface web n'a plus besoin de savoir qu'un commerce
 * doit avoir "601 Achats de marchandises" — le backend le crée automatiquement à
 * l'initialisation.
 *
 * <p>Convention : le {@code parentCode} référence le code d'un compte niveau 1 existant
 * (ex. "4" pour la classe 4 SYSCOHADA "Tiers"). Le {@code code} est le code du compte
 * à créer (niveau 2 si 2 chiffres, niveau 3 si 3+ chiffres). Les comptes créés via ce
 * template ne sont pas verrouillés (locked=false) — l'utilisateur peut les renommer ou
 * les désactiver.
 *
 * <p>Pour le type métier {@code CUSTOM} (sélection manuelle), on retourne un set minimal
 * générique (banque, caisse, capital) — l'utilisateur crée le reste à la main.
 
 *
 * @author jo@Dev
*/
public final class SectorAccountTemplate {

    private SectorAccountTemplate() {}

    /**
     * Définition d'un compte à créer lors du seed sectoriel.
     *
     * @param parentCode code du parent (compte niveau 1, ex. "4")
     * @param code code du compte à créer (niveau 2 = 2 chiffres, niveau 3 = 3+ chiffres)
     * @param label libellé du compte
     * @param reportingClass classe de reporting universelle (ACTIF / PASSIF / CAPITAUX_PROPRES / PRODUITS / CHARGES)
     * @param subcategory sous-catégorie (COURANT / NON_COURANT / N_A)
     * @param normalBalance sens normal de solde (DEBIT / CREDIT)
     * @param collective true si compte collectif (rattache des tiers)
     * @param taxMappingCode code de mapping fiscal (peut être null) — ex. "ACCOUNTS_PAYABLE",
     * "ACCOUNTS_RECEIVABLE", "CASH", "SALES_REVENUE", "VAT_COLLECTED",
     * "VAT_DEDUCTIBLE", "PURCHASES", "PERSONNEL_EXPENSE",
     * "SALARIES_PAYABLE", "SOCIAL_SECURITY_PAYABLE", "OPERATING_EXPENSE"
     */
    public record AccountSeed(
        String parentCode,
        String code,
        String label,
        ReportingClass reportingClass,
        String subcategory,
        String normalBalance,
        boolean collective,
        String taxMappingCode
    ) {}

    /**
     * Retourne la liste des comptes niveau 2+ à créer pour un type métier donné.
     *
     * <p>Si le type métier est inconnu ou non couvert, retourne un set minimal générique
     * (banque, caisse, capital, ventes, achats) — l'utilisateur complétera à la main.
     */
    public static List<AccountSeed> forBusinessType(String businessTypeCode) {
        if (businessTypeCode == null) {
            return generic();
        }
        return switch (businessTypeCode) {
            case "RETAIL_COMMERCE", "WHOLESALE_COMMERCE", "MIXED_COMMERCE", "ECOMMERCE" -> commerce();
            case "PROFESSIONAL_SERVICES" -> professionalServices();
            case "NGO_HUMANITARIAN" -> ngoHumanitarian();
            case "ACCOUNTING_FIRM" -> accountingFirm();
            case "SCHOOL" -> school();
            case "HOSPITAL" -> hospital();
            default -> generic();
        };
    }

    // ════════════════════════════════════════════════════════════════════
    // COMMERCE (RETAIL / WHOLESALE / MIXED / ECOMMERCE)
    // ════════════════════════════════════════════════════════════════════

    private static List<AccountSeed> commerce() {
        return List.of(
            new AccountSeed("4", "40", "Fournisseurs", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("40", "401", "Fournisseurs locaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("4", "41", "Clients", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("41", "411", "Clients locaux", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("4", "42", "Personnel", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("42", "421", "Personnel - rémunérations dues", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SALARIES_PAYABLE"),
            new AccountSeed("4", "43", "Organismes sociaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SOCIAL_SECURITY_PAYABLE"),
            new AccountSeed("43", "433", "Sécurité sociale", ReportingClass.PASSIF, "COURANT", "CREDIT", false, "SOCIAL_SECURITY_PAYABLE"),
            new AccountSeed("4", "44", "État", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("44", "443", "TVA collectée", ReportingClass.PASSIF, "COURANT", "CREDIT", false, "VAT_COLLECTED"),
            new AccountSeed("44", "445", "TVA déductible", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "VAT_DEDUCTIBLE"),
            new AccountSeed("5", "52", "Banques", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("52", "521", "Banque Nationale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("5", "57", "Caisse", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("57", "571", "Caisse principale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("3", "31", "Stocks de marchandises", ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("31", "310", "Stock de marchandises", ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "60", "Achats", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("60", "601", "Achats de marchandises", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("60", "603", "Variation de stocks", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "61", "Transports", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("61", "611", "Transport sur achats", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "62", "Services extérieurs", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "OPERATING_EXPENSE"),
            new AccountSeed("62", "621", "Loyer commercial", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("62", "622", "Électricité et eau", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("62", "623", "Transport et carburant", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "63", "Charges de personnel", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("63", "631", "Salaires et appointements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("6", "64", "Autres charges", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("64", "676", "Pertes de change", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "FX_LOSS"),
            new AccountSeed("6", "68", "Dotations aux amortissements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "681", "Dotations aux amortissements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("7", "70", "Ventes de marchandises", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("70", "701", "Ventes de marchandises", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("7", "75", "Autres produits", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("75", "758", "Produits accessoires", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("75", "776", "Gains de change", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "FX_GAIN"),
            new AccountSeed("1", "10", "Capital", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("10", "101", "Capital social", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("10", "106", "Réserves", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("1", "12", "Résultat de l'exercice", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("2", "22", "Terrains", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "24", "Matériel", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "244", "Matériel de transport", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "245", "Matériel de bureau", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "28", "Amortissements", ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null),
            new AccountSeed("28", "2844", "Amort. matériel de transport", ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null),
            new AccountSeed("28", "2845", "Amort. matériel de bureau", ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null),
            new AccountSeed("1", "16", "Emprunts", ReportingClass.PASSIF, "NON_COURANT", "CREDIT", false, null),
            new AccountSeed("16", "161", "Emprunt bancaire", ReportingClass.PASSIF, "NON_COURANT", "CREDIT", false, null)
        );
    }

    private static List<AccountSeed> professionalServices() {
        return List.of(
            new AccountSeed("4", "40", "Fournisseurs", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("40", "401", "Fournisseurs locaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("4", "41", "Clients", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("41", "411", "Clients locaux", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("4", "42", "Personnel", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("42", "421", "Personnel - rémunérations dues", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SALARIES_PAYABLE"),
            new AccountSeed("4", "43", "Organismes sociaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SOCIAL_SECURITY_PAYABLE"),
            new AccountSeed("43", "433", "Sécurité sociale", ReportingClass.PASSIF, "COURANT", "CREDIT", false, "SOCIAL_SECURITY_PAYABLE"),
            new AccountSeed("4", "44", "État", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("44", "443", "TVA collectée", ReportingClass.PASSIF, "COURANT", "CREDIT", false, "VAT_COLLECTED"),
            new AccountSeed("44", "445", "TVA déductible", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "VAT_DEDUCTIBLE"),
            new AccountSeed("5", "52", "Banques", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("52", "521", "Banque Nationale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("5", "57", "Caisse", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("57", "571", "Caisse principale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("6", "61", "Transports", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "62", "Services extérieurs", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "OPERATING_EXPENSE"),
            new AccountSeed("62", "621", "Loyer bureau", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("62", "622", "Électricité et eau", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("62", "625", "Honoraires sous-traitants", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("62", "626", "Logiciels et abonnements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "63", "Charges de personnel", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("63", "631", "Salaires et appointements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("6", "64", "Autres charges", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "68", "Dotations aux amortissements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("7", "70", "Ventes de services", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("70", "706", "Prestations de services", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("7", "75", "Autres produits", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("1", "10", "Capital", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("10", "101", "Capital social", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("2", "24", "Matériel", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "245", "Matériel de bureau", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "2455", "Matériel informatique", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "28", "Amortissements", ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null),
            new AccountSeed("28", "2845", "Amort. matériel de bureau", ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null)
        );
    }

    private static List<AccountSeed> ngoHumanitarian() {
        return List.of(
            new AccountSeed("4", "40", "Fournisseurs", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("40", "401", "Fournisseurs locaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("4", "41", "Clients", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("4", "42", "Personnel", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("42", "421", "Personnel - rémunérations dues", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SALARIES_PAYABLE"),
            new AccountSeed("4", "43", "Organismes sociaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SOCIAL_SECURITY_PAYABLE"),
            new AccountSeed("4", "44", "État", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("44", "443", "TVA collectée", ReportingClass.PASSIF, "COURANT", "CREDIT", false, "VAT_COLLECTED"),
            new AccountSeed("5", "52", "Banques", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("52", "521", "Banque Nationale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("5", "57", "Caisse", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("57", "571", "Caisse principale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("6", "60", "Achats", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("60", "601", "Achats de matériels", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("6", "61", "Transports", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "62", "Services extérieurs", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "OPERATING_EXPENSE"),
            new AccountSeed("6", "63", "Charges de personnel", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("63", "631", "Salaires et appointements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("6", "64", "Autres charges", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "68", "Dotations aux amortissements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("7", "70", "Ventes", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("7", "75", "Autres produits", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("75", "758", "Produits divers", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("1", "10", "Fonds associatif", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("10", "102", "Fonds associatif sans droit de reprise", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("10", "106", "Réserves", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("2", "22", "Terrains", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "23", "Bâtiments", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "24", "Matériel", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "244", "Matériel de transport", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "245", "Matériel de bureau", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "28", "Amortissements", ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null)
        );
    }

    private static List<AccountSeed> accountingFirm() {
        return List.of(
            new AccountSeed("4", "40", "Fournisseurs", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("40", "401", "Fournisseurs locaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("4", "41", "Clients", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("41", "411", "Clients locaux", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("4", "42", "Personnel", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("42", "421", "Personnel - rémunérations dues", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SALARIES_PAYABLE"),
            new AccountSeed("4", "43", "Organismes sociaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SOCIAL_SECURITY_PAYABLE"),
            new AccountSeed("4", "44", "État", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("44", "443", "TVA collectée", ReportingClass.PASSIF, "COURANT", "CREDIT", false, "VAT_COLLECTED"),
            new AccountSeed("44", "445", "TVA déductible", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "VAT_DEDUCTIBLE"),
            new AccountSeed("5", "52", "Banques", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("52", "521", "Banque Nationale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("5", "57", "Caisse", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("57", "571", "Caisse principale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("6", "62", "Services extérieurs", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "OPERATING_EXPENSE"),
            new AccountSeed("62", "621", "Loyer bureau", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("62", "622", "Électricité et eau", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("62", "626", "Logiciels comptables et abonnements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "63", "Charges de personnel", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("63", "631", "Salaires et appointements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("6", "64", "Autres charges", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "68", "Dotations aux amortissements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("7", "70", "Ventes de services", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("70", "706", "Honoraires comptables", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("1", "10", "Capital", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("10", "101", "Capital social", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("2", "24", "Matériel", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "245", "Matériel de bureau", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "2455", "Matériel informatique", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "28", "Amortissements", ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null)
        );
    }

    private static List<AccountSeed> school() {
        return List.of(
            new AccountSeed("4", "40", "Fournisseurs", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("40", "401", "Fournisseurs locaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("4", "41", "Clients", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("41", "411", "Parents d'élèves", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("4", "42", "Personnel", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("42", "421", "Personnel - rémunérations dues", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SALARIES_PAYABLE"),
            new AccountSeed("4", "43", "Organismes sociaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SOCIAL_SECURITY_PAYABLE"),
            new AccountSeed("4", "44", "État", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("5", "52", "Banques", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("52", "521", "Banque Nationale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("5", "57", "Caisse", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("57", "571", "Caisse principale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("6", "60", "Achats", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("60", "601", "Fournitures pédagogiques", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("6", "62", "Services extérieurs", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "OPERATING_EXPENSE"),
            new AccountSeed("62", "621", "Loyer et entretien", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("62", "622", "Électricité et eau", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "63", "Charges de personnel", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("63", "631", "Salaires du personnel enseignant", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("63", "632", "Salaires du personnel administratif", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "64", "Autres charges", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "68", "Dotations aux amortissements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("7", "70", "Produits scolaires", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("70", "706", "Frais de scolarité", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("7", "75", "Autres produits", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("75", "758", "Cantine et activités parascolaires", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("1", "10", "Fonds propre", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("10", "102", "Fonds associatif", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("2", "23", "Bâtiments scolaires", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "24", "Matériel", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "245", "Mobilier scolaire", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "2455", "Matériel informatique", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "28", "Amortissements", ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null)
        );
    }

    private static List<AccountSeed> hospital() {
        return List.of(
            new AccountSeed("4", "40", "Fournisseurs", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("40", "401", "Fournisseurs médicaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("40", "402", "Fournisseurs non médicaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("4", "41", "Patients", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("41", "411", "Patients - créances", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("41", "413", "Compagnies d'assurance", ReportingClass.ACTIF, "COURANT", "DEBIT", true, null),
            new AccountSeed("4", "42", "Personnel", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("42", "421", "Personnel - rémunérations dues", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SALARIES_PAYABLE"),
            new AccountSeed("4", "43", "Organismes sociaux", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "SOCIAL_SECURITY_PAYABLE"),
            new AccountSeed("4", "44", "État", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("44", "443", "TVA collectée", ReportingClass.PASSIF, "COURANT", "CREDIT", false, "VAT_COLLECTED"),
            new AccountSeed("5", "52", "Banques", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("52", "521", "Banque Nationale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("5", "57", "Caisse", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("57", "571", "Caisse principale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("3", "31", "Stocks de médicaments", ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("31", "310", "Médicaments", ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("31", "311", "Matériel médical", ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("3", "32", "Stocks de consommables", ReportingClass.ACTIF, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "60", "Achats", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("60", "601", "Achats de médicaments", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("60", "602", "Achats de consommables médicaux", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "61", "Transports", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "62", "Services extérieurs", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "OPERATING_EXPENSE"),
            new AccountSeed("62", "621", "Loyer et entretien", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("62", "622", "Électricité et eau", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "63", "Charges de personnel", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("63", "631", "Salaires du personnel médical", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PERSONNEL_EXPENSE"),
            new AccountSeed("63", "632", "Salaires du personnel administratif", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "64", "Autres charges", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("6", "68", "Dotations aux amortissements", ReportingClass.CHARGES, "COURANT", "DEBIT", false, null),
            new AccountSeed("7", "70", "Prestations médicales", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("70", "701", "Consultations", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE"),
            new AccountSeed("70", "706", "Soins et hospitalisation", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("7", "75", "Autres produits", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("75", "758", "Produits accessoires (cantine, parking)", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, null),
            new AccountSeed("1", "10", "Fonds propre", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("10", "102", "Fonds associatif", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("2", "22", "Terrains", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "23", "Bâtiments hospitaliers", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "24", "Matériel", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "244", "Matériel de transport", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "245", "Matériel de bureau", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("24", "246", "Matériel médical", ReportingClass.ACTIF, "NON_COURANT", "DEBIT", false, null),
            new AccountSeed("2", "28", "Amortissements", ReportingClass.ACTIF, "NON_COURANT", "CREDIT", false, null)
        );
    }

    private static List<AccountSeed> generic() {
        return List.of(
            new AccountSeed("4", "40", "Fournisseurs", ReportingClass.PASSIF, "COURANT", "CREDIT", true, "ACCOUNTS_PAYABLE"),
            new AccountSeed("4", "41", "Clients", ReportingClass.ACTIF, "COURANT", "DEBIT", true, "ACCOUNTS_RECEIVABLE"),
            new AccountSeed("4", "44", "État", ReportingClass.PASSIF, "COURANT", "CREDIT", true, null),
            new AccountSeed("44", "443", "TVA collectée", ReportingClass.PASSIF, "COURANT", "CREDIT", false, "VAT_COLLECTED"),
            new AccountSeed("5", "52", "Banques", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("52", "521", "Banque Nationale", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("5", "57", "Caisse", ReportingClass.ACTIF, "COURANT", "DEBIT", false, "CASH"),
            new AccountSeed("1", "10", "Capital", ReportingClass.CAPITAUX_PROPRES, "EQUITY", "CREDIT", false, null),
            new AccountSeed("6", "60", "Achats", ReportingClass.CHARGES, "COURANT", "DEBIT", false, "PURCHASES"),
            new AccountSeed("7", "70", "Ventes", ReportingClass.PRODUITS, "COURANT", "CREDIT", false, "SALES_REVENUE")
        );
    }
}
