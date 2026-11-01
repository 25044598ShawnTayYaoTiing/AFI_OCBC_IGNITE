package com.afi.resolve;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
public class InvestigationWorker {
  final WorkflowService workflows;

  public InvestigationWorker(WorkflowService workflows) {
    this.workflows = workflows;
  }

  @Async
  public void investigate(String id) {
    workflows.completeInvestigation(id);
  }
}
