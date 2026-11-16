package com.afi.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Explicit opt-in; default test runs never use live credentials or send customer addresses. */
@EnabledIfEnvironmentVariable(named="AFI_ONEMAP_LIVE_CHECK", matches="true")
class OneMapLiveSmokeTest {
  @Test void officialPublicSampleCanBeLookedUp() {
    var client = new OneMapClient(System.getenv("ONEMAP_EMAIL"), System.getenv("ONEMAP_PASSWORD"));
    var lookup = client.search("200640");
    assertThat(lookup.status()).as("Live public-sample lookup status (credentials and tokens withheld)").isEqualTo("LIVE_RESULTS");
    assertThat(lookup.candidates()).isNotEmpty();
    assertThat(lookup.candidates()).allMatch(candidate -> "ONEMAP_LIVE".equals(candidate.source()));
  }
}
