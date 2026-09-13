package com.qian.categorize.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qian.categorize.config.ConfigLoader;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ScannedFile;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI Chat Completions 兼容协议适配，可直连：
 * <ul>
 *   <li>OpenAI：https://api.openai.com/v1/chat/completions</li>
 *   <li>阿里通义/DeepSeek/Kimi 等兼容网关（填各自的 /chat/completions 地址）</li>
 *   <li>Ollama：http://localhost:11434/v1/chat/completions（0.1.32+ 内置兼容端点）</li>
 * </ul>
 * 让模型只返回 JSON：{"dimension": "维度", "category": "分类名"}，无法判断时 dimension 为 null。
 * 调用失败/解析失败返回空列表（降级为未分类），不阻断流程。
 */
public class OpenAIModelClient implements ModelClient {

    static final double MODEL_CONFIDENCE = 0.6d;
    private static final int TIMEOUT_MS = 30000;
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final List<String> dimensions;

    public OpenAIModelClient(String endpoint, String model, String apiKeyEnv,
                             List<String> dimensions) {
        this(endpoint, model, null, apiKeyEnv, dimensions);
    }

    /**
     * @param explicitApiKey 配置里直接写的明文 key，非空时优先；否则从 apiKeyEnv 环境变量取
     */
    public OpenAIModelClient(String endpoint, String model, String explicitApiKey,
                             String apiKeyEnv, List<String> dimensions) {
        this.endpoint = endpoint;
        this.model = model;
        String key = explicitApiKey == null ? "" : explicitApiKey.trim();
        if (key.isEmpty() && apiKeyEnv != null && !apiKeyEnv.isEmpty()) {
            key = nullToEmpty(System.getenv(apiKeyEnv));
        }
        this.apiKey = key;
        this.dimensions = dimensions;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public List<CategoryHit> suggest(ScannedFile file) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(endpoint).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (!apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            OutputStream os = conn.getOutputStream();
            os.write(buildRequestBody(file).getBytes(StandardCharsets.UTF_8));
            os.close();

            if (conn.getResponseCode() / 100 != 2) {
                return Collections.emptyList();
            }
            JsonNode root = ConfigLoader.mapper().readTree(conn.getInputStream());
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode()) {
                return Collections.emptyList();
            }
            return parseContent(contentNode.asText());
        } catch (IOException e) {
            return Collections.emptyList();
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 构造 chat/completions 请求体。 */
    String buildRequestBody(ScannedFile file) {
        ObjectNode root = ConfigLoader.mapper().createObjectNode();
        root.put("model", model);
        root.put("temperature", 0);
        ArrayNode messages = root.putArray("messages");

        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", "你是文件分类助手。根据文件名判断它的分类。\n"
                + "可选维度（按优先级）：" + dimensions + "。\n"
                + "只返回一个 JSON 对象，不要输出任何其他内容：\n"
                + "- 能判断时：{\"dimension\": \"上面某个维度英文标识\", \"category\": \"中文分类名\"}\n"
                + "- 无法判断时：{\"dimension\": null, \"category\": null}");

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", "文件名：" + file.getName());
        return root.toString();
    }

    /** 从模型回复中提取 JSON；兼容 ```json 代码块包裹。 */
    List<CategoryHit> parseContent(String content) {
        String json = stripCodeFence(content);
        Matcher m = JSON_OBJECT.matcher(json);
        if (!m.find()) {
            return Collections.emptyList();
        }
        try {
            JsonNode node = ConfigLoader.mapper().readTree(m.group());
            JsonNode dimNode = node.get("dimension");
            JsonNode catNode = node.get("category");
            if (dimNode == null || dimNode.isNull()
                    || catNode == null || catNode.asText().trim().isEmpty()) {
                return Collections.emptyList();
            }
            String dimension = dimNode.asText().trim();
            if (!dimensions.contains(dimension)) {
                // 模型返回了未约定的维度标识，大小写不敏感再匹配一次
                for (String d : dimensions) {
                    if (d.equalsIgnoreCase(dimension)) {
                        dimension = d;
                        break;
                    }
                }
                if (!dimensions.contains(dimension)) {
                    return Collections.emptyList();
                }
            }
            List<CategoryHit> hits = new ArrayList<CategoryHit>();
            hits.add(new CategoryHit(dimension, catNode.asText().trim(),
                    MODEL_CONFIDENCE, "MODEL"));
            return hits;
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```[jJ]?[sS]?[oO]?[nN]?\\s*", "");
            int end = trimmed.lastIndexOf("```");
            if (end >= 0) {
                trimmed = trimmed.substring(0, end);
            }
        }
        return trimmed;
    }
}
