package com.afi.resolve;

import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable outbox. SMTP is opt-in; no delivery occurs in the submission transaction. */
@Service
public class CustomerEmailDispatcher {
  final Store s;
  final TransactionTemplate tx;
  final boolean enabled;
  final String from;

  static final String GENERIC_SUBJECT = "[TEST] AFI Resolve \u2013 Hackathon Prototype";
  static final String GENERIC_BODY =
      "This is a test notification from AFI Resolve, an independent student hackathon prototype.\n\n"
          + "This email demonstrates the system's automated SMTP notification functionality.\n\n"
          + "No real banking transactions or customer information are involved. This prototype is"
          + " not affiliated with any bank.\n\n"
          + "Thank you,\n"
          + "AFI Resolve Project Team";

  @Value("${afi.mail-from-name:AFI Resolve}")
  String fromName = "AFI Resolve";

  @Value("${afi.mail-test-recipients:}")
  String testRecipients = "";

  @Value("${afi.mail-require-allowlist:false}")
  boolean requireAllowlist = false;

  JavaMailSenderImpl mail = new JavaMailSenderImpl();

  public CustomerEmailDispatcher(
      Store s,
      PlatformTransactionManager manager,
      @Value("${afi.mail-enabled:false}") boolean enabled,
      @Value("${spring.mail.host:}") String host,
      @Value("${spring.mail.port:587}") int port,
      @Value("${spring.mail.username:}") String username,
      @Value("${spring.mail.password:}") String password,
      @Value("${afi.mail-from:}") String from,
      @Value("${afi.mail-starttls:true}") boolean tls) {
    this.s = s;
    this.tx = new TransactionTemplate(manager);
    this.enabled = enabled;
    this.from = from;
    if (enabled && (host.isBlank() || from.isBlank() || from.contains("\r") || from.contains("\n")))
      throw new IllegalArgumentException("Enabled SMTP requires a host and sender address");
    mail.setHost(host);
    mail.setPort(port);
    mail.setUsername(username);
    mail.setPassword(password);
    var props = mail.getJavaMailProperties();
    props.setProperty("mail.smtp.auth", Boolean.toString(!username.isBlank()));
    props.setProperty("mail.smtp.starttls.enable", Boolean.toString(tls));
    props.setProperty("mail.smtp.starttls.required", Boolean.toString(tls));
    props.setProperty("mail.smtp.connectiontimeout", "5000");
    props.setProperty("mail.smtp.timeout", "5000");
    props.setProperty("mail.smtp.writetimeout", "5000");
  }

