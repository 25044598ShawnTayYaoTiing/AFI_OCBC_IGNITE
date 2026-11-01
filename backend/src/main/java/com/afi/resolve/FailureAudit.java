package com.afi.resolve;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class FailureAudit {
  final Store store;

  public FailureAudit(Store store) {
    this.store = store;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void record(String id, String actor, String code, String message) {
    store.audit(id, actor, "OPERATION_REJECTED", code + ": " + message, "REJECTED", null);
  }
}
