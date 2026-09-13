package com.qian.categorize.classifier;

import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ReportEntry;
import com.qian.categorize.model.ScannedFile;
import org.junit.jupiter.api.Test;

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
    void modelFallbackWhenRulesEmpty() {
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

        // 文件名无规则命中，走模型；模型建议归属 actor 维度
        assertEquals("阿尔·帕西诺", entry.getCategory());
        assertTrue(entry.isLowConfidence(), "模型置信度低于默认阈值 0.7，应标低置信度");
    }

    @Test
    void modelNotCalledWhenRuleHits() {
        AppConfig config = configWithRules();
        config.getModel().setEnabled(true);
        final boolean[] called = {false};
        ClassificationEngine engine = new ClassificationEngine(config, new ModelClient() {
            @Override
            public List<CategoryHit> suggest(ScannedFile f) {
                called[0] = true;
                return Collections.emptyList();
            }
        });

        engine.classify(file("Godfather.1972.mp4"));

        assertFalse(called[0], "规则已命中时不应调用模型");
    }
}