  @Scheduled(fixedDelayString = "${afi.mail-poll-ms:10000}")
  public void dispatch() {
    if (!enabled) return;
    // A stopped worker may have delivered before crashing. Do not resend an ambiguous delivery.
    tx.executeWithoutResult(
        t ->
            s.db.update(
                "UPDATE customer_email_outbox SET status='DELIVERY_UNKNOWN',updated_at=? WHERE"
                    + " status='SENDING' AND updated_at<?",
                Store.now(),
                Store.now().minusMinutes(5)));
    tx.executeWithoutResult(
        t ->
            s.db.update(
                "UPDATE customer_email_outbox SET status='SUPERSEDED',updated_at=? WHERE"
                    + " status='PENDING' AND notification_id IN (SELECT n.id FROM"
                    + " customer_notifications n JOIN document_submissions c ON"
                    + " c.id=n.submission_id WHERE c.revision<>customer_email_outbox.revision OR"
                    + " (n.notification_type='OPERATIONS_READY' AND c.status<>'OPERATIONS') OR"
                    + " (n.notification_type='CORRECTION_REQUIRED' AND NOT EXISTS (SELECT 1 FROM correction_tasks task WHERE"
                    + " task.submission_id=c.id AND task.status NOT IN ('RESOLVED','ESCALATED'))))",
                Store.now()));
    tx.executeWithoutResult(
        t ->
            s.db.update(
                "UPDATE customer_email_outbox SET status='UNVERIFIED_TEST_ADDRESS',updated_at=?"
                    + " WHERE status='PENDING' AND NOT EXISTS (SELECT 1 FROM customer_notifications"
                    + " n JOIN customer_profiles p ON p.customer_id=n.customer_id WHERE"
                    + " n.id=customer_email_outbox.notification_id AND"
                    + " p.email=customer_email_outbox.recipient AND p.email_verified_test=TRUE)",
                Store.now()));
    for (var row :
        s.db.queryForList(
            "SELECT o.*,n.submission_id,n.message,n.notification_type FROM customer_email_outbox o JOIN"
                + " customer_notifications n ON n.id=o.notification_id WHERE o.status='PENDING' AND"
                + " NOT EXISTS (SELECT 1 FROM document_submissions c WHERE c.id=n.submission_id AND"
                + " c.risk_hold=TRUE) ORDER BY o.created_at LIMIT 10")) {
      String id = (String) row.get("id");
      String recipient = Objects.toString(row.get("recipient"), "");
      if (!isAuthorisedRecipient(recipient)) {
        tx.executeWithoutResult(
            t -> {
              s.db.update(
                  "UPDATE customer_email_outbox SET status='UNVERIFIED_TEST_ADDRESS',updated_at=?"
                      + " WHERE id=? AND status='PENDING'",
                  Store.now(),
                  id);
              s.audit(
                  (String) row.get("submission_id"),
                  "SYSTEM",
                  "CUSTOMER_EMAIL_BLOCKED_UNAUTHORISED",
                  "Recipient is not in the authorised project team allowlist",
                  "UNVERIFIED_TEST_ADDRESS",
                  null);
            });
        continue;
      }
      Integer claimed =
          tx.execute(
              t ->
                  s.db.update(
                      "UPDATE customer_email_outbox SET"
                          + " status='SENDING',attempts=attempts+1,updated_at=? WHERE id=? AND"
                          + " status='PENDING'",
                      Store.now(),
                      id));
      if (!Integer.valueOf(1).equals(claimed)) continue;
      String result = "SENT";
      try {
        var message = genericMessage(recipient);
        if ("REQUEST_CANCELLED".equals(row.get("notification_type")))
          message.setText(GENERIC_BODY + "\n\n" + row.get("message"));
        else if ("OPERATIONS_READY".equals(row.get("notification_type")))
          message.setText(GENERIC_BODY + "\n\nYour request was completed successfully.");
        else if ("CORRECTION_REQUIRED".equals(row.get("notification_type")))
          message.setText(GENERIC_BODY + "\n\nPlease open the Customer Portal to review your correction instructions.");
        mail.send(message);
      } catch (Exception e) {
        result = "DELIVERY_UNKNOWN";
      } // SMTP can accept DATA before a connection fails.
      String outcome = result;
      tx.executeWithoutResult(
          t -> {
            s.db.update(
                "UPDATE customer_email_outbox SET status=?,updated_at=? WHERE id=?",
                outcome,
                Store.now(),
                id);
            s.audit(
                (String) row.get("submission_id"),
                "SYSTEM",
                "CUSTOMER_EMAIL_" + outcome,
                "SMTP handoff recorded; inbox receipt is not guaranteed",
                outcome,
                null);
          });
    }
  }

  String formattedFrom() {
    String trimmed = Objects.toString(from, "").trim();
    if (trimmed.isEmpty() || trimmed.contains("<")) return trimmed;
    String cleanName =
        Objects.toString(fromName, "AFI Resolve").replaceAll("[\\r\\n]+", " ").trim();
    if (cleanName.isEmpty()) cleanName = "AFI Resolve";
    return cleanName + " <" + trimmed + ">";
  }

  SimpleMailMessage genericMessage(String recipient) {
    var message = new SimpleMailMessage();
    message.setFrom(formattedFrom());
    message.setTo(recipient);
    message.setSubject(GENERIC_SUBJECT);
    message.setText(GENERIC_BODY);
    return message;
  }

  boolean isStrictAllowlistConfigured() {
    return requireAllowlist || !Objects.toString(testRecipients, "").isBlank();
  }

  boolean isAuthorisedRecipient(String recipient) {
    if (recipient == null
        || !BankingServicePolicy.validEmail(recipient)
        || recipient.toLowerCase(Locale.ROOT).endsWith(".invalid")) return false;
    var allowlist =
        Arrays.stream(Objects.toString(testRecipients, "").split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .map(s -> s.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());
    if (!allowlist.isEmpty()) {
      return allowlist.contains(recipient.toLowerCase(Locale.ROOT));
    }
    return !requireAllowlist;
  }

  public void sendContactCode(String recipient, String code) {
    if (!canVerifyContact(recipient))
      throw new ApiException(
          409,
          "OTP_UNAVAILABLE",
          "Configure an authorised SMTP test channel and a deliverable email address before"
              + " requesting verification. No OTP has been verified.");
    var message = genericMessage(recipient);
    message.setText(
        GENERIC_BODY
            + "\n\nVerification code: "
            + code
            + "\n\nThis test verification code expires in 5 minutes.");
    mail.send(message);
  }

  public boolean canVerifyContact(String recipient) {
    return enabled && isAuthorisedRecipient(recipient);
  }
}
