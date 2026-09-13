package com.qian.categorize.classifier;

import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ScannedFile;

import java.util.Collections;
import java.util.List;

/**
 * 未配置模型时使用：任何文件都返回空，流程不阻塞，文件进入「未分类」。
 */
public class NullModelClient implements ModelClient {

    @Override
    public List<CategoryHit> suggest(ScannedFile file) {
        return Collections.emptyList();
    }
}
