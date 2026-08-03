package jo.accountant.bankreconciliation.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import jo.accountant.bankreconciliation.dto.Mt940ParseResult;
import jo.accountant.bankreconciliation.entity.BankStatementLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link Mt940Parser} — Fix Dim 1 H2 (audit v9.4).
 *
 * <p>Les parseurs MT940/OFX2 sont des points d'entrée critiques pour le rapprochement bancaire.
 * Avant ce test, ils n'avaient AUCUN test unitaire — un bug dans le parsing (ex: format de date
 * inattendu, montant négatif mal géré, encodage latin-1) aurait pu causer des écritures
 * comptables erronées sans être détecté.
 *
 * <p>Couverture :
 * <ul>
 *   <li>Relevé MT940 nominal avec 2 lignes :61: (débit + crédit)</li>
 *   <li>Tag :86: enrichit la description de la ligne :61: précédente</li>
 *   <li>Relevé vide / null → résultat vide (pas d'exception)</li>
 *   <li>Ligne :61: malformée → ignorée (WARN log), parsing continue</li>
 *   <li>Montants négatifs (signe D) → BigDecimal négatif</li>
 *   <li>Soldes :60F: et :62F: parsés correctement</li>
 * </ul>
 *
 * @author jo@Dev
 */
class Mt940ParserTest {

    private final Mt940Parser parser = new Mt940Parser();

    /**
     * Relevé MT940 minimal valide avec 2 lignes de transaction.
     */
    private static final String SAMPLE_MT940 = """
        :25:1234567890
        :28C:1
        :60F:C260101EUR1000,00
        :61:2601150115D100,00NTRFNONREF//VIREMENT-001
        :86:Virement émis pour paiement fournisseur
        :61:2601200120C500,00NTRFNONREF//REMISE-CARTE
        :86:Remise carte de crédit
        :62F:C260131EUR1400,00
        """;

    @Nested
    @DisplayName("Parsing nominal")
    class ParsingNominal {

        @Test
        @DisplayName("Relevé avec 2 lignes :61: → account, balances et 2 lignes parsées")
        void parseNominalTwoLines() {
            Mt940ParseResult result = parser.parse(SAMPLE_MT940);

            assertThat(result.account())
                .as("Numéro de compte (tag :25:) doit être parsé")
                .isEqualTo("1234567890");

            assertThat(result.openingBalance())
                .as("Solde d'ouverture (tag :60F: C = crédit) doit être +1000.00")
                .isEqualByComparingTo(new BigDecimal("1000.00"));

            assertThat(result.closingBalance())
                .as("Solde de clôture (tag :62F: C = crédit) doit être +1400.00")
                .isEqualByComparingTo(new BigDecimal("1400.00"));

            assertThat(result.lines())
                .as("Deux lignes :61: doivent être parsées")
                .hasSize(2);

            // Ligne 1 : débit de 100 (sortie de trésorerie)
            BankStatementLine line1 = result.lines().get(0);
            assertThat(line1.getAmount())
                .as("Ligne 1 : montant débit (D) doit être négatif")
                .isEqualByComparingTo(new BigDecimal("-100.00"));
            assertThat(line1.getLineDate())
                .as("Ligne 1 : value date 260115 → 2026-01-15")
                .isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(line1.getDescription())
                .as("Ligne 1 : description enrichie par tag :86:")
                .contains("Virement émis pour paiement fournisseur");

            // Ligne 2 : crédit de 500 (entrée de trésorerie)
            BankStatementLine line2 = result.lines().get(1);
            assertThat(line2.getAmount())
                .as("Ligne 2 : montant crédit (C) doit être positif")
                .isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(line2.getLineDate())
                .as("Ligne 2 : value date 260120 → 2026-01-20")
                .isEqualTo(LocalDate.of(2026, 1, 20));
        }

        @Test
        @DisplayName("Tag :86: enrichit la description de la ligne :61: précédente")
        void tag86EnrichesDescription() {
            Mt940ParseResult result = parser.parse(SAMPLE_MT940);

            assertThat(result.lines()).hasSize(2);
            assertThat(result.lines().get(0).getDescription())
                .as("La description de la ligne 1 doit contenir le contenu du tag :86: suivant")
                .contains("Virement émis");
            assertThat(result.lines().get(1).getDescription())
                .as("La description de la ligne 2 doit contenir le contenu du tag :86: suivant")
                .contains("Remise carte");
        }
    }

    @Nested
    @DisplayName("Cas limites")
    class EdgeCases {

        @Test
        @DisplayName("Contenu null → résultat vide (pas d'exception)")
        void parseNullReturnsEmpty() {
            Mt940ParseResult result = parser.parse(null);

            assertThat(result.account()).isNull();
            assertThat(result.openingBalance()).isNull();
            assertThat(result.closingBalance()).isNull();
            assertThat(result.lines()).isEmpty();
        }

        @Test
        @DisplayName("Contenu vide → résultat vide (pas d'exception)")
        void parseBlankReturnsEmpty() {
            Mt940ParseResult result = parser.parse("   ");

            assertThat(result.account()).isNull();
            assertThat(result.lines()).isEmpty();
        }

        @Test
        @DisplayName("Ligne :61: malformée → ignorée, parsing continue")
        void malformedLine61IsIgnored() {
            String mt940WithMalformed = """
                :25:123
                :60F:C260101EUR1000,00
                :61:INVALIDLINE
                :61:2601150115D100,00NTRFNONREF
                :62F:C260131EUR900,00
                """;

            Mt940ParseResult result = parser.parse(mt940WithMalformed);

            assertThat(result.lines())
                .as("La ligne malformée doit être ignorée, la ligne valide doit être parsée")
                .hasSize(1);
            assertThat(result.lines().get(0).getAmount())
                .isEqualByComparingTo(new BigDecimal("-100.00"));
        }

        @Test
        @DisplayName("Montant débit (D) → BigDecimal négatif")
        void debitAmountIsNegative() {
            String mt940 = """
                :25:123
                :60F:C260101EUR0,00
                :61:260115D250,50NTRFNONREF
                :62F:D260131EUR250,50
                """;

            Mt940ParseResult result = parser.parse(mt940);

            assertThat(result.lines()).hasSize(1);
            assertThat(result.lines().get(0).getAmount())
                .as("Un débit (D) doit produire un montant négatif")
                .isEqualByComparingTo(new BigDecimal("-250.50"));

            assertThat(result.closingBalance())
                .as("Solde de clôture débiteur (D) doit être négatif")
                .isEqualByComparingTo(new BigDecimal("-250.50"));
        }
    }
}
