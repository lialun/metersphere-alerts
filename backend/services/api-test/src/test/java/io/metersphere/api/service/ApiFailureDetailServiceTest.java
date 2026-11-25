package io.metersphere.api.service;

import io.metersphere.sdk.dto.api.result.*;
import io.metersphere.api.domain.ApiReportDetail;
import io.metersphere.api.mapper.AlertReportDetailMapper;
import io.metersphere.sdk.util.JSON;
import static org.mockito.Mockito.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ApiFailureDetailServiceTest {
    @Test void identifiesRequestStatusAndNumericAssertionWithoutLeakingBodies() {
        RequestResult request = new RequestResult();
        request.setName("Login"); request.setMethod("POST");
        request.setUrl("https://user:password@example.com/login?token=secret#fragment");
        request.setIsSuccessful(false); request.setBody("hidden-request-body");
        request.setHeaders("Authorization: hidden-header");
        request.getResponseResult().setResponseCode("503");
        request.getResponseResult().setBody("hidden-response-body");
        ResponseAssertionResult assertion = new ResponseAssertionResult();
        assertion.setName("Expected HTTP 200"); assertion.setAssertionType("RESPONSE_CODE");
        assertion.setExpectedValue("200"); assertion.setActualValue("503");
        request.getResponseResult().getAssertions().add(assertion);
        List<String> lines = new ArrayList<>();
        ApiFailureDetailService.appendFailures(request, lines, 0);
        String text = String.join("\n", lines);
        assertTrue(text.contains("Login | POST https://example.com/login | HTTP 503"));
        assertTrue(text.contains("expected 200, actual 503"));
        assertFalse(text.contains("secret")); assertFalse(text.contains("password"));
        assertFalse(text.contains("hidden-")); assertFalse(text.contains("fragment"));
    }
    @Test void bodyAssertionsDoNotExposeActualOrExpectedValues() {
        RequestResult request = new RequestResult(); request.setError(1);
        ResponseAssertionResult assertion = new ResponseAssertionResult();
        assertion.setAssertionType("JSON_PATH"); assertion.setName("Business status");
        assertion.setActualValue("private-response"); assertion.setExpectedValue("private-expectation");
        request.getResponseResult().getAssertions().add(assertion);
        List<String> lines = new ArrayList<>(); ApiFailureDetailService.appendFailures(request, lines, 0);
        assertTrue(lines.getFirst().contains("Business status: assertion failed (JSON_PATH)"));
        assertFalse(lines.getFirst().contains("private-"));
    }
    @Test void parsesScenarioBlobsAndReportsMissingOrOversizedDetails() {
        AlertReportDetailMapper mapper = mock(AlertReportDetailMapper.class);
        RequestResult request = new RequestResult(); request.setName("Snapshot API"); request.setMethod("GET");
        request.setUrl("https://example.com/check"); request.setIsSuccessful(true);
        request.getResponseResult().setResponseCode("503"); request.getResponseResult().setResponseMessage("Service Unavailable");
        ApiReportDetail detail = new ApiReportDetail(); detail.setContent(JSON.toJSONBytes(request));
        when(mapper.failures("scenario")).thenReturn(List.of());
        when(mapper.scenarioFailures("scenario")).thenReturn(List.of(detail));
        ApiFailureDetailService service = new ApiFailureDetailService(mapper);
        assertTrue(service.reportFailures("scenario").contains("Snapshot API | GET https://example.com/check | HTTP 503"));
        detail.setContent(null); detail.setRequestName("Oversized API"); detail.setCode("500");
        assertTrue(service.reportFailures("scenario").contains("Oversized API | HTTP 500"));
    }
    @Test void malformedUrlsAndSecretLabelsAreRedactedAndLengthIsBounded() {
        assertEquals("[URL unavailable]", ApiFailureDetailService.safeUrl("bad url?token=secret"));
        assertFalse(ApiFailureDetailService.clean("token=abc Authorization=Bearer abc").contains("abc"));
        assertTrue(ApiFailureDetailService.clean("x".repeat(1000)).length() < 260);
    }
}
