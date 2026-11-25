package io.metersphere.system.service;

import io.metersphere.sdk.exception.MSException;
import io.metersphere.system.dto.*;
import io.metersphere.system.mapper.ScheduledAlertMapper;
import org.apache.commons.codec.digest.DigestUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;
import java.util.regex.*;

/** Scheduled results are recorded before receiver/event filtering, including disabled success notifications. */
@Service
public class ScheduledAlertService {
    public static final Set<String> VARIABLES = Set.of("projectName", "resourceName", "environment", "reportName",
            "reportUrl", "failureDetails", "failedCount", "consecutiveFailures", "threshold");
    private static final Pattern VARIABLE = Pattern.compile("\\$\\{([^{}]+)}");
    public static final String DEFAULT_TEMPLATE = """
            ${projectName} / ${resourceName}
            Environment: ${environment}
            Consecutive failures: ${consecutiveFailures} (threshold: ${threshold})
            Failed requests: ${failedCount}
            ${failureDetails}
            Report: ${reportUrl}""";
    private final ScheduledAlertMapper mapper;
    public ScheduledAlertService(ScheduledAlertMapper mapper) { this.mapper = mapper; }

    public ScheduledAlertPolicy getPolicy(String projectId, String taskType) {
        ScheduledAlertPolicy policy = mapper.getPolicy(projectId, taskType);
        if (policy == null) {
            policy = new ScheduledAlertPolicy();
            policy.setProjectId(projectId);
            policy.setTaskType(taskType);
        }
        return policy;
    }

    @Transactional
    public ScheduledAlertPolicy savePolicy(ScheduledAlertPolicy policy) {
        if (!Set.of("API_DEFINITION_TASK", "API_SCENARIO_TASK", "TEST_PLAN_TASK").contains(policy.getTaskType())
                || policy.getFailureThreshold() == null || policy.getFailureThreshold() < 1 || policy.getFailureThreshold() > 100) {
            throw new MSException("Invalid scheduled alert policy");
        }
        String template = Objects.requireNonNullElse(policy.getFailureTemplate(), "");
        if (template.length() > 4000) { throw new MSException("Alert template is too long"); }
        Matcher matcher = VARIABLE.matcher(template);
        while (matcher.find()) {
            if (!VARIABLES.contains(matcher.group(1))) { throw new MSException("Unknown alert variable: " + matcher.group(1)); }
        }
        if (VARIABLE.matcher(template).replaceAll("").contains("${")) { throw new MSException("Invalid alert variable"); }
        policy.setFailureTemplate(template);
        long expectedRevision = policy.getRevision();
        ScheduledAlertPolicy previous = mapper.lockPolicy(policy.getProjectId(), policy.getTaskType());
        if ((previous == null ? 0 : previous.getRevision()) != expectedRevision) {
            throw new MSException("Alert policy changed; reload before saving");
        }
        policy.setUpdatedTime(System.currentTimeMillis());
        mapper.savePolicy(policy);
        ScheduledAlertPolicy saved = getPolicy(policy.getProjectId(), policy.getTaskType());
        if (saved.getRevision() != expectedRevision + 1 || saved.getUpdatedTime() != policy.getUpdatedTime()) {
            throw new MSException("Alert policy changed; reload before saving");
        }
        return saved;
    }

    /**
     * The state upsert acquires an exclusive lock immediately (INSERT IGNORE would upgrade a shared lock).
     * READ_COMMITTED keeps the history query fresh after a concurrent callback commits while we wait.
     * A lock serializes concurrent callbacks; unique execution IDs make duplicate reports and Kafka redelivery idempotent.
     * Ordering uses persisted execution completion time, not callback arrival time. A late callback
     * can complete a streak and nominate the latest failed report, rather than send stale content.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ScheduledAlertDecision record(String projectId, String taskType, String resourceId, String environment,
                                         ScheduledAlertResult result, List<ScheduledAlertResult> completedResults) {
        ScheduledAlertPolicy policy = getPolicy(projectId, taskType);
        if (result.getEndTime() < policy.getUpdatedTime()) { return null; }
        String scope = DigestUtils.sha256Hex(String.join("\n", projectId, taskType, resourceId,
                Objects.requireNonNullElse(environment, ""), String.valueOf(policy.getRevision())));
        mapper.createState(scope, projectId, taskType);
        ScheduledAlertState state = mapper.lockState(scope);
        mapper.addResult(scope, result);
        // Reports, rather than message arrival, determine continuity: a delayed success callback
        // must not turn fail/success/fail into a false consecutive-failure alert.
        List<ScheduledAlertResult> backfill = completedResults.stream()
                .filter(item -> item.getEndTime() >= policy.getUpdatedTime()).toList();
        if (!backfill.isEmpty()) { mapper.addResults(scope, backfill); }
        List<ScheduledAlertResult> results = mapper.latestResults(scope);
        mapper.pruneResults(scope);
        int count = 0;
        long recovery = 0;
        for (ScheduledAlertResult item : results) {
            if (!item.isFailed()) { recovery = item.getEndTime(); break; }
            count++;
        }
        ScheduledAlertResult latest = results.getFirst();
        if (!latest.isFailed()) {
            mapper.notified(scope, null, latest.getEndTime());
            return null;
        }
        if (count < policy.getFailureThreshold()
                || latest.isNotified()
                || (!policy.isRepeatNotifications() && state.getLastNotifiedExecution() != null
                    && recovery <= state.getLastRecoveryTime())) { return null; }
        mapper.markNotified(scope, latest.getExecutionId());
        mapper.notified(scope, latest.getExecutionId(), recovery);
        return new ScheduledAlertDecision(latest.getReportId(), count, policy);
    }

    /** Literal, single-pass substitution: values cannot introduce new variables or executable expressions. */
    public static String render(ScheduledAlertDecision decision, Map<String, Object> values) {
        Map<String, Object> context = new HashMap<>(values);
        context.put("consecutiveFailures", decision.consecutiveFailures());
        context.put("threshold", decision.policy().getFailureThreshold());
        String template = decision.policy().getFailureTemplate();
        if (template == null || template.isBlank()) { template = DEFAULT_TEMPLATE; }
        Matcher matcher = VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(out, Matcher.quoteReplacement(String.valueOf(context.getOrDefault(matcher.group(1), ""))));
        }
        matcher.appendTail(out);
        return out.length() > 12000 ? out.substring(0, 12000) + "\n…" : out.toString();
    }
}
