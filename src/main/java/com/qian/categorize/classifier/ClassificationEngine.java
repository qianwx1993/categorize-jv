package com.qian.categorize.classifier;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ReportEntry;
import com.qian.categorize.model.ScannedFile;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 分层分类引擎：规则层有命中就不再调用模型；规则层全空才问模型。
 * 一个文件命中多个维度时，按配置 dimensions 的优先级（靠前优先）取唯一分类。
 */
public class ClassificationEngine {

    public static final String UNCATEGORIZED = "未分类";

    private final AppConfig config;
    private final RuleClassifier ruleClassifier;
    private final ModelClient modelClient;

    /** 模型结果缓存：key=文件路径，避免对同一文件重复请求模型。 */
    private final Map<String, List<CategoryHit>> modelCache =
            new HashMap<String, List<CategoryHit>>();

    public ClassificationEngine(AppConfig config, ModelClient modelClient) {
        this.config = config;
        this.ruleClassifier = new RuleClassifier(config);
        this.modelClient = modelClient;
    }

    public ReportEntry classify(ScannedFile file) {
        ReportEntry entry = new ReportEntry();
        entry.setPath(file.getPath());
        entry.setName(file.getName());
        entry.setSize(file.getSize());

        List<CategoryHit> hits = ruleClassifier.classify(file);
        if (hits.isEmpty() && config.getModel().isEnabled()) {
            hits = modelCache.get(file.getPath());
            if (hits == null) {
                hits = modelClient.suggest(file);
                modelCache.put(file.getPath(), hits);
            }
        }
        entry.getHits().addAll(hits);

        CategoryHit chosen = pickByDimensionPriority(hits);
        if (chosen == null) {
            entry.setCategory(UNCATEGORIZED);
            entry.setLowConfidence(true);
        } else {
            entry.setCategory(chosen.getCategory());
            entry.setLowConfidence(chosen.getConfidence() < config.getLowConfidenceThreshold());
        }
        return entry;
    }

    private CategoryHit pickByDimensionPriority(List<CategoryHit> hits) {
        if (hits == null || hits.isEmpty()) {
            return null;
        }
        Map<String, CategoryHit> bestByDimension = new HashMap<String, CategoryHit>();
        for (CategoryHit hit : hits) {
            CategoryHit existing = bestByDimension.get(hit.getDimension());
            if (existing == null || hit.getConfidence() > existing.getConfidence()) {
                bestByDimension.put(hit.getDimension(), hit);
            }
        }
        for (String dimension : config.getDimensions()) {
            CategoryHit hit = bestByDimension.get(dimension);
            if (hit != null) {
                return hit;
            }
        }
        // 命中了配置外的维度，退化为取置信度最高者
        CategoryHit fallback = null;
        for (CategoryHit hit : hits) {
            if (fallback == null || hit.getConfidence() > fallback.getConfidence()) {
                fallback = hit;
            }
        }
        return fallback;
    }
}
