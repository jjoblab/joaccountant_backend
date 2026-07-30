package jo.accountant.chartofaccounts.service;

import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.core.framework.ReportingClass;
import org.springframework.stereotype.Component;

/**
 * Résolution centralisée des comptes référentiel-agnostique (audit v4.7 §8.1 Finding HAUT).
 *
 * <p><b>Problème</b> : 32 occurrences du pattern cascade
 * {@code findFirstByCompanyIdAndReportingClassAndTaxMappingCodeAndActiveTrueOrderByCodeAsc(...)}
 * {@code .or(findByCompanyIdAndCode("XXX"))} réparties sur 8 services. Chaque nouveau
 * référentiel comptable obligeait à dupliquer la même cascade de fallbacks. Violation DRY.
 *
 * <p><b>Solution</b> : ce composant centralise la résolution avec une API simple :
 * <pre>
 * Account account = accountResolver.resolveByTaxMappingOrCode(
 *     companyId, ReportingClass.PASSIF, "VAT_COLLECTED", "443000", "443");
 * </pre>
 *
 * <p>Ordre de résolution :
 * <ol>
 *   <li>Cherche par (companyId, reportingClass, taxMappingCode, active=true) — trié par code.</li>
 *   <li>Fallback sur chaque code fourni dans l'ordre (ex: "443000" puis "443").</li>
 *   <li>Si aucun trouvé, retourne {@link Optional#empty()} — l'appelant décide du comportement.</li>
 * </ol>
 *
 * <p>Placé dans {@code :chart-of-accounts} (propriétaire du {@link AccountRepository}) — accessible
 * par tous les services qui dépendent déjà de {@code :chart-of-accounts}.
 */
@Component
public class AccountResolver {

    private final AccountRepository accountRepository;

    public AccountResolver(AccountRepository accountRepository) {
        this.accountRepository = accountRepository;
    }

    /**
     * Résout un compte par taxMappingCode avec fallback sur des codes explicites.
     *
     * @param companyId       identifiant de l'entreprise
     * @param reportingClass  classe comptable cible (ACTIF, PASSIF, CHARGES, PRODUITS, CAPITAUX_PROPRES)
     * @param taxMappingCode  code de mapping fiscal (ex: "VAT_COLLECTED", "SALES_REVENUE", "PURCHASES")
     * @param fallbackCodes   codes de fallback dans l'ordre de priorité (ex: "443000", "443")
     * @return le compte trouvé, ou {@link Optional#empty()} si aucun ne matche
     */
    public Optional<Account> resolveByTaxMappingOrCode(UUID companyId,
                                                        ReportingClass reportingClass,
                                                        String taxMappingCode,
                                                        String... fallbackCodes) {
        // 1. Chercher par taxMappingCode
        Optional<Account> account = accountRepository
            .findFirstByCompanyIdAndReportingClassAndTaxMappingCodeAndActiveTrueOrderByCodeAsc(
                companyId, reportingClass, taxMappingCode);
        if (account.isPresent()) return account;

        // 2. Fallback sur chaque code fourni
        if (fallbackCodes != null) {
            for (String code : fallbackCodes) {
                if (code != null && !code.isBlank()) {
                    account = accountRepository.findByCompanyIdAndCode(companyId, code);
                    if (account.isPresent()) return account;
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Résout un compte par taxMappingCode avec fallback — lève une exception si aucun trouvé.
     *
     * @param errorCode     code d'erreur à utiliser dans l'exception (ex: "VAT_COLLECTED_ACCOUNT_NOT_FOUND")
     * @param errorMessage  message d'erreur détaillé
     * @throws jo.accountant.core.exception.ValidationException si aucun compte n'est trouvé
     */
    public Account resolveOrThrow(UUID companyId,
                                   ReportingClass reportingClass,
                                   String taxMappingCode,
                                   String errorCode,
                                   String errorMessage,
                                   String... fallbackCodes) {
        return resolveByTaxMappingOrCode(companyId, reportingClass, taxMappingCode, fallbackCodes)
            .orElseThrow(() -> new jo.accountant.core.exception.ValidationException(errorCode, errorMessage));
    }

    /**
     * Résout un compte par reportingClass uniquement (sans taxMappingCode) avec fallback sur level.
     *
     * @param companyId       identifiant de l'entreprise
     * @param reportingClass  classe comptable cible
     * @param level           niveau hiérarchique pour le fallback (ex: 1 pour compte racine)
     * @return le compte trouvé, ou {@link Optional#empty()}
     */
    public Optional<Account> resolveByReportingClass(UUID companyId,
                                                      ReportingClass reportingClass,
                                                      Integer level) {
        if (level != null) {
            Optional<Account> account = accountRepository
                .findFirstByCompanyIdAndReportingClassAndLevelAndActiveTrueOrderByCodeAsc(
                    companyId, reportingClass, level);
            if (account.isPresent()) return account;
        }
        return accountRepository
            .findFirstByCompanyIdAndReportingClassAndActiveTrueOrderByCodeAsc(
                companyId, reportingClass);
    }
}
