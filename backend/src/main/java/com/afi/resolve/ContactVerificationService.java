package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.security.SecureRandom;
import java.util.*;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** OTPs are hashed, expire, have a bounded attempt count, and are bound to one case revision. */
@Service
public class ContactVerificationService {
  final Store s;
  final CustomerService customers;
  final DocumentService documents;
  final CustomerEmailDispatcher email;
  final TransactionTemplate tx;
  final SecureRandom random = new SecureRandom();

  public ContactVerificationService(
      Store s,
      CustomerService customers,
      DocumentService documents,
      CustomerEmailDispatcher email,
      PlatformTransactionManager manager) {
    this.s = s;
    this.customers = customers;
    this.documents = documents;
    this.email = email;
    tx = new TransactionTemplate(manager);
  }

  public record Confirmation(String challengeId, String code) {}

  public record PhoneReview(int revision, String verificationNote) {}

  public Object request(String id, Authentication a) {
    String code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
    var challenge =
        tx.execute(
            t -> {
              var owner = customers.customer(a, true);
              var c = customers.owned(id, a);
              documents.access(id, a, true);
              documents.editable(c);
              if (!"CONTACT_UPDATE".equals(c.get("category")))
                throw new ApiException(
                    409, "NOT_CONTACT_REQUEST", "This case is not a contact-detail update");
              var policy = new BankingServicePolicy(documents, s);
              var docs = documents.documents(id, false);
              var p = new CustomerProfiles(s).get((String) c.get("customer_id"));
              String requested = policy.value(docs, "email");
              String destination =
                  !requested.isBlank() && !policy.same(requested, p.get("email"))
                      ? requested
                      : (String) p.get("email");
              if (!email.canVerifyContact(destination))
                throw new ApiException(
                    409,
                    "OTP_UNAVAILABLE",
                    "An authorised SMTP channel and deliverable verification email are required."
                        + " Verification remains pending.");
              if (s.db.queryForObject(
                          "SELECT COUNT(*) FROM contact_verifications WHERE customer_id=? AND"
                              + " created_at>?",
                          Integer.class,
                          owner.get("id"),
                          Store.now().minusHours(1))
                      >= 10
                  || s.db.queryForObject(
                          "SELECT COUNT(*) FROM contact_verifications WHERE submission_id=? AND"
                              + " created_at>?",
                          Integer.class,
                          id,
                          Store.now().minusSeconds(60))
                      > 0)
                throw new ApiException(
                    429,
                    "OTP_RATE_LIMIT",
                    "Wait one minute before requesting another code; at most 10 per hour");
              s.db.update(
                  "UPDATE contact_verifications SET status='SUPERSEDED' WHERE submission_id=? AND"
                      + " status IN ('CREATED','SENT')",
                  id);
              String key = s.id("OTP");
              s.db.update(
                  "INSERT INTO contact_verifications VALUES(?,?,?,?,?,?,?,0,?,?)",
                  key,
                  id,
                  owner.get("id"),
                  c.get("revision"),
                  CustomerSessions.hash(key + ":" + code),
                  destination,
                  "CREATED",
                  Store.now(),
                  Store.now().plusMinutes(5));
              return map("id", key, "destination", destination);
            });
    String key = (String) challenge.get("id"), destination = (String) challenge.get("destination");
    String state = "SENT";
    try {
      email.sendContactCode(destination, code);
    } catch (Exception failure) {
      state = "DELIVERY_UNKNOWN";
    }
    String outcome = state;
    tx.executeWithoutResult(
        t -> {
          s.db.update("UPDATE contact_verifications SET status=? WHERE id=?", outcome, key);
          documents.event(id, a, "CONTACT_OTP_" + outcome, map("challengeId", key));
        });
    if (!state.equals("SENT"))
      throw new ApiException(
          503,
          "OTP_DELIVERY_UNKNOWN",
          "Code delivery was not confirmed. Wait before requesting a new code; this request remains"
              + " unverified.");
    return map(
        "challengeId",
        key,
        "channel",
        "EMAIL",
        "destination",
        destination.replaceAll("(^.).*(@.*$)", "$1***$2"),
        "expiresInSeconds",
        300);
  }

