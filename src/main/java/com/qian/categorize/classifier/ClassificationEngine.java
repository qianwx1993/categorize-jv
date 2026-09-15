package com.qian.categorize.classifier;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ReportEntry;
import com.qian.categorize.model.ScannedFile;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分类引擎：每个文件先走规则；模型启用时所有文件都再走一遍模型做多维度补充。
 * 合并规则：同一维度规则有命中则以规则为准（忽略模型在该维度的建议），
 * 规则未命中的维度采用模型结果。最终按配置 dimensions 的优先级（靠前优先）取唯一分类，
 * 其余命中全部保留在报告明细（hits）中。
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

        List<CategoryHit> hits = new ArrayList<CategoryHit>(ruleClassifier.classify(file));
        if (config.getModel().isEnabled()) {
            List<CategoryHit> modelHits = modelCache.get(file.getPath());
            if (modelHits == null) {
                modelHits = modelClient.suggest(file);
                modelCache.put(file.getPath(), modelHits);
            }
            // 规则优先：规则已命中的维度不接受模型覆盖；模型只补充规则没命中的维度
            Set<String> ruleDimensions = new HashSet<String>();
            for (CategoryHit hit : hits) {
                ruleDimensions.add(hit.getDimension());
            }
            for (CategoryHit hit : modelHits) {
                if (!ruleDimensions.contains(hit.getDimension())) {
                    hits.add(hit);
                }
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
