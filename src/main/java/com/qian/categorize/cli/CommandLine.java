package com.qian.categorize.cli;

import com.qian.categorize.classifier.ClassificationEngine;
import com.qian.categorize.classifier.ModelClient;
import com.qian.categorize.classifier.NullModelClient;
import com.qian.categorize.config.AppConfig;
import com.qian.categorize.config.ConfigLoader;
import com.qian.categorize.model.BatchManifest;
import com.qian.categorize.model.CategorizeReport;
import com.qian.categorize.model.ManifestEntry;
import com.qian.categorize.model.ScannedFile;
import com.qian.categorize.ops.FileMover;
import com.qian.categorize.ops.ManifestStore;
import com.qian.categorize.ops.RollbackService;
import com.qian.categorize.report.ExcelReportWriter;
import com.qian.categorize.report.ReportManager;
import com.qian.categorize.scanner.FileScanner;
import com.qian.categorize.scanner.ScanResult;
import com.qian.categorize.service.CategorizeService;
import com.qian.categorize.service.ConfigFiles;
import com.qian.categorize.service.ModelProtocols;
import com.qian.categorize.util.Templates;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 命令行入口：init / scan / report / apply / rollback。
 *
 * <pre>
 * init     &lt;dir&gt;                          生成配置文件骨架(config + rules)
 * scan     &lt;dir&gt; [--config &lt;file&gt;]
 * report   &lt;dir&gt; [--config &lt;file&gt;] [--report &lt;json&gt;]   产出 JSON + Excel + Markdown
 * apply    &lt;report.json&gt; [--config &lt;file&gt;]             人工检查/编辑 JSON 后直接执行
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
        // 模板释放逻辑与桌面版共用（Templates）；已存在的文件跳过并提示
        String[][] files = {
                {Templates.CONFIG, DEFAULT_CONFIG_NAME, "运行设置：维度/过滤/冲突策略等"},
                {Templates.MODEL_CONFIG, "model-config.json", "大模型设置：新手只需填 apiKey"},
                {Templates.RULES, "rules.json", "分类关键词数据，按你的文件填写"},
                {Templates.SYSTEM_PROMPT, "model-system-prompt.txt", "可编辑的模型系统提示词"},
        };
        System.out.println("在 " + dir.getAbsolutePath() + " 生成配置:");
        for (String[] item : files) {
            File target = new File(dir, item[1]);
            boolean written = Templates.extractIfMissing(item[0], target);
            System.out.println("  " + (written ? "" : "已存在，跳过: ") + item[1]
                    + "  （" + item[2] + "）");
        }
        System.out.println("之后执行: categorize report <要分类的目录>");
        return 0;
    }

    private int scan(String[] args) throws Exception {
        File dir = requiredDir(args, 1);
        AppConfig config = loadConfig(args);
        ScanResult scanResult = new FileScanner(config).scanDetailed(dir);
        List<ScannedFile> files = scanResult.getFiles();
        long totalSize = 0;
        for (ScannedFile f : files) {
            totalSize += f.getSize();
        }
        System.out.println("扫描完成: " + files.size() + " 个文件, 总大小 "
                + ReportManager.humanSize(totalSize));
        printScanDiagnostics(scanResult);
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

        ScanResult scanResult = new FileScanner(config).scanDetailed(dir);
        printScanDiagnostics(scanResult);
        List<ScannedFile> files = scanResult.getFiles();
        CategorizeReport report = manager.build(dir, files);

        File json = optionFile(args, "--report", new File(dir, "categorize-report.json"));
        String baseName = json.getName().replaceAll("\\.json$", "");
        File md = new File(json.getParentFile(), baseName + ".md");
        File xlsx = new File(json.getParentFile(), baseName + ".xlsx");
        manager.writeJson(report, json);
        manager.writeMarkdown(report, md);
        new ExcelReportWriter().write(report, xlsx);

        System.out.println("报告已生成（未移动任何文件）:");
        System.out.println("  Excel(人工检查): " + xlsx.getAbsolutePath());
        System.out.println("  JSON(可编辑):   " + json.getAbsolutePath());
        System.out.println("  Markdown:       " + md.getAbsolutePath());
        System.out.println("检查无误（需要调整就编辑 JSON 的 category/excluded）后执行 apply 即可。");
        return 0;
    }

    /** 扫描诊断：联接目录/环/无法访问的跳过统计；过滤规则命中数也一并打印，便于核对文件数。 */
    private static void printScanDiagnostics(ScanResult s) {
        if (s.getSkippedLinkDirs() > 0) {
            System.out.println("[警告] 跳过联接/符号链接目录 " + s.getSkippedLinkDirs()
                    + " 个（内部文件未计入），例如：" + sample(s.getSkippedLinkDirSamples(), 5)
                    + "；需要扫描请在配置中设 followSymlinks=true");
        }
        if (s.getSkippedCycleDirs() > 0) {
            System.out.println("[警告] 检测到目录环并跳过 " + s.getSkippedCycleDirs()
                    + " 个：" + sample(s.getCycleSamples(), 5));
        }
        if (s.getFailed() > 0) {
            System.out.println("[警告] 无法访问的文件/目录 " + s.getFailed()
                    + " 个（路径过长>260/无权限/被占用），例如："
                    + sample(s.getFailedSamples(), 5));
        }
        if (s.getFilteredHidden() + s.getFilteredNames() + s.getFilteredSize()
                + s.getFilteredExt() > 0) {
            System.out.println("  过滤: 隐藏 " + s.getFilteredHidden() + " / 忽略名 "
                    + s.getFilteredNames() + " / 过小 " + s.getFilteredSize()
                    + " / 扩展名 " + s.getFilteredExt());
        }
    }

    private static String sample(List<String> paths, int max) {
        StringBuilder sb = new StringBuilder();
        int n = Math.min(max, paths.size());
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                sb.append("；");
            }
            sb.append(paths.get(i));
        }
        if (paths.size() > n) {
            sb.append(" 等 ").append(paths.size()).append(" 处");
        }
        return sb.toString();
    }

    private int apply(String[] args) throws Exception {
        File reportFile = requiredFile(args, 1);
        AppConfig config = loadConfig(args);
        ReportManager manager = new ReportManager(config,
                new ClassificationEngine(config, new NullModelClient()));
        CategorizeReport report = manager.readJson(reportFile);

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
        int kept = 0;
        int failed = 0;
        for (ManifestEntry e : results) {
            if (FileMover.DONE.equals(e.getStatus())) {
                done++;
            } else if (FileMover.SKIPPED.equals(e.getStatus())) {
                skipped++;
            } else if (FileMover.KEPT.equals(e.getStatus())) {
                kept++;
            } else {
                failed++;
                System.out.println("  [失败] " + e.getSourcePath());
            }
        }
        System.out.println("执行完成: 成功移动 " + done + ", 未分类保持原位 " + kept
                + ", 跳过 " + skipped + ", 失败 " + failed);
        System.out.println("操作清单（用于回滚）: " + manifestFile.getAbsolutePath());

        // 与桌面版一致：移动后额外产出结果 Excel（原路径/实际移动后路径/状态）
        File resultExcel = ExcelReportWriter.resultFileFor(
                new File(report.getOutputDir()), manifest.getBatchId());
        new ExcelReportWriter().writeResult(manifest, report, results, resultExcel);
        System.out.println("结果 Excel: " + resultExcel.getAbsolutePath());
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

    static final String PROTOCOL_OPENAI = ModelProtocols.OPENAI;
    static final String PROTOCOL_HTTP = ModelProtocols.HTTP;
    static final String PROTOCOL_NONE = ModelProtocols.NONE;

    /**
     * 把配置里的 provider 归一化为内部协议标识。
     * 实际逻辑在 {@link ModelProtocols}（CLI 与 GUI 服务层共用）。
     */
    static String resolveProtocol(String rawProvider) {
        return ModelProtocols.resolveProtocol(rawProvider);
    }

    static ModelClient buildModelClient(AppConfig config) {
        List<String> warnings = new ArrayList<String>();
        ModelClient client = ModelProtocols.build(config, warnings);
        for (String w : warnings) {
            System.out.println("[警告] " + w);
        }
        return client;
    }

    /**
     * 配置查找顺序：--config 参数 → 当前目录 categorize-config.json → 内置默认值。
     * 找到外部配置时，再按 rulesFile 把独立规则文件合并进来。
     */
    private static AppConfig loadConfig(String[] args) throws Exception {
        File explicit = option(args, "--config");
        // 与桌面版一致的查找顺序：--config → 当前目录 → 当前目录 config/ 子目录 → 内置默认
        File configFile = explicit != null ? explicit
                : CategorizeService.discoverConfigFile();
        AppConfig config = ConfigLoader.load(configFile);
        if (configFile != null && configFile.isFile()) {
            List<String> warnings = new ArrayList<String>();
            ConfigFiles.loadAll(config, configFile, warnings);
            for (String w : warnings) {
                System.out.println("[警告] " + w);
            }
        }
        return config;
    }


    /**
     * 密钥解析：明文 apiKey 为空且配置了 apiKeyFile 时，从本地文件读取（仅注入内存，不落别处）。
     * 文件路径为相对路径时，相对于配置文件所在目录。文件不存在时打印警告，不中断流程。
     */
    /** 委托 {@link ConfigFiles}（CLI/GUI 共用），告警打印到标准输出。测试依赖此签名。 */
    static void loadApiKeyFile(AppConfig config, File configFile) throws IOException {
        List<String> warnings = new ArrayList<String>();
        ConfigFiles.loadApiKeyFile(config, configFile, warnings);
        for (String w : warnings) {
            System.out.println("[警告] " + w);
        }
    }

    /**
     * 读取自定义系统提示词文件（相对路径相对于配置文件所在目录）。
     * 提示词中可使用 {dimensions} 占位符，运行时替换为配置的维度列表。
     * 文件缺失时打印警告并回退到内置提示词，不中断流程。
     */
    /** 委托 {@link ConfigFiles}（CLI/GUI 共用），告警打印到标准输出。测试依赖此签名。 */
    static void loadSystemPromptFile(AppConfig config, File configFile) throws IOException {
        List<String> warnings = new ArrayList<String>();
        ConfigFiles.loadSystemPromptFile(config, configFile, warnings);
        for (String w : warnings) {
            System.out.println("[警告] " + w);
        }
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
        if (!dir.exists()) {
            // 目录不存在时自动创建（含不存在的父目录），便于对空目录直接生成报告
            if (dir.mkdirs()) {
                System.out.println("目录不存在，已创建: " + dir.getAbsolutePath());
            } else {
                throw new IllegalArgumentException("目录不存在且创建失败: " + dir);
            }
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException("路径存在但不是目录: " + dir);
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
        System.out.println("  categorize apply    <report.json> [--config <file>]");
        System.out.println("  categorize rollback <manifest.json> [--config <file>]");
    }
}
