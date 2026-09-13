package com.qian.categorize.ops;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.BatchManifest;
import com.qian.categorize.model.ManifestEntry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 按 manifest 回滚：把 DONE 的文件移回原路径。
 * 移动前用 SHA-256 校验目标文件未被外部篡改，校验失败的条目跳过并单独列出，
 * 不影响其他文件回滚。
 */
public class RollbackService {

    private final ConflictStrategy conflictStrategy;

    public RollbackService(AppConfig config) {
        this.conflictStrategy = ConflictStrategy.fromConfig(config.getConflictStrategy());
    }

    public static class RollbackResult {
        public final List<ManifestEntry> restored = new ArrayList<ManifestEntry>();
        public final List<ManifestEntry> tampered = new ArrayList<ManifestEntry>();
        public final List<ManifestEntry> skipped = new ArrayList<ManifestEntry>();
        public final List<ManifestEntry> failed = new ArrayList<ManifestEntry>();
    }

    public RollbackResult rollback(BatchManifest manifest) {
        RollbackResult result = new RollbackResult();
        // 逆序还原，尽量贴近执行前状态
        List<ManifestEntry> entries = manifest.getEntries();
        for (int i = entries.size() - 1; i >= 0; i--) {
            ManifestEntry e = entries.get(i);
            if (!FileMover.DONE.equals(e.getStatus())) {
                continue;
            }
            Path target = new File(e.getTargetPath()).toPath();
            Path source = new File(e.getSourcePath()).toPath();

            if (!Files.exists(target)) {
                result.failed.add(e);
                continue;
            }
            try {
                String currentHash = Checksums.sha256(target);
                if (e.getSha256() != null && !e.getSha256().isEmpty()
                        && !e.getSha256().equals(currentHash)) {
                    result.tampered.add(e);
                    continue;
                }
                if (Files.exists(source)) {
                    if (conflictStrategy == ConflictStrategy.SKIP) {
                        result.skipped.add(e);
                        continue;
                    }
                    if (conflictStrategy == ConflictStrategy.RENAME) {
                        source = FileMover.resolveUnique(source);
                    }
                }
                if (source.getParent() != null) {
                    Files.createDirectories(source.getParent());
                }
                Files.move(target, source, StandardCopyOption.REPLACE_EXISTING);
                result.restored.add(e);
            } catch (IOException ex) {
                result.failed.add(e);
            }
        }
        return result;
    }
}
