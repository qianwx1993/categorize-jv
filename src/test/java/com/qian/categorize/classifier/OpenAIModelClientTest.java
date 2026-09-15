package com.qian.categorize.classifier;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ScannedFile;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpenAIModelClientTest {

    private HttpServer server;
    private String url;
    private final AtomicReference<String> authHeader = new AtomicReference<String>();
    private final AtomicReference<String> requestBody = new AtomicReference<String>();
    private volatile String responseBody = "{}";
    private volatile int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                Headers headers = exchange.getRequestHeaders();
                authHeader.set(headers.getFirst("Authorization"));
                byte[] req = readAll(exchange);
                requestBody.set(new String(req, StandardCharsets.UTF_8));
                byte[] resp = responseBody.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(responseStatus, resp.length);
                OutputStream os = exchange.getResponseBody();
                os.write(resp);
                os.close();
            }
        });
        server.start();
        url = "http://localhost:" + server.getAddress().getPort() + "/v1/chat/completions";
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private OpenAIModelClient client() {
        return new OpenAIModelClient(url, "test-model", null,
                Arrays.asList("series", "actor", "theme"));
    }

    private ScannedFile file() {
        return new ScannedFile("/tmp/教父2.mp4", "教父2.mp4", 100, 0);
    }

    @Test
    void parsesJsonWrappedInCodeFence() {
        responseBody = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"```json\\n{\\\"dimension\\\": \\\"series\\\", "
                + "\\\"category\\\": \\\"教父系列\\\"}\\n```\"}}]}";

        List<CategoryHit> hits = client().suggest(file());

        assertEquals(1, hits.size());
        assertEquals("series", hits.get(0).getDimension());
        assertEquals("教父系列", hits.get(0).getCategory());
        assertEquals(0.6, hits.get(0).getConfidence(), 0.0001);
    }

    @Test
    void parsesMultiDimensionHitsArray() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"hits\\\":[{\\\"dimension\\\":\\\"series\\\",\\\"category\\\":\\\"教父系列\\\"},"
                + "{\\\"dimension\\\":\\\"actor\\\",\\\"category\\\":\\\"阿尔·帕西诺\\\"},"
                + "{\\\"dimension\\\":\\\"color\\\",\\\"category\\\":\\\"无效维度被过滤\\\"}]}\"}}]}";

        List<CategoryHit> hits = client().suggest(file());

        assertEquals(2, hits.size(), "无效维度的条目应被过滤");
        assertEquals("series", hits.get(0).getDimension());
        assertEquals("教父系列", hits.get(0).getCategory());
        assertEquals("actor", hits.get(1).getDimension());
        assertEquals("阿尔·帕西诺", hits.get(1).getCategory());
    }

    @Test
    void emptyHitsArrayYieldsEmpty() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"{\\\"hits\\\":[]}\"}}]}";
        assertTrue(client().suggest(file()).isEmpty());
    }

    @Test
    void nullDimensionYieldsEmpty() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"dimension\\\": null, \\\"category\\\": null}\"}}]}";

        assertTrue(client().suggest(file()).isEmpty());
    }

    @Test
    void unknownDimensionRejected() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"dimension\\\": \\\"color\\\", \\\"category\\\": \\\"红色\\\"}\"}}]}";

        assertTrue(client().suggest(file()).isEmpty());
    }

    @Test
    void dimensionCaseInsensitiveFallback() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"dimension\\\": \\\"Series\\\", \\\"category\\\": \\\"教父系列\\\"}\"}}]}";

        List<CategoryHit> hits = client().suggest(file());
        assertEquals(1, hits.size());
        assertEquals("series", hits.get(0).getDimension());
    }

    @Test
    void serverErrorDegradesToEmpty() {
        responseStatus = 500;
        assertTrue(client().suggest(file()).isEmpty());
    }

    @Test
    void customSystemPromptIsUsedInRequestBody() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"{\\\"hits\\\":[]}\"}}]}";

        OpenAIModelClient c = new OpenAIModelClient(url, "test-model", null,
                null, Arrays.asList("series", "actor", "theme"),
                "这是自定义提示词 [series, actor, theme]");
        c.suggest(file());

        assertTrue(requestBody.get().contains("这是自定义提示词"));
    }

    @Test
    void requestCarriesModelNameAndFileName() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"dimension\\\": null, \\\"category\\\": null}\"}}]}";

        client().suggest(file());

        assertTrue(requestBody.get().contains("\"model\":\"test-model\""));
        assertTrue(requestBody.get().contains("教父2.mp4"));
    }

    @Test
    void explicitApiKeySentAsBearer() {
        responseBody = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"dimension\\\": null, \\\"category\\\": null}\"}}]}";
        OpenAIModelClient c = new OpenAIModelClient(url, "m", "sk-direct-123",
                null, Arrays.asList("series", "actor", "theme"));
        c.suggest(file());
        assertEquals("Bearer sk-direct-123", authHeader.get());
    }

    @Test
    void apiKeySentAsBearer() throws IOException {
        // 用一个固定环境变量名不方便（无法在测试中设环境变量），直接验证无 key 时不带 Authorization
        responseBody = "{\"choices\":[{\"message\":{\"content\":"
                + "\"{\\\"dimension\\\": null, \\\"category\\\": null}\"}}]}";
        client().suggest(file());
        assertTrue(authHeader.get() == null, "未配置 key 时不应发送 Authorization 头");
    }

    private static byte[] readAll(HttpExchange exchange) throws IOException {
        byte[] buffer = new byte[8192];
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        java.io.InputStream in = exchange.getRequestBody();
        int n;
        while ((n = in.read(buffer)) != -1) {
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
