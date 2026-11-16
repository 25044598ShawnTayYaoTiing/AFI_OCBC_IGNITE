package com.afi.resolve;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

/** Adapted from C270 geocodingService: private cached auth, bounded requests, one auth retry. */
@Component
public class OneMapClient {
  public record Candidate(String id, String block, String road, String building, String address,
      String postalCode, double latitude, double longitude, String source) {}
  public record Search(String status, String message, String postalCode, List<Candidate> candidates,
      boolean complete, Instant checkedAt) {}
  public record Verification(String status, String source, String message, String requestedAddress,
      String postalCode, Candidate selected, List<Candidate> candidates, Instant checkedAt) {}
  final RestClient http;
  final String email;
  final String password;
  final Clock clock;
  private String token;
  private Instant expires = Instant.EPOCH;
  static final Pattern POSTAL = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
  // Spring's HTTP converter can log request-body toString() at DEBUG. Never use a raw Map here.
  record Credentials(String email, String password) {
    @Override public String toString() { return "OneMap credentials [REDACTED]"; }
  }

  @org.springframework.beans.factory.annotation.Autowired
  public OneMapClient(@Value("${afi.onemap-email:}") String email,
      @Value("${afi.onemap-password:}") String password) {
    this(client(), email, password, Clock.systemUTC());
  }

  OneMapClient(RestClient http, String email, String password, Clock clock) {
    this.http = http; this.email = email; this.password = password; this.clock = clock;
  }

