package com.qian.categorize.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类报告：scan 后生成，JSON 可编辑，confirm 后 confirmed=true 才能 apply。
 */
public class CategorizeReport {

    private String rootDir;
    private String outputDir;
    private String createdAt;
    private boolean confirmed;
    private List<ReportEntry> entries = new ArrayList<ReportEntry>();

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

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public boolean isConfirmed() {
        return confirmed;
    }

    public void setConfirmed(boolean confirmed) {
        this.confirmed = confirmed;
    }

    public List<ReportEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<ReportEntry> entries) {
        this.entries = entries;
    }
}
