package com.afi.resolve;

import java.util.List;
import java.util.Locale;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.annotation.Transactional;

/** The selectable, fictional customer identities used by the demonstration. */
@Component
@ConditionalOnProperty(name = "afi.seed-named-demo-customers", havingValue = "true", matchIfMissing = true)
public class DemoCustomerProfiles implements ApplicationRunner {
  static final List<String> NAMES = List.of(
      "Alex", "Billy", "Charlie", "Daniel", "Ethan", "Felix", "George", "Henry", "Isaac", "Jack");
  final Store store;
  final PasswordEncoder passwords;

  public DemoCustomerProfiles(Store store, PasswordEncoder passwords) {
    this.store = store;
    this.passwords = passwords;
  }

  @Override
  @Transactional
  public void run(ApplicationArguments args) {
    for (String name : NAMES) {
      String username = name.toLowerCase(Locale.ROOT);
      String reference = "SYN-" + name.toUpperCase(Locale.ROOT) + "-001";
      // Preserve existing customer names (such as Alex Tan on SYN-ALEX-001); only backfill blanks.
      store.db.update(
          "UPDATE customers SET customer_name=? WHERE customer_reference=? AND (customer_name IS"
              + " NULL OR TRIM(customer_name)='')",
          name,
          reference);
      var existing =
          store.db.queryForList(
              "SELECT id FROM customers WHERE username=? OR customer_reference=?",
              username,
              reference);
      if (!existing.isEmpty()) {
        String existingId = (String) existing.getFirst().get("id");
        if (store.db.queryForObject(
                "SELECT COUNT(*) FROM customer_profiles WHERE customer_id=?",
                Integer.class,
                existingId)
            == 0) {
          new CustomerProfiles(store).create(existingId, reference);
        }
        continue;
      }
      String customerId = store.id("CUSTOMER");
      store.db.update(
          "INSERT INTO customers(id,username,password_hash,customer_name,customer_reference,relationship_manager,created_at)"
              + " VALUES(?,?,?,?,?,?,?)",
          customerId, username, passwords.encode(""), name, reference, "reviewer", Store.now());
      new CustomerProfiles(store).create(customerId, reference);
    }
  }
}
