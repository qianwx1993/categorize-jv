package com.qian.categorize.classifier;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ScannedFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 关键词规则分类：在文件名（小写）中包含关键词即命中。
 * 每个维度最多产生一个 hit（按配置中分类声明顺序取第一个命中）。
 */
public class RuleClassifier {

    static final double RULE_CONFIDENCE = 0.9d;

    private final AppConfig config;

    public RuleClassifier(AppConfig config) {
        this.config = config;
    }

    public List<CategoryHit> classify(ScannedFile file) {
        List<CategoryHit> hits = new ArrayList<CategoryHit>();
        String lowerName = file.getName().toLowerCase(Locale.ROOT);
        for (String dimension : config.getDimensions()) {
            Map<String, List<String>> categories = config.getRules().get(dimension);
            if (categories == null) {
                continue;
            }
            for (Map.Entry<String, List<String>> e : categories.entrySet()) {
                if (matches(lowerName, e.getValue())) {
                    hits.add(new CategoryHit(dimension, e.getKey(), RULE_CONFIDENCE, "RULE"));
                    break;
                }
            }
        }
        return hits;
    }

    private boolean matches(String lowerName, List<String> keywords) {
        if (keywords == null) {
            return false;
        }
        for (String kw : keywords) {
            if (kw != null && !kw.isEmpty()
                    && lowerName.contains(kw.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
