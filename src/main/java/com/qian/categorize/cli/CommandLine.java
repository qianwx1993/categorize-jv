package com.qian.categorize.cli;

import com.qian.categorize.classifier.ClassificationEngine;
import com.qian.categorize.classifier.HttpModelClient;
import com.qian.categorize.classifier.ModelClient;
import com.qian.categorize.classifier.NullModelClient;
import com.qian.categorize.classifier.OpenAIModelClient;
import com.qian.categorize.config.AppConfig;
import com.qian.categorize.config.ConfigLoader;
import com.qian.categorize.config.Rules;
import com.qian.categorize.model.BatchManifest;
import com.qian.categorize.model.CategorizeReport;
import com.qian.categorize.model.ManifestEntry;
import com.qian.categorize.model.ScannedFile;
import com.qian.categorize.ops.FileMover;
import com.qian.categorize.ops.ManifestStore;
import com.qian.categorize.ops.RollbackService;
import com.qian.categorize.report.ReportManager;
import com.qian.categorize.scanner.FileScanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;

/**
 * 命令行入口：init / scan / report / confirm / apply / rollback。
 *
 * <pre>
 * init     &lt;dir&gt;                          生成配置文件骨架(config + rules)
 * scan     &lt;dir&gt; [--config &lt;file&gt;]
 * report   &lt;dir&gt; [--config &lt;file&gt;] [--report &lt;json&gt;]
 * confirm  &lt;report.json&gt;
 * apply    &lt;report.json&gt; [--config &lt;file&gt;]
 * rollback &lt;manifest.json&gt; [--config &lt;file&gt;]
 * </pre>
 */
public class CommandLine {

    static final String DEFAULT_CONFIG_NAME = "categorize-config.json";

