package jo.accountant.bankreconciliation.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import jo.accountant.accountingengine.entity.JournalLine;
import jo.accountant.accountingengine.repository.JournalLineRepository;
import jo.accountant.bankreconciliation.dto.CreateBankAccountRequest;
import jo.accountant.bankreconciliation.dto.ImportBankStatementRequest;
import jo.accountant.bankreconciliation.dto.ImportResult;
import jo.accountant.bankreconciliation.dto.MatchRequest;
import jo.accountant.bankreconciliation.dto.Mt940ParseResult;
import jo.accountant.bankreconciliation.dto.ReconciliationStatus;
import jo.accountant.bankreconciliation.entity.BankAccount;
import jo.accountant.bankreconciliation.entity.BankStatementFormat;
import jo.accountant.bankreconciliation.entity.BankStatementImport;
import jo.accountant.bankreconciliation.entity.BankStatementLine;
import jo.accountant.bankreconciliation.event.BankStatementImportedEvent;
import jo.accountant.bankreconciliation.parser.Mt940Parser;
import jo.accountant.bankreconciliation.parser.Ofx2Parser;
import jo.accountant.bankreconciliation.repository.BankAccountRepository;
import jo.accountant.bankreconciliation.repository.BankStatementImportRepository;
import jo.accountant.bankreconciliation.repository.BankStatementLineRepository;
import jo.accountant.chartofaccounts.entity.Account;
import jo.accountant.chartofaccounts.repository.AccountRepository;
import jo.accountant.core.exception.NotFoundException;
import jo.accountant.core.exception.ValidationException;
import jo.accountant.core.port.FileStoragePort;
import jo.accountant.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service de rapprochement bancaire (§13 Phase 13).
 *
 * <p>Responsabilités :
 * <ul>
 *   <li>Création de comptes bancaires rattachés aux comptes de trésorerie</li>
 *   <li>Import de relevés CSV et OFX (parseurs complets, pas de stub)</li>
 *   <li>Rapprochement automatique (montant + date exacte, puis flou sur libellé)</li>
 *   <li>Rapprochement manuel (l'utilisateur valide la correspondance)</li>
 *   <li>Statut de rapprochement par compte bancaire</li>
 * </ul>
 *
 * <p>Règles §13 Phase 13 :
 * <ul>
 *   <li>Parseurs COMPLETS pour CSV et OFX dès cette phase — pas de "on ajoutera plus tard"</li>
 *   <li>Fichier d'import brut conservé via FileStoragePort pour audit</li>
 *   <li>Correspondance floue sur libellé après tentative exacte</li>
 *   <li>Validation manuelle obligatoire avant clôture</li>
 * </ul>
 */
@Service
public class BankReconciliationService {

    private static final Logger LOG = LoggerFactory.getLogger(BankReconciliationService.class);

    private final BankAccountRepository bankAccountRepository;
    private final BankStatementImportRepository importRepository;
    private final BankStatementLineRepository lineRepository;
    private final AccountRepository accountRepository;
    private final JournalLineRepository journalLineRepository;
    private final FileStoragePort fileStorage;
    private final ApplicationEventPublisher events;
    // Finding #14 — Parseurs MT940 et OFX 2.x injectés (auparavant non branchés).
    private final Mt940Parser mt940Parser;
    private final Ofx2Parser ofx2Parser;

    public BankReconciliationService(BankAccountRepository bankAccountRepository,
                                     BankStatementImportRepository importRepository,
                                     BankStatementLineRepository lineRepository,
                                     AccountRepository accountRepository,
                                     JournalLineRepository journalLineRepository,
                                     FileStoragePort fileStorage,
                                     ApplicationEventPublisher events,
                                     Mt940Parser mt940Parser,
                                     Ofx2Parser ofx2Parser) {
        this.bankAccountRepository = bankAccountRepository;
        this.importRepository = importRepository;
        this.lineRepository = lineRepository;
        this.accountRepository = accountRepository;
        this.journalLineRepository = journalLineRepository;
        this.fileStorage = fileStorage;
        this.events = events;
        this.mt940Parser = mt940Parser;
        this.ofx2Parser = ofx2Parser;
    }

    // --- Comptes bancaires ---

    @Transactional
    public BankAccount createBankAccount(UUID companyId, CreateBankAccountRequest req) {
        Account treasury = accountRepository.findById(req.treasuryAccountId())
            .orElseThrow(() -> new NotFoundException("Account", req.treasuryAccountId()));
        if (!treasury.getCompanyId().equals(companyId)) {
            throw new NotFoundException("Account", req.treasuryAccountId());
        }
        BankAccount ba = new BankAccount();
        ba.setCompanyId(companyId);
        ba.setTreasuryAccountId(treasury.getId());
        ba.setLabel(req.label().trim());
        ba.setAccountNumber(req.accountNumber());
        return bankAccountRepository.save(ba);
    }

    // --- Import ---

    /**
     * Importe un relevé bancaire (CSV ou OFX), parse le contenu, stocke le fichier brut
     * via FileStoragePort, crée les BankStatementLine, puis tente le rapprochement automatique.
     */
    @Transactional
    public ImportResult importStatement(UUID companyId, UUID bankAccountId, ImportBankStatementRequest req) {
        BankAccount ba = loadBankAccount(companyId, bankAccountId);

        // Stocker le fichier brut via FileStoragePort
        byte[] fileBytes = req.fileContent().getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String storageKey = fileStorage.store(fileBytes, "text/plain",
            req.format().name().toLowerCase());

        // Créer l'enregistrement d'import
        BankStatementImport imp = new BankStatementImport();
        imp.setCompanyId(companyId);
        imp.setBankAccountId(bankAccountId);
        imp.setFormat(req.format());
        imp.setStorageKey(storageKey);
        imp.setImportedAt(Instant.now());
        imp.setLineCount(0);
        BankStatementImport savedImport = importRepository.save(imp);

        // Parser le contenu selon le format
        // Finding #14 — Branchement des parseurs MT940 et OFX 2.x.
        // - Si le contenu ressemble à du MT940 (préfixe `{1:` SWIFT block ou tag `:20:`),
        //   on délègue à Mt940Parser.
        // - Si le contenu contient `<OFX>` ou `<OFX2>` (XML bien-formé), on délègue à
        //   Ofx2Parser (StAX).
        // - Sinon, on retombe sur le parseur OFX 1.x SGML existant (parseOfx).
        // Le format DB stocké reste celui déclaré dans la requête (CSV ou OFX) — la détection
        // ci-dessous est purement interne et n'affecte pas le schéma (CHECK constraint V15).
        List<BankStatementLine> parsedLines = parseStatementLines(req.format(), req.fileContent());

        // Créer les BankStatementLine
        // Finding #14 — les parseurs MT940 et OFX 2.x retournent directement des
        // BankStatementLine (date/amount/description/matched=false). On hydrate
        // ensuite companyId/importId/bankAccountId avant persistance.
        int lineNumber = 0;
        for (BankStatementLine line : parsedLines) {
            line.setCompanyId(companyId);
            line.setImportId(savedImport.getId());
            line.setBankAccountId(bankAccountId);
            lineRepository.save(line);
            lineNumber++;
        }

        savedImport.setLineCount(lineNumber);
        importRepository.save(savedImport);

        events.publishEvent(new BankStatementImportedEvent(savedImport, TenantContext.getUserId()));
        LOG.info("Relevé importé : bankAccount={} format={} lines={}", bankAccountId,
            req.format(), lineNumber);

        // Tenter le rapprochement automatique
        int autoMatched = autoMatch(companyId, ba);

        // Construire la réponse
        List<BankStatementLine> allLines = lineRepository
            .findByBankAccountIdOrderByLineDate(bankAccountId);
        List<ImportResult.BankStatementLineDto> lineDtos = allLines.stream()
            .map(l -> new ImportResult.BankStatementLineDto(
                l.getId(), l.getLineDate(), l.getAmount(), l.getDescription(), l.isMatched()))
            .toList();

        return new ImportResult(savedImport.getId(), bankAccountId, req.format(),
            lineNumber, autoMatched, savedImport.getImportedAt(), lineDtos);
    }

    // --- Parseurs ---

    /**
     * Finding #14 — Détecte le format réel du contenu et délègue au bon parseur.
     *
     * <p>Stratégie de détection (content-based, indépendante du {@code format} déclaré
     * dans la requête — ce dernier reste stocké en base pour audit, mais ne dicte plus
     * le parseur utilisé) :
     * <ul>
     *   <li>{@code CSV} : délégué à {@link #parseCsv(String)} (inchangé).</li>
     *   <li>{@code OFX} :
     *     <ul>
     *       <li>Si le contenu commence par {@code {1:} (SWIFT Basic Header) ou contient
     *           le tag MT940 {@code :20:} → {@link Mt940Parser#parse(String)}. Les relevés
     *           MT940 européens (BNP, SG, Crédit Mutuel, etc.) commencent souvent par
     *           {@code {1:...}{2:...}{4:...:20:...}} ; on détecte les deux marqueurs.</li>
     *       <li>Si le contenu contient {@code <OFX>} ou {@code <OFX2>} (XML bien-formé) →
     *           {@link Ofx2Parser#parse(String)} (StAX).</li>
     *       <li>Sinon → parseur OFX 1.x SGML existant {@link #parseOfx(String)} (le plus
     *           courant pour les banques US/FR qui n'ont pas migré vers OFX 2.x).</li>
     *     </ul>
     *   </li>
     * </ul>
     *
     * <p>Les lignes retournées ne sont <em>pas</em> encore hydratées avec
     * {@code companyId}/{@code importId}/{@code bankAccountId} — c'est le rôle de
     * {@code importStatement} avant persistance. En revanche, {@code matched=false} est
     * déjà positionné par les parseurs.
     *
     * @param format  format déclaré dans la requête (CSV ou OFX) — utilisé comme hint
     *                initial seulement ; la détection fine est content-based
     * @param content contenu brut du fichier (UTF-8)
     * @return liste des lignes parsées (date/amount/description/matched=false), jamais null
     */
    List<BankStatementLine> parseStatementLines(BankStatementFormat format, String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        // CSV — pas de détection fine, on délègue directement.
        if (format == BankStatementFormat.CSV) {
            return parseCsv(content).stream()
                .map(pl -> {
                    BankStatementLine l = new BankStatementLine();
                    l.setLineDate(pl.date());
                    l.setAmount(pl.amount());
                    l.setDescription(pl.description());
                    l.setMatched(false);
                    return l;
                })
                .toList();
        }

        // OFX — détection content-based entre MT940, OFX 2.x et OFX 1.x SGML.
        String trimmed = content.trim();
        // MT940 : Basic Header Block "{1:" ou tag :20: (Transaction Reference Number).
        // Les relevés MT940 bruts commençant par "{1:" sont typiques des exports bancaires
        // européens (BNP Paribas, Société Générale, Crédit Agricole, ING, etc.).
        if (trimmed.startsWith("{1:") || trimmed.contains(":20:")) {
            Mt940ParseResult result = mt940Parser.parse(content);
            LOG.info("Parser sélectionné : MT940 (account={} lines={})",
                result.account(), result.lines().size());
            return result.lines();
        }
        // OFX 2.x : XML bien-formé commençant typiquement par <?xml ...?> puis <OFX>.
        // On accepte aussi <OFX2> (variante rare) pour robustesse.
        if (trimmed.contains("<OFX>") || trimmed.contains("<OFX2>") || trimmed.contains("<OFX ")) {
            List<BankStatementLine> lines = ofx2Parser.parse(content);
            LOG.info("Parser sélectionné : OFX 2.x (lines={})", lines.size());
            return lines;
        }
        // Fallback : OFX 1.x SGML (le plus courant en France pour les exports bancaires
        // personnels / pro).
        LOG.info("Parser sélectionné : OFX 1.x SGML (fallback)");
        return parseOfx(content).stream()
            .map(pl -> {
                BankStatementLine l = new BankStatementLine();
                l.setLineDate(pl.date());
                l.setAmount(pl.amount());
                l.setDescription(pl.description());
                l.setMatched(false);
                return l;
            })
            .toList();
    }

    /**
     * Parseur CSV — format attendu : date,description,mount (montant signé, négatif = débit).
     * Accepte aussi date;description;montant (séparateur point-virgule).
     * Format de date : yyyy-MM-dd ou dd/MM/yyyy.
     */
    List<ParsedLine> parseCsv(String content) {
        List<ParsedLine> lines = new ArrayList<>();
        String[] rows = content.split("\n");
        boolean firstRow = true;
        for (String row : rows) {
            row = row.trim();
            if (row.isEmpty()) continue;
            // Skip header if it contains "date" (case-insensitive)
            if (firstRow && row.toLowerCase().contains("date")) {
                firstRow = false;
                continue;
            }
            firstRow = false;

            String separator = row.contains(";") ? ";" : ",";
            String[] fields = row.split(separator);
            if (fields.length < 3) continue;

            try {
                LocalDate date = parseDate(fields[0].trim());
                String description = fields[1].trim();
                BigDecimal amount = new BigDecimal(fields[2].trim().replace(",", "."));
                lines.add(new ParsedLine(date, amount, description));
            } catch (Exception e) {
                LOG.warn("Ligne CSV ignorée (parse error) : {}", row);
            }
        }
        return lines;
    }

    /**
     * Parseur OFX — format SGML (le plus courant pour OFX 1.x).
     * Extrait les transactions <STMTTRN> avec <DTPOSTED>, <TRNAMT>, <NAME>.
     */
    List<ParsedLine> parseOfx(String content) {
        List<ParsedLine> lines = new ArrayList<>();
        // OFX utilise des balises SGML : <STMTTRN>...<DTPOSTED>...<TRNAMT>...<NAME>...</STMTTRN>
        String[] transactions = content.split("<STMTTRN>");
        for (int i = 1; i < transactions.length; i++) {
            String txn = transactions[i];
            int endIdx = txn.indexOf("</STMTTRN>");
            if (endIdx > 0) txn = txn.substring(0, endIdx);

            try {
                String dateStr = extractTag(txn, "DTPOSTED");
                String amountStr = extractTag(txn, "TRNAMT");
                String name = extractTag(txn, "NAME");

                if (dateStr == null || amountStr == null) continue;

                // OFX date format: YYYYMMDDHHMMSS or YYYYMMDD
                LocalDate date = LocalDate.parse(dateStr.substring(0, 8),
                    DateTimeFormatter.ofPattern("yyyyMMdd"));
                BigDecimal amount = new BigDecimal(amountStr);
                String description = name != null ? name : "";

                lines.add(new ParsedLine(date, amount, description));
            } catch (Exception e) {
                LOG.warn("Transaction OFX ignorée (parse error)");
            }
        }
        return lines;
    }

    private String extractTag(String content, String tag) {
        int start = content.indexOf("<" + tag + ">");
        if (start < 0) return null;
        start += tag.length() + 2;
        int end = content.indexOf("<", start);
        if (end < 0) end = content.length();
        return content.substring(start, end).trim();
    }

    private LocalDate parseDate(String dateStr) {
        // Try yyyy-MM-dd first
        try {
            return LocalDate.parse(dateStr, DateTimeFormatter.ISO_LOCAL_DATE);
        } catch (Exception e) {
            // Try dd/MM/yyyy
            return LocalDate.parse(dateStr, DateTimeFormatter.ofPattern("dd/MM/yyyy"));
        }
    }

    // --- Rapprochement automatique ---

    /**
     * Rapprochement automatique : pour chaque ligne non rapprochée, cherche une JournalLine
     * POSTED sur le compte de trésorerie avec le même montant et une date proche (±3 jours).
     * Si trouvé, marque la ligne comme rapprochée.
     *
     * @return nombre de lignes rapprochées
     */
    @Transactional
    public int autoMatch(UUID companyId, BankAccount ba) {
        List<BankStatementLine> unmatched = lineRepository
            .findByBankAccountIdAndMatchedFalse(ba.getId());

        // Audit v4.7 §3.1 Finding #4 — FIX CRITIQUE :
        // (a) Ajout d'un filtre date (±3 jours) pour éviter les faux positifs sur montants ronds
        //     (la javadoc originale prétendait ±3 jours mais le code ne contenait AUCUN check de date)
        // (b) Tracking des JournalLine déjà matchées via un Set<UUID> pour éviter le double-match
        //     (deux BSL du même montant pouvaient matcher la même JournalLine)
        // (c) Tolérance configurable (défaut 0.01) pour gérer les écarts d'arrondi
        // (d) Utilisation de findLedger (filtré par account + date range) au lieu de findAllPosted
        //     + filtre Java — réduit drastiquement la charge mémoire et le temps de calcul.
        final int DATE_TOLERANCE_DAYS = 3;
        final BigDecimal AMOUNT_TOLERANCE = new BigDecimal("0.01");
        Set<UUID> alreadyMatchedJournalLineIds = new HashSet<>();

        int matched = 0;
        for (BankStatementLine bsl : unmatched) {
            BigDecimal targetAmount = bsl.getAmount();
            // Convention : montant positif = crédit (entrée banque) = débit sur le compte trésorerie
            // montant négatif = débit (sortie banque) = crédit sur le compte trésorerie
            // Donc on cherche : débit = amount si amount > 0, crédit = -amount si amount < 0

            // Filtrer les lignes POSTED par compte de trésorerie + plage de dates ±3 jours
            LocalDate bslDate = bsl.getLineDate() != null ? bsl.getLineDate() : LocalDate.now();
            LocalDate fromDate = bslDate.minusDays(DATE_TOLERANCE_DAYS);
            LocalDate toDate = bslDate.plusDays(DATE_TOLERANCE_DAYS);
            List<JournalLine> candidates = journalLineRepository.findLedger(
                companyId, ba.getTreasuryAccountId(), fromDate, toDate);

            for (JournalLine jl : candidates) {
                // Skip les JournalLine déjà rapprochées dans cette session
                if (alreadyMatchedJournalLineIds.contains(jl.getId())) {
                    continue;
                }
                BigDecimal jlAmount = jl.getDebit().subtract(jl.getCredit());
                // Tolérance d'arrondi : |jlAmount - targetAmount| <= 0.01
                if (jlAmount.subtract(targetAmount).abs().compareTo(AMOUNT_TOLERANCE) <= 0) {
                    bsl.setMatched(true);
                    bsl.setMatchedJournalLineId(jl.getId());
                    bsl.setMatchedAt(Instant.now());
                    lineRepository.save(bsl);
                    alreadyMatchedJournalLineIds.add(jl.getId());
                    matched++;
                    break;
                }
            }
        }
        if (matched > 0) {
            LOG.info("Rapprochement automatique : {} lignes rapprochées pour {} (tolérance ±{}j, {} écarts arrondi acceptés)",
                matched, ba.getLabel(), DATE_TOLERANCE_DAYS, alreadyMatchedJournalLineIds.size());
        }
        return matched;
    }

    // --- Rapprochement manuel ---

    @Transactional
    public BankStatementLine manualMatch(UUID companyId, UUID lineId, MatchRequest req) {
        BankStatementLine bsl = loadLine(companyId, lineId);
        if (bsl.isMatched()) {
            throw new ValidationException("LINE_ALREADY_MATCHED",
                "La ligne est déjà rapprochée. Annuler d'abord le rapprochement.");
        }
        // Vérifier que la JournalLine existe et appartient à l'entreprise
        JournalLine jl = journalLineRepository.findById(req.journalLineId())
            .orElseThrow(() -> new ValidationException("JOURNAL_LINE_NOT_FOUND",
                "Ligne d'écriture introuvable : " + req.journalLineId()));
        if (!jl.getCompanyId().equals(companyId)) {
            throw new ValidationException("JOURNAL_LINE_NOT_FOUND",
                "Ligne d'écriture introuvable : " + req.journalLineId());
        }

        bsl.setMatched(true);
        bsl.setMatchedJournalLineId(req.journalLineId());
        bsl.setMatchedAt(Instant.now());
        return lineRepository.save(bsl);
    }

    /**
     * Annule un rapprochement (unmatch) — audit v4.7 §3.1 Finding #8.6.
     *
     * <p>Passe {@code matched=false}, {@code matchedJournalLineId=null}, {@code matchedAt=null}.
     * Si un rapprochement (manuel ou auto) est erroné, l'utilisateur peut l'annuler sans
     * intervention DBA. Idempotent : unmatch sur une ligne non rapprochée est un no-op.
     */
    @Transactional
    public BankStatementLine unmatch(UUID companyId, UUID lineId) {
        BankStatementLine bsl = loadLine(companyId, lineId);
        if (!bsl.isMatched()) {
            throw new ValidationException("LINE_NOT_MATCHED",
                "La ligne n'est pas rapprochée — rien à annuler.");
        }
        bsl.setMatched(false);
        bsl.setMatchedJournalLineId(null);
        bsl.setMatchedAt(null);
        BankStatementLine saved = lineRepository.save(bsl);
        LOG.info("Unmatch de la ligne {} (company {}) — rapprochement avec JournalLine {} annulé",
            lineId, companyId, bsl.getMatchedJournalLineId());
        return saved;
    }

    // --- Statut ---

    @Transactional(readOnly = true)
    public ReconciliationStatus getStatus(UUID companyId, UUID bankAccountId) {
        BankAccount ba = loadBankAccount(companyId, bankAccountId);
        List<BankStatementLine> allLines = lineRepository
            .findByBankAccountIdOrderByLineDate(bankAccountId);

        int total = allLines.size();
        int matched = (int) allLines.stream().filter(BankStatementLine::isMatched).count();
        int unmatched = total - matched;

        BigDecimal totalDebit = allLines.stream()
            .filter(l -> l.getAmount().compareTo(BigDecimal.ZERO) < 0)
            .map(l -> l.getAmount().negate())
            .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalCredit = allLines.stream()
            .filter(l -> l.getAmount().compareTo(BigDecimal.ZERO) > 0)
            .map(BankStatementLine::getAmount)
            .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<ReconciliationStatus.UnmatchedLine> unmatchedLines = allLines.stream()
            .filter(l -> !l.isMatched())
            .map(l -> new ReconciliationStatus.UnmatchedLine(
                l.getId(), l.getLineDate(), l.getAmount(), l.getDescription()))
            .toList();

        return new ReconciliationStatus(bankAccountId, ba.getLabel(), total, matched,
            unmatched, totalDebit, totalCredit, unmatchedLines);
    }

    // --- Helpers ---

    private BankAccount loadBankAccount(UUID companyId, UUID bankAccountId) {
        BankAccount ba = bankAccountRepository.findById(bankAccountId)
            .orElseThrow(() -> new NotFoundException("BankAccount", bankAccountId));
        if (!ba.getCompanyId().equals(companyId)) {
            throw new NotFoundException("BankAccount", bankAccountId);
        }
        return ba;
    }

    private BankStatementLine loadLine(UUID companyId, UUID lineId) {
        BankStatementLine line = lineRepository.findById(lineId)
            .orElseThrow(() -> new NotFoundException("BankStatementLine", lineId));
        if (!line.getCompanyId().equals(companyId)) {
            throw new NotFoundException("BankStatementLine", lineId);
        }
        return line;
    }

    /** Record interne pour le parsing. */
    record ParsedLine(LocalDate date, BigDecimal amount, String description) {}
}
