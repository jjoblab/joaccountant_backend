package jo.accountant.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * R-44 (lot-F2-tests-qa) — Vérification Spring Modulith des boundaries runtime.
 *
 * <p>Complémente ArchUnit (qui vérifie compile-time) en vérifiant runtime que :
 * <ul>
 *   <li>Chaque module Gradle est un module Spring Modulith valide</li>
 *   <li>Les internals de package ne sont pas accédés depuis l'extérieur</li>
 *   <li>Les événements publiés sont bien déclarés comme externes</li>
 * </ul>
 *
 * <p>Documentation générée dans {@code build/spring-modulith/} (diagrammes PlantUML
 * + document agrégé Markdown).
 *
 * <p><b>État actuel (lot-F2)</b> : ce test est {@code @Disabled} car les modules
 * Spring Boot ne respectent pas encore la convention Modulith. Les violations
 * détectées sont documentées ci-dessous. Le test sert de « garde-fou futur » : il
 * suffit de retirer l'annotation {@code @Disabled} après correction des violations
 * pour que le build casse si une nouvelle violation est introduite.
 *
 * <h2>Violations détectées (à corriger avant réactivation)</h2>
 * <p>Spring Modulith déduit les modules à partir des packages directs sous le package
 * de base ({@code jo.accountant}). Pour que {@code ApplicationModules.of(...)} réussisse,
 * chaque module doit :
 * <ol>
 *   <li>Avoir un package {@code jo.accountant.<module>} contenant soit une classe
 *       portant le nom du package ({@code Module} comme "package-info"), soit une
 *       annotation {@code @ApplicationModule}.</li>
 *   <li>Exposer explicitement ses beans via un {@code @Bean} sur une classe
 *       {@code <ModuleName>Configuration} ou être détecté par scan de composants
 *       limité au package du module (pas de sous-package {@code internal} leaké).</li>
 *   <li>Ne pas dépendre d'un package {@code internal} d'un autre module.</li>
 * </ol>
 *
 * <p>Le backend JOAccountant a 27 modules Gradle, chacun avec sa propre structure
 * de packages (controller, service, entity, repository, dto, event, etc.). Cette
 * structure est compatible avec la convention Modulith, mais :
 * <ul>
 *   <li>Aucun module n'a de package-info ou de classe {@code @ApplicationModule}
 *       déclarant explicitement sa surface d'API.</li>
 *   <li>Plusieurs modules publient des events ({@code InvoiceIssuedEvent},
 *       {@code CompanyCreatedEvent}, {@code ApprovalDecidedEvent}, etc.) qui ne
 *       sont pas marqués comme externes (par défaut, Modulith les considère
 *       internes et peut signaler une fuite si un autre module les écoute).</li>
 *   <li>Plusieurs modules exposent leurs services via {@code @Service} public
 *       sur toute la classe — la surface d'API effective n'est pas explicitée.</li>
 * </ul>
 *
 * <p>Plan de réactivation (lot-F3 ou ultérieur) :
 * <ol>
 *   <li>Ajouter une classe {@code package-info.java} par module avec
 *       {@code @org.springframework.modulith.ApplicationModule} pour déclarer la
 *       surface d'API et les events externes.</li>
 *   <li>Marquer les sous-packages internes ({@code entity}, {@code repository},
 *       {@code dto}) comme {@code internal} en les déplaçant sous
 *       {@code jo.accountant.<module>.internal} (OU en gardant la structure actuelle
 *       et en déclarant les packages externes via
 *       {@code @ApplicationModule(namedInterface = "...")}.</li>
 *   <li>Réactiver ce test en retirant {@code @Disabled}.</li>
 * </ol>
 *
 * <p>Voir <a href="https://docs.spring.io/spring-modulith/reference/">la doc officielle</a>
 * pour les conventions et le mode de configuration.
 *
 * @see ArchUnitTest (compile-time architecture checks)
 */
class ModulithVerificationTest {

    /**
     * Vérifie que les modules Spring Modulith sont valides (boundaries runtime).
     *
     * <p>Ce test échouerait actuellement car les modules ne respectent pas la
     * convention Modulith (voir la Javadoc de classe pour le détail). Il est
     * {@code @Disabled} jusqu'à la correction.
     */
    @Test
    // @Disabled temporarily removed (lot-F2) to detect runtime violations.
    void verifyApplicationModules() {
        ApplicationModules modules = ApplicationModules.of(JoAccountantApplication.class);
        // Vérifier que les modules sont valides — lève VerificationException si :
        //   - un module accède à un package "internal" d'un autre module
        //   - un event externe n'est pas déclaré
        //   - un module n'a pas de dépendance vers un module dont il utilise une classe
        modules.verify();

        // Sanity check : s'il y a 0 modules, le test ne vérifie rien → on fail.
        assertThat(modules).isNotNull();
        assertThat(modules.stream().count())
            .as("ApplicationModules.of doit détecter au moins 1 module")
            .isGreaterThan(0);
    }

    /**
     * Génère la documentation Spring Modulith (diagrammes PlantUML + document Markdown
     * agrégé) dans {@code build/spring-modulith/}.
     *
     * <p>Ce test ne fait pas de vérification à proprement parler — il sert à générer
     * les artefacts de documentation qui seront ensuite commités dans le repo pour
     * visualiser l'architecture cible.
     *
     * <p>Lui aussi {@code @Disabled} : la génération de documentation nécessite que
     * les modules soient valides (sinon Modulith ne peut pas inférer les relations).
     */
    @Test
    // @Disabled temporarily removed (lot-F2) to detect runtime violations.
    void generateDocumentation() {
        ApplicationModules modules = ApplicationModules.of(JoAccountantApplication.class);
        // Les 3 appels sont séparés (pas chaînés) car les types de retour diffèrent
        // entre les versions Spring Modulith (CanvasDiagramGroup vs Documentation).
        // Voir Javadoc de classe pour le détail de l'API 1.4.x.
        Documenter documenter = new Documenter(modules);
        documenter.writeModulesAsPlantUml();
        documenter.writeIndividualModulesAsPlantUml();
        documenter.writeAggregatingDocument();
    }
}
