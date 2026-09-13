package com.qian.categorize.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告中一个文件的分类结果。category 为按维度优先级选出的唯一目标分类，
 * 可在 confirm 阶段被人工修改；excluded=true 表示本次不处理。
 */
public class ReportEntry {

    private String path;
    private String name;
    private long size;
    private String category;
    private boolean excluded;
    private boolean lowConfidence;
    private List<CategoryHit> hits = new ArrayList<CategoryHit>();

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public boolean isExcluded() {
        return excluded;
    }

    public void setExcluded(boolean excluded) {
        this.excluded = excluded;
    }

    public boolean isLowConfidence() {
        return lowConfidence;
    }

    public void setLowConfidence(boolean lowConfidence) {
        this.lowConfidence = lowConfidence;
    }

    public List<CategoryHit> getHits() {
        return hits;
    }

    public void setHits(List<CategoryHit> hits) {
        this.hits = hits;
    }
}
