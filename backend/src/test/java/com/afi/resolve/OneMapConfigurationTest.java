package com.afi.resolve;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

class OneMapConfigurationTest {
  final ApplicationContextRunner context = new ApplicationContextRunner()
      .withInitializer(ctx -> {
        try {
          new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
              .forEach(source -> ctx.getEnvironment().getPropertySources().addLast(source));
        } catch (java.io.IOException ex) { throw new IllegalStateException(ex); }
      }).withBean(OneMapClient.class);

  @Test void canonicalCredentialsAreRecognised() {
    context.withPropertyValues("ONEMAP_EMAIL=canonical@example.invalid", "ONEMAP_PASSWORD=canonical-test")
        .run(ctx -> {
          assertThat(ctx.getBean(OneMapClient.class).email).isEqualTo("canonical@example.invalid");
          assertThat(ctx.getBean(OneMapClient.class).password).isEqualTo("canonical-test");
        });
  }

  @Test void emptyCanonicalCredentialsFallBackToAliases() {
    context.withPropertyValues("ONEMAP_EMAIL=", "ONEMAP_PASSWORD=",
        "ONEMAP_API_EMAIL=alias@example.invalid", "ONEMAP_API_PASSWORD=alias-test")
        .run(ctx -> {
          assertThat(ctx.getBean(OneMapClient.class).email).isEqualTo("alias@example.invalid");
          assertThat(ctx.getBean(OneMapClient.class).password).isEqualTo("alias-test");
        });
  }

  @Test void nonEmptyCanonicalCredentialsWinWhenBothAreConfigured() {
    context.withPropertyValues("ONEMAP_EMAIL=canonical@example.invalid", "ONEMAP_PASSWORD=canonical-test",
        "ONEMAP_API_EMAIL=alias@example.invalid", "ONEMAP_API_PASSWORD=alias-test")
        .run(ctx -> {
          assertThat(ctx.getBean(OneMapClient.class).email).isEqualTo("canonical@example.invalid");
          assertThat(ctx.getBean(OneMapClient.class).password).isEqualTo("canonical-test");
        });
  }

  @Test void absentCanonicalCredentialsAndWhitespaceUseAliases() {
    context.withPropertyValues("ONEMAP_API_EMAIL=alias@example.invalid", "ONEMAP_API_PASSWORD=alias-test")
        .run(ctx -> assertThat(ctx.getBean(OneMapClient.class).email).isEqualTo("alias@example.invalid"));
    assertThat(OneMapClient.configuredValue("  ", "alias-test")).isEqualTo("alias-test");
    assertThat(OneMapClient.configuredValue(" password ", "alias-test")).isEqualTo(" password ");
  }
}
