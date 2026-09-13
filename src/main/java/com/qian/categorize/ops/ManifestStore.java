package com.qian.categorize.ops;

import com.qian.categorize.config.ConfigLoader;
import com.qian.categorize.model.BatchManifest;

import java.io.File;
import java.io.IOException;

/**
 * manifest 的落盘与读取。存放于 &lt;outputDir&gt;/.categorize/manifests/。
 * apply 前必须先落盘，保证任何一批已执行的移动都能被回滚。
 */
public class ManifestStore {

    public static final String METADATA_DIR = ".categorize";
    public static final String MANIFEST_DIR = "manifests";

    public File manifestFile(File outputDir, String batchId) {
        return new File(new File(new File(outputDir, METADATA_DIR), MANIFEST_DIR),
                batchId + ".json");
    }

    public void save(BatchManifest manifest) throws IOException {
        File file = manifestFile(new File(manifest.getOutputDir()), manifest.getBatchId());
        save(manifest, file);
    }

    public void save(BatchManifest manifest, File file) throws IOException {
        File parent = file.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        ConfigLoader.mapper().writerWithDefaultPrettyPrinter().writeValue(file, manifest);
    }

    public BatchManifest load(File file) throws IOException {
        return ConfigLoader.mapper().readValue(file, BatchManifest.class);
    }
}
