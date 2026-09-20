package com.qian.categorize.classifier;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ReportEntry;
import com.qian.categorize.model.ScannedFile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassificationEngineTest {

    private AppConfig configWithRules() {
        AppConfig config = new AppConfig();
        config.setDimensions(Arrays.asList("series", "actor"));
        // series: 教父系列
        config.getRules().put("series",
                Collections.singletonMap("教父系列", Arrays.asList("教父", "godfather")));
        // actor: 阿尔·帕西诺
        config.getRules().put("actor",
                Collections.singletonMap("阿尔·帕西诺", Arrays.asList("帕西诺", "pacino")));
        return config;
    }

    private ScannedFile file(String name) {
        return new ScannedFile("/tmp/" + name, name, 100, 0);
    }

    @Test
    void dimensionPriorityPicksSeriesFirst() {
        AppConfig config = configWithRules();
        ClassificationEngine engine =
                new ClassificationEngine(config, new NullModelClient());

        ReportEntry entry = engine.classify(file("教父2-阿尔·帕西诺.mp4"));

        assertEquals("教父系列", entry.getCategory());
        assertFalse(entry.isLowConfidence());
        assertEquals(2, entry.getHits().size());
    }

    @Test
    void uncategorizedWhenNoHitAndNoModel() {
        AppConfig config = configWithRules();
        ClassificationEngine engine =
                new ClassificationEngine(config, new NullModelClient());

        ReportEntry entry = engine.classify(file("家庭录像001.mp4"));

        assertEquals(ClassificationEngine.UNCATEGORIZED, entry.getCategory());
        assertTrue(entry.isLowConfidence());
        assertTrue(entry.getHits().isEmpty());
    }

    @Test
    void modelFillsDimensionsWhenRulesEmpty() {
        AppConfig config = configWithRules();
        config.getModel().setEnabled(true);
        ClassificationEngine engine = new ClassificationEngine(config, new ModelClient() {
            @Override
            public List<CategoryHit> suggest(ScannedFile f) {
                return Collections.singletonList(
                        new CategoryHit("actor", "阿尔·帕西诺", 0.6, "MODEL"));
            }
        });

        ReportEntry entry = engine.classify(file("some-movie.mp4"));

        // 文件名无规则命中，采用模型建议的 actor 维度
        assertEquals("阿尔·帕西诺", entry.getCategory());
        assertTrue(entry.isLowConfidence(), "模型置信度低于默认阈值 0.7，应标低置信度");
    }

    @Test
    void modelAlwaysCalledAndSupplementsOtherDimensions() {
        AppConfig config = configWithRules();
        config.getModel().setEnabled(true);
        final int[] calls = {0};
        ClassificationEngine engine = new ClassificationEngine(config, new ModelClient() {
            @Override
            public List<CategoryHit> suggest(ScannedFile f) {
                calls[0]++;
                // 模型同时给出 series 和 actor 两个维度
                return Arrays.asList(
                        new CategoryHit("series", "模型猜的系列", 0.6, "MODEL"),
                        new CategoryHit("actor", "阿尔·帕西诺", 0.6, "MODEL"));
            }
        });

        ReportEntry entry = engine.classify(file("Godfather.1972.mp4"));

        assertEquals(1, calls[0], "模型启用时即使规则命中也要调用模型");
        // series 规则已命中 → 以规则为准；actor 由模型补充
        assertEquals("教父系列", entry.getCategory(), "series 维度规则优先，不被模型覆盖");
        assertEquals(2, entry.getHits().size(), "规则的 series + 模型补充的 actor");
        boolean actorFromModel = false;
        for (CategoryHit hit : entry.getHits()) {
            if ("actor".equals(hit.getDimension())) {
                actorFromModel = "阿尔·帕西诺".equals(hit.getCategory())
                        && "MODEL".equals(hit.getSource());
            }
            if ("series".equals(hit.getDimension())) {
                assertEquals("教父系列", hit.getCategory());
            }
        }
        assertTrue(actorFromModel, "actor 维度应采用模型补充结果");
    }

    @Test
    void batchClassifyCallsModelOnceAndMergesRules() {
        AppConfig config = configWithRules();
        config.getModel().setEnabled(true);
        final int[] batchCalls = {0};
        final int[] singleCalls = {0};
        ClassificationEngine engine = new ClassificationEngine(config, new ModelClient() {
            @Override
            public List<List<CategoryHit>> suggestAll(List<ScannedFile> files) {
                batchCalls[0]++;
                // 全局视角下：教父文件模型也认 series（被规则覆盖）；
                // 婚礼生活录像模型给空 hits（新提示词要求）；指环王给 series
                List<List<CategoryHit>> out = new ArrayList<List<CategoryHit>>();
                out.add(Collections.singletonList(
                        new CategoryHit("series", "模型猜的教父", 0.6, "MODEL")));
                out.add(Collections.<CategoryHit>emptyList());
                out.add(Collections.singletonList(
                        new CategoryHit("series", "指环王系列", 0.6, "MODEL")));
                return out;
            }

            @Override
            public List<CategoryHit> suggest(ScannedFile f) {
                singleCalls[0]++;
                return Collections.emptyList();
            }
        });

        List<ReportEntry> entries = engine.classifyAll(Arrays.asList(
                file("教父1.mp4"),
                file("结婚典礼.mp4"),
                file("指环王1.mkv")));

        assertEquals(1, batchCalls[0], "应只调用一次批量接口（模型一次看到全部文件名）");
        assertEquals(0, singleCalls[0], "批量接口有结果时不应退化为逐个调用");
        assertEquals("教父系列", entries.get(0).getCategory(), "规则维度优先，不被模型覆盖");
        assertEquals(ClassificationEngine.UNCATEGORIZED, entries.get(1).getCategory(),
                "生活录像模型返回空 → 未分类保持原位，不应出现婚礼/美食类目");
        assertEquals("指环王系列", entries.get(2).getCategory());
    }
}
