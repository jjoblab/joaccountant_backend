package jo.accountant.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests unitaires pour {@link PiiMasker} — R-16 (lot-D-qualite-arch).
 *
 * <p>Pas de Spring, pas de Mockito : {@code PiiMasker} est une classe utilitaire statique
 * pure. Les tests vérifient les 3 fonctions de masquage (email, fullName, phone) sur les
 * cas nominal, edge et error (null/blank).
 */
class PiiMaskerTest {

    @Test
    @DisplayName("maskEmail — nominal : garde 2 premiers caractères + *** + domaine")
    void maskEmail_nominal() {
        assertThat(PiiMasker.maskEmail("marie@joaccountant.dev")).isEqualTo("ma***@joaccountant.dev");
        assertThat(PiiMasker.maskEmail("jean.pierre@example.com")).isEqualTo("je***@example.com");
    }

    @Test
    @DisplayName("maskEmail — edge : email trop court (≤ 1 char avant @) → \"***\"")
    void maskEmail_edge_shortPrefix() {
        assertThat(PiiMasker.maskEmail("a@b.com")).isEqualTo("***");
        assertThat(PiiMasker.maskEmail("@nodomain.com")).isEqualTo("***");
    }

    @Test
    @DisplayName("maskEmail — error : null ou blank → retourné tel quel")
    void maskEmail_error_nullOrBlank() {
        assertThat(PiiMasker.maskEmail(null)).isNull();
        assertThat(PiiMasker.maskEmail("")).isEmpty();
        assertThat(PiiMasker.maskEmail("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("maskFullName — nominal : garde les initiales de chaque mot + \".\"")
    void maskFullName_nominal() {
        assertThat(PiiMasker.maskFullName("Marie Joseph")).isEqualTo("M. J.");
        assertThat(PiiMasker.maskFullName("Jean-Pierre Dubois")).isEqualTo("J. D.");
        assertThat(PiiMasker.maskFullName("Albert")).isEqualTo("A.");
    }

    @Test
    @DisplayName("maskFullName — edge : espaces multiples en début/fin/milieu")
    void maskFullName_edge_extraSpaces() {
        assertThat(PiiMasker.maskFullName("  Marie   Joseph  ")).isEqualTo("M. J.");
    }

    @Test
    @DisplayName("maskFullName — error : null ou blank → retourné tel quel")
    void maskFullName_error_nullOrBlank() {
        assertThat(PiiMasker.maskFullName(null)).isNull();
        assertThat(PiiMasker.maskFullName("")).isEmpty();
        assertThat(PiiMasker.maskFullName("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("maskPhone — nominal : garde les 4 premiers caractères + ***")
    void maskPhone_nominal() {
        assertThat(PiiMasker.maskPhone("+509 3701 2345")).isEqualTo("+509***");
        assertThat(PiiMasker.maskPhone("+33123456789")).isEqualTo("+331***");
    }

    @Test
    @DisplayName("maskPhone — edge : numéro court (< 4 char) → préfixe + ***")
    void maskPhone_edge_shortNumber() {
        assertThat(PiiMasker.maskPhone("123")).isEqualTo("123***");
        assertThat(PiiMasker.maskPhone("12")).isEqualTo("12***");
    }

    @Test
    @DisplayName("maskPhone — error : null ou blank → retourné tel quel")
    void maskPhone_error_nullOrBlank() {
        assertThat(PiiMasker.maskPhone(null)).isNull();
        assertThat(PiiMasker.maskPhone("")).isEmpty();
        assertThat(PiiMasker.maskPhone("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("maskPiiInJson — nominal : masque email, fullName et phone dans un JSON")
    void maskPiiInJson_nominal() {
        String json = "{\"email\":\"marie@joaccountant.dev\",\"fullName\":\"Marie Joseph\",\"phone\":\"+509 3701 2345\"}";
        String masked = PiiMasker.maskPiiInJson(json);
        assertThat(masked).contains("\"email\":\"ma***@joaccountant.dev\"");
        assertThat(masked).contains("\"fullName\":\"M. J.\"");
        assertThat(masked).contains("\"phone\":\"+509***\"");
        // Les clés originales doivent rester présentes
        assertThat(masked).contains("\"email\":");
        assertThat(masked).contains("\"fullName\":");
        assertThat(masked).contains("\"phone\":");
    }

    @Test
    @DisplayName("maskPiiInJson — edge : JSON sans PII → retourné inchangé")
    void maskPiiInJson_edge_noPii() {
        String json = "{\"id\":\"123\",\"amount\":100.00}";
        assertThat(PiiMasker.maskPiiInJson(json)).isEqualTo(json);
    }

    @Test
    @DisplayName("maskPiiInJson — error : null ou blank → retourné tel quel")
    void maskPiiInJson_error_nullOrBlank() {
        assertThat(PiiMasker.maskPiiInJson(null)).isNull();
        assertThat(PiiMasker.maskPiiInJson("")).isEmpty();
    }
}
