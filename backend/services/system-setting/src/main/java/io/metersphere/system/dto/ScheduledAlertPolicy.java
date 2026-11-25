package io.metersphere.system.dto;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class ScheduledAlertPolicy {
    @NotBlank @Size(max = 50)
    private String projectId;
    @Pattern(regexp = "API_DEFINITION_TASK|API_SCENARIO_TASK|TEST_PLAN_TASK")
    @NotNull
    private String taskType;
    @Min(1) @Max(100) @NotNull
    private Integer failureThreshold = 1;
    private boolean repeatNotifications = true;
    @Size(max = 4000)
    private String failureTemplate = "";
    private long revision;
    private long updatedTime;
}
