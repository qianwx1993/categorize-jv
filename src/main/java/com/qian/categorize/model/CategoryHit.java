package com.qian.categorize.model;

/**
 * 单个分类器对一个文件的一次命中：维度 + 分类名 + 置信度 + 来源。
 */
public class CategoryHit {

    /** 置信度：规则命中 0.9，模型命中 0.6，未命中不产生 hit。 */
    private String dimension;
    private String category;
    private double confidence;
    /** 命中来源：RULE / MODEL */
    private String source;

    public CategoryHit() {
    }

    public CategoryHit(String dimension, String category, double confidence, String source) {
        this.dimension = dimension;
        this.category = category;
        this.confidence = confidence;
        this.source = source;
    }

    public String getDimension() {
        return dimension;
    }

    public void setDimension(String dimension) {
        this.dimension = dimension;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }
}
