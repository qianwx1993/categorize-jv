package com.qian.categorize.report;

import com.qian.categorize.classifier.ClassificationEngine;
import com.qian.categorize.config.AppConfig;
import com.qian.categorize.config.ConfigLoader;
import com.qian.categorize.model.CategorizeReport;
import com.qian.categorize.model.ReportEntry;
import com.qian.categorize.model.ScannedFile;
import com.qian.categorize.util.Progress;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.concurrent.CancellationException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成 / 读取分类报告。JSON 是权威格式（可人工编辑、作为 apply 输入），
 * Markdown 供人工阅读（目录树预览、统计、低置信度标注）。
 */
public class ReportManager {

    private final AppConfig config;
    private final ClassificationEngine engine;

    public ReportManager(AppConfig config, ClassificationEngine engine) {
        this.config = config;
        this.engine = engine;
    }

    public CategorizeReport build(File rootDir, List<ScannedFile> files) {
        return build(rootDir, files, Progress.NONE);
    }

    /** 带进度/取消的构建版本；在每个文件分类之间检查取消标志。 */
    public CategorizeReport build(File rootDir, List<ScannedFile> files, Progress progress) {
        CategorizeReport report = new CategorizeReport();
        report.setRootDir(rootDir.getAbsolutePath());
        report.setOutputDir(resolveOutputDir(rootDir).getAbsolutePath());
        report.setCreatedAt(now());
        int total = files.size();
        if (progress != null && progress.isCancelled()) {
            throw new CancellationException("分类分析已取消");
        }
        // 模型批量归类：一次请求看到全部文件名，通观全局后归纳分类
        if (progress != null && total > 0) {
            progress.progress(0, total, "正在让模型通览全部文件并归纳分类…");
        }
        List<ReportEntry> classified = engine.classifyAll(files);
        List<ReportEntry> entries = new ArrayList<ReportEntry>(total);
        for (int i = 0; i < total; i++) {
            if (progress != null && progress.isCancelled()) {
                throw new CancellationException("分类分析已取消");
            }
            entries.add(classified.get(i));
            if (progress != null) {
                progress.progress(i + 1, total, "正在分析: " + files.get(i).getName());
            }
        }
        report.setEntries(entries);
        return report;
    }

    public File resolveOutputDir(File rootDir) {
        File out = new File(config.getOutputDir());
        if (out.isAbsolute()) {
            return out;
        }
        return new File(rootDir, config.getOutputDir());
    }

    public void writeJson(CategorizeReport report, File jsonFile) throws IOException {
        File parent = jsonFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ConfigLoader.mapper().writerWithDefaultPrettyPrinter().writeValue(jsonFile, report);
    }

    public CategorizeReport readJson(File jsonFile) throws IOException {
        return ConfigLoader.mapper().readValue(jsonFile, CategorizeReport.class);
    }

    public void writeMarkdown(CategorizeReport report, File mdFile) throws IOException {
        // 真正会移动的文件按分类分组；未分类文件单独收集（保持原位，不移动）
        Map<String, List<ReportEntry>> byCategory =
                new LinkedHashMap<String, List<ReportEntry>>();
        List<ReportEntry> kept = new ArrayList<ReportEntry>();
        long lowConfidence = 0;
        long moveSize = 0;
        long excluded = 0;
        for (ReportEntry e : report.getEntries()) {
            if (e.isExcluded()) {
                excluded++;
                continue;
            }
            if (e.isUncategorized()) {
                kept.add(e);
                continue;
            }
            List<ReportEntry> list = byCategory.get(e.getCategory());
            if (list == null) {
                list = new ArrayList<ReportEntry>();
                byCategory.put(e.getCategory(), list);
            }
            list.add(e);
            moveSize += e.getSize();
            if (e.isLowConfidence()) {
                lowConfidence++;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# 分类报告\n\n");
        sb.append("- 生成时间：").append(report.getCreatedAt()).append('\n');
        sb.append("- 扫描目录：").append(report.getRootDir()).append('\n');
        sb.append("- 目标目录：").append(report.getOutputDir()).append('\n');
        sb.append("- 文件总数：").append(report.getEntries().size()).append('\n');
        sb.append("- 待移动：").append(countMove(report)).append('\n');
        sb.append("- 未分类（保持原位，不移动）：").append(kept.size()).append('\n');
        sb.append("- 已排除：").append(excluded).append('\n');
        sb.append("- 待移动总大小：").append(humanSize(moveSize)).append('\n');
        sb.append("- **低置信度文件：").append(lowConfidence).append("（请重点复核）**\n");
        sb.append("\n## 目标目录预览（仅待移动文件）\n\n");
        sb.append("```\n");
        for (Map.Entry<String, List<ReportEntry>> cat : byCategory.entrySet()) {
            sb.append(cat.getKey()).append('/').append('\n');
            for (ReportEntry e : cat.getValue()) {
                sb.append("  └─ ").append(e.getName());
                if (e.isLowConfidence()) {
                    sb.append("  [低置信度]");
                }
                sb.append('\n');
            }
        }
        sb.append("```\n");

        if (!kept.isEmpty()) {
            sb.append("\n## 保持原位（未分类，执行时不会移动）\n\n");
            for (ReportEntry e : kept) {
                sb.append("- ").append(e.getPath());
                if (e.isLowConfidence()) {
                    sb.append("  [低置信度]");
                }
                sb.append('\n');
            }
        }

        File parent = mdFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        java.nio.file.Files.write(mdFile.toPath(),
                sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** 真正会移动的文件数：未排除且有明确分类（未分类保持原位，不计入）。 */
    private long countMove(CategorizeReport report) {
        long n = 0;
        for (ReportEntry e : report.getEntries()) {
            if (!e.isExcluded() && !e.isUncategorized()) {
                n++;
            }
        }
        return n;
    }

    public static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double v = bytes;
        String[] units = {"KB", "MB", "GB", "TB"};
        int idx = -1;
        do {
            v /= 1024;
            idx++;
        } while (v >= 1024 && idx < units.length - 1);
        return String.format(java.util.Locale.ROOT, "%.2f %s", v, units[idx]);
    }

    private static String now() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date());
    }
}
