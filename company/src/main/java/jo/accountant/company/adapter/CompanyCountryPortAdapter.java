package jo.accountant.company.adapter;

import java.util.Optional;
import java.util.UUID;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.port.CompanyCountryPort;
import org.springframework.stereotype.Component;

/**
 * Adaptateur du port {@link CompanyCountryPort} — implémentation concrète côté {@code :company}.
 *
 * <p>Lit le code pays ISO 3166-1 alpha-2 d'une entreprise via {@link CompanyRepository}.
 * Permet à {@code :document-generation} de sélectionner le bon template PDF selon le pays
 * (mentions légales Haïti vs France) sans dépendre de {@code :company} compile-time.
 *
 * <p>Bean Spring détecté automatiquement via {@code @Component}. {@code :document-generation}
 * injecte le port via constructeur — Spring résout l'implémentation au runtime.
 *
 * <p><b>Fix Dim 3 C1 (audit v9.4)</b> : cet adapter a été ajouté pour que
 * {@code DocumentGenerationService} puisse filtrer les templates par {@code country_code}.
 *
 * @author jo@Dev
 */
@Component
public class CompanyCountryPortAdapter implements CompanyCountryPort {

    private final CompanyRepository companyRepository;

    public CompanyCountryPortAdapter(CompanyRepository companyRepository) {
        this.companyRepository = companyRepository;
    }

    @Override
    public Optional<String> resolveCountryCode(UUID companyId) {
        if (companyId == null) {
            return Optional.empty();
        }
        return companyRepository.findById(companyId)
            .map(Company::getCountry);
    }
}
