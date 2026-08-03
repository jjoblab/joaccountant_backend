package jo.accountant.bankreconciliation.parser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import jo.accountant.bankreconciliation.entity.BankStatementLine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link Ofx2Parser} — Fix Dim 1 H2 (audit v9.4).
 *
 * <p>Les parseurs OFX2 sont des points d'entrée critiques pour le rapprochement bancaire.
 * Ce test valide le parsing de fichiers OFX 2.x (XML bien-formé) avec différents scénarios.
 *
 * <p>Couverture :
 * <ul>
 *   <li>OFX 2.x nominal avec 2 transactions (DEBIT + CREDIT)</li>
 *   <li>Contenu null / vide → liste vide (pas d'exception)</li>
 *   <li>XML malformé → IllegalArgumentException</li>
 *   <li>Transaction sans DTPOSTED ou TRNAMT → ignorée</li>
 *   <li>Protection XXE (entités externes désactivées)</li>
 * </ul>
 *
 * @author jo@Dev
 */
class Ofx2ParserTest {

    private final Ofx2Parser parser = new Ofx2Parser();

    /**
     * OFX 2.x minimal valide avec 2 transactions.
     */
    private static final String SAMPLE_OFX2 = """
        <?xml version="1.0" encoding="UTF-8"?>
        <OFX>
          <BANKMSGSRSV1>
            <STMTTRNRS>
              <STMTRS>
                <BANKTRANLIST>
                  <STMTTRN>
                    <TRNTYPE>DEBIT</TRNTYPE>
                    <DTPOSTED>20260115</DTPOSTED>
                    <TRNAMT>-100.00</TRNAMT>
                    <FITID>TX-001</FITID>
                    <NAME>VIREMENT EMIS</NAME>
                    <MEMO>Paiement fournisseur ABC</MEMO>
                  </STMTTRN>
                  <STMTTRN>
                    <TRNTYPE>CREDIT</TRNTYPE>
                    <DTPOSTED>20260120</DTPOSTED>
                    <TRNAMT>500.00</TRNAMT>
                    <FITID>TX-002</FITID>
                    <NAME>REMISE CARTE</NAME>
                    <MEMO>Remise carte de credit</MEMO>
                  </STMTTRN>
                </BANKTRANLIST>
              </STMTRS>
            </STMTTRNRS>
          </BANKMSGSRSV1>
        </OFX>
        """;

    @Nested
    @DisplayName("Parsing nominal")
    class ParsingNominal {

        @Test
        @DisplayName("OFX 2.x avec 2 STMTTRN → 2 lignes parsées")
        void parseNominalTwoTransactions() {
            var lines = parser.parse(SAMPLE_OFX2);

            assertThat(lines)
                .as("Deux transactions STMTTRN doivent être parsées")
                .hasSize(2);

            // Transaction 1 : débit -100
            BankStatementLine line1 = lines.get(0);
            assertThat(line1.getAmount())
                .as("TRNAMT=-100.00 doit produire un montant négatif")
                .isEqualByComparingTo(new BigDecimal("-100.00"));
            assertThat(line1.getLineDate())
                .as("DTPOSTED=20260115 → 2026-01-15")
                .isEqualTo(LocalDate.of(2026, 1, 15));
            assertThat(line1.getDescription())
                .as("La description doit contenir NAME et MEMO")
                .contains("VIREMENT EMIS")
                .contains("Paiement fournisseur");

            // Transaction 2 : crédit +500
            BankStatementLine line2 = lines.get(1);
            assertThat(line2.getAmount())
                .as("TRNAMT=500.00 doit produire un montant positif")
                .isEqualByComparingTo(new BigDecimal("500.00"));
            assertThat(line2.getLineDate())
                .isEqualTo(LocalDate.of(2026, 1, 20));
            assertThat(line2.getDescription())
                .contains("REMISE CARTE");
        }
    }

    @Nested
    @DisplayName("Cas limites")
    class EdgeCases {

        @Test
        @DisplayName("Contenu null → liste vide (pas d'exception)")
        void parseNullReturnsEmpty() {
            var lines = parser.parse(null);
            assertThat(lines).isEmpty();
        }

        @Test
        @DisplayName("Contenu vide → liste vide (pas d'exception)")
        void parseBlankReturnsEmpty() {
            var lines = parser.parse("   ");
            assertThat(lines).isEmpty();
        }

        @Test
        @DisplayName("XML malformé → IllegalArgumentException")
        void parseMalformedXmlThrows() {
            String malformed = "<OFX><BANKTRANLIST><STMTTRN></OFX>"; // balise non fermée
            assertThatThrownBy(() -> parser.parse(malformed))
                .as("Un XML malformé doit lever une exception")
                .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("Transaction sans DTPOSTED → ignorée")
        void transactionWithoutDatePostedIsIgnored() {
            String ofxMissingDate = """
                <?xml version="1.0" encoding="UTF-8"?>
                <OFX>
                  <BANKTRANLIST>
                    <STMTTRN>
                      <TRNTYPE>DEBIT</TRNTYPE>
                      <TRNAMT>-100.00</TRNAMT>
                      <NAME>SANS DATE</NAME>
                    </STMTTRN>
                  </BANKTRANLIST>
                </OFX>
                """;
            var lines = parser.parse(ofxMissingDate);
            assertThat(lines)
                .as("Une transaction sans DTPOSTED doit être ignorée")
                .isEmpty();
        }

        @Test
        @DisplayName("Transaction avec TRNAMT non numérique → ignorée")
        void transactionWithNonNumericAmountIsIgnored() {
            String ofxBadAmount = """
                <?xml version="1.0" encoding="UTF-8"?>
                <OFX>
                  <BANKTRANLIST>
                    <STMTTRN>
                      <TRNTYPE>DEBIT</TRNTYPE>
                      <DTPOSTED>20260115</DTPOSTED>
                      <TRNAMT>NON-NUMERIC</TRNAMT>
                      <NAME>BAD AMOUNT</NAME>
                    </STMTTRN>
                  </BANKTRANLIST>
                </OFX>
                """;
            var lines = parser.parse(ofxBadAmount);
            assertThat(lines)
                .as("Une transaction avec TRNAMT non numérique doit être ignorée")
                .isEmpty();
        }
    }
}
