package com.qian.categorize.config;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;

/**
 * 加载 JSON 配置；未指定配置文件时使用代码内置默认配置。
 * 允许配置文件中使用 // 行注释（JSONC），未知字段忽略不报错。
 */
public final class ConfigLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(JsonReadFeature.ALLOW_JAVA_COMMENTS.mappedFeature(), true);

    private ConfigLoader() {
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    public static AppConfig load(File configFile) throws IOException {
        if (configFile == null || !configFile.exists()) {
            return defaultConfig();
        }
        return MAPPER.readValue(configFile, AppConfig.class);
    }

    /** 代码内置默认配置：仅在没有任何外部配置文件时兜底（模型默认不启用）。 */
    public static AppConfig defaultConfig() {
        AppConfig config = new AppConfig();
        config.setDimensions(Arrays.asList("series", "actor", "theme"));
        config.getIgnoreNames().add(".DS_Store");
        config.getModel().setEnabled(false);
        return config;
    }
}
