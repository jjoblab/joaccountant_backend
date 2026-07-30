package jo.accountant.chartofaccounts.guard;

import java.util.UUID;

/**
 * Garde-fou anti-désactivation d'un compte non soldé (§13 Phase 3).
 *
 * <p>Définie dans {@code :chart-of-accounts} (Phase 3) mais <strong>implémentée</strong> dans
 * {@code :accounting-engine} (Phase 5). Cette séparation permet à {@code chart-of-accounts}
 * de vérifier qu'un compte est soldé avant sa désactivation SANS dépendre de
 * {@code accounting-engine} — ce qui violerait le principe 5 du prompt maître
 * ("un module ne doit jamais accéder directement aux tables internes d'un autre module").
 *
 * <p>L'implémentation par défaut de Phase 3 (fournie par
 * {@link DefaultAccountBalanceGuard}) retourne toujours {@code false} — c'est-à-dire autorise
 * la désactivation. C'est intentionnel : Phase 3 ne sait pas encore interroger le solde d'un
 * compte (le moteur comptable n'existe pas encore). Phase 5 remplacera cette implémentation
 * par défaut par une vraie qui interroge {@code JournalLine}.
 *
 * <p>L'inversion de dépendance via interface est le pattern canonique pour respecter le
 * principe 5 sans casser l'ordre de construction des phases : la dépendance va du module
 * aval (chart-of-accounts) vers une abstraction qui sera implémentée par le module amont
 * (accounting-engine). À noter que accounting-engine dépendra de chart-of-accounts (pour
 * référencer les comptes), pas l'inverse — c'est la flèche de dépendance de l'implémentation
 * qui s'inverse, pas celle du code.
 */
public interface AccountBalanceGuard {

    /**
     * Retourne {@code true} si le compte donné a un solde non nul pour l'entreprise donnée,
     * {@code false} sinon.
     *
     * <p>Un compte non soldé ne peut pas être désactivé. Le solde est calculé en devise
     * fonctionnelle — toutes devises de transaction confondues, converties au taux en vigueur
     * au moment de l'écriture (Phase 5).
     *
     * @param companyId identifiant de l'entreprise (tenant)
     * @param accountId identifiant du compte à vérifier
     * @return {@code true} si le solde n'est pas nul, {@code false} sinon
     */
    boolean hasNonZeroBalance(UUID companyId, UUID accountId);

    /**
     * Retourne {@code true} si le compte donné a un solde négatif (anormal) pour l'entreprise.
     *
     * <p><b>Audit v4.7 §3.2 Finding MOYENNE — FIX</b> : la v4.7 ne vérifiait pas les soldes
     * négatifs anormaux. Un compte client (ACTIF, normalBalance=DEBIT) avec un solde créditeur
     * négatif indique un bug (ex: règlement reçu avant la facture, ou double règlement). De même,
     * un compte fournisseur (PASSIF, normalBalance=CREDIT) avec un solde débiteur négatif
     * indique un avance fournisseur ou un bug.
     *
     * <p>Cette méthode est utilisée par les contrôleurs pour alerter l'utilisateur sur les
     * écritures anormales, et par les validations de clôture pour bloquer la clôture d'un
     * exercice avec des soldes négatifs non justifiés.
     *
     * <p>Implémentation par défaut : retourne {@code false} (pas de vérification). Surcharge par
     * {@code JournalBasedAccountBalanceGuard} qui calcule le solde réel.
     *
     * @param companyId identifiant de l'entreprise (tenant)
     * @param accountId identifiant du compte à vérifier
     * @return {@code true} si le solde est négatif (anormal), {@code false} sinon
     */
    default boolean hasNegativeBalance(UUID companyId, UUID accountId) {
        return false;  // défaut : pas de vérification (rétro-compat Phase 3)
    }
}
