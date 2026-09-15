package com.qian.categorize.classifier;

import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ScannedFile;

import java.util.List;

/**
 * 外部/私有化模型适配接口。模型启用时每个文件都会调用一次，返回该文件在多个维度上的
 * 分类建议（可为空列表表示无法判断）；与规则结果合并时，规则已命中的维度以规则为准。
 */
public interface ModelClient {

    List<CategoryHit> suggest(ScannedFile file);
}
