package com.afi.resolve;

import java.util.Arrays;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.*;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
  @Bean
  PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  UserDetailsService users(
      @Value("${afi.operator-password}") String operator,
      @Value("${afi.reviewer-password}") String reviewer,
      @Value("${afi.rules-admin-password}") String rulesAdmin,
      PasswordEncoder encoder) {
    return new InMemoryUserDetailsManager(
        User.withUsername("operator").password(encoder.encode(operator)).roles("OPERATOR").build(),
        User.withUsername("reviewer")
            .password(encoder.encode(reviewer))
            .roles("OPERATOR", "REVIEWER")
            .build(),
        User.withUsername("rules-admin")
            .password(encoder.encode(rulesAdmin))
            .roles("OPERATOR", "REVIEWER")
            .build());
  }

  @Bean
  @Order(1)
  SecurityFilterChain customerSecurity(HttpSecurity http, CustomerSessions sessions)
      throws Exception {
    return http.securityMatcher("/api/customer/**")
        .csrf(c -> c.disable())
        .cors(c -> {})
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(HttpMethod.POST, "/api/customer/auth/token")
                    .permitAll()
                    .anyRequest()
                    .hasRole("CUSTOMER"))
        .addFilterBefore(
            new CustomerBearerFilter(sessions), UsernamePasswordAuthenticationFilter.class)
        .exceptionHandling(
            e ->
                e.authenticationEntryPoint(
                        (req, res, ex) -> {
                          res.setStatus(401);
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Sign in with a"
                                      + " customer account\"}");
                        })
                    .accessDeniedHandler(
                        (req, res, ex) -> {
                          res.setStatus(403);
                          res.setContentType("application/json");
                          res.getWriter()
                              .write(
                                  "{\"code\":\"FORBIDDEN\",\"message\":\"Customer access"
                                      + " required\"}");
                        }))
        .build();
  }

  @Bean
  @Order(2)
  SecurityFilterChain security(HttpSecurity http) throws Exception {
    return http.csrf(csrf -> csrf.disable())
        .cors(cors -> {})
        .sessionManagement(
            s ->
                s.sessionCreationPolicy(
                    org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers(
                        "/actuator/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
                    .permitAll()
                    .anyRequest()
                    .hasAnyRole("OPERATOR", "REVIEWER"))
        .httpBasic(
            b ->
                b.authenticationEntryPoint(
                    (req, res, e) -> {
                      res.setStatus(401);
                      res.setContentType("application/json");
                      res.getWriter()
                          .write(
                              "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Sign in with a demo"
                                  + " account\"}");
                    }))
        .exceptionHandling(
            e ->
                e.accessDeniedHandler(
                    (req, res, ex) -> {
                      res.setStatus(403);
                      res.setContentType("application/json");
                      res.getWriter()
                          .write(
                              "{\"code\":\"FORBIDDEN\",\"message\":\"This operation requires"
                                  + " reviewer privileges\"}");
                    }))
        .build();
  }

  @Bean
  CorsConfigurationSource corsConfigurationSource(
      @Value("${afi.cors-origin}") String origin,
      @Value("${afi.customer-portal-origins:http://localhost:5174,http://127.0.0.1:5174}")
          String portalOrigins) {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(origin));
    config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(false);
    var source = new UrlBasedCorsConfigurationSource();
    var customer = new CorsConfiguration();
    var origins =
        Arrays.stream(portalOrigins.split(","))
            .map(String::trim)
            .filter(v -> !v.isEmpty())
            .toList();
    for (String allowed : origins) {
      try {
        var uri = java.net.URI.create(allowed);
        if (!SetSupport.isOrigin(uri, allowed)) throw new IllegalArgumentException();
      } catch (IllegalArgumentException e) {
        throw new IllegalArgumentException("Customer portal origins must be exact HTTP(S) origins");
      }
    }
    customer.setAllowedOrigins(origins);
    customer.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    customer.setAllowedHeaders(List.of("Authorization", "Content-Type", "Idempotency-Key"));
    customer.setAllowCredentials(false);
    source.registerCorsConfiguration("/api/customer/**", customer);
    source.registerCorsConfiguration("/**", config);
    return source;
  }

  static class SetSupport {
    static boolean isOrigin(java.net.URI uri, String value) {
      return List.of("http", "https").contains(uri.getScheme())
          && uri.getHost() != null
          && !value.contains("*")
          && uri.getUserInfo() == null
          && uri.getQuery() == null
          && uri.getFragment() == null
          && (uri.getPath() == null || uri.getPath().isEmpty());
    }
  }
}
