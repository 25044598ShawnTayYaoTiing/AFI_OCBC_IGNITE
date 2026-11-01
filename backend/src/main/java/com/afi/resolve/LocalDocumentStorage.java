package com.afi.resolve;

import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "afi.storage-provider", havingValue = "local", matchIfMissing = true)
public class LocalDocumentStorage implements DocumentStorage {
  private final Path root;

  public LocalDocumentStorage(@Value("${afi.document-storage:.local/documents}") String directory)
      throws Exception {
    root = Path.of(directory).toAbsolutePath().normalize();
    Files.createDirectories(root);
  }

  private Path path(String key) {
    DocumentStorage.validateKey(key);
    Path path = root.resolve(key).normalize();
    if (!path.startsWith(root)) throw new IllegalArgumentException("Invalid storage path");
    return path;
  }

  public void put(String key, byte[] bytes) throws Exception {
    Files.write(path(key), bytes, StandardOpenOption.CREATE_NEW);
  }

  public byte[] read(String key) throws Exception {
    return Files.readAllBytes(path(key));
  }

  public void delete(String key) throws Exception {
    Files.deleteIfExists(path(key));
  }
}
