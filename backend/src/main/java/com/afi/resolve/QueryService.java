package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class QueryService {
  final Store store;

  public QueryService(Store store) {
    this.store = store;
  }

  public Map<String, Object> cases(
      String search, String status, String severity, int page, int size, String sort) {
    if (page < 0 || size < 1 || size > 100)
      throw new ApiException(400, "INVALID_PAGINATION", "Use page >= 0 and size between 1 and 100");
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (search != null && !search.isBlank()) {
      where.append(
          " AND (LOWER(id) LIKE ? OR LOWER(description) LIKE ? OR LOWER(batch_id) LIKE ?)");
      String term = "%" + search.toLowerCase(Locale.ROOT) + "%";
      args.add(term);
      args.add(term);
      args.add(term);
    }
    if (status != null && !status.isBlank()) {
      where.append(" AND status=?");
      args.add(status);
    }
    if (severity != null && !severity.isBlank()) {
      where.append(" AND severity=?");
      args.add(severity);
    }
    int total =
        store.db.queryForObject(
            "SELECT COUNT(*) FROM exceptions" + where, Integer.class, args.toArray());
    String[] parts = sort.split(",");
    String column =
        Map.of(
                "detectedAt",
                "detected_at",
                "status",
                "status",
                "severity",
                "severity",
                "id",
                "id",
                "type",
                "type")
            .get(parts[0]);
    if (column == null
        || parts.length > 2
        || (parts.length == 2
            && !Set.of("asc", "desc").contains(parts[1].toLowerCase(Locale.ROOT))))
      throw new ApiException(400, "INVALID_SORT", "Unsupported sort field or direction");
    String direction = parts.length == 2 ? parts[1].toUpperCase(Locale.ROOT) : "DESC";
    args.add(size);
    args.add((long) page * size);
    return map(
        "items",
        store.db.query(
            "SELECT * FROM exceptions"
                + where
                + " ORDER BY "
                + column
                + " "
                + direction
                + ",id LIMIT ? OFFSET ?",
            store::caseRow,
            args.toArray()),
        "total",
        total,
        "page",
        page,
        "size",
        size);
  }

  public Map<String, Object> summary() {
    var cases = store.db.query("SELECT * FROM exceptions", store::caseRow);
    int total = cases.size();
    long resolved = cases.stream().filter(c -> c.get("status").equals("RESOLVED")).count(),
        approval = cases.stream().filter(c -> c.get("status").equals("AWAITING_APPROVAL")).count();
    long successful =
        store.db.queryForObject(
            "SELECT COUNT(*) FROM resolution_actions WHERE status='VERIFIED'", Long.class);
    long failed =
        store.db.queryForObject(
            "SELECT COUNT(*) FROM resolution_actions WHERE status IN"
                + " ('FAILED','VERIFICATION_FAILED')",
            Long.class);
    double average =
        cases.stream()
            .filter(c -> c.get("resolvedAt") != null)
            .mapToLong(
                c ->
                    Duration.between((Instant) c.get("detectedAt"), (Instant) c.get("resolvedAt"))
                        .toSeconds())
            .average()
            .orElse(0);
    List<Map<String, Object>> categories = group(cases, "type"),
        statuses = group(cases, "status"),
        trends = new ArrayList<>();
    for (int days = 6; days >= 0; days--) {
      LocalDate date = LocalDate.now(ZoneOffset.UTC).minusDays(days);
      long
          detected =
              cases.stream()
                  .filter(
                      c ->
                          ((Instant) c.get("detectedAt"))
                              .atOffset(ZoneOffset.UTC)
                              .toLocalDate()
                              .equals(date))
                  .count(),
          closed =
              cases.stream()
                  .filter(
                      c ->
                          c.get("resolvedAt") != null
                              && ((Instant) c.get("resolvedAt"))
                                  .atOffset(ZoneOffset.UTC)
                                  .toLocalDate()
                                  .equals(date))
                  .count();
      trends.add(map("date", date.toString(), "detected", detected, "resolved", closed));
    }
    return map(
        "totalExceptions",
        total,
        "activeExceptions",
        total - resolved,
        "resolvedExceptions",
        resolved,
        "awaitingApproval",
        approval,
        "autoResolutionRate",
        total == 0 ? 0 : 100.0 * successful / total,
        "averageResolutionSeconds",
        average,
        "categories",
        categories,
        "statuses",
        statuses,
        "trends",
        trends,
        "automatedAttempts",
        map("successful", successful, "failed", failed));
  }

  private List<Map<String, Object>> group(List<Map<String, Object>> cases, String key) {
    Map<String, Integer> result = new TreeMap<>();
    cases.forEach(c -> result.merge((String) c.get(key), 1, Integer::sum));
    return result.entrySet().stream()
        .map(e -> map("name", e.getKey(), "value", e.getValue()))
        .toList();
  }
}
