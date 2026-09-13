package com.qian.categorize.config;

import com.qian.categorize.cli.CommandLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;


import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RulesAndInitTest {

    @Test
    void externalRulesFileOverridesInline(@TempDir Path dir) throws Exception {
        File rulesFile = dir.resolve("my-rules.json").toFile();
        Files.write(rulesFile.toPath(),
                ("{\"series\": {\"我的系列\": [\"自定义关键词\"]}}")
                        .getBytes(StandardCharsets.UTF_8));

        File configFile = dir.resolve("categorize-config.json").toFile();
        Files.write(configFile.toPath(),
                ("{\"rulesFile\": \"my-rules.json\","
                        + " \"rules\": {\"series\": {\"内联分类\": [\"inline\"]}}}")
                        .getBytes(StandardCharsets.UTF_8));

        AppConfig config = ConfigLoader.load(configFile);
        config.setRules(Rules.resolve(config, configFile));

        assertTrue(config.getRules().containsKey("series"));
        assertTrue(config.getRules().get("series").containsKey("我的系列"));
        assertFalse(config.getRules().get("series").containsKey("内联分类"),
                "外部规则文件存在时应整体优先生效");
    }

    @Test
    void missingRulesFileFallsBackToInline(@TempDir Path dir) throws Exception {
        File configFile = dir.resolve("categorize-config.json").toFile();
        Files.write(configFile.toPath(),
                ("{\"rulesFile\": \"not-exist.json\","
                        + " \"rules\": {\"series\": {\"内联分类\": [\"inline\"]}}}")
                        .getBytes(StandardCharsets.UTF_8));

        AppConfig config = ConfigLoader.load(configFile);
        config.setRules(Rules.resolve(config, configFile));

        assertTrue(config.getRules().get("series").containsKey("内联分类"));
    }

    @Test
    void rulesFileSupportsComments(@TempDir Path dir) throws Exception {
        File rulesFile = dir.resolve("rules.json").toFile();
        Files.write(rulesFile.toPath(),
                ("{ // 行注释也能读\n \"theme\": {\"科幻\": [\"sci-fi\"]}}")
                        .getBytes(StandardCharsets.UTF_8));
        File configFile = dir.resolve("categorize-config.json").toFile();
        Files.write(configFile.toPath(),
                "{\"rulesFile\": \"rules.json\"}".getBytes(StandardCharsets.UTF_8));

        AppConfig config = ConfigLoader.load(configFile);
        config.setRules(Rules.resolve(config, configFile));

        assertEquals(Collections.singleton("科幻"), config.getRules().get("theme").keySet());
    }

    @Test
    void initGeneratesEditableConfigAndRules(@TempDir Path dir) throws Exception {
        int code = new CommandLine().run(new String[]{"init", dir.toString()});
        assertEquals(0, code);

        File configFile = dir.resolve("categorize-config.json").toFile();
        File rulesFile = dir.resolve("rules.json").toFile();
        assertTrue(configFile.exists());
        assertTrue(rulesFile.exists());

        // init 出来的文件（带注释）能被直接加载，且 rules 引用生效
        AppConfig config = ConfigLoader.load(configFile);
        assertEquals(Arrays.asList("series", "actor", "theme"), config.getDimensions());
        config.setRules(Rules.resolve(config, configFile));
        assertTrue(config.getRules().get("series").containsKey("教父系列"));

        // 已存在的文件不覆盖
        Files.write(configFile.toPath(), "{}".getBytes(StandardCharsets.UTF_8));
        new CommandLine().run(new String[]{"init", dir.toString()});
        assertEquals("{}", new String(Files.readAllBytes(configFile.toPath()),
                StandardCharsets.UTF_8));
    }
}
