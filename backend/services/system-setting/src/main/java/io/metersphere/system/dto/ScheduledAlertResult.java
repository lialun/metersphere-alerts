package io.metersphere.system.dto;

import lombok.Data;

@Data
public class ScheduledAlertResult {
    private String reportId;
    private String executionId;
    public String getExecutionId() { return executionId == null || executionId.isBlank() ? reportId : executionId; }
    private long endTime;
    private boolean failed;
    private boolean notified;
}
