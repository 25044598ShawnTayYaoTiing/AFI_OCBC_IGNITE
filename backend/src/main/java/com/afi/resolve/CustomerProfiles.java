package com.afi.resolve;

import static com.afi.resolve.Domain.*;

import java.util.*;
import org.springframework.stereotype.Service;

/** Fictional core-banking records, never Singpass or a production identity provider. */
@Service
public class CustomerProfiles {
  final Store s;

  public CustomerProfiles(Store s) {
    this.s = s;
  }

  public void create(String id, String reference) {
    boolean alex = reference.equals("SYN-ALEX-001");
    s.db.update(
        "INSERT INTO customer_profiles VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        "DEMO-ID-" + reference,
        alex ? "8 Fictional Avenue" : "1 Fictional Road",
        alex ? "123 Example Road" : "1 Fictional Road",
        "customer." + id.substring(9, 21).toLowerCase(Locale.ROOT) + "@example.invalid",
        "+65 8000 0000",
        "Singapore",
        "Synthetic professional",
        "SAVINGS",
        "SGD",
        "Singapore",
        "STANDARD",
        "SYNTHETIC_DEMO_NOT_SINGPASS",
        Store.now());
  }

  public Map<String, Object> get(String id) {
    var rows =
        s.db.queryForList(
            "SELECT p.*,c.customer_name,c.customer_reference FROM customer_profiles p JOIN"
                + " customers c ON c.id=p.customer_id WHERE customer_id=?",
            id);
    if (rows.isEmpty())
      throw new ApiException(
          409,
          "PROFILE_UNAVAILABLE",
          "A synthetic customer profile must be provisioned before continuing");
    return rows.getFirst();
  }

  public Map<String, Object> dto(String id) {
    var p = get(id);
    return map(
        "identityNumber",
        p.get("identity_number"),
        "residentialAddress",
        p.get("residential_address"),
        "mailingAddress",
        p.get("mailing_address"),
        "email",
        p.get("email"),
        "mobile",
        p.get("mobile"),
        "nationality",
        p.get("nationality"),
        "occupation",
        p.get("occupation"),
        "accountType",
        p.get("account_type"),
        "currency",
        p.get("currency"),
        "taxResidency",
        p.get("tax_residency"),
        "riskRating",
        p.get("risk_rating"),
        "provenance",
        p.get("provenance"));
  }
}
