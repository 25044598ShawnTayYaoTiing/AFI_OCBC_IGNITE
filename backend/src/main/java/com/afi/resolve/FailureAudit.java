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

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void imageConversionFailed(String submission, String actor, com.fasterxml.jackson.databind.JsonNode originals) {
    store.audit(null, actor, "IMAGE_CONVERSION_FAILED",
        store.json(Domain.map("submissionId", submission, "source_images", originals,
            "recovery", "Private originals retained; retry the same upload request")), "FAILED", null);
  }
}
