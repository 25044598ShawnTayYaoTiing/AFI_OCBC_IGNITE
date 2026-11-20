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
        "INSERT INTO"
            + " customer_profiles(customer_id,identity_number,date_of_birth,postal_code,existing_account_reference,residential_address,mailing_address,email,mobile,nationality,occupation,account_type,currency,tax_residency,risk_rating,provenance,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        "DEMO-ID-" + reference,
        java.sql.Date.valueOf(alex ? "1988-05-14" : "1990-08-21"),
        alex ? "048616" : "018983",
        "SYN-ACCT-" + reference,
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

  Map<String, Object> lock(String id) {
    var rows = s.db.queryForList("SELECT * FROM customer_profiles WHERE customer_id=? FOR UPDATE", id);
    if (rows.isEmpty()) throw new ApiException(409, "PROFILE_UNAVAILABLE", "Customer profile unavailable");
    return rows.getFirst();
  }

  /** Caller holds the profile row lock. Both address entry points update the same records. */
  void saveRegisteredAddress(String customer, String type, Map<String, Object> address,
      String profileAddress, String actor) {
    String source = Objects.toString(address.get("source"));
    String json = s.json(address);
    int updated = s.db.update("UPDATE customer_registered_addresses SET address_json=?,source=?,"
        + "updated_by=?,updated_at=? WHERE customer_id=? AND address_type=?",
        json, source, actor, Store.now(), customer, type);
    if (updated == 0) {
      s.db.update("INSERT INTO customer_registered_addresses"
          + "(customer_id,address_type,address_json,source,updated_by,updated_at) VALUES(?,?,?,?,?,?)",
          customer, type, json, source, actor, Store.now());
    }
    if ("MAILING".equals(type)) {
      s.db.update("UPDATE customer_profiles SET mailing_address=?,postal_code=?,updated_at=? WHERE customer_id=?",
          profileAddress, address.get("postalCode"), Store.now(), customer);
    } else {
      s.db.update("UPDATE customer_profiles SET residential_address=?,updated_at=? WHERE customer_id=?",
          profileAddress, Store.now(), customer);
    }
  }

  public Map<String, Object> dto(String id) {
    var p = get(id);
    return map(
        "dateOfBirth",
        p.get("date_of_birth") == null ? null : p.get("date_of_birth").toString(),
        "postalCode",
        p.get("postal_code"),
        "existingAccountReference",
        p.get("existing_account_reference"),
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
        "foreignTaxCountry",
        p.get("foreign_tax_country"),
        "riskRating",
        p.get("risk_rating"),
        "provenance",
        p.get("provenance"));
  }
}
