package io.metersphere.api.service;

import io.metersphere.api.domain.ApiReportDetail;
import io.metersphere.api.mapper.AlertReportDetailMapper;
import io.metersphere.sdk.dto.api.result.*;
import io.metersphere.api.utils.ApiDataUtils;
import io.metersphere.system.dto.ScheduledAlertResult;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Bounded projection of persisted results. Never include request/response bodies, cookies or headers. */
@Service
public class ApiFailureDetailService {
    private final AlertReportDetailMapper mapper;
    public ApiFailureDetailService(AlertReportDetailMapper mapper) { this.mapper = mapper; }

    public String executionId(String reportId) { return mapper.executionId(reportId); }

    public List<ScheduledAlertResult> completedResults(String project, String resource, String environment, boolean scenario) {
        return scenario ? mapper.completedScenarios(project, resource, environment) : mapper.completedCases(project, resource, environment);
    }

    public List<ScheduledAlertResult> completedPlanResults(String project, String resource) {
        return mapper.completedPlans(project, resource);
    }

    public String reportFailures(String reportId) {
        List<ApiReportDetail> details = mapper.failures(reportId);
        if (details.isEmpty()) { details = mapper.scenarioFailures(reportId); }
        List<String> lines = new ArrayList<>();
        for (ApiReportDetail detail : details.stream().limit(20).toList()) {
            try {
                RequestResult result = detail.getContent() == null ? null
                        : ApiDataUtils.parseObject(new String(detail.getContent(), StandardCharsets.UTF_8), RequestResult.class);
                if (result == null) {
                    lines.add(clean(detail.getRequestName()) + " | HTTP " + clean(detail.getCode()) + " | Execution failed; see report");
                } else {
                    result.setIsSuccessful(false); // The persisted detail row is ERROR, including assertion-only failures.
                    appendFailures(result, lines, 0);
                }
            } catch (Exception ignored) {
                lines.add(clean(detail.getRequestName()) + " | HTTP " + clean(detail.getCode()) + " | Details unavailable; see report");
            }
        }
        if (details.size() > 20 || lines.size() >= 20) { lines.add("… More failures in report"); }
        return bounded(String.join("\n", lines));
    }

    public String planFailures(String reportId) {
        List<String> reports = new ArrayList<>();
        reports.add(reportId);
        reports.addAll(mapper.childPlanReports(reportId));
        List<String> lines = new ArrayList<>();
        for (String plan : reports) {
            List<String> children = new ArrayList<>(mapper.planCaseReports(plan));
            children.addAll(mapper.planScenarioReports(plan));
            for (String child : children) {
                if (child != null && !child.isBlank()) { lines.add(reportFailures(child)); }
                if (String.join("\n", lines).length() >= 5000) { return bounded(String.join("\n", lines)); }
            }
        }
        return bounded(String.join("\n", lines));
    }

    static void appendFailures(RequestResult result, List<String> lines, int depth) {
        if (depth > 20 || lines.size() >= 20) { return; }
        if (Boolean.FALSE.equals(result.getIsSuccessful()) || result.getError() > 0 || "ERROR".equals(result.getStatus())) {
            ResponseResult response = result.getResponseResult();
            List<String> reasons = new ArrayList<>();
            if (response != null) {
                for (ResponseAssertionResult assertion : response.getAssertions()) {
                    if (assertion.isPass()) { continue; }
                    // Arbitrary body/header/variable assertion values can contain secrets. Only expose numeric expectations.
                    if (Set.of("RESPONSE_CODE", "RESPONSE_TIME").contains(Objects.requireNonNullElse(assertion.getAssertionType(), ""))) {
                        reasons.add(clean(assertion.getName()) + ": expected " + numeric(assertion.getExpectedValue())
                                + ", actual " + numeric(assertion.getActualValue()));
                    } else {
                        reasons.add(clean(assertion.getName()) + ": assertion failed (" + clean(assertion.getAssertionType()) + ")");
                    }
                    if (reasons.size() >= 5) { break; }
                }
                if (reasons.isEmpty()) { reasons.add(clean(response.getResponseMessage())); }
            }
            if (reasons.isEmpty()) { reasons.add("Request failed; see report"); }
            lines.add(clean(result.getName()) + " | " + clean(result.getMethod()) + " " + safeUrl(result.getUrl())
                    + " | HTTP " + (response == null ? "-" : clean(response.getResponseCode()))
                    + " | " + String.join("; ", reasons));
        }
        if (result.getSubRequestResults() != null) {
            for (RequestResult child : result.getSubRequestResults()) { appendFailures(child, lines, depth + 1); }
        }
    }

    static String safeUrl(String url) {
        try {
            URI uri = URI.create(url);
            // Remove credentials, query values and fragments. Malformed URLs are never echoed.
            return clean(new URI(uri.getScheme(), null, uri.getHost(), uri.getPort(), uri.getPath(), null, null).toString());
        } catch (Exception ignored) { return "[URL unavailable]"; }
    }
    private static String numeric(String value) { return value != null && value.matches("[0-9. -]{1,40}") ? value : "[see report]"; }
    static String clean(String value) {
        if (value == null) { return "-"; }
        String text = value.replaceAll("[\\p{Cntrl}&&[^\\n]]", " ").replace('\n', ' ')
                .replaceAll("(?i)(bearer\\s+)[^\\s,;]+", "$1[redacted]")
                .replaceAll("(?i)((?:password|passwd|token|secret|authorization|api[_-]?key)\\s*[=:]\\s*)[^\\s,;&]+", "$1[redacted]");
        return text.length() > 250 ? text.substring(0, 250) + "…" : text;
    }
    private static String bounded(String text) {
        if (text.isBlank()) { return "No request details available; inspect execution report for setup or script errors."; }
        return text.length() > 5000 ? text.substring(0, 5000) + "\n… More failures in report" : text;
    }
}
