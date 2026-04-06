package com.rohit8020.claimsservice.repository;

import com.rohit8020.claimsservice.entity.OutboxEvent;
import com.rohit8020.claimsservice.entity.OutboxStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, String> {

    @Query(value = """
            select *
            from outbox_events
            where status in ('PENDING', 'FAILED')
              and available_at <= now()
            order by created_at
            for update skip locked
            limit :limit
            """, nativeQuery = true)
    List<OutboxEvent> claimNextBatch(@Param("limit") int limit);

    long countByStatusInAndAvailableAtLessThanEqual(List<OutboxStatus> statuses, Instant availableAt);

    long countByStatus(OutboxStatus status);
}
