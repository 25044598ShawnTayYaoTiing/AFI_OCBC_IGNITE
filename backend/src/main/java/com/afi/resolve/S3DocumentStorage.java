package com.afi.resolve;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.InstanceProfileCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

/** EC2 temporary role credentials only; no access-key configuration or public/presigned URLs. */
@Component
@ConditionalOnProperty(name = "afi.storage-provider", havingValue = "s3")
public class S3DocumentStorage implements DocumentStorage {
  private final InstanceProfileCredentialsProvider credentials;
  private final S3Client client;
  private final String bucket;
  private final String prefix;

  public S3DocumentStorage(
      @Value("${afi.s3-bucket}") String bucket,
      @Value("${afi.s3-region}") String region,
      @Value("${afi.s3-prefix:documents/}") String prefix) {
    if (bucket.isBlank()
        || region.isBlank()
        || !prefix.matches("[A-Za-z0-9/_-]*")
        || prefix.startsWith("/")) throw new IllegalArgumentException("Invalid S3 configuration");
    this.bucket = bucket;
    this.prefix = prefix.isEmpty() || prefix.endsWith("/") ? prefix : prefix + "/";
    credentials =
        InstanceProfileCredentialsProvider.builder().asyncCredentialUpdateEnabled(true).build();
    client =
        S3Client.builder()
            .region(Region.of(region))
            .credentialsProvider(credentials)
            .httpClientBuilder(
                UrlConnectionHttpClient.builder()
                    .connectionTimeout(Duration.ofSeconds(5))
                    .socketTimeout(Duration.ofSeconds(30)))
            .overrideConfiguration(c -> c.apiCallTimeout(Duration.ofSeconds(60)))
            .build();
  }

  private String key(String key) {
    DocumentStorage.validateKey(key);
    return prefix + key;
  }

  public void put(String key, byte[] bytes) {
    String objectKey = key(key);
    try {
      client.putObject(
          PutObjectRequest.builder()
              .bucket(bucket)
              .key(objectKey)
              .contentType("application/pdf")
              .serverSideEncryption(ServerSideEncryption.AES256)
              .ifNoneMatch("*")
              .build(),
          RequestBody.fromBytes(bytes));
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  public byte[] read(String key) {
    String objectKey = key(key);
    try {
      return client
          .getObjectAsBytes(GetObjectRequest.builder().bucket(bucket).key(objectKey).build())
          .asByteArray();
    } catch (NoSuchKeyException e) {
      throw new ApiException(404, "DOCUMENT_UNAVAILABLE", "Private document bytes were not found");
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  public void delete(String key) {
    String objectKey = key(key);
    try {
      client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    } catch (RuntimeException e) {
      throw unavailable();
    }
  }

  private ApiException unavailable() {
    return new ApiException(
        503,
        "DOCUMENT_STORAGE_UNAVAILABLE",
        "Private document storage is unavailable; retry after checking EC2 role permissions and"
            + " connectivity");
  }

  @PreDestroy
  void close() {
    client.close();
    credentials.close();
  }
}