    public int run(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return 1;
        }
        String command = args[0];
        if ("init".equals(command)) {
            return init(args);
        }
        if ("scan".equals(command)) {
            return scan(args);
        }
        if ("report".equals(command)) {
            return report(args);
        }
        if ("confirm".equals(command)) {
            return confirm(args);
        }
        if ("apply".equals(command)) {
            return apply(args);
        }
        if ("rollback".equals(command)) {
            return rollback(args);
        }
        usage();
        return 1;
    }

    private int init(String[] args) throws IOException {
        File dir = args.length > 1 ? new File(args[1]) : new File(".");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalArgumentException("无法创建目录: " + dir);
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("不是目录: " + dir);
        }
        copyTemplate("/templates/categorize-config.json", new File(dir, DEFAULT_CONFIG_NAME));
        copyTemplate("/templates/rules.json", new File(dir, "rules.json"));
        System.out.println("已在 " + dir.getAbsolutePath() + " 生成:");
        System.out.println("  " + DEFAULT_CONFIG_NAME + "  （运行设置：维度/过滤/模型等）");
        System.out.println("  rules.json                （分类关键词数据，按你的文件填写）");
        System.out.println("之后执行: categorize report <要分类的目录>");
        return 0;
    }

    private void copyTemplate(String resource, File target) throws IOException {
        if (target.exists()) {
            System.out.println("  已存在，跳过: " + target.getName());
            return;
        }
        InputStream in = CommandLine.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("内置模板缺失: " + resource);
        }
        OutputStream out = new java.io.FileOutputStream(target);
        try {
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) != -1) {
                out.write(buffer, 0, n);
            }
        } finally {
            out.close();
            in.close();
        }
    }

    private int scan(String[] args) throws Exception {
        File dir = requiredDir(args, 1);
        AppConfig config = loadConfig(args);
        List<ScannedFile> files = new FileScanner(config).scan(dir);
        long totalSize = 0;
        for (ScannedFile f : files) {
            totalSize += f.getSize();
        }
        System.out.println("扫描完成: " + files.size() + " 个文件, 总大小 "
                + ReportManager.humanSize(totalSize));
        for (ScannedFile f : files) {
            System.out.println("  " + f.getPath());
        }
        return 0;
    }

    private int report(String[] args) throws Exception {
        File dir = requiredDir(args, 1);
        AppConfig config = loadConfig(args);
        ClassificationEngine engine = new ClassificationEngine(config, buildModelClient(config));
        ReportManager manager = new ReportManager(config, engine);

        List<ScannedFile> files = new FileScanner(config).scan(dir);
        CategorizeReport report = manager.build(dir, files);

        File json = optionFile(args, "--report", new File(dir, "categorize-report.json"));
        File md = new File(json.getParentFile(),
                json.getName().replaceAll("\\.json$", "") + ".md");
        manager.writeJson(report, json);
        manager.writeMarkdown(report, md);

        System.out.println("报告已生成（未确认，未移动任何文件）:");
        System.out.println("  JSON:     " + json.getAbsolutePath());
        System.out.println("  Markdown: " + md.getAbsolutePath());
        System.out.println("可编辑 JSON 修改分类/排除文件，然后执行 confirm，再 apply。");
        return 0;
    }

    private int confirm(String[] args) throws Exception {
        File reportFile = requiredFile(args, 1);
        AppConfig config = ConfigLoader.defaultConfig();
        ReportManager manager = new ReportManager(config,
                new ClassificationEngine(config, new NullModelClient()));
        CategorizeReport report = manager.readJson(reportFile);
        report.setConfirmed(true);
        manager.writeJson(report, reportFile);
        System.out.println("报告已标记为已确认: " + reportFile.getAbsolutePath());
        System.out.println("共 " + report.getEntries().size() + " 个条目，可以执行 apply。");
        return 0;
    }

    private int apply(String[] args) throws Exception {
        File reportFile = requiredFile(args, 1);
        AppConfig config = loadConfig(args);
        ReportManager manager = new ReportManager(config,
                new ClassificationEngine(config, new NullModelClient()));
        CategorizeReport report = manager.readJson(reportFile);
        if (!report.isConfirmed()) {
            System.out.println("报告尚未确认，请先执行: confirm " + reportFile.getAbsolutePath());
            return 2;
        }

        FileMover mover = new FileMover(config);
        ManifestStore store = new ManifestStore();
        BatchManifest manifest = mover.newBatch(report);

        // 关键：移动前先落盘空批次 manifest，确保任何已执行操作都有记录可回滚
        File manifestFile = store.manifestFile(new File(report.getOutputDir()),
                manifest.getBatchId());
        store.save(manifest, manifestFile);

        List<ManifestEntry> results = mover.apply(report, manifest);
        store.save(manifest, manifestFile);

        int done = 0;
        int skipped = 0;
        int failed = 0;
        for (ManifestEntry e : results) {
            if (FileMover.DONE.equals(e.getStatus())) {
                done++;
            } else if (FileMover.SKIPPED.equals(e.getStatus())) {
                skipped++;
            } else {
                failed++;
                System.out.println("  [失败] " + e.getSourcePath());
            }
        }
        System.out.println("执行完成: 成功 " + done + ", 跳过 " + skipped + ", 失败 " + failed);
        System.out.println("操作清单（用于回滚）: " + manifestFile.getAbsolutePath());
        return failed == 0 ? 0 : 3;
    }

    private int rollback(String[] args) throws Exception {
        File manifestFile = requiredFile(args, 1);
        AppConfig config = loadConfig(args);
        BatchManifest manifest = new ManifestStore().load(manifestFile);
        RollbackService service = new RollbackService(config);
        RollbackService.RollbackResult result = service.rollback(manifest);
        System.out.println("回滚完成: 还原 " + result.restored.size()
                + ", 校验失败(文件被改动) " + result.tampered.size()
                + ", 冲突跳过 " + result.skipped.size()
                + ", 其他失败 " + result.failed.size());
        for (ManifestEntry e : result.tampered) {
            System.out.println("  [校验失败] " + e.getTargetPath());
        }
        for (ManifestEntry e : result.failed) {
            System.out.println("  [失败] " + e.getTargetPath());
        }
        return result.tampered.isEmpty() && result.failed.isEmpty() ? 0 : 3;
    }

    private static ModelClient buildModelClient(AppConfig config) {
        AppConfig.ModelConfig mc = config.getModel();
        if (!mc.isEnabled()) {
            return new NullModelClient();
        }
        String provider = mc.getProvider() == null ? "" : mc.getProvider().trim().toLowerCase();
        if ("http".equals(provider)) {
            // 自定义协议：POST {"name":...} → {"hits":[...]}
            return new HttpModelClient(mc.getEndpoint(), mc.getApiKey(), mc.getApiKeyEnv());
        }
        if ("openai".equals(provider)) {
            // OpenAI Chat Completions 兼容协议（OpenAI / 通义 / DeepSeek / Ollama 等）
            return new OpenAIModelClient(mc.getEndpoint(), mc.getModel(),
                    mc.getApiKey(), mc.getApiKeyEnv(), config.getDimensions());
        }
        return new NullModelClient();
    }

    /**
     * 配置查找顺序：--config 参数 → 当前目录 categorize-config.json → 内置默认值。
     * 找到外部配置时，再按 rulesFile 把独立规则文件合并进来。
     */
    private static AppConfig loadConfig(String[] args) throws Exception {
        File explicit = option(args, "--config");
        File configFile = explicit;
        if (configFile == null) {
            File inCwd = new File(DEFAULT_CONFIG_NAME).getAbsoluteFile();
            if (inCwd.isFile()) {
                configFile = inCwd;
            }
        }
        AppConfig config = ConfigLoader.load(configFile);
        if (configFile != null && configFile.isFile()) {
            config.setRules(Rules.resolve(config, configFile));
        }
        return config;
    }

    private static File option(String[] args, String name) {
        for (int i = 0; i < args.length - 1; i++) {
            if (name.equals(args[i])) {
                return new File(args[i + 1]);
            }
        }
        return null;
    }

    private static File optionFile(String[] args, String name, File def) {
        File f = option(args, name);
        return f == null ? def : f;
    }

    private static File requiredDir(String[] args, int idx) {
        File dir = new File(args[idx]);
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("目录不存在: " + dir);
        }
        return dir;
    }

    private static File requiredFile(String[] args, int idx) {
        File file = new File(args[idx]);
        if (!file.isFile()) {
            throw new IllegalArgumentException("文件不存在: " + file);
        }
        return file;
    }

    private static void usage() {
        System.out.println("用法:");
        System.out.println("  categorize init     [dir]                       # 在目录下生成 config + rules 骨架");
        System.out.println("  categorize scan     <dir> [--config <file>]");
        System.out.println("  categorize report   <dir> [--config <file>] [--report <json>]");
        System.out.println("  categorize confirm  <report.json>");
        System.out.println("  categorize apply    <report.json> [--config <file>]");
        System.out.println("  categorize rollback <manifest.json> [--config <file>]");
    }
}
