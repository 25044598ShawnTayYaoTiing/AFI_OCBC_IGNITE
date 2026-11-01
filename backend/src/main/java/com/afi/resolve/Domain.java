package com.afi.resolve;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public final class Domain {
  private Domain() {}

  public static final Set<String> ACTIONS =
      Set.of("RETRY_FULL_BATCH", "RESUME_PENDING_ONLY", "REQUEST_MANUAL_REVIEW");

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record SnapshotRecord(
      String id,
      String status,
      String idempotencyKey,
      int processingCount,
      BigDecimal amount,
      String currency,
      String sourceReference,
      int reconciliationCount,
      boolean reconciliationMatches) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Snapshot(
      String exceptionId,
      String exceptionType,
      String batchId,
      String sourceHash,
      List<SnapshotRecord> records,
      String failureReason,
      List<String> evidence,
      List<String> previousIncidents) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Investigation(
      String exceptionId,
      String investigationStatus,
      String probableRootCause,
      List<String> evidence,
      List<String> candidateActions,
      boolean requiresAdditionalEvidence,
      String explanation,
      String method,
      String fallbackReason) {}

  @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
  public record Simulation(
      String simulationId,
      String exceptionId,
      String strategy,
      String sourceHash,
      boolean safe,
      int totalRecords,
      int alreadyProcessed,
      int pendingRecords,
      int affectedRecords,
      int expectedSuccessfulRecords,
      int duplicateAttemptRisk,
      List<String> policyViolations,
      String decision,
      String explanation,
      Instant createdAt) {}

  public record SimulationResponse(List<Simulation> results) {}

  public record ResolveRequest(
      @NotBlank @Pattern(regexp = "RESUME_PENDING_ONLY|RETRY_FULL_BATCH|REQUEST_MANUAL_REVIEW")
          String actionType,
      @NotBlank @Size(max = 64) String simulationId,
      @NotBlank @Size(min = 8, max = 100) String idempotencyKey) {}

  public record ApprovalRequest(
      @NotBlank @Pattern(regexp = "APPROVED|REJECTED") String decision,
      @NotBlank @Pattern(regexp = "REQUEST_MANUAL_REVIEW") String actionType,
      @NotBlank @Size(max = 2000) String comments) {}

  public record FailureRequest(
      @Min(0) @Max(10000) int processedCount, @Min(2) @Max(10000) int totalRecords) {}

  public record Check(String name, boolean passed, String detail) {}

  public record Verification(
      boolean passed,
      int totalRecords,
      int processedRecords,
      int pendingRecords,
      int duplicateProcessingAttempts,
      boolean reconciliationIntegrity,
      List<Check> checks) {}

  public static Map<String, Object> map(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
    return result;
  }
}
