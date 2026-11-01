package com.afi.resolve;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface BatchRepository extends JpaRepository<BatchEntity, String> {
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select b from BatchEntity b where b.id = :id")
  Optional<BatchEntity> lock(@Param("id") String id);
}
