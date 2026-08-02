package jo.accountant.core.port;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Implémentation filesystem par défaut de {@link FileStoragePort} pour dev/test (§3.11).
 *
 * <p>§3.11 : {@code storageKey} est opaque — l'appelant ne connaît jamais le chemin physique.
 * Stocké sous un répertoire racine unique configuré par {@code app.storage.fs.root} (défaut
 * {@code ./storage}).
 *
 * <p>Marqué {@link ConditionalOnMissingBean} pour qu'une implémentation prod-grade S3/MinIO
 * puisse l'overrider en enregistrant simplement son propre bean.
 *
 * <p><b>§9 </b> : activé uniquement quand {@code app.storage.backend=fs}
 * (défaut) ou non défini. Désactivé quand {@code app.storage.backend=s3} pour laisser la place
 * à {@link S3FileStorageAdapter}.
 
 *
 * @author jo@Dev


*/
@Component
@ConditionalOnProperty(name = "app.storage.backend", havingValue = "fs", matchIfMissing = true)
public class FileSystemFileStorageAdapter implements FileStoragePort {

 private static final Logger LOG = LoggerFactory.getLogger(FileSystemFileStorageAdapter.class);

 private final Path root;

 public FileSystemFileStorageAdapter(@Value("${app.storage.fs.root:./storage}") String root) {
 //§9 défaut changé de ./build/storage (wipe on gradlew clean)
 // vers ./storage. Cohérent avec application.yml (qui a aussi ./storage comme défaut).
 // Sans cette cohérence, si l'env var APP_STORAGE_ROOT n'était pas positionnée,
 // l'application utilisait ./build/storage — perte garantie des PDF à chaque clean.
 this.root = Path.of(root).toAbsolutePath().normalize();
 }

 @PostConstruct
 void init() throws IOException {
 Files.createDirectories(root);
 LOG.info("FileStoragePort root = {}", root);
 }

 @Override
 public String store(byte[] content, String contentType, String suggestedExtension) {
 // S3 (fix) : sanitiser l'extension pour empêcher le path traversal
 // Seuls les caractères alphanumériques sont autorisés dans l'extension
 String ext = "";
 if (suggestedExtension != null && !suggestedExtension.isBlank()) {
 String sanitized = suggestedExtension.replaceAll("[^a-zA-Z0-9]", "");
 if (!sanitized.isEmpty() && sanitized.length() <= 10) {
 ext = "." + sanitized.toLowerCase();
 }
 }
 String key = UUID.randomUUID().toString().replace("-", "") + ext;
 // S3 (fix) : vérifier que le path résolu reste dans la racine
 java.nio.file.Path resolved = root.resolve(key).normalize();
 if (!resolved.startsWith(root)) {
 throw new IllegalArgumentException("Path traversal attempt detected in store()");
 }
 try {
 Files.write(resolved, content,
 StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
 } catch (IOException ex) {
 throw new IllegalStateException("Failed to store file under " + root, ex);
 }
 return key;
 }

 @Override
 public byte[] load(String storageKey) {
 try {
 Path resolved = root.resolve(storageKey).normalize();
 if (!resolved.startsWith(root)) {
 throw new IllegalArgumentException("Path traversal attempt: " + storageKey);
 }
 return Files.readAllBytes(resolved);
 } catch (IOException ex) {
 throw new IllegalStateException("Failed to load file " + storageKey, ex);
 }
 }

 @Override
 public void delete(String storageKey) {
 try {
 Path resolved = root.resolve(storageKey).normalize();
 if (!resolved.startsWith(root)) {
 throw new IllegalArgumentException("Path traversal attempt: " + storageKey);
 }
 Files.deleteIfExists(resolved);
 } catch (IOException ex) {
 LOG.warn("Failed to delete file {} (best-effort)", storageKey, ex);
 }
 }
}
