package com.qian.categorize.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 报告中一个文件的分类结果。category 为按维度优先级选出的唯一目标分类，
 * 可在 apply 前人工修改；excluded=true 表示本次不处理。
 * category 为「未分类」（或空）时，apply 不移动文件，保持原位。
 */
public class ReportEntry {

    /** 未分类的固定标识；命中不到任何维度时使用，apply 时此类文件保持原位。 */
    public static final String UNCATEGORIZED = "未分类";

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

    /** 是否未分类：分类名为空或等于固定标识「未分类」——执行移动时保持在原路径，不移动。 */
    public boolean isUncategorized() {
        String c = category == null ? "" : category.trim();
        return c.isEmpty() || UNCATEGORIZED.equals(c);
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
