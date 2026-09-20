package com.qian.categorize.classifier;

import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.ScannedFile;

import java.util.ArrayList;
import java.util.List;

/**
 * 外部/私有化模型适配接口。模型启用时由分类引擎调用，返回每个文件在多个维度上的
 * 分类建议（空列表表示无法判断）；与规则结果合并时，规则已命中的维度以规则为准。
 *
 * <p>优先实现 {@link #suggestAll(List)}：让模型一次看到整批文件名，通观全局后再归纳
 * 分类，避免逐文件判断时凭空造出碎片化类目（如给单个生活视频造「婚礼」「美食」）。
 * 不支持批量时可不实现本方法，默认退化为逐个调用 {@link #suggest(ScannedFile)}。
 */
public interface ModelClient {

    List<CategoryHit> suggest(ScannedFile file);

    /**
     * 批量归类：入参与返回结果一一对应、同序，返回列表长度必须等于 files.size()，
     * 某个元素为空列表表示该文件所有维度都无法判断。
     */
    default List<List<CategoryHit>> suggestAll(List<ScannedFile> files) {
        List<List<CategoryHit>> results = new ArrayList<List<CategoryHit>>(files.size());
        for (ScannedFile file : files) {
            results.add(suggest(file));
        }
        return results;
    }
}
