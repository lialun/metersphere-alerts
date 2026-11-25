CREATE TABLE scheduled_alert_policy (
    project_id VARCHAR(50) NOT NULL,
    task_type VARCHAR(50) NOT NULL,
    failure_threshold INT NOT NULL DEFAULT 1,
    repeat_notifications BIT NOT NULL DEFAULT 1,
    failure_template TEXT NOT NULL,
    revision BIGINT NOT NULL DEFAULT 1,
    updated_time BIGINT NOT NULL,
    PRIMARY KEY (project_id, task_type),
    CONSTRAINT fk_alert_policy_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE scheduled_alert_state (
    scope_key CHAR(64) NOT NULL,
    project_id VARCHAR(50) NOT NULL,
    task_type VARCHAR(50) NOT NULL,
    last_notified_execution VARCHAR(50),
    last_recovery_time BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (scope_key),
    INDEX idx_alert_project (project_id),
    CONSTRAINT fk_alert_state_project FOREIGN KEY (project_id) REFERENCES project(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;

CREATE TABLE scheduled_alert_result (
    scope_key CHAR(64) NOT NULL,
    execution_id VARCHAR(50) NOT NULL,
    report_id VARCHAR(50) NOT NULL,
    end_time BIGINT NOT NULL,
    failed BIT NOT NULL,
    notified BIT NOT NULL DEFAULT 0,
    PRIMARY KEY (scope_key, execution_id),
    INDEX idx_alert_result_time (scope_key, end_time),
    CONSTRAINT fk_alert_result_scope FOREIGN KEY (scope_key) REFERENCES scheduled_alert_state(scope_key) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci;
