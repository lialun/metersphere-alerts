package io.metersphere.system.dto;

import lombok.Data;

@Data
public class ScheduledAlertResult {
    private String reportId;
    private long endTime;
    private boolean failed;
}
