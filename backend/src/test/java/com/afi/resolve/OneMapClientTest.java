package com.afi.resolve;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class OneMapClientTest {
  final RestClient.Builder builder = RestClient.builder().baseUrl("https://www.onemap.gov.sg");
  final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  final OneMapClient client = new OneMapClient(builder.build(), "test@example.invalid", "test-password", Clock.fixed(Instant.parse("2026-11-16T00:00:00Z"), ZoneOffset.UTC));
  static final String TOKEN = "{\"access_token\":\"test-token\",\"expiry_timestamp\":\"1795046400\"}";
  static final String RESULTS = "{\"found\":1,\"totalNumPages\":1,\"results\":[{\"BLK_NO\":\"640\",\"ROAD_NAME\":\"ROWELL ROAD\",\"BUILDING\":\"NIL\",\"ADDRESS\":\"640 ROWELL ROAD SINGAPORE 200640\",\"POSTAL\":\"200640\",\"LATITUDE\":\"1.3074\",\"LONGITUDE\":\"103.8547\"}]}";
  static final String SEARCH = "https://www.onemap.gov.sg/api/common/elastic/search?searchVal=200640&returnGeom=Y&getAddrDetails=Y&pageNum=1";
  void auth() { server.expect(requestTo("https://www.onemap.gov.sg/api/auth/post/getToken")).andExpect(method(HttpMethod.POST)).andExpect(content().json("{\"email\":\"test@example.invalid\",\"password\":\"test-password\"}")).andRespond(withSuccess(TOKEN, MediaType.APPLICATION_JSON)); }
  void search(String response) { server.expect(requestTo(SEARCH)).andExpect(header("Authorization", "test-token")).andRespond(withSuccess(response, MediaType.APPLICATION_JSON)); }

  @Test void cachesTokenAndRevalidatesSelectionAgainstFreshResults() {
    auth(); search(RESULTS); search(RESULTS);
    var candidate = client.search("200640").candidates().getFirst();
    var result = client.verifySelection("200640", candidate.id(), "client-controlled label");
    assertThat(result.status()).isEqualTo("VERIFIED");
    assertThat(result.selected().address()).isEqualTo("640 ROWELL ROAD SINGAPORE 200640");
    server.verify();
  }

  @Test void handlesAuthenticationErrorsInsideHttp200WithOneRenewal() {
    auth(); search("{\"error\":\"Authentication token expired\",\"results\":[]}"); auth(); search(RESULTS);
    assertThat(client.search("200640").status()).isEqualTo("LIVE_RESULTS"); server.verify();
  }

  @Test void distinguishesConfirmedConflictFromNoResults() {
    auth(); search(RESULTS); search("{\"found\":0,\"totalNumPages\":0,\"results\":[]}");
    assertThat(client.verifySelection("200640", "invented", "wrong address").status()).isEqualTo("MISMATCH");
    assertThat(client.verifySelection("200640", "invented", "unknown address").status()).isEqualTo("INCONCLUSIVE"); server.verify();
  }

  @Test void providerFailureIsNotAnInvalidAddress() {
    auth(); server.expect(requestTo(SEARCH)).andRespond(withServerError());
    var result = client.verifySelection("200640", "invented", "private input");
    assertThat(result.status()).isEqualTo("UNAVAILABLE");
    assertThat(result.message()).doesNotContain("test-token", "test-password", "test@example.invalid"); server.verify();
  }

  @Test void verifiesPdfStreetAndPostalButNotUnitOrOccupancy() {
    auth(); search(RESULTS); search(RESULTS); search(RESULTS); search(RESULTS);
    assertThat(client.verifyText("640 Rowell Rd #12-34 Singapore 200640").status()).isEqualTo("VERIFIED");
    assertThat(client.verifyText("640 Rowell Road, Unit #12-34, Singapore 200640").status()).isEqualTo("VERIFIED");
    assertThat(client.verifyText("640 Rowell Road, #12-34\nBuilding / estate\nSingapore 200640").status()).isEqualTo("VERIFIED");
    assertThat(client.verifyText("99 Wrong Road Singapore 200640").status()).isEqualTo("MISMATCH"); server.verify();
  }

  @Test void ambiguousOrMissingPostalNeverBecomesAConflict() {
    assertThat(client.verifyText("Example building without postal code").status()).isEqualTo("INCONCLUSIVE");
    assertThat(client.verifyText("640 Rowell Road 200640 or 123456").source()).isEqualTo("NOT_QUERIED"); server.verify();
  }

  @Test void rejectsMalformedOrErrorBearingSuccessResponses() {
    auth(); search("{\"found\":1,\"totalNumPages\":1}");
    assertThat(client.search("200640").status()).isEqualTo("UNAVAILABLE"); server.verify();
  }

  @Test void credentialBodyCannotExposeValuesThroughDebugToString() {
    assertThat(new OneMapClient.Credentials("private-email", "private-password").toString()).contains("REDACTED").doesNotContain("private-email", "private-password");
  }

  @Test void unifiesAbbreviationsAndRejectsDifferentStreetNumberSuffixes() {
    String rivervale = "{\"found\":1,\"totalNumPages\":1,\"results\":[{\"BLK_NO\":\"109\",\"ROAD_NAME\":\"RIVERVALE WALK\",\"BUILDING\":\"RIVERVALE COURT\",\"ADDRESS\":\"109 RIVERVALE WALK RIVERVALE COURT SINGAPORE 540109\",\"POSTAL\":\"540109\",\"LATITUDE\":\"1.3821\",\"LONGITUDE\":\"103.9012\"}]}";
    String search540109 = "https://www.onemap.gov.sg/api/common/elastic/search?searchVal=540109&returnGeom=Y&getAddrDetails=Y&pageNum=1";
    auth();
    server.expect(requestTo(search540109)).andExpect(header("Authorization", "test-token")).andRespond(withSuccess(rivervale, MediaType.APPLICATION_JSON));
    server.expect(requestTo(search540109)).andExpect(header("Authorization", "test-token")).andRespond(withSuccess(rivervale, MediaType.APPLICATION_JSON));
    server.expect(requestTo(search540109)).andExpect(header("Authorization", "test-token")).andRespond(withSuccess(rivervale, MediaType.APPLICATION_JSON));

    assertThat(client.verifyText("Blk 109 Rivervale Walk, #12-34, S(540109)").status()).isEqualTo("VERIFIED");
    assertThat(client.verifyText("Rivervale Court, 109 Rivervale Walk #12-34 Singapore 540109").status()).isEqualTo("VERIFIED");
    assertThat(client.verifyText("109 Rivervale Walk 2 #12-34 Singapore 540109").status()).isEqualTo("MISMATCH");
    server.verify();
  }
}
