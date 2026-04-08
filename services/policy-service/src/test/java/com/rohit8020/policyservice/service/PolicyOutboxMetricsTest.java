package com.rohit8020.policyservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

import com.rohit8020.policyservice.entity.OutboxStatus;
import com.rohit8020.policyservice.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PolicyOutboxMetricsTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Test
    void registersReadyAndFailedGauges() {
        when(outboxEventRepository.countByStatusInAndAvailableAtLessThanEqual(anyList(), any(Instant.class)))
                .thenReturn(4L);
        when(outboxEventRepository.countByStatus(OutboxStatus.FAILED)).thenReturn(2L);
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        new PolicyOutboxMetrics(meterRegistry, outboxEventRepository);

        assertThat(meterRegistry.get("outbox.events.ready").gauge().value()).isEqualTo(4.0);
        assertThat(meterRegistry.get("outbox.events.failed").gauge().value()).isEqualTo(2.0);
    }
}
