package com.qian.categorize.config;

import com.fasterxml.jackson.core.type.TypeReference;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.Map;
import java.util.List;

/**
 * 规则数据加载。规则是会持续增长的数据（含以后人工修正沉淀），
 * 独立于运行配置存放：config.rulesFile 指向的文件存在时整体优先生效，
 * 否则回退到配置中内联的 rules（兼容旧配置）。
 */
public final class Rules {

    public static final TypeReference<Map<String, Map<String, List<String>>>> TYPE =
            new TypeReference<Map<String, Map<String, List<String>>>>() {
            };

    private Rules() {
    }

    /**
     * 解析最终生效的规则并写回 config.setRules(...)。
     *
     * @param configFile 配置文件本身，用于解析 rulesFile 的相对路径；无配置文件时以工作目录为基准
     */
    public static Map<String, Map<String, List<String>>> resolve(AppConfig config, File configFile)
            throws IOException {
        String path = config.getRulesFile();
        if (path == null || path.trim().isEmpty()) {
            return config.getRules();
        }
        File rulesFile = new File(path.trim());
        if (!rulesFile.isAbsolute()) {
            File base = configFile != null && configFile.getParentFile() != null
                    ? configFile.getParentFile() : new File(".").getAbsoluteFile();
            rulesFile = new File(base, path.trim());
        }
        if (!rulesFile.isFile()) {
            return config.getRules();
        }
        Map<String, Map<String, List<String>>> loaded =
                ConfigLoader.mapper().readValue(rulesFile, TYPE);
        return loaded == null ? Collections.<String, Map<String, List<String>>>emptyMap() : loaded;
    }
}
