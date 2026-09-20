package com.qian.categorize.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具配置（JSON 格式）。dimensions 的顺序即维度优先级（靠前优先）。
 *
 * <pre>
 * {
 *   "dimensions": ["series", "actor", "theme"],
 *   "outputDir": "categorized",
 *   "ignoreHidden": true,
 *   "ignoreNames": [".DS_Store"],
 *   "minSizeBytes": 0,
 *   "extensionMode": "OFF",            // OFF / WHITELIST / BLACKLIST
 *   "extensions": [],
 *   "conflictStrategy": "RENAME",       // RENAME / SKIP / OVERWRITE
 *   "cleanEmptyDirs": false,
 *   "lowConfidenceThreshold": 0.7,
 *   "rules": { "series": { "教父": ["教父", "Godfather"] } },
 *   "model": { "enabled": false, "provider": "none", "endpoint": "", "apiKeyEnv": "" }
 * }
 * </pre>
 */
public class AppConfig {

    private List<String> dimensions = new ArrayList<String>();
    private String outputDir = "categorized";
    private boolean ignoreHidden = true;
    private List<String> ignoreNames = new ArrayList<String>();
    private long minSizeBytes = 0;
    /** OFF / WHITELIST / BLACKLIST */
    private String extensionMode = "OFF";
    private List<String> extensions = new ArrayList<String>();
    /** RENAME / SKIP / OVERWRITE */
    private String conflictStrategy = "RENAME";
    private boolean cleanEmptyDirs = false;
    /**
     * 是否跟随符号链接/目录联接（Windows junction）进入扫描。默认关闭以防环和误入系统目录；
     * 库文件通过联接挂到其他盘时需要打开。打开后按真实路径去环。
     */
    private boolean followSymlinks = false;
    private double lowConfidenceThreshold = 0.7d;
    /** 独立规则文件路径（相对路径相对于配置文件所在目录）；文件存在时整体替换内联 rules */
    private String rulesFile = "";
    /**
     * 独立模型配置文件（如 model-config.json；相对路径相对于主配置文件所在目录，桌面版在 config/ 子目录）。
     * 文件存在时整体替换内联 model 节点；程序保存主配置时不写/覆盖该文件，供用户直接编辑并保留注释。
     */
    private String modelFile = "";
    /** 维度 -> (分类名 -> 文件名命中关键词列表)，顺序不影响（优先级只看 dimensions） */
    private Map<String, Map<String, List<String>>> rules =
            new LinkedHashMap<String, Map<String, List<String>>>();
    private ModelConfig model = new ModelConfig();

    public List<String> getDimensions() {
        return dimensions;
    }

    public void setDimensions(List<String> dimensions) {
        this.dimensions = dimensions;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public boolean isIgnoreHidden() {
        return ignoreHidden;
    }

    public void setIgnoreHidden(boolean ignoreHidden) {
        this.ignoreHidden = ignoreHidden;
    }

    public List<String> getIgnoreNames() {
        return ignoreNames;
    }

    public void setIgnoreNames(List<String> ignoreNames) {
        this.ignoreNames = ignoreNames;
    }

    public long getMinSizeBytes() {
        return minSizeBytes;
    }

    public void setMinSizeBytes(long minSizeBytes) {
        this.minSizeBytes = minSizeBytes;
    }

    public String getExtensionMode() {
        return extensionMode;
    }

    public void setExtensionMode(String extensionMode) {
        this.extensionMode = extensionMode;
    }

    public List<String> getExtensions() {
        return extensions;
    }

    public void setExtensions(List<String> extensions) {
        this.extensions = extensions;
    }

    public String getConflictStrategy() {
        return conflictStrategy;
    }

    public void setConflictStrategy(String conflictStrategy) {
        this.conflictStrategy = conflictStrategy;
    }

    public boolean isCleanEmptyDirs() {
        return cleanEmptyDirs;
    }

    public boolean isFollowSymlinks() {
        return followSymlinks;
    }

    public void setFollowSymlinks(boolean followSymlinks) {
        this.followSymlinks = followSymlinks;
    }

    public void setCleanEmptyDirs(boolean cleanEmptyDirs) {
        this.cleanEmptyDirs = cleanEmptyDirs;
    }

    public double getLowConfidenceThreshold() {
        return lowConfidenceThreshold;
    }

    public void setLowConfidenceThreshold(double lowConfidenceThreshold) {
        this.lowConfidenceThreshold = lowConfidenceThreshold;
    }

    public String getRulesFile() {
        return rulesFile;
    }

    public void setRulesFile(String rulesFile) {
        this.rulesFile = rulesFile;
    }

    public String getModelFile() {
        return modelFile;
    }

    public void setModelFile(String modelFile) {
        this.modelFile = modelFile;
    }

    public Map<String, Map<String, List<String>>> getRules() {
        return rules;
    }

    public void setRules(Map<String, Map<String, List<String>>> rules) {
        this.rules = rules;
    }

    public ModelConfig getModel() {
        return model;
    }

    public void setModel(ModelConfig model) {
        this.model = model;
    }

    public static class ModelConfig {
        private boolean enabled = true;
        /** none / http(自定义协议) / openai(OpenAI 兼容协议，含 Ollama) */
        private String provider = "none";
        /** http: 完整接口地址；openai: chat/completions 地址，如 https://api.openai.com/v1/chat/completions */
        private String endpoint = "";
        /** openai 协议下使用的模型名，如 gpt-4o-mini / qwen-plus / llama3 */
        private String model = "";
        /** 直接写明文密钥（方便本地使用；配置文件请勿提交/分享） */
        private String apiKey = "";
        /** 或从该环境变量读取密钥；apiKey 非空时优先用 apiKey */
        private String apiKeyEnv = "";
        /** 或从本地文件读取密钥（推荐）：相对路径相对于配置文件所在目录；该文件应加入 .gitignore */
        private String apiKeyFile = "";
        /** 自定义系统提示词文件：相对路径相对于配置文件所在目录；留空用内置提示词。支持 {dimensions} 占位符 */
        private String systemPromptFile = "";
        /** 运行时从 systemPromptFile 读入的提示词，仅存内存，不写回配置 JSON */
        private String systemPrompt = "";

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getApiKeyEnv() {
            return apiKeyEnv;
        }

        public void setApiKeyEnv(String apiKeyEnv) {
            this.apiKeyEnv = apiKeyEnv;
        }

        public String getApiKeyFile() {
            return apiKeyFile;
        }

        public void setApiKeyFile(String apiKeyFile) {
            this.apiKeyFile = apiKeyFile;
        }

        public String getSystemPromptFile() {
            return systemPromptFile;
        }

        public void setSystemPromptFile(String systemPromptFile) {
            this.systemPromptFile = systemPromptFile;
        }

        /** 运行时从文件读入的提示词仅存内存；保存配置时不写回 JSON。 */
        @com.fasterxml.jackson.annotation.JsonIgnore
        public String getSystemPrompt() {
            return systemPrompt;
        }

        public void setSystemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt;
        }
    }
}
