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

  @Value("${afi.customer-portal-url:https://ocbc-customer-portal.vercel.app}")
  String portalUrl = "https://ocbc-customer-portal.vercel.app";

  @Value("${afi.mail-test-recipients:}")
  String testRecipients = "";

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
                    + " NOT EXISTS (SELECT 1 FROM correction_tasks task WHERE"
                    + " task.submission_id=c.id AND task.status NOT IN ('RESOLVED','ESCALATED')))",
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
            "SELECT o.*,n.submission_id,n.message FROM customer_email_outbox o JOIN"
                + " customer_notifications n ON n.id=o.notification_id WHERE o.status='PENDING' AND"
                + " NOT EXISTS (SELECT 1 FROM document_submissions c WHERE c.id=n.submission_id AND"
                + " c.risk_hold=TRUE) ORDER BY o.created_at LIMIT 10")) {
      String id = (String) row.get("id");
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
        var message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo((String) row.get("recipient"));
        message.setSubject("AFI Resolve demo: correction requested");
        message.setText(
            row.get("message")
                + "\nApplication: "
                + row.get("submission_id")
                + "\n"
                + "Sign in at "
                + portalUrl
                + "/submissions/"
                + row.get("submission_id")
                + " for instructions. Do not email identity"
                + " documents.\n"
                + "Synthetic hackathon demonstration.");
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

  public void sendContactCode(String recipient, String code) {
    if (!canVerifyContact(recipient))
      throw new ApiException(
          409,
          "OTP_UNAVAILABLE",
          "Configure an authorised SMTP test channel and a deliverable profile/contact email before"
              + " requesting verification. No OTP has been verified.");
    var message = new SimpleMailMessage();
    message.setFrom(from);
    message.setTo(recipient);
    message.setSubject("AFI Resolve synthetic contact verification");
    message.setText(
        "Your synthetic contact-update code is: "
            + code
            + "\n"
            + "It expires in 5 minutes and authorises only this request revision. Never share real"
            + " banking codes. This is a hackathon test.");
    mail.send(message);
  }

  public boolean canVerifyContact(String recipient) {
    return enabled
        && recipient != null
        && recipient.matches("[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+")
        && !recipient.toLowerCase(Locale.ROOT).endsWith(".invalid")
        && (Arrays.stream(testRecipients.split(","))
                .map(String::trim)
                .anyMatch(address -> address.equalsIgnoreCase(recipient))
            || s.db.queryForObject(
                    "SELECT COUNT(*) FROM customer_profiles WHERE LOWER(email)=? AND"
                        + " email_verified_test=TRUE",
                    Integer.class,
                    recipient.toLowerCase(Locale.ROOT))
                > 0);
  }
}
