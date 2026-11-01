package com.afi.resolve;

import jakarta.persistence.*;

@Entity
@Table(name = "transaction_batches")
public class BatchEntity {
  @Id private String id;

  @Column(nullable = false)
  private String status;

  @Version private long version;

  protected BatchEntity() {}

  public String getId() {
    return id;
  }

  public String getStatus() {
    return status;
  }
}
