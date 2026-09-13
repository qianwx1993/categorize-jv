package com.qian.categorize.model;

/**
 * 一次文件移动的完整记录，是回滚的最小单位。
 */
public class ManifestEntry {

    private String sourcePath;
    private String targetPath;
    private long size;
    private String sha256;
    /** DONE / SKIPPED / FAILED */
    private String status;

    public ManifestEntry() {
    }

    public ManifestEntry(String sourcePath, String targetPath, long size, String sha256, String status) {
        this.sourcePath = sourcePath;
        this.targetPath = targetPath;
        this.size = size;
        this.sha256 = sha256;
        this.status = status;
    }

    public String getSourcePath() {
        return sourcePath;
    }

    public void setSourcePath(String sourcePath) {
        this.sourcePath = sourcePath;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}
