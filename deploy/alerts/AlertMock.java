import com.sun.net.httpserver.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Synthetic HTTP target and notification sink. No host ports or production data. */
public class AlertMock {
    private static final AtomicInteger status = new AtomicInteger(503);
    private static final List<String> notifications = Collections.synchronizedList(new ArrayList<>());
    private static final List<String> notificationAttempts = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger temporaryFailures = new AtomicInteger();
    private static void reply(HttpExchange exchange, int code, String text) throws Exception {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes); exchange.close();
    }
    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 0);
        server.createContext("/", exchange -> {
            try {
                String path = exchange.getRequestURI().getPath();
                if (path.equals("/check")) { reply(exchange, status.get(), "{\"synthetic\":true}"); }
                else if (path.startsWith("/mode/")) { status.set(Integer.parseInt(path.substring(6))); reply(exchange, 200, "{}"); }
                else if (path.equals("/notify-mode/retry")) { temporaryFailures.set(2); reply(exchange, 200, "{}"); }
                else if (path.equals("/notify")) {
                    String payload = new String(exchange.getRequestBody().readNBytes(20000), StandardCharsets.UTF_8);
                    notificationAttempts.add(payload);
                    int remaining = temporaryFailures.getAndUpdate(value -> Math.max(0, value - 1));
                    if (remaining == 2) { reply(exchange, 503, "{}"); }
                    else if (remaining == 1) { reply(exchange, 200, "{\"code\":11232}"); }
                    else { notifications.add(payload); reply(exchange, 200, "{\"code\":0}"); }
                } else if (path.equals("/notifications")) {
                    synchronized(notifications) { reply(exchange, 200, "[" + String.join(",", notifications) + "]"); }
                } else if (path.equals("/notification-attempts")) {
                    synchronized(notificationAttempts) { reply(exchange, 200, "[" + String.join(",", notificationAttempts) + "]"); }
                } else { reply(exchange, 404, "{}"); }
            } catch (Exception error) { exchange.close(); }
        });
        server.start();
    }
}
