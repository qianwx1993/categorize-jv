package com.qian.categorize.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 一批 apply 操作的清单，apply 前落盘，支持按批次回滚 / 断点续做。
 */
public class BatchManifest {

    private String batchId;
    private String createdAt;
    private String rootDir;
    private String outputDir;
    private List<ManifestEntry> entries = new ArrayList<ManifestEntry>();

    public String getBatchId() {
        return batchId;
    }

    public void setBatchId(String batchId) {
        this.batchId = batchId;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getRootDir() {
        return rootDir;
    }

    public void setRootDir(String rootDir) {
        this.rootDir = rootDir;
    }

    public String getOutputDir() {
        return outputDir;
    }

    public void setOutputDir(String outputDir) {
        this.outputDir = outputDir;
    }

    public List<ManifestEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<ManifestEntry> entries) {
        this.entries = entries;
    }
}
