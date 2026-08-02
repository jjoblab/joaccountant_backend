package jo.accountant.accountingengine.dto;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Wrapper de pagination keyset.
 *
 * <p>Contrairement à {@link org.springframework.data.domain.Page}, ce wrapper ne transporte
 * ni {@code totalElements} ni {@code totalPages} — ces deux valeurs nécessitent un
 * {@code COUNT(*)} qui, sur 10M+ de lignes, est aussi coûteux qu'un OFFSET profond. Le keyset
 * abandonne volontairement cette information au profit d'une latence constante sur toutes les
 * pages.
 *
 * <p>Le client itère en utilisant {@link #nextAfterEntryDate()} et {@link #nextAfterId()} comme
 * curseurs de la page suivante, tant que {@link #hasNext()} est {@code true}.
 *
 * <p><b>Contrat du curseur</b> :
 * <ul>
 * <li>Le curseur "suivant" est calculé à partir du <em>dernier</em> élément de {@link #content()}
 * (jamais le premier, pour respecter l'ordre DESC).</li>
 * <li>{@link #hasNext()} est {@code true} ssi la page retournée contient exactement
 * {@code pageSize} éléments — heuristique qui suppose que si la DB a retourné une page
 * pleine, il y a probablement une page suivante. Si la page est partielle, c'est la
 * dernière.</li>
 * <li>Si {@link #content()} est vide, {@link #hasNext()} est {@code false} et les curseurs
 * sont {@code null}.</li>
 * </ul>
 *
 * @param <T> type d'élément (typiquement {@link JournalEntryResponse})
 */
public record KeysetPage<T>(
 List<T> content,
 LocalDate nextAfterEntryDate,
 UUID nextAfterId,
 boolean hasNext
) {

 /**
 * Construit une page keyset à partir d'une liste d'éléments et de la taille de page
 * demandée. Calcule automatiquement le curseur suivant et le flag {@code hasNext}.
 *
 * @param content liste d'éléments retournée par la requête (déjà limitée à {@code pageSize}+1
 * si l'appelant a utilisé la technique du "LIMIT N+1" pour détecter hasNext
 * de façon certaine ; sinon la taille de la liste est utilisée).
 * @param pageSize taille de page demandée (utilisée pour l'heuristique hasNext)
 * @param lastEntryDateExtractor fonction pour extraire la date du curseur d'un élément
 * @param lastIdExtractor fonction pour extraire l'ID du curseur d'un élément
 */
 public static <T> KeysetPage<T> of(
 List<T> content,
 int pageSize,
 java.util.function.Function<T, LocalDate> lastEntryDateExtractor,
 java.util.function.Function<T, UUID> lastIdExtractor) {
 if (content == null || content.isEmpty()) {
 return new KeysetPage<>(List.of(), null, null, false);
 }
 T last = content.get(content.size() - 1);
 boolean hasNext = content.size() >= pageSize;
 return new KeysetPage<>(
 List.copyOf(content),
 lastEntryDateExtractor.apply(last),
 lastIdExtractor.apply(last),
 hasNext
 );
 }
}
