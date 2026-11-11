package com.afi.resolve;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Installed only in the customer filter chain, never as a global servlet filter. */
final class CustomerBearerFilter extends OncePerRequestFilter {
  final CustomerSessions sessions;

  CustomerBearerFilter(CustomerSessions sessions) {
    this.sessions = sessions;
  }

  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String header = request.getHeader("Authorization");
    if (header != null) {
      var identity =
          header.startsWith("Bearer ") ? sessions.authenticate(header.substring(7)) : null;
      if (identity == null) {
        response.setStatus(401);
        response.setContentType("application/json");
        response
            .getWriter()
            .write(
                "{\"code\":\"UNAUTHENTICATED\",\"message\":\"Customer session is invalid or"
                    + " expired\"}");
        return;
      }
      var context = SecurityContextHolder.createEmptyContext();
      context.setAuthentication(
          UsernamePasswordAuthenticationToken.authenticated(
              identity, null, List.of(new SimpleGrantedAuthority("ROLE_CUSTOMER"))));
      SecurityContextHolder.setContext(context);
    }
    response.setHeader("Cache-Control", "no-store");
    chain.doFilter(request, response);
  }
}
