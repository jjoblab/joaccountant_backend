package jo.accountant.invoicing.signature;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration Spring pour le framework de signature électronique — R-36 (lot-F3-security).
 *
 * <p>Enregistre conditionnellement les beans {@link ElectronicSignatureService} :
 *
 * <ol>
 *   <li><b>XAdES</b> (activé uniquement si {@code app.signature.xades.enabled=true}) :
 *       bean {@code xAdESSignatureService} de type {@link XAdESSignatureService}. Enregistré
 *       en PREMIER dans cette @Configuration pour que le {@code @ConditionalOnMissingBean}
 *       du NoOp (ci-dessous) le voie et désactive le NoOp.</li>
 *   <li><b>NoOp</b> (activé par défaut si XAdES n'est pas activé) : bean
 *       {@code noOpElectronicSignatureService} de type {@link NoOpElectronicSignatureService}.
 *       L'annotation {@code @ConditionalOnMissingBean(ElectronicSignatureService.class)}
 *       garantit que le NoOp est remplacé dès qu'une autre implémentation est enregistrée
 *       (XAdES, ou une future implémentation PAdES/CAdES).</li>
 * </ol>
 *
 * <p><b>Ordre de traitement</b> : dans une @Configuration, les @Bean methods sont traitées
 * dans l'ordre de déclaration. XAdES est déclaré en premier → si
 * {@code app.signature.xades.enabled=true}, le bean XAdES est créé, puis le NoOp est skippé
 * (XAdES déjà présent). Si {@code enabled=false} (défaut), XAdES est skippé, puis NoOp est créé
 * (aucun bean {@link ElectronicSignatureService} déjà enregistré).
 *
 * <p><b>Backward-compat</b> : par défaut (aucune config), c'est NoOp qui est actif →
 * l'application démarre sans configuration et le endpoint {@code /sign} retourne le document
 * non signé (avec WARNING dans les logs).
 *
 * @see ElectronicSignatureService
 * @see XAdESSignatureService
 * @see NoOpElectronicSignatureService
 * @see SignatureProperties
 */
@Configuration
@EnableConfigurationProperties(SignatureProperties.class)
public class SignatureConfig {

    private static final Logger LOG = LoggerFactory.getLogger(SignatureConfig.class);

    /**
     * Implémentation XAdES — activée uniquement si {@code app.signature.xades.enabled=true}.
     *
     * <p>Le bean est enregistré en PREMIER pour que le {@code @ConditionalOnMissingBean}
     * du NoOp (méthode suivante) le détecte.
     */
    @Bean
    @ConditionalOnProperty(name = "app.signature.xades.enabled", havingValue = "true")
    public ElectronicSignatureService xAdESSignatureService(SignatureProperties properties) {
        // Validation fail-fast : si enabled=true mais keystorePath/password vide → on lance
        // IllegalStateException AVANT l'enregistrement du bean. L'application ne démarre pas
        // (fail-fast) plutôt que de démarrer en mode NoOp silencieux.
        XAdESSignatureService.validateProperties(properties);
        LOG.info("Activating XAdES electronic signature service (keystore={}, tsa={})",
            properties.getKeystorePath(), properties.getTsaUrl());
        return new XAdESSignatureService(properties);
    }

    /**
     * Implémentation NoOp — activée par défaut si aucune autre implémentation
     * {@link ElectronicSignatureService} n'est enregistrée (XAdES désactivé, par exemple).
     */
    @Bean
    @ConditionalOnMissingBean(ElectronicSignatureService.class)
    public ElectronicSignatureService noOpElectronicSignatureService() {
        return new NoOpElectronicSignatureService();
    }
}
