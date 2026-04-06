package com.rohit8020.claimsservice.entity;

public enum OutboxStatus {
    PENDING,
    IN_FLIGHT,
    SENT,
    FAILED
}
