package com.rohit8020.policyservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit8020.platformcommon.event.AggregateType;
import com.rohit8020.policyservice.entity.OutboxEvent;
import com.rohit8020.policyservice.entity.OutboxStatus;
import com.rohit8020.policyservice.repository.OutboxEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class PolicyOutboxPublisherTest {

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private KafkaTemplate<String, com.rohit8020.platformcommon.event.DomainEvent> kafkaTemplate;

    @Test
    void publishPendingMarksEventSentWhenKafkaSendSucceeds() {
        OutboxEvent event = event();
        when(outboxEventRepository.claimNextBatch(25)).thenReturn(List.of(event));
        CompletableFuture<SendResult<String, com.rohit8020.platformcommon.event.DomainEvent>> future =
                CompletableFuture.completedFuture(null);
        when(kafkaTemplate.send(
                eq("policy-events.v1"),
                eq("aggregate-1"),
                any(com.rohit8020.platformcommon.event.DomainEvent.class)))
                .thenReturn(future);

        PolicyOutboxPublisher publisher = new PolicyOutboxPublisher(
                outboxEventRepository,
                kafkaTemplate,
                new ObjectMapper(),
                "policy-events.v1",
                25
        );

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getSentAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
        verify(outboxEventRepository).flush();
    }

    @Test
    void publishPendingMarksEventFailedWhenKafkaSendThrows() {
        OutboxEvent event = event();
        event.setRetryCount(2);
        when(outboxEventRepository.claimNextBatch(25)).thenReturn(List.of(event));
        when(kafkaTemplate.send(
                eq("policy-events.v1"),
                eq("aggregate-1"),
                any(com.rohit8020.platformcommon.event.DomainEvent.class)))
                .thenThrow(new RuntimeException("boom"));

        PolicyOutboxPublisher publisher = new PolicyOutboxPublisher(
                outboxEventRepository,
                kafkaTemplate,
                new ObjectMapper(),
                "policy-events.v1",
                25
        );

        publisher.publishPending();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getRetryCount()).isEqualTo(3);
        assertThat(event.getAvailableAt()).isAfter(Instant.now().minusSeconds(1));
        assertThat(event.getLastError()).contains("boom");
    }

    private OutboxEvent event() {
        OutboxEvent event = new OutboxEvent();
        event.setId("event-1");
        event.setAggregateType(AggregateType.POLICY);
        event.setAggregateId("aggregate-1");
        event.setEventType("POLICY_ISSUED");
        event.setEventVersion(1);
        event.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        event.setAvailableAt(Instant.now());
        event.setRetryCount(0);
        event.setStatus(OutboxStatus.PENDING);
        event.setPayload("{\"status\":\"ISSUED\"}");
        return event;
    }
}
