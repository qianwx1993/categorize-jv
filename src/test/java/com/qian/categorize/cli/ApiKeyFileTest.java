package com.qian.categorize.cli;

import com.qian.categorize.config.AppConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyFileTest {

    private AppConfig configWithKeyFile(String fileRef) {
        AppConfig config = new AppConfig();
        config.getModel().setApiKey("");
        config.getModel().setApiKeyFile(fileRef);
        return config;
    }

    @Test
    void readsFirstNonCommentLineRelativeToConfig(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("model-api-key.txt"),
                ("# 注释行\n\n  ark-abc123  \n# 另一条注释\n").getBytes(StandardCharsets.UTF_8));
        File configFile = dir.resolve("categorize-config.json").toFile();
        AppConfig config = configWithKeyFile("model-api-key.txt");

        CommandLine.loadApiKeyFile(config, configFile);

        assertEquals("ark-abc123", config.getModel().getApiKey(), "应取首个非注释行并 trim");
    }

    @Test
    void explicitPlaintextWinsOverFile(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("k.txt"), "ark-from-file".getBytes(StandardCharsets.UTF_8));
        AppConfig config = configWithKeyFile("k.txt");
        config.getModel().setApiKey("ark-plain");

        CommandLine.loadApiKeyFile(config, dir.resolve("categorize-config.json").toFile());

        assertEquals("ark-plain", config.getModel().getApiKey());
    }

    @Test
    void missingFileLeavesKeyEmpty(@TempDir Path dir) throws Exception {
        AppConfig config = configWithKeyFile("not-exist.txt");
        CommandLine.loadApiKeyFile(config, dir.resolve("categorize-config.json").toFile());
        assertTrue(config.getModel().getApiKey().isEmpty());
    }

    @Test
    void commentOnlyFileLeavesKeyEmpty(@TempDir Path dir) throws Exception {
        Files.write(dir.resolve("k.txt"), "# 只有注释\n".getBytes(StandardCharsets.UTF_8));
        AppConfig config = configWithKeyFile("k.txt");
        CommandLine.loadApiKeyFile(config, dir.resolve("categorize-config.json").toFile());
        assertTrue(config.getModel().getApiKey().isEmpty());
    }
}
