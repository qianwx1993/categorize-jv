package com.qian.categorize.cli;

import com.qian.categorize.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SystemPromptFileTest {

    @Test
    void loadsPromptAndReplacesDimensions(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("model-system-prompt.txt"),
                "请按维度分类：{dimensions}".getBytes(StandardCharsets.UTF_8));
        AppConfig config = new AppConfig();
        config.setDimensions(Arrays.asList("series", "actor"));
        config.getModel().setSystemPromptFile("model-system-prompt.txt");

        CommandLine.loadSystemPromptFile(config, dir.resolve("categorize-config.json").toFile());

        assertEquals("请按维度分类：[series, actor]", config.getModel().getSystemPrompt());
    }

    @Test
    void missingFileKeepsBuiltInPrompt(@TempDir Path dir) throws Exception {
        AppConfig config = new AppConfig();
        config.getModel().setSystemPromptFile("missing.txt");

        CommandLine.loadSystemPromptFile(config, dir.resolve("categorize-config.json").toFile());

        assertTrue(config.getModel().getSystemPrompt().isEmpty());
    }
}
