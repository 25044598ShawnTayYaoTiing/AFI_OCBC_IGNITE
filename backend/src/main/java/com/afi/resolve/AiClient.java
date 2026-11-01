package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import com.fasterxml.jackson.databind.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;

@Component
public class AiClient {
  final RestClient client;
  final ObjectMapper json;

  public AiClient(
      ObjectMapper json, @Value("${afi.ai-url}") String url, @Value("${afi.ai-key}") String key) {
    this.json = json;
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build());
    factory.setReadTimeout(Duration.ofSeconds(35));
    client =
        RestClient.builder()
            .baseUrl(url)
            .requestFactory(factory)
            .defaultHeader("X-Service-Key", key)
            .build();
  }

  public Investigation investigate(Snapshot snapshot) {
    try {
      String body = client.post().uri("/investigate").body(snapshot).retrieve().body(String.class);
      var root = json.readTree(body);
      for (String field :
          List.of(
              "exception_id",
              "investigation_status",
              "probable_root_cause",
              "evidence",
              "candidate_actions",
              "requires_additional_evidence",
              "explanation",
              "method"))
        if (!root.hasNonNull(field)) throw new IllegalArgumentException("Missing field " + field);
      var result =
          json.readerFor(Investigation.class)
              .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .<Investigation>readValue(body);
      validateInvestigation(snapshot, result);
      return result;
    } catch (RestClientException e) {
      return fallback(snapshot);
    } catch (Exception e) {
      throw new ApiException(
          502,
          "INVALID_AI_RESPONSE",
          "Investigation service returned an invalid structured response; human review is"
              + " required");
    }
  }

  static void validateInvestigation(Snapshot s, Investigation r) {
    if (!s.exceptionId().equals(r.exceptionId())
        || !"COMPLETED".equals(r.investigationStatus())
        || !Set.of("GEMINI", "DETERMINISTIC_FALLBACK").contains(r.method())
        || r.probableRootCause() == null
        || r.probableRootCause().isBlank()
        || r.explanation() == null
        || r.evidence() == null
        || r.candidateActions() == null
        || r.candidateActions().isEmpty()
        || !ACTIONS.containsAll(r.candidateActions()))
      throw new IllegalArgumentException("Invalid investigation result");
  }

  private Investigation fallback(Snapshot s) {
    boolean eligible =
        s.exceptionType().equals("PARTIAL_BATCH_FAILURE") && PolicyService.healthy(s);
    return new Investigation(
        s.exceptionId(),
        "COMPLETED",
        eligible
            ? "Synthetic worker timeout after partial acknowledgement"
            : "Uncertain internal reconciliation evidence",
        s.evidence(),
        eligible
            ? List.of("RETRY_FULL_BATCH", "RESUME_PENDING_ONLY")
            : List.of("REQUEST_MANUAL_REVIEW"),
        !eligible,
        eligible
            ? "Acknowledged records must be preserved; simulate selective recovery before any"
                  + " mutation."
            : "Conflicting record evidence requires an authorised review. No automated correction"
                  + " is proposed.",
        "DETERMINISTIC_FALLBACK",
        "AI service unavailable; Java rule-based investigation used");
  }

  public List<Simulation> simulate(Snapshot s, List<String> actions) {
    try {
      var body =
          json.convertValue(
              s, new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      body.put("candidate_actions", actions);
      String response = client.post().uri("/simulate").body(body).retrieve().body(String.class);
      var result =
          json.readerFor(SimulationResponse.class)
              .with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
              .<SimulationResponse>readValue(response);
      if (result == null || result.results() == null || result.results().size() != actions.size())
        throw new IllegalArgumentException("Missing simulation results");
      Set<String> seen = new HashSet<>();
      for (var r : result.results()) {
        if (!actions.contains(r.strategy()) || !seen.add(r.strategy()))
          throw new IllegalArgumentException("Unexpected strategy");
        PolicyService.validateSimulation(s, r);
      }
      return result.results();
    } catch (RestClientException e) {
      throw new ApiException(
          503,
          "SIMULATOR_UNAVAILABLE",
          "Digital twin service is unavailable. Execution is blocked until a valid simulation"
              + " completes.");
    } catch (Exception e) {
      if (e instanceof ApiException a) throw a;
      throw new ApiException(
          502,
          "INVALID_SIMULATION",
          "Digital twin response did not pass independent backend validation");
    }
  }
}
