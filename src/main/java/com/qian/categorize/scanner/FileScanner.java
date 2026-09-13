package com.qian.categorize.scanner;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.ScannedFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 递归扫描目录，按配置过滤：隐藏文件、忽略名、扩展名黑白名单、最小大小。
 * 不跟随符号链接。
 */
public class FileScanner {

    private final AppConfig config;

    public FileScanner(AppConfig config) {
        this.config = config;
    }

    public List<ScannedFile> scan(File rootDir) throws IOException {
        if (!rootDir.exists() || !rootDir.isDirectory()) {
            throw new IllegalArgumentException("不是有效目录: " + rootDir);
        }
        final List<ScannedFile> result = new ArrayList<ScannedFile>();
        final Set<String> ignoreNames = new HashSet<String>(config.getIgnoreNames());
        final Set<String> extSet = normalizeExtensions(config.getExtensions());

        Files.walkFileTree(rootDir.toPath(), new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                File f = file.toFile();
                if (attrs.isSymbolicLink()) {
                    return FileVisitResult.CONTINUE;
                }
                String name = f.getName();
                if (config.isIgnoreHidden() && f.isHidden()) {
                    return FileVisitResult.CONTINUE;
                }
                if (ignoreNames.contains(name)) {
                    return FileVisitResult.CONTINUE;
                }
                if (f.length() < config.getMinSizeBytes()) {
                    return FileVisitResult.CONTINUE;
                }
                if (!extensionAllowed(name, extSet)) {
                    return FileVisitResult.CONTINUE;
                }
                result.add(new ScannedFile(
                        f.getAbsolutePath(),
                        name,
                        f.length(),
                        f.lastModified()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (attrs.isSymbolicLink()) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                // 单个文件无权限等错误不阻断整体扫描
                return FileVisitResult.CONTINUE;
            }
        });
        return result;
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
