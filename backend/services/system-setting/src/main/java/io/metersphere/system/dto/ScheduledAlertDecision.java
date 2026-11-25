package io.metersphere.system.dto;

public record ScheduledAlertDecision(String reportId, int consecutiveFailures, ScheduledAlertPolicy policy) {}
