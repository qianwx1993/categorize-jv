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
import com.qian.categorize.report.ExcelReportWriter;
import com.qian.categorize.report.ReportManager;
import com.qian.categorize.scanner.FileScanner;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
        copyTemplate("/templates/categorize-config.json", new File(dir, DEFAULT_CONFIG_NAME));
        writeIfAbsent(new File(dir, "rules.json"), DEFAULT_RULES_JSON);
        copyTemplate("/templates/model-system-prompt.txt",
                new File(dir, "model-system-prompt.txt"));
        System.out.println("已在 " + dir.getAbsolutePath() + " 生成:");
        System.out.println("  " + DEFAULT_CONFIG_NAME + "  （运行设置：维度/过滤/模型等）");
        System.out.println("  rules.json                （分类关键词数据，按你的文件填写）");
        System.out.println("  model-system-prompt.txt   （可编辑的模型系统提示词）");
        System.out.println("之后执行: categorize report <要分类的目录>");
        return 0;
    }

    /** init 生成的规则骨架（内置在代码中，避免再维护一份模板文件）。 */
    static final String DEFAULT_RULES_JSON = "{\n"
            + "  // 分类规则数据：维度 -> 分类名 -> [文件名中出现任一关键词即命中]。\n"
            + "  // 关键词不区分大小写；同一维度内按书写顺序取第一个命中的分类。\n"
            + "  // 维度标识（series/actor/theme）要与 categorize-config.json 的 dimensions 对应。\n"
            + "  // 下面仅为格式示例，请替换为你自己的分类；也可以全部留空 {}，让模型兜底判断。\n"
            + "  \"series\": {\n"
            + "    \"教父系列\": [\"教父\", \"godfather\"],\n"
            + "    \"指环王系列\": [\"指环王\", \"lord of the rings\"]\n"
            + "  },\n"
            + "  \"actor\": {\n"
            + "    \"阿尔·帕西诺\": [\"帕西诺\", \"pacino\"]\n"
            + "  },\n"
            + "  \"theme\": {\n"
            + "    \"科幻\": [\"科幻\", \"sci-fi\"]\n"
            + "  }\n"
            + "}\n";

    private void writeIfAbsent(File target, String content) throws IOException {
        if (target.exists()) {
            System.out.println("  已存在，跳过: " + target.getName());
            return;
        }
        java.nio.file.Files.write(target.toPath(),
                content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    static final String PROTOCOL_OPENAI = "openai";
    static final String PROTOCOL_HTTP = "http";
    static final String PROTOCOL_NONE = "none";

    /**
     * 把配置里的 provider 归一化为内部协议标识：
     * openai 兼容协议（各家厂商的 OpenAI 兼容网关）或 http 自定义协议；none/空表示不接模型。
     */
    static String resolveProtocol(String rawProvider) {
        String p = rawProvider == null ? "" : rawProvider.trim().toLowerCase(java.util.Locale.ROOT);
        // 火山引擎方舟（Ark / 豆包）走 OpenAI 兼容协议
        if (p.equals("openai") || p.equals("volcengine") || p.equals("volcano")
                || p.equals("ark") || p.equals("doubao")
                || p.equals("火山引擎") || p.equals("火山") || p.equals("方舟") || p.equals("豆包")) {
            return PROTOCOL_OPENAI;
        }
        if (p.equals(PROTOCOL_HTTP)) {
            return PROTOCOL_HTTP;
        }
        if (p.isEmpty() || p.equals(PROTOCOL_NONE) || p.equals("off") || p.equals("disable")) {
            return PROTOCOL_NONE;
        }
        return "";
    }

    static ModelClient buildModelClient(AppConfig config) {
        AppConfig.ModelConfig mc = config.getModel();
        if (!mc.isEnabled()) {
            return new NullModelClient();
        }
        String protocol = resolveProtocol(mc.getProvider());
        if (PROTOCOL_HTTP.equals(protocol)) {
            // 自定义协议：POST {"name":...} → {"hits":[...]}
            return new HttpModelClient(mc.getEndpoint(), mc.getApiKey(), mc.getApiKeyEnv());
        }
        if (PROTOCOL_OPENAI.equals(protocol)) {
            // OpenAI Chat Completions 兼容协议（OpenAI / 火山方舟 / 通义 / DeepSeek / Ollama 等）
            return new OpenAIModelClient(mc.getEndpoint(), mc.getModel(),
                    mc.getApiKey(), mc.getApiKeyEnv(), config.getDimensions(),
                    mc.getSystemPrompt());
        }
        if (!PROTOCOL_NONE.equals(protocol)) {
            System.out.println("[警告] 无法识别的 model.provider: " + mc.getProvider()
                    + "，本次不会调用模型。支持：openai（含火山引擎/方舟等 OpenAI 兼容服务）、http、none。");
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
            loadApiKeyFile(config, configFile);
            loadSystemPromptFile(config, configFile);
        }
        return config;
    }

    /**
     * 密钥解析：明文 apiKey 为空且配置了 apiKeyFile 时，从本地文件读取（仅注入内存，不落别处）。
     * 文件路径为相对路径时，相对于配置文件所在目录。文件不存在时打印警告，不中断流程。
     */
    static void loadApiKeyFile(AppConfig config, File configFile) throws IOException {
        AppConfig.ModelConfig mc = config.getModel();
        String fileRef = mc.getApiKeyFile();
        if (fileRef == null || fileRef.trim().isEmpty()) {
            return;
        }
        if (mc.getApiKey() != null && !mc.getApiKey().trim().isEmpty()) {
            return; // 明文优先
        }
        File keyFile = new File(fileRef.trim());
        if (!keyFile.isAbsolute()) {
            keyFile = new File(configFile.getAbsoluteFile().getParentFile(), fileRef.trim());
        }
        if (!keyFile.isFile()) {
            System.out.println("[警告] model.apiKeyFile 指定的密钥文件不存在: " + keyFile.getAbsolutePath());
            return;
        }
        List<String> lines = java.nio.file.Files.readAllLines(keyFile.toPath(),
                java.nio.charset.StandardCharsets.UTF_8);
        for (String line : lines) {
            String key = line.trim();
            // 忽略空行和 # 注释行；取第一个非注释行为密钥
            if (!key.isEmpty() && !key.startsWith("#")) {
                mc.setApiKey(key);
                return;
            }
        }
        System.out.println("[警告] 密钥文件中没有有效密钥（仅注释/空行）: " + keyFile.getAbsolutePath());
    }

    /**
     * 读取自定义系统提示词文件（相对路径相对于配置文件所在目录）。
     * 提示词中可使用 {dimensions} 占位符，运行时替换为配置的维度列表。
     * 文件缺失时打印警告并回退到内置提示词，不中断流程。
     */
    static void loadSystemPromptFile(AppConfig config, File configFile) throws IOException {
        AppConfig.ModelConfig mc = config.getModel();
        String fileRef = mc.getSystemPromptFile();
        if (fileRef == null || fileRef.trim().isEmpty()) {
            return;
        }
        File promptFile = new File(fileRef.trim());
        if (!promptFile.isAbsolute()) {
            promptFile = new File(configFile.getAbsoluteFile().getParentFile(), fileRef.trim());
        }
        if (!promptFile.isFile()) {
            System.out.println("[警告] model.systemPromptFile 不存在，将使用内置提示词: "
                    + promptFile.getAbsolutePath());
            return;
        }
        String prompt = new String(java.nio.file.Files.readAllBytes(promptFile.toPath()),
                java.nio.charset.StandardCharsets.UTF_8).trim();
        if (!prompt.isEmpty()) {
            mc.setSystemPrompt(prompt.replace("{dimensions}", String.valueOf(config.getDimensions())));
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
