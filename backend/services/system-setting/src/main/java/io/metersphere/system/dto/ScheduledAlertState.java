package io.metersphere.system.dto;

import lombok.Data;

@Data
public class ScheduledAlertState {
    private String lastNotifiedExecution;
    private long lastRecoveryTime;
}
