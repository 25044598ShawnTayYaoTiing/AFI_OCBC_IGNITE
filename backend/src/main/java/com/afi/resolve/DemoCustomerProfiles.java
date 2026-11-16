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
      store.db.update("UPDATE customers SET customer_name=? WHERE customer_reference=?", name, reference);
      // Preserve existing identities, assignments and cases; never replace an account.
      if (store.db.queryForObject(
          "SELECT COUNT(*) FROM customers WHERE username=? OR customer_reference=?",
          Integer.class, username, reference) > 0) continue;
      store.db.update(
          "INSERT INTO customers(id,username,password_hash,customer_name,customer_reference,relationship_manager,created_at)"
              + " VALUES(?,?,?,?,?,?,?)",
          store.id("CUSTOMER"), username, passwords.encode(""), name, reference, "reviewer", Store.now());
    }
  }
}
