package com.qian.categorize.scanner;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.ScannedFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 递归扫描目录，按配置过滤：隐藏文件、忽略名、扩展名黑白名单、最小大小。
 * 默认不跟随符号链接（含 Windows 目录联接 junction，防止环和误入其他盘）；
 * 置 followSymlinks=true 时以 FOLLOW_LINKS 遍历，可进入联接目录，
 * 同时按目录真实路径（toRealPath）去环/去重（多个联接指向同一目标只扫一次）。
 *
 * <p>注意平台差异：Windows JDK 8 默认遍历会把符号链接目录当文件交给 visitFile，
 * 而目录联接(junction)又会被当普通目录直接钻进 preVisitDirectory；两类链接需要在
 * 两个回调里分别识别。所有跳过/失败都计入 {@link ScanResult}，供上层向用户告警
 * （这类静默跳过是「文件数对不上」的头号原因）。
 */
public class FileScanner {

    private final AppConfig config;

    public FileScanner(AppConfig config) {
        this.config = config;
    }

    /** 兼容旧调用：只要文件清单。 */
    public List<ScannedFile> scan(File rootDir) throws IOException {
        return scanDetailed(rootDir).getFiles();
    }

    /** 带诊断信息的扫描。 */
    public ScanResult scanDetailed(File rootDir) throws IOException {
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            throw new IllegalArgumentException("不是有效目录: " + rootDir);
        }
        final ScanResult result = new ScanResult();
        final Set<String> ignoreNames = new HashSet<String>(config.getIgnoreNames());
        final Set<String> extSet = normalizeExtensions(config.getExtensions());
        final boolean follow = config.isFollowSymlinks();
        // 跟随模式下按真实路径去环：起始目录自身也会回调 preVisitDirectory，在那里登记即可，
        // 不能预置根路径，否则根会被误判成环
        final Set<String> visitedReal = new HashSet<String>();
        Set<FileVisitOption> options = follow
                ? EnumSet.of(FileVisitOption.FOLLOW_LINKS)
                : Collections.<FileVisitOption>emptySet();

