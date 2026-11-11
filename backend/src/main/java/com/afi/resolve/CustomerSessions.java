package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Opaque, revocable sessions. Only the SHA-256 digest is persisted. */
@Service
public class CustomerSessions {
  public record Identity(String id, String username) implements Principal {
    public String getName() {
      return "customer:" + id;
    }
  }

  final Store s;
  final PasswordEncoder passwords;
  final String dummyHash;
  final int ttl;
  final SecureRandom random = new SecureRandom();

  public CustomerSessions(
      Store s, PasswordEncoder passwords, @Value("${afi.customer-session-seconds:1800}") int ttl) {
    this.s = s;
    this.passwords = passwords;
    this.ttl = Math.max(60, Math.min(86400, ttl));
    dummyHash = passwords.encode(UUID.randomUUID().toString());
  }

  static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }

  static Identity identity(Authentication a) {
    if (a == null || !(a.getPrincipal() instanceof Identity identity))
      throw new ApiException(401, "UNAUTHENTICATED", "Sign in with a customer account");
    return identity;
  }

  @Transactional(noRollbackFor = ApiException.class)
  public Object login(String username, String password) {
    if (username == null
        || password == null
        || username.length() > 80
        || password.getBytes(StandardCharsets.UTF_8).length > 72) throw invalid();
    var rows = s.db.queryForList("SELECT * FROM customers WHERE username=? FOR UPDATE", username);
    var c = rows.isEmpty() ? null : rows.getFirst();
    boolean matches =
        passwords.matches(password, c == null ? dummyHash : (String) c.get("password_hash"));
    boolean locked =
        c != null
            && c.get("locked_until") != null
            && instant(c.get("locked_until")).isAfter(Instant.now());
    if (c == null || !Boolean.TRUE.equals(c.get("enabled")) || locked || !matches) {
      if (c != null && !locked) {
        int failures = ((Number) c.get("failed_logins")).intValue() + 1;
        s.db.update(
            "UPDATE customers SET failed_logins=?,locked_until=? WHERE id=?",
            failures,
            failures >= 5 ? Store.now().plusMinutes(15) : null,
            c.get("id"));
      }
      throw invalid();
    }
    s.db.update("UPDATE customers SET failed_logins=0,locked_until=NULL WHERE id=?", c.get("id"));
    byte[] bytes = new byte[32];
    random.nextBytes(bytes);
    String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    var now = Store.now();
    var expires = now.plusSeconds(ttl);
    s.db.update(
        "INSERT INTO customer_sessions(token_hash,customer_id,issued_at,expires_at)"
            + " VALUES(?,?,?,?)",
        hash(token),
        c.get("id"),
        now,
        expires);
    s.audit(
        null,
        "customer:" + c.get("id"),
        "CUSTOMER_LOGIN",
        "Customer session issued",
        "COMPLETED",
        null);
    return map("accessToken", token, "tokenType", "Bearer", "expiresAt", expires.toInstant());
  }

  static Instant instant(Object value) {
    if (value instanceof OffsetDateTime t) return t.toInstant();
    if (value instanceof java.sql.Timestamp t) return t.toInstant();
    return Instant.parse(value.toString());
  }

  public Identity authenticate(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
    var rows =
        s.db.queryForList(
            "SELECT c.id,c.username FROM customer_sessions t JOIN customers c ON c.id=t.customer_id"
                + " WHERE t.token_hash=? AND t.revoked_at IS NULL AND t.expires_at>? AND"
                + " c.enabled=TRUE",
            hash(token),
            Store.now());
    return rows.isEmpty()
        ? null
        : new Identity(
            (String) rows.getFirst().get("id"), (String) rows.getFirst().get("username"));
  }

  @Transactional
  public void logout(String token, Authentication a) {
    var identity = identity(a);
    s.db.update(
        "UPDATE customer_sessions SET revoked_at=? WHERE token_hash=? AND customer_id=? AND"
            + " revoked_at IS NULL",
        Store.now(),
        hash(token),
        identity.id());
    s.audit(
        null, identity.getName(), "CUSTOMER_LOGOUT", "Customer session revoked", "COMPLETED", null);
  }

  ApiException invalid() {
    return new ApiException(
        401, "INVALID_CREDENTIALS", "Invalid customer credentials or temporarily locked account");
  }
}
