package io.metersphere.system.dto;

import lombok.Data;

@Data
public class ScheduledAlertState {
    private String lastNotifiedReport;
    private long lastRecoveryTime;
}
