package jo.accountant.company.adapter;

import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.company.entity.Company;
import jo.accountant.company.repository.CompanyRepository;
import jo.accountant.core.port.CompanyInfoPort;
import jo.accountant.core.port.FileStoragePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Fix PDF v9.4 — Adaptateur du port {@link CompanyInfoPort} — implémentation concrète côté
 * {@code :company}.
 *
 * <p>Lit les informations d'identité d'une entreprise via {@link CompanyRepository} et résout
 * le logo via {@link FileStoragePort} (si {@code logoStorageKey} est non null).
 *
 * <p>Permet à {@code :document-generation} de récupérer le nom, l'adresse, le NIF et le logo
 * d'une entreprise sans dépendre de {@code :company} compile-time (principe 5).
 *
 * <p>Bean Spring détecté automatiquement via {@code @Component}. {@code :document-generation}
 * injecte le port via constructeur — Spring résout l'implémentation au runtime.
 *
 * @author jo@Dev
 */
@Component
public class CompanyInfoPortAdapter implements CompanyInfoPort {

    private static final Logger LOG = LoggerFactory.getLogger(CompanyInfoPortAdapter.class);

    private final CompanyRepository companyRepository;
    private final FileStoragePort fileStorage;

    public CompanyInfoPortAdapter(CompanyRepository companyRepository,
                                    FileStoragePort fileStorage) {
        this.companyRepository = companyRepository;
        this.fileStorage = fileStorage;
    }

    @Override
    public Optional<CompanyInfo> resolveCompanyInfo(UUID companyId) {
        if (companyId == null) {
            return Optional.empty();
        }
        return companyRepository.findById(companyId).map(this::toInfo);
    }

    private CompanyInfo toInfo(Company company) {
        String logoBase64 = null;
        if (company.getLogoStorageKey() != null && !company.getLogoStorageKey().isBlank()) {
            try {
                byte[] logoBytes = fileStorage.load(company.getLogoStorageKey());
                logoBase64 = Base64.getEncoder().encodeToString(logoBytes);
            } catch (Exception e) {
                LOG.warn("Logo introuvable pour company {} (key={}) : {}",
                    company.getId(), company.getLogoStorageKey(), e.getMessage());
            }
        }
        return new CompanyInfo(
            company.getName(),
            company.getAddress(),
            company.getNif(),
            company.getSiret(),
            company.getVatNumber(),
            company.getCountry(),
            logoBase64
        );
    }
}
