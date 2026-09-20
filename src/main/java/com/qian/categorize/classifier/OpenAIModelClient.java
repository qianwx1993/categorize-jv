package com.qian.categorize.classifier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAI Chat Completions 兼容协议适配，可直连：
 * <ul>
 *   <li>OpenAI：https://api.openai.com/v1/chat/completions</li>
 *   <li>阿里通义/DeepSeek/Kimi 等兼容网关（填各自的 /chat/completions 地址）</li>
 *   <li>Ollama：http://localhost:11434/v1/chat/completions（0.1.32+ 内置兼容端点）</li>
 * </ul>
 *
 * <p>分类采用<b>整批归纳</b>（{@link #suggestAll(List)}）：一次请求把全部文件名（带编号）
 * 交给模型，要求它先通观全量文件再归纳共用类目、逐编号返回，避免逐文件判断时凭空造出
 * 「婚礼」「美食」这类碎片化、只含单个生活视频的类目；文件过多时分批请求。
 * 完全无法判断时对应文件 hits 为空数组（上层归入「未分类」，保持原位）。
 * 调用失败/解析失败返回空结果（降级为未分类），不阻断流程。
 *
 * <p>单文件 {@link #suggest(ScannedFile)} 保留兼容，响应兼容旧格式
 * {"dimension":"维度","category":"分类名"} 与 {"hits":[...]}。
 */
public class OpenAIModelClient implements ModelClient {

    static final double MODEL_CONFIDENCE = 0.6d;
    private static final int TIMEOUT_MS = 60000;
    /** 单次请求携带的文件数上限：控制 prompt 长度，超出自动分批。 */
    static final int BATCH_SIZE = 80;
    private static final Pattern JSON_OBJECT = Pattern.compile("\\{.*\\}", Pattern.DOTALL);

    private final String endpoint;
    private final String model;
    private final String apiKey;
    private final List<String> dimensions;
    /** 自定义系统提示词；null/空时使用内置提示词 */
    private final String systemPromptOverride;

    public OpenAIModelClient(String endpoint, String model, String apiKeyEnv,
                             List<String> dimensions) {
        this(endpoint, model, null, apiKeyEnv, dimensions, null);
    }

    public OpenAIModelClient(String endpoint, String model, String explicitApiKey,
                             String apiKeyEnv, List<String> dimensions) {
        this(endpoint, model, explicitApiKey, apiKeyEnv, dimensions, null);
    }

    /**
     * @param explicitApiKey 配置里直接写的明文 key，非空时优先；否则从 apiKeyEnv 环境变量取
     * @param systemPromptOverride 从外部文件读取的自定义系统提示词；为空则用内置提示词
     */
    public OpenAIModelClient(String endpoint, String model, String explicitApiKey,
                             String apiKeyEnv, List<String> dimensions,
                             String systemPromptOverride) {
        this.endpoint = endpoint;
        this.model = model;
        String key = explicitApiKey == null ? "" : explicitApiKey.trim();
        if (key.isEmpty() && apiKeyEnv != null && !apiKeyEnv.isEmpty()) {
            key = nullToEmpty(System.getenv(apiKeyEnv));
        }
        this.apiKey = key;
        this.dimensions = dimensions;
        this.systemPromptOverride = systemPromptOverride;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public List<CategoryHit> suggest(ScannedFile file) {
        String content = postChat("文件名：" + file.getName());
        if (content == null) {
            return Collections.emptyList();
        }
        return parseContent(content);
    }

    /**
     * 批量归类：模型一次看到一批文件名，返回结果与入参同序一一对应。
     * 模型漏回某个编号或整批调用失败时，该位置降级为空命中（未分类），不为 null。
     */
    @Override
    public List<List<CategoryHit>> suggestAll(List<ScannedFile> files) {
        List<List<CategoryHit>> all = new ArrayList<List<CategoryHit>>(files.size());
        for (int start = 0; start < files.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, files.size());
            List<ScannedFile> batch = files.subList(start, end);
            Map<Integer, List<CategoryHit>> parsed = Collections.emptyMap();
            String content = postChat(buildBatchUserContent(batch));
            if (content != null) {
                parsed = parseBatchContent(content);
            }
            for (int i = 0; i < batch.size(); i++) {
                // 模型看到的编号从 1 开始
                List<CategoryHit> hits = parsed.get(i + 1);
                all.add(hits == null ? Collections.<CategoryHit>emptyList() : hits);
            }
        }
        return all;
    }

    /** 组装批量用户消息：编号 + 文件名清单。 */
    String buildBatchUserContent(List<ScannedFile> batch) {
        StringBuilder sb = new StringBuilder();
        sb.append("请归类下面 ").append(batch.size()).append(" 个文件（编号从 1 开始）：\n");
        for (int i = 0; i < batch.size(); i++) {
            sb.append(i + 1).append(". ")
                    .append(sanitizeName(batch.get(i).getName())).append('\n');
        }
        sb.append("严格按系统提示词要求的 JSON 格式，为每个编号都给出结果，不要输出其他内容。");
        return sb.toString();
    }

    /** 文件名中的换行/制表符会破坏编号清单，统一压成空格。 */
    private static String sanitizeName(String name) {
        if (name == null) {
            return "";
        }
        return name.replaceAll("[\\r\\n\\t]", " ").trim();
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    /** 发送一次 chat/completions 请求，返回 assistant 文本；任何失败返回 null（降级未分类）。 */
    private String postChat(String userContent) {
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
            if (!apiKey.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            }
            OutputStream os = conn.getOutputStream();
            os.write(buildRequestBody(userContent).getBytes(StandardCharsets.UTF_8));
            os.close();

            if (conn.getResponseCode() / 100 != 2) {
                return null;
            }
            JsonNode root = ConfigLoader.mapper().readTree(conn.getInputStream());
            JsonNode contentNode = root.path("choices").path(0).path("message").path("content");
            if (contentNode.isMissingNode()) {
                return null;
            }
            return contentNode.asText();
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** 构造 chat/completions 请求体。 */
    String buildRequestBody(ScannedFile file) {
        return buildRequestBody("文件名：" + file.getName());
    }

    String buildRequestBody(String userContent) {
        ObjectNode root = ConfigLoader.mapper().createObjectNode();
        root.put("model", model);
        root.put("temperature", 0);
        ArrayNode messages = root.putArray("messages");

        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", systemPrompt());

        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", userContent);
        return root.toString();
    }

    /** 实际发送的系统提示词：外部文件非空时完全采用自定义内容，否则用内置提示词。 */
    String systemPrompt() {
        if (systemPromptOverride != null && !systemPromptOverride.trim().isEmpty()) {
            return systemPromptOverride;
        }
        return defaultSystemPrompt();
    }

    private String defaultSystemPrompt() {
        return "你是文件归类助手。用户会一次给出一批带编号的文件名，"
                + "你必须先通览全部文件名、归纳出本批文件共有的分类，再逐编号判断，"
                + "禁止孤立地盯着单个文件名发挥。\n"
                + "可用分类维度（dimension 字段只能填下列英文标识，按优先级排列）：\n"
                + dimensionGuide()
                + "硬性规则：\n"
                + "1. 只能依据文件名中明确出现的信息判断；信息不足或拿不准的文件 hits 返回空数组，"
                + "宁可留空也不要猜。\n"
                + "2. 分类名必须是能容纳多部作品的集合名词，同一系列所有文件必须使用完全相同的分类名；"
                + "不要给无法成类的单个文件造分类。\n"
                + "3. 私人生活录像不属于上述任何影视维度：婚礼、婚宴、美食、聚餐、旅行、聚会、"
                + "生日、宝宝成长、家庭录像等生活场景一律返回空 hits。\n"
                + "4. 一个文件能判断多个维度时给出多个 hit；所有维度都无法判断时 hits 为空数组。\n"
                + "只返回一个 JSON 对象，不要输出任何解释：\n"
                + "{\"results\":[{\"index\":1,\"hits\":["
                + "{\"dimension\":\"series\",\"category\":\"中文分类名\"}]}]}\n"
                + "- index 与输入编号一一对应，每个编号都必须出现；\n"
                + "- 没有任何命中的文件给 \"hits\":[]。";
    }

    /** 维度英文标识 → 中文含义与边界；配置了内置不认识的维度时按自定义维度兜底。 */
    private String dimensionGuide() {
        StringBuilder sb = new StringBuilder();
        for (String d : dimensions) {
            String desc;
            if ("series".equals(d)) {
                desc = "影视作品的系列/IP 名，必须是能容纳多部作品的集合名词，"
                        + "如「教父系列」「指环王系列」「复仇者联盟系列」";
            } else if ("actor".equals(d)) {
                desc = "影视作品的主演真人姓名，如「汤姆·汉克斯」「阿尔·帕西诺」";
            } else if ("theme".equals(d)) {
                desc = "影视作品的题材大类，如「科幻」「谍战」「动漫」「武侠」";
            } else {
                desc = "自定义维度";
            }
            sb.append("- ").append(d).append("：").append(desc).append("；\n");
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 响应解析
    // ------------------------------------------------------------------

    /** 从模型回复中提取 JSON；兼容 ```json 代码块包裹。 */
    List<CategoryHit> parseContent(String content) {
        JsonNode node = extractJson(content);
        if (node == null) {
            return Collections.emptyList();
        }
        List<CategoryHit> hits = new ArrayList<CategoryHit>();
        JsonNode hitsNode = node.get("hits");
        if (hitsNode != null && hitsNode.isArray()) {
            // 新格式：{"hits":[{"dimension":...,"category":...}, ...]}
            for (JsonNode item : hitsNode) {
                addValidHit(hits, item);
            }
        } else {
            // 兼容旧格式：{"dimension":...,"category":...}
            addValidHit(hits, node);
        }
        return hits;
    }

    /**
     * 解析批量响应 {"results":[{"index":1,"hits":[...]}]}，key 为文件编号（从 1 开始）。
     * 缺少 results / 编号非法的条目直接忽略，上层按缺号降级为未分类。
     */
    Map<Integer, List<CategoryHit>> parseBatchContent(String content) {
        JsonNode node = extractJson(content);
        Map<Integer, List<CategoryHit>> out = new LinkedHashMap<Integer, List<CategoryHit>>();
        if (node == null) {
            return out;
        }
        JsonNode results = node.get("results");
        if (results == null || !results.isArray()) {
            return out;
        }
        for (JsonNode item : results) {
            JsonNode idxNode = item.get("index");
            JsonNode hitsNode = item.get("hits");
            if (idxNode == null || !idxNode.isNumber() || hitsNode == null
                    || !hitsNode.isArray()) {
                continue;
            }
            List<CategoryHit> hits = new ArrayList<CategoryHit>();
            for (JsonNode h : hitsNode) {
                addValidHit(hits, h);
            }
            out.put(idxNode.asInt(), hits);
        }
        return out;
    }

    private static JsonNode extractJson(String content) {
        String json = stripCodeFence(content);
        Matcher m = JSON_OBJECT.matcher(json);
        if (!m.find()) {
            return null;
        }
        try {
            return ConfigLoader.mapper().readTree(m.group());
        } catch (IOException e) {
            return null;
        }
    }

    /** 校验并加入一条命中：维度必须在配置的维度列表内（大小写不敏感），分类名非空。 */
    private void addValidHit(List<CategoryHit> hits, JsonNode item) {
        JsonNode dimNode = item.get("dimension");
        JsonNode catNode = item.get("category");
        if (dimNode == null || dimNode.isNull() || catNode == null
                || catNode.asText().trim().isEmpty()) {
            return;
        }
        String dimension = dimNode.asText().trim();
        if (!dimensions.contains(dimension)) {
            for (String d : dimensions) {
                if (d.equalsIgnoreCase(dimension)) {
                    dimension = d;
                    break;
                }
            }
            if (!dimensions.contains(dimension)) {
                return;
            }
        }
        hits.add(new CategoryHit(dimension, catNode.asText().trim(),
                MODEL_CONFIDENCE, "MODEL"));
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