  static RestClient client() {
    var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build());
    factory.setReadTimeout(Duration.ofSeconds(8));
    return RestClient.builder().baseUrl("https://www.onemap.gov.sg").requestFactory(factory).build();
  }

  private synchronized String accessToken() {
    if (token != null && clock.instant().isBefore(expires.minusSeconds(300))) return token;
    if (email.isBlank() || password.isBlank()) throw new Unavailable();
    try {
      var body = http.post().uri("/api/auth/post/getToken").body(new Credentials(email, password))
          .retrieve().body(JsonNode.class);
      if (body == null || body.has("error") || body.path("access_token").asText().isBlank()) throw new Unavailable();
      long expiry = Long.parseLong(body.path("expiry_timestamp").asText());
      if (expiry > 1_000_000_000_000L) expiry /= 1000;
      var parsed = Instant.ofEpochSecond(expiry);
      if (!parsed.isAfter(clock.instant().plusSeconds(300))) throw new Unavailable();
      token = body.path("access_token").asText(); expires = parsed;
      return token;
    } catch (Exception failure) {
      token = null; expires = Instant.EPOCH;
      throw new Unavailable(); // Never propagate provider bodies, URLs or credential-bearing exceptions.
    }
  }

  private synchronized void invalidate(String rejected) {
    if (Objects.equals(token, rejected)) { token = null; expires = Instant.EPOCH; }
  }

  private JsonNode searchPage(String postal, int page) {
    for (int attempt = 0; attempt < 2; attempt++) {
      String current = accessToken();
      try {
        var body = http.get().uri(builder -> builder.path("/api/common/elastic/search")
            .queryParam("searchVal", postal).queryParam("returnGeom", "Y")
            .queryParam("getAddrDetails", "Y").queryParam("pageNum", page).build())
            .header("Authorization", current).retrieve().body(JsonNode.class);
        if (body == null) throw new Unavailable();
        if (body.has("error")) {
          String error = body.path("error").asText().toLowerCase(Locale.ROOT);
          if (error.contains("token") || error.contains("auth")) {
            invalidate(current);
            if (attempt == 0) continue;
          }
          throw new Unavailable();
        }
        return body;
      } catch (RestClientResponseException failure) {
        if (failure.getStatusCode().value() == 401 || failure.getStatusCode().value() == 403) {
          invalidate(current);
          if (attempt == 0) continue;
        }
        throw new Unavailable();
      } catch (RestClientException failure) { throw new Unavailable(); }
    }
    throw new Unavailable();
  }

  public Search search(String postal) {
    if (postal == null || !postal.matches("\\d{6}"))
      throw new ApiException(400, "INVALID_POSTAL_CODE", "Enter a six-digit Singapore postal code.");
    var candidates = new LinkedHashMap<String, Candidate>();
    boolean complete = true;
    try {
      int pages = 1;
      for (int page = 1; page <= Math.min(pages, 3); page++) {
        var body = searchPage(postal, page);
        if (!body.path("results").isArray() || !body.path("found").canConvertToInt()
            || !body.path("totalNumPages").canConvertToInt()) throw new Unavailable();
        pages = body.path("totalNumPages").asInt();
        if (pages < 0 || body.path("found").asInt() < 0) throw new Unavailable();
        complete &= pages <= 3;
        for (var row : body.path("results")) {
          var candidate = candidate(row, postal);
          if (candidate != null) candidates.putIfAbsent(candidate.id(), candidate);
          else complete = false;
        }
        if (body.path("found").asInt() > 0 && body.path("results").isEmpty()) complete = false;
      }
      String status = candidates.isEmpty() ? "INCONCLUSIVE" : "LIVE_RESULTS";
      return new Search(status, candidates.isEmpty()
          ? "No conclusive address was returned. Check the postal code, retry, or request manual review."
          : "Select the correct building address. OneMap does not confirm your unit or occupancy.",
          postal, List.copyOf(candidates.values()), complete, clock.instant());
    } catch (Exception failure) {
      return new Search("UNAVAILABLE", "OneMap is temporarily unavailable. Retry or submit for manual review; your address has not been rejected.",
          postal, List.of(), false, clock.instant());
    }
  }

  Candidate candidate(JsonNode row, String postal) {
    try {
      if (!postal.equals(row.path("POSTAL").asText())) return null;
      double lat = Double.parseDouble(row.path("LATITUDE").asText());
      double lon = Double.parseDouble(row.path("LONGITUDE").asText(row.path("LONGTITUDE").asText()));
      String address = clean(row.path("ADDRESS").asText());
      String block = clean(row.path("BLK_NO").asText()), road = clean(row.path("ROAD_NAME").asText());
      if (!Double.isFinite(lat) || !Double.isFinite(lon) || lat < 1.10 || lat > 1.50
          || lon < 103.55 || lon > 104.45 || address.isBlank() || block.isBlank() || road.isBlank()
          || "NIL".equalsIgnoreCase(block) || "NIL".equalsIgnoreCase(road)
          || address.length() > 1000 || block.length() > 80 || road.length() > 200) return null;
      String id = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
          (postal + "|" + block + "|" + road + "|" + address).getBytes(StandardCharsets.UTF_8)));
      String building = clean(row.path("BUILDING").asText());
      if ("NIL".equalsIgnoreCase(building)) building = "";
      return new Candidate(id, block, road, building, address, postal, lat, lon, "ONEMAP_LIVE");
    } catch (Exception invalid) { return null; }
  }

  public Verification verifySelection(String postal, String candidateId, String suppliedAddress) {
    var lookup = search(postal);
    if (!"LIVE_RESULTS".equals(lookup.status())) return result(lookup.status(), lookup.message(), suppliedAddress, lookup, null);
    var candidate = lookup.candidates().stream().filter(c -> c.id().equals(candidateId)).findFirst().orElse(null);
    if (candidate != null) return result("VERIFIED", "The selected building address was revalidated against live OneMap records. Unit number and occupancy are not verified.", suppliedAddress, lookup, candidate);
    return result(lookup.complete() ? "MISMATCH" : "INCONCLUSIVE",
        lookup.complete() ? "The selected address does not match the current OneMap records for this postal code. Search again and select the correct address."
            : "The search result is incomplete. Retry or request manual review.", suppliedAddress, lookup, null);
  }

  public Verification verifyText(String address) {
    address = clean(address);
    var matcher = POSTAL.matcher(address);
    if (!matcher.find()) return inconclusive(address, "No six-digit postal code was extracted. Check the source PDF or request manual review.");
    String postal = matcher.group(1);
    if (matcher.find()) return inconclusive(address, "More than one postal code was extracted. A reviewer must identify the new address.");
    var lookup = search(postal);
    if (!"LIVE_RESULTS".equals(lookup.status())) return result(lookup.status(), lookup.message(), address, lookup, null);
    String base = normalizedBase(address, postal);
    for (var candidate : lookup.candidates()) {
      String street = normal(candidate.block() + " " + candidate.road());
      String withBuilding = normal(street + " " + candidate.building());
      String buildingFirst = normal(candidate.building() + " " + street);
      if (base.equals(street) || base.equals(withBuilding) || base.equals(buildingFirst))
        return result("VERIFIED", "The extracted building and street match live OneMap records for this postal code. Unit number and occupancy are not verified.", address, lookup, candidate);
    }
    // A missing/abbreviated/unstructured value is not evidence of a conflict.
    boolean clearStreet = base.matches("^\\d+[A-Z]? .*(?:ROAD|STREET|AVENUE|DRIVE|LANE|CRESCENT|CLOSE|WALK|PLACE|TERRACE|BOULEVARD)(?: \\d+)?$");
    return result(clearStreet && lookup.complete() ? "MISMATCH" : "INCONCLUSIVE",
        clearStreet && lookup.complete() ? "The extracted block or street conflicts with OneMap records for this postal code. Obtain a corrected address."
            : "The extracted address cannot be matched conclusively. Check the original PDF and confirm the address manually.", address, lookup, null);
  }

  static String normalizedBase(String address, String postal) {
    return normal(address.replaceAll("(?i)(?:#|UNIT\\s*)\\s*\\d{1,4}\\s*[-/]\\s*[A-Z0-9]{1,6}", " ")
        .replace(postal, " ").replaceAll("(?i)\\b(?:SINGAPORE|POSTAL\\s*CODE|POSTCODE|BLOCK|BLK)\\b", " "));
  }

  static String clean(String value) { return Normalizer.normalize(Objects.toString(value, ""), Normalizer.Form.NFKC).replaceAll("\\s+", " ").trim(); }
  static String normal(String value) {
    return clean(value).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ")
        .replaceAll("\\bRD\\b", "ROAD").replaceAll("\\bST\\b", "STREET")
        .replaceAll("\\bAVE\\b", "AVENUE").replaceAll("\\bDR\\b", "DRIVE")
        .replaceAll("\\bCRES\\b", "CRESCENT").replaceAll("\\bCL\\b", "CLOSE")
        .replaceAll("\\s+", " ").trim();
  }

  Verification result(String status, String message, String address, Search lookup, Candidate selected) {
    return new Verification(status, "UNAVAILABLE".equals(status) ? "ONEMAP_UNAVAILABLE" : "ONEMAP_LIVE",
        message, address, lookup.postalCode(), selected, lookup.candidates(), lookup.checkedAt());
  }
  Verification inconclusive(String address, String message) {
    return new Verification("INCONCLUSIVE", "NOT_QUERIED", message, address, "", null, List.of(), clock.instant());
  }
  static class Unavailable extends RuntimeException { Unavailable() { super("Address service unavailable"); } }
}
