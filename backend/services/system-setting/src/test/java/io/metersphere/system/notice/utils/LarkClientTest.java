package io.metersphere.system.notice.utils;

import com.sun.net.httpserver.HttpServer;
import io.metersphere.sdk.exception.MSException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class LarkClientTest {
    private HttpServer server;
    private String webhook;
    private volatile List<Reply> replies;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger redirectedRequests = new AtomicInteger();

    private record Reply(int status, String body, Map<String, String> headers) {
        Reply(int status, String body) { this(status, body, Map.of()); }
    }

    @BeforeEach void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hook/opaque-token", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Reply reply = replies.get(Math.min(requests.size() - 1, replies.size() - 1));
            if (reply.status() == 0) { exchange.close(); return; }
            reply.headers().forEach((key, value) -> exchange.getResponseHeaders().set(key, value));
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(reply.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/redirected", exchange -> {
            redirectedRequests.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();
        webhook = "http://127.0.0.1:" + server.getAddress().getPort() + "/hook/opaque-token";
    }

    @AfterEach void stopServer() { server.stop(0); }

    @Test void retriesTwoServiceFailuresThenAcceptsExactlyTheSamePayload() {
        replies = List.of(new Reply(503, "unavailable"), new Reply(503, "unavailable"), new Reply(200, "{\"code\":0}"));
        LarkClient.send(webhook, "验收消息");
        assertEquals(3, requests.size());
        assertEquals(1, requests.stream().distinct().count());
        assertTrue(requests.getFirst().contains("验收消息"));
    }

    @Test void businessRejectionIsReportedWithoutLeakingPayloadOrWebhook() {
        replies = List.of(new Reply(200, "{\"code\":19024,\"msg\":\"opaque-token sensitive-context\"}"));
        MSException error = assertThrows(MSException.class, () -> LarkClient.send(webhook, "sensitive-context"));
        assertEquals(1, requests.size());
        assertTrue(error.getMessage().contains("19024"));
        assertFalse(error.getMessage().contains("opaque-token"));
        assertFalse(error.getMessage().contains("sensitive-context"));
        assertNull(error.getCause());
    }

    @Test void exhaustedServiceFailureIsReportedAfterThreeAttempts() {
        replies = List.of(new Reply(503, "unavailable"));
        MSException error = assertThrows(MSException.class, () -> LarkClient.send(webhook, "audit"));
        assertEquals(3, requests.size());
        assertTrue(error.getMessage().contains("503"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"code\":0}", "{\"StatusCode\":0}", "{\"code\":0,\"StatusCode\":19024}"})
    void acceptsModernAndLegacySuccessReceipts(String receipt) {
        replies = List.of(new Reply(200, receipt));
        LarkClient.send(webhook, "audit");
        assertEquals(1, requests.size());
    }

    @ParameterizedTest
    @ValueSource(longs = {5000, 11232, 11233, 99991400})
    void retriesTemporaryBusinessErrors(long code) {
        replies = List.of(new Reply(200, "{\"code\":" + code + "}"), new Reply(200, "{\"code\":0}"));
        LarkClient.send(webhook, "audit");
        assertEquals(2, requests.size());
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429})
    void retriesTemporaryHttpErrors(int status) {
        replies = List.of(new Reply(status, "{}", Map.of("Retry-After", "0")), new Reply(200, "{\"code\":0}"));
        LarkClient.send(webhook, "audit");
        assertEquals(2, requests.size());
    }

    @Test void retriesTransportFailure() {
        replies = List.of(new Reply(0, ""), new Reply(200, "{\"code\":0}"));
        LarkClient.send(webhook, "audit");
        assertEquals(2, requests.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"code\":9499}", "{\"code\":19021}", "{\"code\":19024,\"StatusCode\":0}", "{\"code\":99991403}"})
    void neverRetriesPermanentBusinessErrorsOrUsesLegacySuccessToOverrideModernFailure(String receipt) {
        replies = List.of(new Reply(200, receipt));
        assertThrows(MSException.class, () -> LarkClient.send(webhook, "audit"));
        assertEquals(1, requests.size());
    }

    @Test void neverRetriesPermanentHttpErrors() {
        replies = List.of(new Reply(400, "opaque-token sensitive-context"));
        MSException error = assertThrows(MSException.class, () -> LarkClient.send(webhook, "sensitive-context"));
        assertEquals(1, requests.size());
        assertTrue(error.getMessage().contains("HTTP 400"));
        assertFalse(error.getMessage().contains("opaque-token"));
        assertFalse(error.getMessage().contains("sensitive-context"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not-json", "{\"code\":\"0\"}", "{\"code\":0.1}", "{\"code\":null,\"StatusCode\":0}"})
    void invalidAcknowledgementCannotBeMistakenForSuccess(String receipt) {
        replies = List.of(new Reply(200, receipt));
        MSException error = assertThrows(MSException.class, () -> LarkClient.send(webhook, "audit"));
        assertEquals(3, requests.size());
        assertTrue(error.getMessage().contains("invalid Feishu acknowledgement"));
    }

    @Test void doesNotFollowRedirectsWithNotificationContent() {
        replies = List.of(new Reply(302, "{}", Map.of("Location", "/redirected")));
        assertThrows(MSException.class, () -> LarkClient.send(webhook, "sensitive-context"));
        assertEquals(1, requests.size());
        assertEquals(0, redirectedRequests.get());
    }

    @Test void oversizedResponseFailsWithoutIncludingItsContent() {
        replies = List.of(new Reply(200, "sensitive-context".repeat(600)));
        MSException error = assertThrows(MSException.class, () -> LarkClient.send(webhook, "audit"));
        assertEquals(1, requests.size());
        assertTrue(error.getMessage().contains("oversized"));
        assertFalse(error.getMessage().contains("sensitive-context"));
    }

    @Test void retryAfterHasBoundedDelayAndNeverRemovesBackoff() {
        assertEquals(1000, LarkClient.retryDelayMillis(1, null));
        assertEquals(2000, LarkClient.retryDelayMillis(2, "0"));
        assertEquals(10000, LarkClient.retryDelayMillis(1, "9223372036854775807"));
        assertEquals(10000, LarkClient.retryDelayMillis(1, "Wed, 01 Jan 2031 00:00:00 GMT"));
        assertEquals(1000, LarkClient.retryDelayMillis(1, "Wed, 01 Jan 2020 00:00:00 GMT"));
        assertEquals(2000, LarkClient.retryDelayMillis(2, "invalid"));
    }

    @Test void interruptionStopsBeforeSendingAndKeepsTheInterruptFlag() {
        replies = List.of(new Reply(200, "{\"code\":0}"));
        Thread.currentThread().interrupt();
        try {
            MSException error = assertThrows(MSException.class, () -> LarkClient.send(webhook, "audit"));
            assertTrue(error.getMessage().contains("interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertEquals(0, requests.size());
        } finally { Thread.interrupted(); }
    }
}
