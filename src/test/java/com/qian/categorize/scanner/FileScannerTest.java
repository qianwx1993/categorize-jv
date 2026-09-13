package com.qian.categorize.scanner;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.ScannedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileScannerTest {

    private Set<String> names(List<ScannedFile> files) {
        Set<String> names = new HashSet<String>();
        for (ScannedFile f : files) {
            names.add(f.getName());
        }
        return names;
    }

    @Test
    void recursiveScanWithDefaults(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("a.mp4"));
        Path sub = Files.createDirectories(root.resolve("sub/deep"));
        Files.createFile(sub.resolve("b.mkv"));
        Files.createFile(root.resolve(".DS_Store"));

        AppConfig config = new AppConfig();
        config.getIgnoreNames().add(".DS_Store");

        List<ScannedFile> files = new FileScanner(config).scan(root.toFile());

        assertEquals(new HashSet<String>(Arrays.asList("a.mp4", "b.mkv")), names(files));
    }

    @Test
    void extensionWhitelist(@TempDir Path root) throws Exception {
        Files.createFile(root.resolve("a.mp4"));
        Files.createFile(root.resolve("b.txt"));

        AppConfig config = new AppConfig();
        config.setExtensionMode("WHITELIST");
        config.getExtensions().add(".mp4");

        List<ScannedFile> files = new FileScanner(config).scan(root.toFile());
        assertEquals(new HashSet<String>(Arrays.asList("a.mp4")), names(files));
    }

    @Test
    void minSizeFilter(@TempDir Path root) throws Exception {
        Files.write(root.resolve("small.txt"), new byte[]{1});
        Files.write(root.resolve("big.txt"), new byte[]{1, 2, 3, 4});

        AppConfig config = new AppConfig();
        config.setMinSizeBytes(3);

        List<ScannedFile> files = new FileScanner(config).scan(root.toFile());
        assertEquals(new HashSet<String>(Arrays.asList("big.txt")), names(files));
        assertTrue(new File(root.toFile(), "small.txt").exists());
    }
}
