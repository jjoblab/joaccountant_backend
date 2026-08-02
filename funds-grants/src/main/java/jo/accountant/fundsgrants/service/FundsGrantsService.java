package jo.accountant.fundsgrants.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.AnalyticalTagDto;
import jo.accountant.accountingengine.dto.CreateJournalEntryRequest.LineDto;
import jo.accountant.accountingengine.dto.JournalEntryResponse;
import jo.accountant.accountingengine.entity.JournalEntrySourceModule;
import jo.accountant.accountingengine.entity.JournalEntryStatus;
import jo.accountant.accountingengine.entity.JournalLine;
import jo.accountant.accountingengine.repository.JournalLineAnalyticalTagRepository;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.accountingengine.repository.JournalRepository;
import jo.accountant.accountingengine.service.AccountingEngineService;
import jo.accountant.analytics.entity.AnalyticalDimensionValue;
import jo.accountant.analytics.repository.AnalyticalDimensionValueRepository;
import jo.accountant.approvalworkflow.dto.EvaluateResult;
import jo.accountant.approvalworkflow.entity.ApprovalActionType;
import jo.accountant.approvalworkflow.service.ApprovalWorkflowService;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.core.currency.CurrencyRoundingService;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.framework.ReportingClass;
import jo.accountant.core.tenant.TenantContext;
import jo.accountant.documentnumbering.dto.IssuedNumber;
import jo.accountant.documentnumbering.entity.DocumentType;
import jo.accountant.documentnumbering.service.DocumentNumberingService;
import jo.accountant.fundsgrants.dto.CloseFiscalYearResult;
import jo.accountant.fundsgrants.dto.CreateDonationReceiptRequest;
import jo.accountant.fundsgrants.dto.CreateGrantRequest;
import jo.accountant.fundsgrants.dto.DonorReport;
import jo.accountant.fundsgrants.dto.GrantResponse;
import jo.accountant.fundsgrants.entity.DonationReceipt;
import jo.accountant.fundsgrants.entity.Grant;
import jo.accountant.fundsgrants.entity.RestrictionType;
import jo.accountant.fundsgrants.event.GrantCreatedEvent;
import jo.accountant.fundsgrants.repository.DonationReceiptRepository;
import jo.accountant.fundsgrants.repository.GrantRepository;
import jo.accountant.thirdparties.entity.ThirdParty;
import jo.accountant.thirdparties.entity.ThirdPartyType;
import jo.accountant.thirdparties.repository.ThirdPartyRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service des fonds et subventions (§13.
 *
 * <p>Mécanisme des fonds dédiés (§13* <ol>
 * <li>À la clôture d'exercice, pour chaque subvention RESTRICTED, le module calcule :
 * produit constaté cette année − charges de l'année portant le tag analytique de ce fonds.</li>
 * <li>Si le solde est positif (ressource affectée non encore utilisée), le module soumet
 * une {@link jo.accountant.approvalworkflow.entity.ApprovalRequest} via approval-workflow
 * pour l'écriture proposée (Débit compte de charge "engagement à réaliser" / Crédit
 * compte de passif "fonds dédiés").</li>
 * <li>Tant que la demande n'est pas APPROVED, aucune écriture n'est postée.</li>
 * <li>Les exercices suivants, au rythme des dépenses réelles, une écriture inverse suit
 * le même circuit d'approbation.</li>
 * </ol>
 *
 * <p>Numérotation des comptes concernés non figée en dur : dépend du référentiel actif (§4).
 * Seuls le mécanisme et les {@code reportingClass} cibles sont fixes.
 
 *
 * @author jo@Dev


*/
@Service
public class FundsGrantsService {

    private static final Logger LOG = LoggerFactory.getLogger(FundsGrantsService.class);

    private final GrantRepository grantRepository;
    private final DonationReceiptRepository receiptRepository;
    private final ThirdPartyRepository thirdPartyRepository;
    private final AccountRepository accountRepository;
    private final JournalLineRepository journalLineRepository;
    private final JournalLineAnalyticalTagRepository analyticalTagRepository;
    private final JournalRepository journalRepository;
    private final AccountingEngineService accountingEngineService;
    private final AnalyticalDimensionValueRepository analyticalValueRepository;
    private final DocumentNumberingService documentNumberingService;
    private final ApprovalWorkflowService approvalWorkflowService;
    private final CurrencyRoundingService roundingService;
    private final ApplicationEventPublisher events;
    // Audit #3 — AccountResolver centralisé (remplace la cascade de fallbacks)
    private final jo.accountant.chartofaccounts.service.AccountResolver accountResolver;

    public FundsGrantsService(GrantRepository grantRepository,
                              DonationReceiptRepository receiptRepository,
                              ThirdPartyRepository thirdPartyRepository,
                              AccountRepository accountRepository,
                              JournalLineRepository journalLineRepository,
                              JournalLineAnalyticalTagRepository analyticalTagRepository,
                              JournalRepository journalRepository,
                              AccountingEngineService accountingEngineService,
                              AnalyticalDimensionValueRepository analyticalValueRepository,
                              DocumentNumberingService documentNumberingService,
                              ApprovalWorkflowService approvalWorkflowService,
                              CurrencyRoundingService roundingService,
                              ApplicationEventPublisher events,
                              jo.accountant.chartofaccounts.service.AccountResolver accountResolver) {
        this.grantRepository = grantRepository;
        this.receiptRepository = receiptRepository;
        this.thirdPartyRepository = thirdPartyRepository;
        this.accountRepository = accountRepository;
        this.journalLineRepository = journalLineRepository;
        this.analyticalTagRepository = analyticalTagRepository;
        this.journalRepository = journalRepository;
        this.accountingEngineService = accountingEngineService;
        this.analyticalValueRepository = analyticalValueRepository;
        this.documentNumberingService = documentNumberingService;
        this.approvalWorkflowService = approvalWorkflowService;
        this.roundingService = roundingService;
        this.events = events;
        this.accountResolver = accountResolver;
    }

    // --- Subventions ---

    @Transactional
    public GrantResponse createGrant(UUID companyId, CreateGrantRequest req) {
        ThirdParty donor = thirdPartyRepository.findById(req.donorThirdPartyId())
            .orElseThrow(() -> new NotFoundException("ThirdParty", req.donorThirdPartyId()));
        if (!donor.getCompanyId().equals(companyId)) {
            throw new NotFoundException("ThirdParty", req.donorThirdPartyId());
        }
        if (donor.getType() != ThirdPartyType.DONOR) {
            throw new ValidationException("NOT_A_DONOR",
                "Le tiers doit être de type DONOR (actuel : " + donor.getType() + ")");
        }

        Grant grant = new Grant();
        grant.setCompanyId(companyId);
        grant.setDonorThirdPartyId(donor.getId());
        grant.setCode(req.code().trim());
        grant.setLabel(req.label().trim());
        grant.setTotalAmount(req.totalAmount());
        grant.setCurrency(req.currency().toUpperCase());
        grant.setStartDate(req.startDate());
        grant.setEndDate(req.endDate());
        grant.setRestrictionType(req.restrictionType());
        grant.setAnalyticalValueId(req.analyticalValueId());
        Grant saved = grantRepository.save(grant);

        events.publishEvent(new GrantCreatedEvent(saved, TenantContext.getUserId()));
        LOG.info("Subvention créée : code={} label={} restricted={}", saved.getCode(),
            saved.getLabel(), saved.getRestrictionType());
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public List<GrantResponse> listGrants(UUID companyId) {
        return grantRepository.findByCompanyId(companyId).stream()
            .map(FundsGrantsService::toResponse).toList();
    }

    // --- Reçus de don ---

    /**
     * Crée un reçu de don et génère l'écriture comptable de produit associée (audit M7).
     *
     * <p>Avant cette correction, {@code createDonationReceipt} ne générait aucune écriture
     * comptable — le rapport bailleur ({@link #getDonorReport}) calculait alors
     * {@code totalReceived} depuis les {@code DonationReceipt} (documents externes) et
     * {@code totalSpent} depuis les {@code JournalLine} (écritures comptables), ce qui rendait
     * les deux sources non reconciliables.
     *
     * <p><b>Écriture générée</b> (journal "OD" — opérations diverses) :
     * <ul>
     * <li>Débit : compte de trésorerie (ACTIF, taxMappingCode="CASH") ou fallback sur le
     * premier compte d'ACTIF actif.</li>
     * <li>Crédit : compte de produit de don (PRODUITS, taxMappingCode="DONATION_REVENUE")
     * ou fallback sur le premier compte de PRODUITS actif.</li>
     * </ul>
     *
     * <p>Si le reçu est rattaché à une subvention elle-même rattachée à une valeur analytique
     * ({@link Grant#getAnalyticalValueId()}), les deux lignes portent un tag analytique
     * (allocation 100%) — ainsi le {@code getDonorReport} peut filtrer les produits par tag
     * analytique du grant (mécanisme symétrique au calcul des charges).
     *
     * <p>Idempotence : clé {@code "funds-grants-donation-" + receipt.getId()}. Le rejeu d'un
     * même reçu retourne l'écriture existante sans en créer de nouvelle.
     */
    @Transactional
    public DonationReceipt createDonationReceipt(UUID companyId, CreateDonationReceiptRequest req) {
        ThirdParty donor = thirdPartyRepository.findById(req.donorThirdPartyId())
            .orElseThrow(() -> new NotFoundException("ThirdParty", req.donorThirdPartyId()));
        if (!donor.getCompanyId().equals(companyId)) {
            throw new NotFoundException("ThirdParty", req.donorThirdPartyId());
        }

        Grant grant = null;
        if (req.grantId() != null) {
            grant = grantRepository.findById(req.grantId())
                .orElseThrow(() -> new NotFoundException("Grant", req.grantId()));
            if (!grant.getCompanyId().equals(companyId)) {
                throw new NotFoundException("Grant", req.grantId());
            }
        }

        // Générer le numéro de reçu via document-numbering
        LocalDate receiptDate = req.receiptDate() != null ? req.receiptDate() : LocalDate.now();
        IssuedNumber issued = documentNumberingService.nextNumber(
            companyId, DocumentType.DONATION_RECEIPT, "",
            receiptDate.atStartOfDay(java.time.ZoneOffset.UTC).toInstant());

        DonationReceipt receipt = new DonationReceipt();
        receipt.setCompanyId(companyId);
        receipt.setGrantId(req.grantId());
        receipt.setDonorThirdPartyId(donor.getId());
        receipt.setAmount(req.amount());
        receipt.setReceiptNumber(issued.number());
        receipt.setReceiptDate(receiptDate);
        receipt.setDescription(req.description());
        DonationReceipt saved = receiptRepository.save(receipt);

        // Audit M7 : générer l'écriture comptable de produit à la création du reçu.
        // La devise est celle du grant s'il est fourni, sinon HTG par défaut (le reçu ne porte
        // pas de devise — cohérent avec le fait qu'une ONG fonctionne généralement en HTG).
        String currencyCode = grant != null ? grant.getCurrency() : "HTG";
        BigDecimal amount = roundingService.round(currencyCode, req.amount());
        generateDonationReceiptEntry(companyId, saved, grant, donor, amount, currencyCode, receiptDate);

        LOG.info("Reçu de don créé : number={} amount={} donor={} entry={}",
            saved.getReceiptNumber(), saved.getAmount(), donor.getName(), saved.getJournalEntryId());
        return saved;
    }

    /**
     * Génère l'écriture comptable de produit d'un reçu de don (audit M7).
     *
     * <p>Débit trésorerie (ACTIF) / Crédit produit de don (PRODUITS). Si le grant est rattaché
     * à une valeur analytique, les deux lignes portent un tag analytique (allocation 100%).
     *
     * <p>Résolution des comptes référentiel-agnostique (même principe que InvoicingService) :
     * <ul>
     * <li><b>Trésorerie</b> : (1) ACTIF + taxMappingCode="CASH", (2) fallback sur le premier
     * compte d'ACTIF actif (pour rétro-compatibilité SYSCOHADA — codes "5x").</li>
     * <li><b>Produit de don</b> : (1) PRODUITS + taxMappingCode="DONATION_REVENUE",
     * (2) fallback sur le premier compte de PRODUITS actif.</li>
     * </ul>
     */
    private void generateDonationReceiptEntry(UUID companyId, DonationReceipt receipt, Grant grant,
                                              ThirdParty donor, BigDecimal amount,
                                              String currencyCode, LocalDate receiptDate) {
        // V8-5 — Si don en nature (IN_KIND), utiliser un compte de stock (3x) ou d'immo (215)
        // au lieu du compte de trésorerie (521). Code Fiscal art. 197 Haïti : les ONG doivent
        // valoriser et comptabiliser les dons en nature (médicaments, nourriture, équipements).
        boolean isInKind = receipt.getDonationType() == jo.accountant.fundsgrants.entity.DonationType.IN_KIND;

        // Résoudre le compte de débit (trésorerie pour CASH, stock/immo pour IN_KIND)
        Account debitAccount;
        if (isInKind) {
            // Pour un don en nature, on débite un compte d'ACTIF (stock 3x ou immo 215).
            // AccountResolver ne supporte pas encore INVENTORY_DONATION — fallback sur ACTIF.
            debitAccount = accountResolver
                .resolveByTaxMappingOrCode(companyId, ReportingClass.ACTIF, "INVENTORY_DONATION")
                .or(() -> accountResolver.resolveByTaxMappingOrCode(companyId, ReportingClass.ACTIF, "CASH"))
                .or(() -> accountResolver.resolveByReportingClass(companyId, ReportingClass.ACTIF, null))
                .orElseThrow(() -> new ValidationException("IN_KIND_ASSET_ACCOUNT_NOT_FOUND",
                    "Aucun compte d'ACTIF trouvé pour don en nature. Configurer un compte " +
                    "stock (3x) ou immobilisation (215) marqué taxMappingCode=\"INVENTORY_DONATION\"."));
        } else {
            // Comportement historique : compte de trésorerie (521 CASH)
            debitAccount = accountResolver
                .resolveByTaxMappingOrCode(companyId, ReportingClass.ACTIF, "CASH")
                .or(() -> accountResolver.resolveByReportingClass(companyId, ReportingClass.ACTIF, null))
                .orElseThrow(() -> new ValidationException("CASH_ACCOUNT_NOT_FOUND",
                    "Aucun compte de trésorerie trouvé. Configurer un compte ACTIF " +
                    "(idéalement marqué taxMappingCode=\"CASH\") dans le plan comptable."));
        }

        // Résoudre le compte de produit de don (PRODUITS, idéalement marqué DONATION_REVENUE)
        Account revenueAccount = accountResolver
            .resolveByTaxMappingOrCode(companyId, ReportingClass.PRODUITS, "DONATION_REVENUE")
            .or(() -> accountResolver.resolveByReportingClass(companyId, ReportingClass.PRODUITS, null))
            .orElseThrow(() -> new ValidationException("DONATION_REVENUE_ACCOUNT_NOT_FOUND",
                "Aucun compte de produit de don trouvé. Configurer un compte PRODUITS " +
                "(idéalement marqué taxMappingCode=\"DONATION_REVENUE\") dans le plan comptable."));

        // V8.2getOrCreateJournal retourne le journal existant ou le crée avec
        // le code/label par défaut du type (jamais d'exception pour les types standards).
        // (Journal OD — opérations diverses)
        String journalCode = accountingEngineService.getOrCreateJournal(companyId,
            jo.accountant.accountingengine.entity.JournalType.OD).getCode();

        // Construire le tag analytique si le grant est rattaché à une valeur analytique
        List<AnalyticalTagDto> analyticalTags = buildAnalyticalTags(companyId, grant);

        String description = (isInKind ? "Don en nature reçu — " : "Don reçu — ")
            + donor.getName() + " — Reçu " + receipt.getReceiptNumber();

        List<LineDto> lines = new ArrayList<>();
        // Débit compte d'actif (trésorerie pour CASH, stock/immo pour IN_KIND)
        lines.add(new LineDto(debitAccount.getCode(), donor.getId(),
            amount, null, description, analyticalTags));
        // Crédit produit de don
        lines.add(new LineDto(revenueAccount.getCode(), donor.getId(),
            null, amount, description, analyticalTags));

        CreateJournalEntryRequest entryReq = new CreateJournalEntryRequest(
            journalCode, receiptDate, description, lines, JournalEntrySourceModule.FUNDS_GRANTS);

        // Idempotence : fondée sur l'ID du reçu — le rejeu ne crée pas de doublon
        String idempotencyKey = "funds-grants-donation-" + receipt.getId();
        JournalEntryResponse entry = accountingEngineService.createJournalEntry(
            companyId, idempotencyKey, entryReq);
        JournalEntryResponse posted = accountingEngineService.postJournalEntry(
            companyId, entry.id(), List.of());

        receipt.setJournalEntryId(posted.id());
        receiptRepository.save(receipt);
    }

    /**
     * Construit la liste de tags analytiques (allocation 100%) pour une ligne d'écriture liée
     * à un grant. Retourne une liste vide si le grant n'est pas rattaché à une valeur
     * analytique (ou si la valeur a disparu — anecdote : ne devrait pas arriver).
     */
    private List<AnalyticalTagDto> buildAnalyticalTags(UUID companyId, Grant grant) {
        if (grant == null || grant.getAnalyticalValueId() == null) {
            return List.of();
        }
        AnalyticalDimensionValue value = analyticalValueRepository
            .findById(grant.getAnalyticalValueId())
            .orElse(null);
        if (value == null || !companyId.equals(value.getCompanyId())) {
            LOG.warn("Valeur analytique {} du grant {} introuvable — écriture sans tag analytique",
                grant.getAnalyticalValueId(), grant.getCode());
            return List.of();
        }
        return List.of(new AnalyticalTagDto(
            value.getPlanId(), value.getId(), new BigDecimal("100")));
    }

    // --- Rapport bailleur ---

    /**
     * Rapport bailleur par subvention : montant reçu, dépenses par catégorie analytique,
     * solde restant — utile pour la reddition de comptes aux bailleurs institutionnels.
     *
     * <p><b>Audit M7 (corrigé)</b> : {@code totalReceived} est désormais calculé depuis les
     * {@code JournalLine} (même source que {@code totalSpent}) afin que les deux montants
     * soient reconciliables. La logique est la suivante :
     * <ol>
     * <li>Si le grant porte un {@code analyticalValueId} : on somme les crédits des
     * JournalLine POSTED sur un compte de PRODUITS, taguées avec cette valeur
     * analytique (mécanisme symétrique à {@link #calculateChargesByAnalyticalTag}).</li>
     * <li>Sinon (grant sans analyticalValueId, cas des dons non affectés) : on somme les
     * crédits des JournalLine PRODUITS des écritures générées par les reçus du grant
     * (lien via {@code DonationReceipt.journalEntryId}).</li>
     * <li>Rétro-compatibilité : pour les reçus créés avant la correction (sans
     * {@code journalEntryId}), on ajoute leur montant (fallback sur
     * {@code DonationReceipt.amount}).</li>
     * </ol>
     */
    @Transactional(readOnly = true)
    public DonorReport getDonorReport(UUID companyId, UUID grantId) {
        Grant grant = loadGrant(companyId, grantId);
        ThirdParty donor = thirdPartyRepository.findById(grant.getDonorThirdPartyId())
            .orElseThrow(() -> new NotFoundException("ThirdParty", grant.getDonorThirdPartyId()));
        //— defense-in-depth
        if (!donor.getCompanyId().equals(companyId)) {
            throw new NotFoundException("ThirdParty", grant.getDonorThirdPartyId());
        }

        // Total reçu depuis les JournalLine (audit M7 — sources reconciliables)
        BigDecimal totalReceivedFromJournal;
        if (grant.getAnalyticalValueId() != null) {
            // Cas nominal : filtre par tag analytique (symétrique au calcul des charges).
            totalReceivedFromJournal = calculateProductsByAnalyticalTag(companyId, grant);
        } else {
            // Cas des dons non affectés : lien via journalEntryId des reçus du grant.
            totalReceivedFromJournal = calculateProductsByReceiptEntries(companyId, grant);
        }

        // Rétro-compatibilité : pour les reçus créés avant la correction (sans journalEntryId),
        // on ajoute leur montant au total reçu (fallback sur DonationReceipt.amount).
        BigDecimal legacyReceived = receiptRepository.findByGrantId(grant.getId()).stream()
            .filter(r -> r.getJournalEntryId() == null)
            .map(DonationReceipt::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalReceived = totalReceivedFromJournal.add(legacyReceived);

        // Vague 2, item 2.6 : calcul exact des charges par tag analytique
        BigDecimal totalSpent = calculateChargesByAnalyticalTag(companyId, grant);

        BigDecimal balance = totalReceived.subtract(totalSpent);

        return new DonorReport(grant.getId(), grant.getCode(), grant.getLabel(),
            donor.getId(), donor.getName(), totalReceived, totalSpent, balance,
            grant.getStartDate(), grant.getEndDate());
    }

    // --- Clôture d'exercice (fonds dédiés) ---

    /**
     * Clôture d'exercice pour une subvention — génère la proposition d'écriture de fonds
     * dédiés et la soumet à {@code approval-workflow} (§13.
     *
     * <p>Pour une subvention RESTRICTED :
     * <ol>
     * <li>Calcule : produit constaté − charges de l'année portant le tag analytique.</li>
     * <li>Si solde positif (ressource affectée non utilisée) → soumet une ApprovalRequest
     * pour l'écriture proposée (Débit "engagement à réaliser" / Crédit "fonds dédiés").</li>
     * <li>Tant que la demande n'est pas APPROVED, aucune écriture n'est postée.</li>
     * </ol>
     *
     * <p>Pour une subvention UNRESTRICTED → pas de fonds dédiés, retourne un message informatif.
     */
    @Transactional
    public CloseFiscalYearResult closeFiscalYear(UUID companyId, UUID grantId) {
        Grant grant = loadGrant(companyId, grantId);

        if (grant.getRestrictionType() == RestrictionType.UNRESTRICTED) {
            return new CloseFiscalYearResult(grant.getId(), grant.getCode(),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                false, null,
                "Subvention non restreinte — pas de fonds dédiés à la clôture");
        }

        // Vague 2, item 2.6 : calcul exact par tag analytique
        // Produits = total reçu (donations)
        BigDecimal products = receiptRepository.findByGrantId(grant.getId()).stream()
            .map(DonationReceipt::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Charges = somme des débits des JournalLine POSTED taguées avec l'analyticalValueId du grant
        BigDecimal charges = calculateChargesByAnalyticalTag(companyId, grant);
        BigDecimal balance = products.subtract(charges);

        if (balance.compareTo(BigDecimal.ZERO) <= 0) {
            return new CloseFiscalYearResult(grant.getId(), grant.getCode(),
                products, charges, balance,
                false, null,
                "Solde nul ou négatif — pas de fonds dédiés à constituer");
        }

        // Solde positif → soumettre une ApprovalRequest pour l'écriture de fonds dédiés
        // (Débit "engagement à réaliser sur ressources affectées" / Crédit "fonds dédiés")
        EvaluateResult evalResult = approvalWorkflowService.evaluate(
            companyId,
            ApprovalActionType.GRANT_DISBURSEMENT_PROPOSAL,
            "Grant", grant.getId(),
            balance,
            List.of() // approverEmails — à résoudre par l'appelant
        );

        if (evalResult.autoApproved()) {
            // Pas de règle d'approbation active → on ne poste pas l'écriture automatiquement
            // (le mécanisme des fonds dédiés exige une approbation explicite, même sans règle)
            return new CloseFiscalYearResult(grant.getId(), grant.getCode(),
                products, charges, balance,
                false, null,
                "Solde positif de " + balance + " mais aucune règle d'approbation active. " +
                "Créer une règle GRANT_DISBURSEMENT_PROPOSAL pour soumettre l'écriture de fonds dédiés.");
        }

        LOG.info("Fonds dédiés proposés : grant={} balance={} requestId={}",
            grant.getCode(), balance, evalResult.requestId());

        return new CloseFiscalYearResult(grant.getId(), grant.getCode(),
            products, charges, balance,
            true, evalResult.requestId(),
            "Proposition d'écriture de fonds dédiés soumise à approbation (montant : " + balance + ")");
    }

    /**
     * Expose la consommation d'une subvention via un service public — utilisé par
     * :notificationspour l'alerte de seuil.
     */
    @Transactional(readOnly = true)
    public BigDecimal getConsumptionPercentage(UUID companyId, UUID grantId) {
        Grant grant = loadGrant(companyId, grantId);
        BigDecimal totalReceived = receiptRepository.findByGrantId(grant.getId()).stream()
            .map(DonationReceipt::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (grant.getTotalAmount().compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        // Audit M14 : le pourcentage est sans dimension, mais 2 décimales est insuffisant
        // (une subvention de 1 000 000 HTG consommée à 1 HTG donne 0,0001% qui s'arrondit à 0,00%).
        // On arrondit à 6 décimales (COMPUTATION_SCALE) pour conserver la précision.
        return totalReceived.multiply(new BigDecimal("100"))
            .divide(grant.getTotalAmount(),
                CurrencyRoundingService.COMPUTATION_SCALE, RoundingMode.HALF_UP);
    }

    // --- Helpers ---

    /**
     * Calcule les charges réelles consommées par un fonds (Vague 2, item 2.6, fix F3-6).
     *
     * <p>Filtre les JournalLine par :
     * <ol>
     * <li>Tag analytique = analyticalValueId du grant</li>
     * <li>Compte de classe CHARGES (ReportingClass.CHARGES) — pas tous les comptes</li>
     * <li>Écriture POSTED uniquement (pas DRAFT/PENDING_APPROVAL)</li>
     * </ol>
     *
     * <p>Somme les débits des lignes filtrées (= charges consommées par le fonds).
     */
    private BigDecimal calculateChargesByAnalyticalTag(UUID companyId, Grant grant) {
        if (grant.getAnalyticalValueId() == null) {
            LOG.debug("Grant {} n'a pas d'analyticalValueId — charges = 0", grant.getCode());
            return BigDecimal.ZERO;
        }

        // Trouver les IDs de JournalLine taguées avec cette valeur analytique
        List<UUID> taggedLineIds = analyticalTagRepository
            .findJournalLineIdsByAnalyticalValueId(companyId, grant.getAnalyticalValueId());

        if (taggedLineIds.isEmpty()) {
            return BigDecimal.ZERO;
        }

        // F3-6 (fix) : filtrer par CHARGES + POSTED
        // Récupérer les JournalLine POSTED pour filtrer efficacement
        List<JournalLine> allPostedLines = journalLineRepository.findAllPosted(companyId);
        java.util.Set<UUID> postedLineIds = new java.util.HashSet<>();
        for (JournalLine l : allPostedLines) {
            postedLineIds.add(l.getId());
        }

        // Pour chaque ligne taguée, vérifier qu'elle est POSTED ET que le compte est CHARGES
        BigDecimal charges = BigDecimal.ZERO;
        for (UUID lineId : taggedLineIds) {
            // Vérifier que la ligne est POSTED
            if (!postedLineIds.contains(lineId)) continue;

            //— defense-in-depth : filtrer par companyId
            JournalLine line = journalLineRepository.findById(lineId)
                .filter(l -> l.getCompanyId().equals(companyId))
                .orElse(null);
            if (line == null) continue;

            // F3 (fix) : vérifier que le compte est de classe CHARGES
            //— defense-in-depth : filtrer par companyId
            Account account = accountRepository.findById(line.getAccountId())
                .filter(a -> a.getCompanyId().equals(companyId))
                .orElse(null);
            if (account == null) continue;
            if (account.getReportingClass() != jo.accountant.core.framework.ReportingClass.CHARGES) continue;

            // Sommer les débits (charges = débit sur compte de charge)
            charges = charges.add(line.getDebit());
        }

        LOG.debug("Grant {} : charges calculées par tag analytique = {}", grant.getCode(), charges);
        return charges;
    }

    /**
     * Calcule les produits réellement reçus par un fonds (audit M7 — symétrique de
     * {@link #calculateChargesByAnalyticalTag} mais pour les PRODUITS/credits).
     *
     * <p>Filtre les JournalLine par :
     * <ol>
     * <li>Tag analytique = analyticalValueId du grant</li>
     * <li>Compte de classe PRODUITS (ReportingClass.PRODUITS) — pas tous les comptes</li>
     * <li>Écriture POSTED uniquement (pas DRAFT/PENDING_APPROVAL)</li>
     * </ol>
     *
     * <p>Somme les crédits des lignes filtrées (= produits reçus via le fonds).
     * Si le grant n'a pas d'analyticalValueId (don non affecté), retourne 0 — les produits
     * ne peuvent pas être attribués à un fonds précis sans tag analytique.
     */
    private BigDecimal calculateProductsByAnalyticalTag(UUID companyId, Grant grant) {
        if (grant.getAnalyticalValueId() == null) {
            LOG.debug("Grant {} n'a pas d'analyticalValueId — produits = 0", grant.getCode());
            return BigDecimal.ZERO;
        }

        List<UUID> taggedLineIds = analyticalTagRepository
            .findJournalLineIdsByAnalyticalValueId(companyId, grant.getAnalyticalValueId());

        if (taggedLineIds.isEmpty()) {
            return BigDecimal.ZERO;
        }

        List<JournalLine> allPostedLines = journalLineRepository.findAllPosted(companyId);
        java.util.Set<UUID> postedLineIds = new java.util.HashSet<>();
        for (JournalLine l : allPostedLines) {
            postedLineIds.add(l.getId());
        }

        BigDecimal products = BigDecimal.ZERO;
        for (UUID lineId : taggedLineIds) {
            if (!postedLineIds.contains(lineId)) continue;

            //— defense-in-depth : filtrer par companyId
            JournalLine line = journalLineRepository.findById(lineId)
                .filter(l -> l.getCompanyId().equals(companyId))
                .orElse(null);
            if (line == null) continue;

            //— defense-in-depth : filtrer par companyId
            Account account = accountRepository.findById(line.getAccountId())
                .filter(a -> a.getCompanyId().equals(companyId))
                .orElse(null);
            if (account == null) continue;
            if (account.getReportingClass() != ReportingClass.PRODUITS) continue;

            // Sommer les crédits (produits = crédit sur compte de produits)
            products = products.add(line.getCredit());
        }

        LOG.debug("Grant {} : produits calculés par tag analytique = {}", grant.getCode(), products);
        return products;
    }

    /**
     * Calcule les produits reçus par un fonds en sommant les crédits des JournalLine PRODUITS
     * des écritures générées par les reçus du grant (audit M7).
     *
     * <p>Utilisé lorsque le grant ne porte pas d'{@code analyticalValueId} (dons non affectés) —
     * le lien receipt → {@code journalEntryId} → JournalLine filtre alors les produits.
     * Ne retient que les lignes sur un compte de PRODUITS (pour exclure la contre-passation
     * trésorerie qui figure aussi dans l'écriture).
     */
    private BigDecimal calculateProductsByReceiptEntries(UUID companyId, Grant grant) {
        List<DonationReceipt> receipts = receiptRepository.findByGrantId(grant.getId());
        if (receipts.isEmpty()) {
            return BigDecimal.ZERO;
        }

        BigDecimal products = BigDecimal.ZERO;
        for (DonationReceipt receipt : receipts) {
            if (receipt.getJournalEntryId() == null) continue; // reçu pré-correction (legacy)

            List<JournalLine> lines = journalLineRepository
                .findByJournalEntryIdOrderByLineNumber(receipt.getJournalEntryId());
            for (JournalLine line : lines) {
                //— defense-in-depth : filtrer par companyId
                Account account = accountRepository.findById(line.getAccountId())
                    .filter(a -> a.getCompanyId().equals(companyId))
                    .orElse(null);
                if (account == null) continue;
                if (account.getReportingClass() != ReportingClass.PRODUITS) continue;
                // Sommer les crédits (produits = crédit sur compte de produits)
                products = products.add(line.getCredit());
            }
        }

        LOG.debug("Grant {} : produits calculés par journalEntryId des reçus = {}",
            grant.getCode(), products);
        return products;
    }

    private Grant loadGrant(UUID companyId, UUID grantId) {
        Grant grant = grantRepository.findById(grantId)
            .orElseThrow(() -> new NotFoundException("Grant", grantId));
        if (!grant.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Grant", grantId);
        }
        return grant;
    }

    private static GrantResponse toResponse(Grant g) {
        return new GrantResponse(g.getId(), g.getCompanyId(), g.getDonorThirdPartyId(),
            g.getCode(), g.getLabel(), g.getTotalAmount(), g.getCurrency(),
            g.getStartDate(), g.getEndDate(), g.getRestrictionType(),
            g.getAnalyticalValueId(), g.getCreatedAt(), g.getUpdatedAt());
    }
}
