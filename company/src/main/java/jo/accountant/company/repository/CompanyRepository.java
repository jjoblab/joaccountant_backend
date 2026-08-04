package jo.accountant.company.repository;

import java.util.List;
import java.util.UUID;
import jo.accountant.company.entity.Company;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Repository JPA Company.
 *
 * @author jo@Dev


 */

public interface CompanyRepository extends JpaRepository<Company, UUID> {
    long countByCreatedBy(UUID createdBy);

    /**
     * FIX v9.4.1 (audit T3.1) — Retourne toutes les entreprises démo (is_demo=true).
     *
     * <p>Utilisé par {@link jo.accountant.demo.scheduler.DemoResetScheduler} pour
     * vérifier le TTL de 28 jours sur les données démo. Sans cette méthode, le
     * scheduler devrait scanner toutes les companies en DB puis filtrer côté Java
     * (N+1 problem sur 100+ companies).
     *
     * @return liste des entreprises avec is_demo=true (peut être vide si le profil
     *         demo n'est pas actif ou si le seed n'a pas encore tourné)
     */
    List<Company> findByIsDemoTrue();
}
