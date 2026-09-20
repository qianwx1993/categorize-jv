package com.qian.categorize.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.qian.categorize.config.ConfigLoader;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ScannedFile;
import com.qian.categorize.util.HttpsTrust;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 简单 HTTP 模型适配：POST {"name": ..., "path": ...}，
 * 期望响应 {"hits": [{"dimension": "series", "category": "教父"}]}。
 * 密钥从配置指定的环境变量读取，放入 Authorization: Bearer 头。
 * 调用失败时返回空列表（降级为未分类），不阻断整体流程。
 */
public class HttpModelClient implements ModelClient {

    private static final double MODEL_CONFIDENCE = 0.6d;
    private static final int TIMEOUT_MS = 15000;

    private final String endpoint;
    private final String apiKey;

    public HttpModelClient(String endpoint, String apiKeyEnv) {
        this(endpoint, null, apiKeyEnv);
    }

    /**
     * @param explicitApiKey 配置里直接写的明文 key，非空时优先；否则从 apiKeyEnv 环境变量取
     */
    public HttpModelClient(String endpoint, String explicitApiKey, String apiKeyEnv) {
        this.endpoint = endpoint;
        String key = explicitApiKey == null ? "" : explicitApiKey.trim();
        if (key.isEmpty() && apiKeyEnv != null && !apiKeyEnv.isEmpty()) {
            String env = System.getenv(apiKeyEnv);
            key = env == null ? "" : env;
        }
        this.apiKey = key;
    }

    @Override
    public List<CategoryHit> suggest(ScannedFile file) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            // 老版本内置 JRE 的 cacerts 可能缺新根证书，追加信任 Windows 系统证书库
            HttpsTrust.apply(conn);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (apiKey != null && !apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            String body = ConfigLoader.mapper().writeValueAsString(
                    Collections.singletonMap("name", file.getName()));
            OutputStream os = conn.getOutputStream();
            os.write(body.getBytes(StandardCharsets.UTF_8));
            os.close();

            if (conn.getResponseCode() / 100 != 2) {
                return Collections.emptyList();
            }
            JsonNode root = ConfigLoader.mapper().readTree(conn.getInputStream());
            List<CategoryHit> hits = new ArrayList<CategoryHit>();
            JsonNode arr = root.get("hits");
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    JsonNode dim = n.get("dimension");
                    JsonNode cat = n.get("category");
                    if (dim != null && cat != null) {
                        hits.add(new CategoryHit(dim.asText(), cat.asText(),
                                MODEL_CONFIDENCE, "MODEL"));
                    }
                }
            }
            return hits;
        } catch (IOException e) {
            return Collections.emptyList();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
