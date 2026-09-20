package com.qian.categorize.ops;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.BatchManifest;
import com.qian.categorize.model.CategorizeReport;
import com.qian.categorize.model.ManifestEntry;
import com.qian.categorize.model.ReportEntry;
import com.qian.categorize.util.Progress;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 按已确认报告执行移动。移动前必须先生成 manifest；
 * 跨磁盘/重命名失败时不留半成品；重复执行支持断点续做（DONE 跳过）。
 * 未分类（{@link ReportEntry#isUncategorized()}）的文件保持原位不移动，
 * 以 KEPT 状态记入 manifest（回滚只处理 DONE，KEPT 无需还原）。
 */
public class FileMover {

    public static final String DONE = "DONE";
    public static final String SKIPPED = "SKIPPED";
    public static final String FAILED = "FAILED";
    /** 未分类保持原位：源路径与目标路径相同，不做任何文件操作。 */
    public static final String KEPT = "KEPT";

    private final AppConfig config;
    private final ConflictStrategy conflictStrategy;

    public FileMover(AppConfig config) {
        this.config = config;
        this.conflictStrategy = ConflictStrategy.fromConfig(config.getConflictStrategy());
    }

    public BatchManifest newBatch(CategorizeReport report) {
        BatchManifest manifest = new BatchManifest();
        manifest.setBatchId("batch-" + new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date())
                + "-" + UUID.randomUUID().toString().substring(0, 8));
        manifest.setCreatedAt(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
        manifest.setRootDir(report.getRootDir());
        manifest.setOutputDir(report.getOutputDir());
        return manifest;
    }

    /**
     * 执行移动并更新 manifest。manifest 中已 DONE 的条目跳过（断点续做）。
     */
    public List<ManifestEntry> apply(CategorizeReport report, BatchManifest manifest)
            throws IOException {
        return apply(report, manifest, Progress.NONE);
    }

    /** 带进度/取消的执行版本：在每个文件之间检查取消标志（当前文件完成后停止，manifest 已落盘）。 */
    public List<ManifestEntry> apply(CategorizeReport report, BatchManifest manifest,
                                     Progress progress) throws IOException {
        List<ManifestEntry> results = new ArrayList<ManifestEntry>();
        int total = report.getEntries().size();
        int index = 0;
        for (ReportEntry entry : report.getEntries()) {
            index++;
            if (progress != null && progress.isCancelled()) {
                break;
            }
            if (entry.isExcluded()) {
                continue;
            }
            if (entry.isUncategorized()) {
                if (progress != null) {
                    progress.progress(index, total, "未分类，保持原位: " + entry.getName());
                }
                // 断点续做时已有 KEPT 记录则直接复用，避免重复登记
                ManifestEntry kept = findExisting(manifest, entry.getPath(), KEPT);
                if (kept == null) {
                    kept = new ManifestEntry(entry.getPath(), entry.getPath(),
                            entry.getSize(), "", KEPT);
                    manifest.getEntries().add(kept);
                }
                results.add(kept);
                continue;
            }
            if (progress != null) {
                progress.progress(index, total, "正在移动: " + entry.getName());
            }
            ManifestEntry existing = findExisting(manifest, entry.getPath(), DONE);
            if (existing != null) {
                results.add(existing);
                continue;
            }

            Path source = new File(entry.getPath()).toPath();
            if (!Files.exists(source)) {
                ManifestEntry me = new ManifestEntry(entry.getPath(), "", entry.getSize(),
                        "", FAILED);
                manifest.getEntries().add(me);
                results.add(me);
                continue;
            }

            Path targetDir = new File(report.getOutputDir(), sanitize(entry.getCategory())).toPath();
            Files.createDirectories(targetDir);
            Path target = targetDir.resolve(source.getFileName().toString());

            if (Files.exists(target) && conflictStrategy == ConflictStrategy.SKIP) {
                ManifestEntry me = new ManifestEntry(source.toString(), target.toString(),
                        entry.getSize(), "", SKIPPED);
                manifest.getEntries().add(me);
                results.add(me);
                continue;
            }
            if (Files.exists(target) && conflictStrategy == ConflictStrategy.RENAME) {
                target = resolveUnique(target);
            }

            String hash;
            try {
                hash = Checksums.sha256(source);
                // 先复制后删除：跨磁盘也安全；OVERWRITE 时允许替换
                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                String movedHash = Checksums.sha256(target);
                if (!hash.equals(movedHash)) {
                    Files.deleteIfExists(target);
                    throw new IOException("移动后校验不一致: " + source);
                }
                Files.delete(source);
            } catch (IOException e) {
                ManifestEntry me = new ManifestEntry(source.toString(),
                        target.toString(), entry.getSize(), "", FAILED);
                manifest.getEntries().add(me);
                results.add(me);
                continue;
            }

            ManifestEntry done = new ManifestEntry(source.toString(), target.toString(),
                    entry.getSize(), hash, DONE);
            manifest.getEntries().add(done);
            results.add(done);
        }

        if (config.isCleanEmptyDirs()) {
            cleanupEmptyDirs(new File(report.getRootDir()).toPath(),
                    new File(report.getOutputDir()).toPath());
        }
        return results;
    }

    private ManifestEntry findExisting(BatchManifest manifest, String sourcePath, String status) {
        for (ManifestEntry e : manifest.getEntries()) {
            if (sourcePath.equals(e.getSourcePath()) && status.equals(e.getStatus())) {
                return e;
            }
        }
        return null;
    }

    /** 生成 name (2).ext / name (3).ext 形式的不冲突路径。 */
    static Path resolveUnique(Path target) {
        if (!Files.exists(target)) {
            return target;
        }
        Path parent = target.getParent();
        String fileName = target.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String base = dot > 0 ? fileName.substring(0, dot) : fileName;
        String ext = dot > 0 ? fileName.substring(dot) : "";
        int i = 2;
        Path candidate;
        do {
            candidate = parent.resolve(base + " (" + i + ")" + ext);
            i++;
        } while (Files.exists(candidate));
        return candidate;
    }

    /** 分类名里的非法路径字符替换掉。报告/结果 Excel 计算预计目标路径时也共用此规则。 */
    public static String sanitize(String category) {
        if (category == null || category.trim().isEmpty()) {
            return ReportEntry.UNCATEGORIZED;
        }
        return category.replaceAll("[/\\\\:*?\"<>|\\x00-\\x1F]", "_").trim();
    }

    private void cleanupEmptyDirs(Path root, Path outputDir) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        Files.walk(root)
                .filter(Files::isDirectory)
                .filter(p -> !p.startsWith(outputDir))
                .sorted((a, b) -> b.getNameCount() - a.getNameCount())
                .forEach(p -> {
                    try {
                        if (p.equals(root)) {
                            return;
                        }
                        if (Files.isDirectory(p) && isEmptyDir(p)) {
                            Files.delete(p);
                        }
                    } catch (IOException ignored) {
                        // 非空或无权限则保留
                    }
                });
    }

    private static boolean isEmptyDir(Path p) throws IOException {
        java.nio.file.DirectoryStream<Path> ds = Files.newDirectoryStream(p);
        try {
            return !ds.iterator().hasNext();
        } finally {
            ds.close();
        }
    }
}
