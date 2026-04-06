package com.rohit8020.claimsservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rohit8020.claimsservice.entity.OutboxEvent;
import com.rohit8020.claimsservice.entity.OutboxStatus;
import com.rohit8020.claimsservice.repository.OutboxEventRepository;
import com.rohit8020.platformcommon.event.DomainEvent;
import jakarta.transaction.Transactional;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.outbox.enabled", havingValue = "true", matchIfMissing = true)
public class ClaimsOutboxPublisher {

    private final OutboxEventRepository outboxEventRepository;
    private final KafkaTemplate<String, DomainEvent> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final String topic;
    private final int batchSize;

    public ClaimsOutboxPublisher(OutboxEventRepository outboxEventRepository,
                                 KafkaTemplate<String, DomainEvent> kafkaTemplate,
                                 ObjectMapper objectMapper,
                                 @Value("${app.kafka.claim-topic}") String topic,
                                 @Value("${app.outbox.batch-size:25}") int batchSize) {
        this.outboxEventRepository = outboxEventRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.topic = topic;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.outbox.fixed-delay-ms:5000}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outboxEventRepository.claimNextBatch(batchSize);
        for (OutboxEvent event : batch) {
            event.setStatus(OutboxStatus.IN_FLIGHT);
        }
        outboxEventRepository.flush();

        for (OutboxEvent event : batch) {
            try {
                DomainEvent domainEvent = new DomainEvent(
                        event.getId(),
                        event.getAggregateType(),
                        event.getAggregateId(),
                        event.getEventType(),
                        event.getEventVersion(),
                        event.getCreatedAt(),
                        objectMapper.readTree(event.getPayload())
                );
                kafkaTemplate.send(topic, event.getAggregateId(), domainEvent).get();
                event.setStatus(OutboxStatus.SENT);
                event.setSentAt(Instant.now());
                event.setLastError(null);
            } catch (Exception ex) {
                event.setStatus(OutboxStatus.FAILED);
                event.setRetryCount(event.getRetryCount() + 1);
                event.setAvailableAt(Instant.now().plusSeconds(Math.min(300, 10L * event.getRetryCount())));
                event.setLastError(ex.getMessage());
            }
        }
    }
}
