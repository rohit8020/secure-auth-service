package com.rohit8020.claimsservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit8020.claimsservice.entity.OutboxEvent;
import com.rohit8020.claimsservice.entity.OutboxStatus;
import com.rohit8020.claimsservice.repository.OutboxEventRepository;
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
class ClaimsOutboxPublisherTest {

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
                eq("claim-events.v1"),
                eq("claim-1"),
                any(com.rohit8020.platformcommon.event.DomainEvent.class)))
                .thenReturn(future);

        ClaimsOutboxPublisher publisher = new ClaimsOutboxPublisher(
                outboxEventRepository,
                kafkaTemplate,
                new ObjectMapper(),
                "claim-events.v1",
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
                eq("claim-events.v1"),
                eq("claim-1"),
                any(com.rohit8020.platformcommon.event.DomainEvent.class)))
                .thenThrow(new RuntimeException("boom"));

        ClaimsOutboxPublisher publisher = new ClaimsOutboxPublisher(
                outboxEventRepository,
                kafkaTemplate,
                new ObjectMapper(),
                "claim-events.v1",
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
        event.setAggregateType(com.rohit8020.platformcommon.event.AggregateType.CLAIM);
        event.setAggregateId("claim-1");
        event.setEventType("CLAIM_SUBMITTED");
        event.setEventVersion(1);
        event.setCreatedAt(Instant.parse("2026-01-01T00:00:00Z"));
        event.setAvailableAt(Instant.now());
        event.setRetryCount(0);
        event.setStatus(OutboxStatus.PENDING);
        event.setPayload("{\"status\":\"SUBMITTED\"}");
        return event;
    }
}
