package com.rohit8020.platformcommon.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record DomainEvent(
        String eventId,
        AggregateType aggregateType,
        String aggregateId,
        String eventType,
        int eventVersion,
        Instant occurredAt,
        JsonNode payload
) {
}
