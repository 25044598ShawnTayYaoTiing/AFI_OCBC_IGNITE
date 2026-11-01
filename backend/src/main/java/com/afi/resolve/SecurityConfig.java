package com.afi.resolve;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
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
                    .authenticated())
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
  CorsConfigurationSource cors(@Value("${afi.cors-origin}") String origin) {
    var config = new CorsConfiguration();
    config.setAllowedOrigins(List.of(origin));
    config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
    config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
    config.setAllowCredentials(false);
    var source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
