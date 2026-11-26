package io.metersphere.system.notice.utils;

import io.metersphere.sdk.exception.MSException;
import io.metersphere.sdk.util.JSON;
import io.metersphere.sdk.util.LogUtils;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.io.CloseMode;
import org.apache.hc.core5.util.Timeout;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class LarkClient {
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_RESPONSE_BYTES = 8192;
    private static final long MAX_RETRY_DELAY_MILLIS = 10000;
    // Feishu documents these as temporary service errors or message/API rate limits.
    private static final Set<Long> RETRYABLE_CODES = Set.of(5000L, 11232L, 11233L, 99991400L);

    private record Delivery(boolean accepted, boolean retryable, String reason, String retryAfter) { }

    public static void send(String webhook, String context) {
        URI target;
        try {
            target = URI.create(webhook);
            if (!Set.of("http", "https").contains(target.getScheme()) || target.getHost() == null) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException invalid) {
            throw new MSException("Feishu notification failed: invalid webhook address");
        }
        String payload = JSON.toJSONString(Map.of("msg_type", "text",
                "content", Map.of("text", Objects.requireNonNullElse(context, ""))));
        CloseableHttpClient client = HttpClients.custom()
                .disableAutomaticRetries() // The explicit loop owns the attempt limit, including HTTP 503.
                .disableRedirectHandling()
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectionRequestTimeout(Timeout.ofSeconds(3))
                        .setConnectTimeout(Timeout.ofSeconds(5))
                        .setResponseTimeout(Timeout.ofSeconds(10)).build())
                .build();
        try {
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                checkInterrupted(attempt - 1);
                Delivery delivery;
                try {
                    delivery = attempt(client, target, payload);
                } catch (IOException error) {
                    boolean retryable = !(error instanceof SSLHandshakeException || error instanceof SSLPeerUnverifiedException);
                    // Transport exception messages can contain the secret webhook URL; never retain the cause.
                    delivery = new Delivery(false, retryable, "transport error (" + error.getClass().getSimpleName() + ")", null);
                } catch (RuntimeException error) {
                    delivery = new Delivery(false, false, "HTTP client error", null);
                }
                if (delivery.accepted()) {
                    LogUtils.info("Feishu notification accepted after {} attempt(s)", attempt);
                    return;
                }
                checkInterrupted(attempt);
                if (!delivery.retryable() || attempt == MAX_ATTEMPTS) {
                    throw new MSException("Feishu notification failed after " + attempt + " attempt(s): " + delivery.reason());
                }
                LogUtils.warn("Feishu notification retry after attempt " + attempt + "/" + MAX_ATTEMPTS + ": " + delivery.reason());
                try {
                    Thread.sleep(retryDelayMillis(attempt, delivery.retryAfter()));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    checkInterrupted(attempt);
                }
            }
        } finally {
            client.close(CloseMode.IMMEDIATE);
        }
    }

    private static Delivery attempt(CloseableHttpClient client, URI target, String payload) throws IOException {
        HttpPost post = new HttpPost(target);
        post.setEntity(new StringEntity(payload, ContentType.APPLICATION_JSON));
        CloseableHttpResponse response = client.execute(post);
        try {
            int status = response.getCode();
            if (status < 200 || status >= 300) {
                String retryAfter = response.getFirstHeader("Retry-After") == null ? null : response.getFirstHeader("Retry-After").getValue();
                return new Delivery(false, status == 408 || status == 429 || (status >= 500 && status <= 599),
                        "HTTP " + status, retryAfter);
            }
            if (response.getEntity() == null) { return invalidResponse(); }
            byte[] bytes = response.getEntity().getContent().readNBytes(MAX_RESPONSE_BYTES + 1);
            if (bytes.length > MAX_RESPONSE_BYTES) {
                return new Delivery(false, false, "oversized Feishu response", null);
            }
            try {
                Map<?, ?> receipt = JSON.parseMap(new String(bytes, StandardCharsets.UTF_8));
                Object rawCode = receipt.containsKey("code") ? receipt.get("code") : receipt.get("StatusCode");
                if (!(rawCode instanceof Number)) { return invalidResponse(); }
                long code = Long.parseLong(rawCode.toString());
                return new Delivery(code == 0, RETRYABLE_CODES.contains(code), "Feishu code " + code, null);
            } catch (RuntimeException malformed) {
                return invalidResponse();
            }
        } finally {
            // Do not drain an unbounded error body or allow a close exception to leak the webhook URL.
            response.close(CloseMode.IMMEDIATE);
        }
    }

    private static Delivery invalidResponse() {
        return new Delivery(false, true, "invalid Feishu acknowledgement", null);
    }

    private static void checkInterrupted(int attempts) {
        if (Thread.currentThread().isInterrupted()) {
            throw new MSException("Feishu notification interrupted after " + attempts + " attempt(s)");
        }
    }

    static long retryDelayMillis(int failedAttempt, String retryAfter) {
        long backoff = failedAttempt == 1 ? 1000 : 2000;
        if (retryAfter == null) { return backoff; }
        long requested;
        try {
            requested = Math.max(0, Math.min(MAX_RETRY_DELAY_MILLIS / 1000, Long.parseLong(retryAfter.trim()))) * 1000;
        } catch (RuntimeException notSeconds) {
            try {
                requested = ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - Instant.now().toEpochMilli();
            } catch (RuntimeException invalid) {
                return backoff;
            }
        }
        return Math.max(backoff, Math.min(MAX_RETRY_DELAY_MILLIS, requested));
    }
}
