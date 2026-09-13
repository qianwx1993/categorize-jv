package com.qian.categorize.classifier;

import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ScannedFile;

import java.util.List;

/**
 * 外部/私有化模型适配接口。规则层无法判断时才调用。
 * 返回按维度给出的分类建议（可为空列表表示无法判断）。
 */
public interface ModelClient {

    List<CategoryHit> suggest(ScannedFile file);
}
