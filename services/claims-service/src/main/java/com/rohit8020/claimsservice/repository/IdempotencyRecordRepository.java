package com.rohit8020.claimsservice.repository;

import com.rohit8020.claimsservice.entity.IdempotencyRecord;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, Long> {
    Optional<IdempotencyRecord> findByOperationAndActorIdAndIdempotencyKey(String operation,
                                                                           Long actorId,
                                                                           String idempotencyKey);
}
