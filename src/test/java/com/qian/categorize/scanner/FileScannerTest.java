package com.qian.categorize.scanner;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.ScannedFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FileScannerTest {

    /** 创建符号链接；环境不允许（无管理员权限/开发者模式）时返回 null，测试自动跳过。 */
    private static Path symlinkOrNull(Path link, Path target) {
        try {
            Path p = Files.createSymbolicLink(link, target);
            return Files.isSymbolicLink(p) ? p : null;
        } catch (IOException | SecurityException | UnsupportedOperationException e) {
            return null;
        }
    }

    @Test
    void symlinkDirectoryIsRecordedWhenNotFollowed(@TempDir Path root) throws Exception {
        // 联接目标放在被扫描目录之外，避免被正常递归直接扫到
        Path target = Files.createDirectories(root.resolve("outside-lib"));
        Files.createFile(target.resolve("电影1.mp4"));
        Path scan = Files.createDirectories(root.resolve("scan"));
        Path link = symlinkOrNull(scan.resolve("影片库"), target);
        assumeTrue(link != null, "当前环境不允许创建符号链接，跳过");
        Files.createFile(scan.resolve("普通文件.mp4"));

        ScanResult result = new FileScanner(new AppConfig()).scanDetailed(scan.toFile());

        assertEquals(1, result.getFiles().size(), "默认不跟随联接，只扫到普通文件");
        assertEquals("普通文件.mp4", result.getFiles().get(0).getName());
        assertEquals(1, result.getSkippedLinkDirs());
        assertTrue(result.getSkippedLinkDirSamples().get(0).contains("影片库"));
    }

    @Test
    void followSymlinksEntersLinkedDirectory(@TempDir Path root) throws Exception {
        Path target = Files.createDirectories(root.resolve("outside-lib"));
        Files.createFile(target.resolve("电影1.mp4"));
        Path scan = Files.createDirectories(root.resolve("scan"));
        Path link = symlinkOrNull(scan.resolve("影片库"), target);
        assumeTrue(link != null, "当前环境不允许创建符号链接，跳过");

        AppConfig config = new AppConfig();
        config.setFollowSymlinks(true);
        ScanResult result = new FileScanner(config).scanDetailed(scan.toFile());

        assertEquals(1, result.getFiles().size(), "跟随联接后应扫到目标里的文件");
        assertEquals("电影1.mp4", result.getFiles().get(0).getName());
        assertEquals(0, result.getSkippedLinkDirs());
    }

    @Test
    void followSymlinksBreakCyclesByRealPath(@TempDir Path root) throws Exception {
        Path target = Files.createDirectories(root.resolve("outside-lib"));
        Files.createFile(target.resolve("电影1.mp4"));
        Path scan = Files.createDirectories(root.resolve("scan"));
        Path link1 = symlinkOrNull(scan.resolve("库1"), target);
        Path link2 = symlinkOrNull(scan.resolve("库2"), target);
        assumeTrue(link1 != null && link2 != null, "当前环境不允许创建符号链接，跳过");

        AppConfig config = new AppConfig();
        config.setFollowSymlinks(true);
        ScanResult result = new FileScanner(config).scanDetailed(scan.toFile());

        // 同一真实目录从两个联接进入，第二次判为环跳过，文件不重复
        assertEquals(1, result.getSkippedCycleDirs());
        assertEquals(1, result.getFiles().size());
    }

    /**
     * 创建 Windows 目录联接(junction)：与符号链接不同，mklink /J 不需要管理员权限。
     * 非 Windows 或创建失败时返回 null，测试自动跳过。
     */
    private static Path junctionOrNull(Path link, Path target) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }
        Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                link.toString(), target.toString()).redirectErrorStream(true).start();
        InputStream in = p.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[512];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        boolean finished = p.waitFor() == 0;
        if (!finished || !Files.isDirectory(link)) {
            System.out.println("创建 junction 失败，跳过：" + out.toString());
            return null;
        }
        return link;
    }

    @Test
    void junctionDirectoryIsRecordedWhenNotFollowed(@TempDir Path root) throws Exception {
        Path target = Files.createDirectories(root.resolve("outside-lib"));
        Files.createFile(target.resolve("电影1.mp4"));
        Path scan = Files.createDirectories(root.resolve("scan"));
        Path junction = junctionOrNull(scan.resolve("影片库"), target);
        assumeTrue(junction != null, "当前环境无法创建 junction，跳过");
        Files.createFile(scan.resolve("普通文件.mp4"));

        ScanResult result = new FileScanner(new AppConfig()).scanDetailed(scan.toFile());

        assertEquals(1, result.getFiles().size(), "默认不跟随联接，只扫到普通文件");
        assertEquals("普通文件.mp4", result.getFiles().get(0).getName());
        assertEquals(1, result.getSkippedLinkDirs());
        assertTrue(result.getSkippedLinkDirSamples().get(0).contains("影片库"));
    }

    @Test
    void followJunctionEntersAndDedupes(@TempDir Path root) throws Exception {
        Path target = Files.createDirectories(root.resolve("outside-lib"));
        Files.createFile(target.resolve("电影1.mp4"));
        Path scan = Files.createDirectories(root.resolve("scan"));
        Path j1 = junctionOrNull(scan.resolve("库1"), target);
        Path j2 = junctionOrNull(scan.resolve("库2"), target);
        assumeTrue(j1 != null && j2 != null, "当前环境无法创建 junction，跳过");

        AppConfig config = new AppConfig();
        config.setFollowSymlinks(true);
        ScanResult result = new FileScanner(config).scanDetailed(scan.toFile());

        // 同一真实目录经两个联接进入，第二次判为环跳过，文件不重复
        assertEquals(1, result.getSkippedCycleDirs());
        assertEquals(1, result.getFiles().size());
        assertEquals("电影1.mp4", result.getFiles().get(0).getName());
    }

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
