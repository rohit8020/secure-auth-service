package com.rohit8020.policyservice.service;

import com.rohit8020.policyservice.entity.OutboxStatus;
import com.rohit8020.policyservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class PolicyOutboxMetrics {

    public PolicyOutboxMetrics(MeterRegistry meterRegistry, OutboxEventRepository outboxEventRepository) {
        Gauge.builder("outbox.events.ready",
                        outboxEventRepository,
                        repository -> repository.countByStatusInAndAvailableAtLessThanEqual(
                                List.of(OutboxStatus.PENDING, OutboxStatus.FAILED), Instant.now()))
                .description("Outbox events ready for publication")
                .tag("service", "policy-service")
                .register(meterRegistry);

        Gauge.builder("outbox.events.failed",
                        outboxEventRepository,
                        repository -> repository.countByStatus(OutboxStatus.FAILED))
                .description("Outbox events currently marked as failed")
                .tag("service", "policy-service")
                .register(meterRegistry);
    }
}
