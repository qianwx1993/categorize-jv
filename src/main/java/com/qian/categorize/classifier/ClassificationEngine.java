package com.qian.categorize.classifier;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ReportEntry;
import com.qian.categorize.model.ScannedFile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 分类引擎：每个文件先走规则；模型启用时所有文件再走一遍模型做多维度补充。
 * 合并规则：同一维度规则有命中则以规则为准（忽略模型在该维度的建议），
 * 规则未命中的维度采用模型结果。最终按配置 dimensions 的优先级（靠前优先）取唯一分类，
 * 其余命中全部保留在报告明细（hits）中。
 *
 * <p>模型走 {@link ModelClient#suggestAll(List)} 批量接口：模型一次看到本次扫描的
 * 全部文件名，通观全局归纳类目，而不是逐文件凭空造分类。
 */
public class ClassificationEngine {

    /** 兼容旧引用，固定标识定义在 {@link ReportEntry#UNCATEGORIZED}。 */
    public static final String UNCATEGORIZED = ReportEntry.UNCATEGORIZED;

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
        List<ScannedFile> one = Collections.singletonList(file);
        return classifyAll(one).get(0);
    }

    /**
     * 整批归类：规则逐文件本地匹配（不花钱），模型只调用一次（批量）看到全部文件名。
     * 返回顺序与 files 一一对应。
     */
    public List<ReportEntry> classifyAll(List<ScannedFile> files) {
        // 1. 规则先行
        List<List<CategoryHit>> ruleHits = new ArrayList<List<CategoryHit>>(files.size());
        for (ScannedFile f : files) {
            ruleHits.add(ruleClassifier.classify(f));
        }

        // 2. 模型批量补充（命中过缓存的文件不再重复请求）
        Map<String, List<CategoryHit>> modelHitsByPath =
                new HashMap<String, List<CategoryHit>>();
        if (config.getModel().isEnabled() && !files.isEmpty()) {
            List<ScannedFile> pending = new ArrayList<ScannedFile>();
            for (ScannedFile f : files) {
                List<CategoryHit> cached = modelCache.get(f.getPath());
                if (cached != null) {
                    modelHitsByPath.put(f.getPath(), cached);
                } else {
                    pending.add(f);
                }
            }
            if (!pending.isEmpty()) {
                List<List<CategoryHit>> batch = safeBatch(pending);
                for (int i = 0; i < pending.size(); i++) {
                    List<CategoryHit> hits = batch.get(i);
                    if (hits == null) {
                        hits = Collections.emptyList();
                    }
                    modelCache.put(pending.get(i).getPath(), hits);
                    modelHitsByPath.put(pending.get(i).getPath(), hits);
                }
            }
        }

        // 3. 合并 + 按维度优先级选最终分类
        List<ReportEntry> entries = new ArrayList<ReportEntry>(files.size());
        for (int i = 0; i < files.size(); i++) {
            ScannedFile f = files.get(i);
            List<CategoryHit> hits = new ArrayList<CategoryHit>(ruleHits.get(i));
            Set<String> ruleDimensions = new HashSet<String>();
            for (CategoryHit hit : hits) {
                ruleDimensions.add(hit.getDimension());
            }
            // 规则优先：规则已命中的维度不接受模型覆盖；模型只补充规则没命中的维度
            List<CategoryHit> modelHits = modelHitsByPath.get(f.getPath());
            if (modelHits != null) {
                for (CategoryHit hit : modelHits) {
                    if (!ruleDimensions.contains(hit.getDimension())) {
                        hits.add(hit);
                    }
                }
            }
            entries.add(buildEntry(f, hits));
        }
        return entries;
    }

    /** 模型批量结果长度异常（适配器实现错误）时退化为逐文件调用，保证不崩。 */
    private List<List<CategoryHit>> safeBatch(List<ScannedFile> pending) {
        List<List<CategoryHit>> batch = modelClient.suggestAll(pending);
        if (batch != null && batch.size() == pending.size()) {
            return batch;
        }
        List<List<CategoryHit>> fallback =
                new ArrayList<List<CategoryHit>>(pending.size());
        for (ScannedFile f : pending) {
            fallback.add(modelClient.suggest(f));
        }
        return fallback;
    }

    private ReportEntry buildEntry(ScannedFile file, List<CategoryHit> hits) {
        ReportEntry entry = new ReportEntry();
        entry.setPath(file.getPath());
        entry.setName(file.getName());
        entry.setSize(file.getSize());
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
