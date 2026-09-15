package com.qian.categorize.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 分类报告：report 生成，JSON 可人工编辑（改分类/排除），随后直接 apply。
 */
public class CategorizeReport {

    private String rootDir;
    private String outputDir;
    private String createdAt;
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

    public List<ReportEntry> getEntries() {
        return entries;
    }

    public void setEntries(List<ReportEntry> entries) {
        this.entries = entries;
    }
}