        Files.walkFileTree(rootDir.toPath(), options, Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                File f = file.toFile();
                if (!follow && attrs.isSymbolicLink()) {
                    // 非跟随模式：Windows 上指向目录的链接会走到这里，按目标类型分别计数
                    if (f.isDirectory()) {
                        result.skippedLinkDirs++;
                        result.addSample(result.getSkippedLinkDirSamples(),
                                file.toString());
                    } else {
                        result.skippedLinkFiles++;
                    }
                    return FileVisitResult.CONTINUE;
                }
                String name = f.getName();
                if (config.isIgnoreHidden() && isHiddenQuietly(f)) {
                    result.filteredHidden++;
                    return FileVisitResult.CONTINUE;
                }
                if (ignoreNames.contains(name)) {
                    result.filteredNames++;
                    return FileVisitResult.CONTINUE;
                }
                if (f.length() < config.getMinSizeBytes()) {
                    result.filteredSize++;
                    return FileVisitResult.CONTINUE;
                }
                if (!extensionAllowed(name, extSet)) {
                    result.filteredExt++;
                    return FileVisitResult.CONTINUE;
                }
                result.getFiles().add(new ScannedFile(
                        f.getAbsolutePath(),
                        name,
                        f.length(),
                        f.lastModified()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (!follow) {
                    // Unix 风格遍历下符号链接目录会走到这里（Windows 上符号链接目录在 visitFile 处理）
                    if (attrs.isSymbolicLink() || isWindowsJunction(dir)) {
                        // isWindowsJunction 兜底 Windows 目录联接(junction)：Java 不把它算作符号链接，
                        // 默认遍历会直接钻进去（可能误入其他盘/卷）
                        result.skippedLinkDirs++;
                        result.addSample(result.getSkippedLinkDirSamples(),
                                dir.toString());
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    return FileVisitResult.CONTINUE;
                }
                // 跟随模式 attrs 是链接目标的属性（isSymbolicLink 恒 false），
                // 一律按真实路径判重：已访问过（环或多个联接指向同一目标）则跳过
                String real;
                try {
                    real = dir.toRealPath().toString();
                } catch (IOException e) {
                    result.failed++;
                    result.addSample(result.getFailedSamples(),
                            dir + "（联接目标无法访问: " + e.getMessage() + "）");
                    return FileVisitResult.SKIP_SUBTREE;
                }
                if (!visitedReal.add(real)) {
                    result.skippedCycleDirs++;
                    result.addSample(result.getCycleSamples(), dir.toString());
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 无权限 / 路径过长(>260) / 文件被独占占用 / 断裂的联接 等：计数留样例，不阻断扫描
                result.failed++;
                result.addSample(result.getFailedSamples(),
                        file.toString() + "（" + describe(exc) + "）");
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
    }

    /**
     * 判断目录是否为 Windows 目录联接（junction）：Java 把 junction 当普通目录，
     * attrs.isSymbolicLink() 为 false，默认遍历会直接钻进去（可能误入其他盘/卷）。
     * 判据：目录的真实路径与「父目录真实路径 + 本名」不一致，即说明被重定向。
     * 非 Windows、根目录、解析失败一律返回 false（宁可漏判也不误杀普通目录）。
     */
    static boolean isWindowsJunction(Path dir) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return false;
        }
        Path parent = dir.getParent();
        if (parent == null) {
            return false;
        }
        try {
            // 非跟随模式下进入本回调时，父目录链上不可能还有联接（遇到即 SKIP_SUBTREE），
            // 故 parent.toRealPath() 解析出来的就是逻辑上的父目录
            String real = dir.toRealPath().toString();
            String logical = parent.toRealPath()
                    .resolve(dir.getFileName().toString()).toString();
            return !real.equalsIgnoreCase(logical);
        } catch (IOException e) {
            return false;
        }
    }

    /** f.isHidden() 理论上可能抛 SecurityException，扫描不应因此中断。 */
    private static boolean isHiddenQuietly(File f) {
        try {
            return f.isHidden();
        } catch (SecurityException e) {
            return false;
        }
    }

    /** 把常见 IO 异常翻成对用户友好的中文原因。 */
    private static String describe(IOException e) {
        String msg = e.getMessage() == null ? e.toString() : e.getMessage();
        String cn = null;
        if (msg.contains("being used by another process")
                || msg.contains("另一个程序")) {
            cn = "文件被占用";
        } else if (msg.contains("Access is denied")
                || msg.contains("拒绝访问")
                || msg.contains("Permission")) {
            cn = "无权限访问";
        } else if (msg.contains("too long") || msg.contains("文件名过长")
                || (msg.contains("path") && msg.length() > 260)) {
            cn = "路径过长(>260)";
        }
        return cn == null ? msg : cn;
    }

    private boolean extensionAllowed(String name, Set<String> extSet) {
        String mode = config.getExtensionMode();
        if (mode == null || "OFF".equalsIgnoreCase(mode) || extSet.isEmpty()) {
            return true;
        }
        String ext = extensionOf(name);
        if ("WHITELIST".equalsIgnoreCase(mode)) {
            return extSet.contains(ext);
        }
        if ("BLACKLIST".equalsIgnoreCase(mode)) {
            return !extSet.contains(ext);
        }
        return true;
    }

    private static Set<String> normalizeExtensions(List<String> exts) {
        Set<String> set = new HashSet<String>();
        for (String e : exts) {
            if (e == null) {
                continue;
            }
            String v = e.trim().toLowerCase(Locale.ROOT);
            if (!v.startsWith(".")) {
                v = "." + v;
            }
            set.add(v);
        }
        return set;
    }

    private static String extensionOf(String name) {
        int i = name.lastIndexOf('.');
        if (i < 0) {
            return "";
        }
        return name.substring(i).toLowerCase(Locale.ROOT);
    }
}