  public Object confirm(String id, Confirmation request, Authentication a) {
    if (request.challengeId() == null
        || request.code() == null
        || !request.code().matches("[0-9]{6}"))
      throw new ApiException(
          400,
          "INVALID_CODE",
          "Provide the challenge and six-digit code delivered by the authorised channel");
    boolean accepted =
        Boolean.TRUE.equals(
            tx.execute(
                t -> {
                  customers.customer(a, true);
                  var c = customers.owned(id, a);
                  documents.access(id, a, true);
                  documents.editable(c);
                  var rows =
                      s.db.queryForList(
                          "SELECT * FROM contact_verifications WHERE id=? AND submission_id=? AND"
                              + " customer_id=? FOR UPDATE",
                          request.challengeId(),
                          id,
                          c.get("customer_id"));
                  if (rows.isEmpty()) return false;
                  var challenge = rows.getFirst();
                  if (!Objects.equals(c.get("revision"), challenge.get("revision"))
                      || !"SENT".equals(challenge.get("status"))
                      || ((Number) challenge.get("attempts")).intValue() >= 5) return false;
                  if (!CustomerSessions.instant(challenge.get("expires_at"))
                      .isAfter(java.time.Instant.now())) {
                    s.db.update(
                        "UPDATE contact_verifications SET status='EXPIRED' WHERE id=?",
                        request.challengeId());
                    return false;
                  }
                  boolean correct =
                      java.security.MessageDigest.isEqual(
                          CustomerSessions.hash(request.challengeId() + ":" + request.code())
                              .getBytes(java.nio.charset.StandardCharsets.US_ASCII),
                          ((String) challenge.get("code_hash"))
                              .getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                  int attempts = ((Number) challenge.get("attempts")).intValue() + 1;
                  s.db.update(
                      "UPDATE contact_verifications SET attempts=?,status=? WHERE id=?",
                      attempts,
                      correct ? "VERIFIED" : attempts >= 5 ? "LOCKED" : "SENT",
                      request.challengeId());
                  documents.event(
                      id,
                      a,
                      correct ? "CONTACT_OTP_VERIFIED" : "CONTACT_OTP_REJECTED",
                      map("challengeId", request.challengeId(), "revision", c.get("revision")));
                  if (correct) documents.validate(id, a);
                  return correct;
                }));
    if (!accepted)
      throw new ApiException(
          400,
          "INVALID_OR_EXPIRED_CODE",
          "The code is incorrect, expired, locked, superseded or bound to an older request"
              + " revision");
    return customers.detail(id, a);
  }

  public Object confirmPhone(String id, PhoneReview request, Authentication a) {
    if (request.verificationNote() == null
        || request.verificationNote().strip().length() < 12
        || request.verificationNote().length() > 500)
      throw new ApiException(
          400,
          "VERIFICATION_NOTE_REQUIRED",
          "Record how the synthetic new phone number was independently verified; do not enter OTP"
              + " codes");
    return tx.execute(
        t -> {
          var c = documents.access(id, a, true);
          documents.editable(c);
          if (!documents.reviewer(a) || !"CONTACT_UPDATE".equals(c.get("category")))
            throw new ApiException(
                403, "FORBIDDEN", "Resolution Management phone verification is required");
          if (((Number) c.get("revision")).intValue() != request.revision())
            throw new ApiException(
                409, "STALE_REVIEW", "Reload the current case before confirming verification");
          s.db.update(
              "UPDATE document_submissions SET phone_verified_revision=? WHERE id=?",
              request.revision(),
              id);
          documents.event(
              id,
              a,
              "PHONE_VERIFICATION_RECORDED",
              map("revision", request.revision(), "verificationNote", request.verificationNote()));
          return documents.validate(id, a);
        });
  }
}
