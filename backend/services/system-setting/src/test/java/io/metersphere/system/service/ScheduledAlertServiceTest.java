package io.metersphere.system.service;

import io.metersphere.system.dto.*;
import io.metersphere.system.mapper.ScheduledAlertMapper;
import org.junit.jupiter.api.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ScheduledAlertServiceTest {
    ScheduledAlertMapper mapper;
    ScheduledAlertService service;
    ScheduledAlertPolicy policy;
    Map<String, ScheduledAlertState> states;
    Map<String, Map<String, ScheduledAlertResult>> histories;

    @BeforeEach void setup() {
        mapper = mock(ScheduledAlertMapper.class);
        service = new ScheduledAlertService(mapper);
        policy = new ScheduledAlertPolicy(); policy.setFailureThreshold(2);
        states = new HashMap<>(); histories = new HashMap<>();
        when(mapper.getPolicy(anyString(), anyString())).thenReturn(policy);
        when(mapper.lockState(anyString())).thenAnswer(call -> states.computeIfAbsent(call.getArgument(0), key -> new ScheduledAlertState()));
        when(mapper.addResult(anyString(), any())).thenAnswer(call -> {
            Map<String, ScheduledAlertResult> history = histories.computeIfAbsent(call.getArgument(0), key -> new HashMap<>());
            ScheduledAlertResult result = call.getArgument(1);
            return history.compute(result.getExecutionId(), (key, old) -> { if (old == null || result.getEndTime() >= old.getEndTime()) { result.setNotified(old != null && old.isNotified()); return result; } return old; }) == result ? 1 : 0;
        });
        doAnswer(call -> {
            Map<String, ScheduledAlertResult> history = histories.get(call.getArgument(0));
            List<ScheduledAlertResult> rows = call.getArgument(1);
            rows.forEach(row -> history.compute(row.getExecutionId(), (key, old) -> { if (old == null || row.getEndTime() >= old.getEndTime()) { row.setNotified(old != null && old.isNotified()); return row; } return old; })); return null;
        }).when(mapper).addResults(anyString(), anyList());
        doAnswer(call -> { histories.get(call.getArgument(0)).get(call.getArgument(1)).setNotified(true); return null; })
                .when(mapper).markNotified(anyString(), anyString());
        when(mapper.latestResults(anyString())).thenAnswer(call -> histories.get(call.getArgument(0)).values().stream()
                .sorted(Comparator.comparingLong(ScheduledAlertResult::getEndTime).reversed()
                        .thenComparing(ScheduledAlertResult::getReportId, Comparator.reverseOrder())).limit(100).toList());
        doAnswer(call -> {
            ScheduledAlertState state = states.get(call.getArgument(0));
            state.setLastNotifiedExecution(call.getArgument(1)); state.setLastRecoveryTime(call.getArgument(2)); return null;
        }).when(mapper).notified(anyString(), nullable(String.class), anyLong());
    }
    ScheduledAlertDecision result(String id, long time, boolean failed) { return result("resource", "env", id, time, failed); }
    ScheduledAlertDecision result(String resource, String env, String id, long time, boolean failed) {
        ScheduledAlertResult item = new ScheduledAlertResult();
        item.setReportId(id); item.setEndTime(time); item.setFailed(failed);
        return service.record("project", "API_SCENARIO_TASK", resource, env, item, List.of());
    }
    @Test void thresholdRecoveryAndRedelivery() {
        assertNull(result("1", 1, true));
        assertNull(result("1", 1, true));
        assertEquals(2, result("2", 2, true).consecutiveFailures());
        assertNull(result("2", 2, true));
        assertNull(result("3", 3, false));
        assertNull(result("4", 4, true));
        assertEquals(2, result("5", 5, true).consecutiveFailures());
    }
    @Test void resourcesEnvironmentsAndPolicyRevisionsAreIsolated() {
        assertNull(result("a", "test", "1", 1, true));
        assertNull(result("b", "test", "2", 2, true));
        assertNull(result("a", "prod", "3", 3, true));
        assertNotNull(result("a", "test", "4", 4, true));
        policy.setRevision(2);
        assertNull(result("a", "test", "5", 5, true));
    }
    @Test void lateCallbacksUseLatestReportAndPersistedCompletionOrder() {
        assertNull(result("new", 20, true));
        ScheduledAlertDecision decision = result("old", 10, true);
        assertEquals("new", decision.reportId());
        assertNull(result("recovery", 30, false));
        assertNull(result("late", 15, true));
        assertNull(result("next", 40, true));
        assertNotNull(result("next2", 50, true));
    }
    @Test void persistedSuccessBreaksStreakBeforeItsCallbackArrives() {
        assertNull(result("1", 1, true));
        ScheduledAlertResult success = new ScheduledAlertResult(); success.setReportId("2"); success.setEndTime(2);
        ScheduledAlertResult failure = new ScheduledAlertResult(); failure.setReportId("3"); failure.setEndTime(3); failure.setFailed(true);
        assertNull(service.record("project", "API_SCENARIO_TASK", "resource", "env", failure, List.of(success)));
        assertNull(result("2", 2, false));
        assertEquals(2, result("4", 4, true).consecutiveFailures());
    }
    @Test void onceUntilRecoveryEvenAfterHistoryWindow() {
        policy.setRepeatNotifications(false);
        assertNull(result("1", 1, true));
        assertNotNull(result("2", 2, true));
        for (int i = 3; i < 110; i++) { assertNull(result(String.valueOf(i), i, true)); }
        assertNull(result("ok", 200, false));
        assertNull(result("new1", 201, true));
        assertNotNull(result("new2", 202, true));
    }
    @Test void interleavedLateReportsCannotRenotifyAnExecution() {
        policy.setFailureThreshold(1);
        for (int i=1; i<=3; i++) {
            ScheduledAlertResult report = new ScheduledAlertResult(); report.setReportId("late-report" + i);
            report.setExecutionId(i == 2 ? "second-round" : "first-round");
            report.setEndTime(i); report.setFailed(true);
            ScheduledAlertDecision decision = service.record("project", "API_SCENARIO_TASK", "resource", "env", report, List.of());
            if (i <= 2) { assertNotNull(decision); } else { assertNull(decision); }
        }
    }
    @Test void multipleReportsFromOneExecutionCountOnce() {
        for (int i=1; i<=4; i++) {
            ScheduledAlertResult report = new ScheduledAlertResult(); report.setReportId("report" + i);
            report.setExecutionId(i <= 2 ? "round-one" : "round-two");
            report.setEndTime(i); report.setFailed(true);
            ScheduledAlertDecision decision = service.record("project", "API_SCENARIO_TASK", "resource", "env", report, List.of());
            if (i == 3) { assertEquals(2, decision.consecutiveFailures()); }
            else { assertNull(decision); }
        }
    }
    @Test void defaultPolicyAndThresholdOnePreserveImmediateAlerts() {
        policy.setFailureThreshold(1);
        assertNotNull(result("1", 1, true));
        assertNotNull(result("2", 2, true));
        when(mapper.getPolicy(anyString(), anyString())).thenReturn(null);
        assertEquals(1, service.getPolicy("p", "API_SCENARIO_TASK").getFailureThreshold());
    }
    @Test void templatesAreValidatedAndSubstitutionIsLiteral() {
        policy.setTaskType("API_SCENARIO_TASK");
        policy.setFailureTemplate("${environment} ${failureDetails}");
        ScheduledAlertDecision decision = new ScheduledAlertDecision("r", 2, policy);
        assertEquals("Test ${reportUrl}", ScheduledAlertService.render(decision, Map.of("environment", "Test", "failureDetails", "${reportUrl}")));
        policy.setFailureTemplate("${env:SECRET}");
        assertThrows(RuntimeException.class, () -> service.savePolicy(policy));
        policy.setFailureTemplate("${bad");
        assertThrows(RuntimeException.class, () -> service.savePolicy(policy));
    }
}
