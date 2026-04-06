package com.rohit8020.policyservice.entity;

public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    SENT,
    FAILED
}
