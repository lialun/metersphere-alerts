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
            return history.putIfAbsent(result.getReportId(), result) == null ? 1 : 0;
        });
        when(mapper.latestResults(anyString())).thenAnswer(call -> histories.get(call.getArgument(0)).values().stream()
                .sorted(Comparator.comparingLong(ScheduledAlertResult::getEndTime).reversed()
                        .thenComparing(ScheduledAlertResult::getReportId, Comparator.reverseOrder())).limit(100).toList());
        doAnswer(call -> {
            ScheduledAlertState state = states.get(call.getArgument(0));
            state.setLastNotifiedReport(call.getArgument(1)); state.setLastRecoveryTime(call.getArgument(2)); return null;
        }).when(mapper).notified(anyString(), nullable(String.class), anyLong());
    }
    ScheduledAlertDecision result(String id, long time, boolean failed) { return result("resource", "env", id, time, failed); }
    ScheduledAlertDecision result(String resource, String env, String id, long time, boolean failed) {
        ScheduledAlertResult item = new ScheduledAlertResult();
        item.setReportId(id); item.setEndTime(time); item.setFailed(failed);
        return service.record("project", "API_SCENARIO_TASK", resource, env, item);
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
    @Test void onceUntilRecoveryEvenAfterHistoryWindow() {
        policy.setRepeatNotifications(false);
        assertNull(result("1", 1, true));
        assertNotNull(result("2", 2, true));
        for (int i = 3; i < 110; i++) { assertNull(result(String.valueOf(i), i, true)); }
        assertNull(result("ok", 200, false));
        assertNull(result("new1", 201, true));
        assertNotNull(result("new2", 202, true));
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
