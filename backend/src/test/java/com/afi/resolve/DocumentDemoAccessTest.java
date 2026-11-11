package com.afi.resolve;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class DocumentDemoAccessTest {
  @Test
  void documentDemoAndSamplesRequireTheDemoProfileAsWellAsTheFlag() {
    var environment = new MockEnvironment();
    environment.setActiveProfiles("production");
    var demo = new DemoService(mock(Store.class), environment, true);
    var service = new DocumentService(null, null, null, null, demo);
    assertThat(demo.isEnabled()).isFalse();
    assertThatThrownBy(() -> service.demo(null)).isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("DEMO_DISABLED"));
    assertThatThrownBy(() -> new DocumentController(service).sample("customer-information.pdf"))
        .isInstanceOf(ApiException.class)
        .satisfies(error -> assertThat(((ApiException) error).code()).isEqualTo("DEMO_DISABLED"));
    environment.setActiveProfiles("demo");
    assertThat(demo.isEnabled()).isTrue();
    assertThatCode(demo::requireDemo).doesNotThrowAnyException();
  }
}
