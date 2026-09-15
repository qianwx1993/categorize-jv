package com.qian.categorize.ops;

import com.qian.categorize.classifier.ClassificationEngine;
import com.qian.categorize.classifier.NullModelClient;
import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.BatchManifest;
import com.qian.categorize.model.CategorizeReport;
import com.qian.categorize.model.ManifestEntry;
import com.qian.categorize.model.ScannedFile;
import com.qian.categorize.report.ReportManager;
import com.qian.categorize.scanner.FileScanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApplyRollbackIntegrationTest {

    private AppConfig baseConfig(Path root) {
        AppConfig config = new AppConfig();
        config.setDimensions(Collections.singletonList("series"));
        config.setOutputDir(root.resolve("categorized").toString());
        config.getRules().put("series",
                Collections.singletonMap("教父系列", Arrays.asList("教父", "godfather")));
        return config;
    }

    @Test
    void applyThenRollbackRestoresEverything(@TempDir Path root) throws Exception {
        byte[] content = {1, 2, 3, 9, 8, 7};
        Path source = root.resolve("movies/教父1.mp4");
        Files.createDirectories(source.getParent());
        Files.write(source, content);

        AppConfig config = baseConfig(root);
        CategorizeReport report = classify(root, config);
        assertEquals("教父系列", report.getEntries().get(0).getCategory());

        FileMover mover = new FileMover(config);
        BatchManifest manifest = mover.newBatch(report);
        mover.apply(report, manifest);

        // 文件已移动到分类目录
        assertFalse(Files.exists(source));
        Path target = new File(manifest.getEntries().get(0).getTargetPath()).toPath();
        assertTrue(Files.exists(target));
        assertArrayEquals(content, Files.readAllBytes(target));
        assertEquals(FileMover.DONE, manifest.getEntries().get(0).getStatus());
        assertTrue(manifest.getEntries().get(0).getSha256().length() == 64);

        // 回滚
        RollbackService.RollbackResult result = new RollbackService(config).rollback(manifest);
        assertEquals(1, result.restored.size());
        assertTrue(Files.exists(source));
        assertFalse(Files.exists(target));
        assertArrayEquals(content, Files.readAllBytes(source));
    }

    @Test
    void nameConflictRenamesNeverOverwrites(@TempDir Path root) throws Exception {
        byte[] original = {9, 9, 9};
        // 目标位置已存在同名文件
        Path existing = root.resolve("categorized/教父系列/教父1.mp4");
        Files.createDirectories(existing.getParent());
        Files.write(existing, original);

        Path source = root.resolve("incoming/教父1.mp4");
        Files.createDirectories(source.getParent());
        Files.write(source, new byte[]{1, 2, 3});

        AppConfig config = baseConfig(root);
        CategorizeReport report = classify(root, config);
        // 扫描会把已存在的目标文件也扫出来（同根目录），排除掉，只移动 incoming
        assertEquals(2, report.getEntries().size());
        for (com.qian.categorize.model.ReportEntry e : report.getEntries()) {
            if (e.getPath().contains("categorized")) {
                e.setExcluded(true);
            }
        }
        BatchManifest manifest = new FileMover(config).newBatch(report);
        List<ManifestEntry> results = new FileMover(config).apply(report, manifest);

        ManifestEntry done = results.get(0);
        Path moved = new File(done.getTargetPath()).toPath();
        assertTrue(moved.getFileName().toString().contains("(2)"),
                "冲突时应重命名: " + moved.getFileName());
        // 已有文件内容不被覆盖
        assertArrayEquals(original, Files.readAllBytes(existing));
        assertArrayEquals(new byte[]{1, 2, 3}, Files.readAllBytes(moved));
    }

    private CategorizeReport classify(Path root, AppConfig config) throws Exception {
        List<ScannedFile> files = new FileScanner(config).scan(root.toFile());
        ClassificationEngine engine = new ClassificationEngine(config, new NullModelClient());
        return new ReportManager(config, engine).build(root.toFile(), files);
    }
}
