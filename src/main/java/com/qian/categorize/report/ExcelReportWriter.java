package com.qian.categorize.report;

import com.qian.categorize.model.BatchManifest;
import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.CategorizeReport;
import com.qian.categorize.model.ManifestEntry;
import com.qian.categorize.model.ReportEntry;
import com.qian.categorize.ops.FileMover;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFConditionalFormattingRule;
import org.apache.poi.xssf.usermodel.XSSFDataBarFormatting;
import org.apache.poi.xssf.usermodel.XSSFFont;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFSheetConditionalFormatting;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFColor;

import java.awt.Color;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成供人工检查的 Excel(.xlsx) 报告，仅用于查看，apply 不读此文件（权威输入是 JSON）。
 *
 * <p>{@link #write} 为 apply 前的分类报告：明细（分类/名称/原路径/预计移动后路径/
 * 处理方式等，按状态着色）+ 分类汇总（带数据条）+ 说明 三个工作表；
 * {@link #writeResult} 为 apply 后的执行结果：每个文件的实际移动后路径与状态。
 */
public class ExcelReportWriter {

    // 处理方式
    static final String ACTION_MOVE = "移动到分类目录";
    static final String ACTION_KEPT = "保持原位（未分类）";
    static final String ACTION_EXCLUDED = "已排除（不处理）";

    // 执行结果状态
    static final String RESULT_DONE = "成功移动";
    static final String RESULT_KEPT = "未分类·保持原位";
    static final String RESULT_SKIPPED = "同名冲突·已跳过";
    static final String RESULT_FAILED = "移动失败";
    static final String RESULT_EXCLUDED = "已排除（未执行）";

    // 视觉配色（与桌面界面低置信度黄色风格一致）
    private static final Color HEADER_BG = new Color(0x1F4E79);
    private static final Color CATEGORY_FONT = HEADER_BG;
    private static final Color LOW_BG = new Color(0xFFF2CC);
    private static final Color KEPT_BG = new Color(0xFCE4D6);
    private static final Color EXCLUDED_BG = new Color(0xF2F2F2);
    private static final Color DONE_BG = new Color(0xE2EFDA);
    private static final Color SKIPPED_BG = new Color(0xFFF2CC);
    private static final Color FAILED_BG = new Color(0xF8CBAD);
    private static final Color GRAY_FONT = new Color(0x808080);
    private static final Color RED_FONT = new Color(0xC00000);
    private static final Color GREEN_FONT = new Color(0x375623);
    private static final Color BAR_COLOR = new Color(0x5B9BD5);

    /** apply 后结果 Excel 的固定文件名规则，与 manifest 同放输出目录。 */
    public static File resultFileFor(File outputDir, String batchId) {
        return new File(outputDir, "分类结果-" + batchId + ".xlsx");
    }

    // ------------------------------------------------------------------
    // apply 前：分类报告
    // ------------------------------------------------------------------

    public void write(CategorizeReport report, File xlsxFile) throws IOException {
        File parent = xlsxFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Workbook workbook = new XSSFWorkbook();
        try {
            Styles styles = new Styles(workbook);
            writeDetailSheet(workbook, styles, report);
            writeSummarySheet(workbook, styles, report);
            writeNotesSheet(workbook, styles, report);
            OutputStream out = new FileOutputStream(xlsxFile);
            try {
                workbook.write(out);
            } finally {
                out.close();
            }
        } finally {
            workbook.close();
        }
    }

    private void writeDetailSheet(Workbook workbook, Styles styles, CategorizeReport report) {
        Sheet sheet = workbook.createSheet("分类明细");
        // 分类固定第一列：同分类文件连续排列，首列同分类单元格纵向合并
        String[] headers = {"最终分类", "序号", "文件名", "处理方式", "大小",
                "低置信度", "原路径", "移动后路径（预计）", "命中明细（维度=分类[来源,置信度]）"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header);
        }

        int rowIndex = 1;
        for (List<ReportEntry> group : groupByCategory(report.getEntries())) {
            int startRow = rowIndex;
            String categoryKey = displayCategory(group.get(0));
            for (ReportEntry e : group) {
                Row row = sheet.createRow(rowIndex);
                boolean excluded = e.isExcluded();
                boolean kept = !excluded && e.isUncategorized();
                boolean low = !excluded && !kept && e.isLowConfidence();

                CellStyle style = styles.dataStyle(
                        excluded ? EXCLUDED_BG : kept ? KEPT_BG : low ? LOW_BG : null,
                        excluded ? GRAY_FONT : null, false);
                CellStyle center = styles.dataStyle(
                        excluded ? EXCLUDED_BG : kept ? KEPT_BG : low ? LOW_BG : null,
                        excluded ? GRAY_FONT : null, true);

                // 合并区域内每个单元格都写入同值并设置边框（筛选时仍可用，区域边框才完整）
                createCell(row, 0, categoryKey, styles.category());
                createCell(row, 1, rowIndex, center);
                createCell(row, 2, e.getName(), style);
                createCell(row, 3, actionOf(e), center);
                createCell(row, 4, ReportManager.humanSize(e.getSize()), center);
                createCell(row, 5, e.isLowConfidence() && !excluded ? "是" : "", center);
                createCell(row, 6, e.getPath(), style);
                createCell(row, 7, plannedTarget(report, e), style);
                createCell(row, 8, hitsDetail(e), style);
                rowIndex++;
            }
            if (rowIndex - 1 > startRow) {
                sheet.addMergedRegion(new CellRangeAddress(startRow, rowIndex - 1, 0, 0));
            }
        }

        // 列宽（字符数），路径/命中列给宽一些
        int[] widths = {16, 6, 28, 18, 10, 9, 55, 55, 40};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(1, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));
    }

    private void writeSummarySheet(Workbook workbook, Styles styles, CategorizeReport report) {
        // key = 分类显示名；value = [文件数, 总大小, 是否未分类/排除]
        Map<String, long[]> stats = new LinkedHashMap<String, long[]>();
        long excludedCount = 0;
        long excludedSize = 0;
        long moveCount = 0;
        long moveSize = 0;
        for (ReportEntry e : report.getEntries()) {
            if (e.isExcluded()) {
                excludedCount++;
                excludedSize += e.getSize();
                continue;
            }
            String key = e.isUncategorized() ? ReportEntry.UNCATEGORIZED : e.getCategory();
            long[] v = stats.get(key);
            if (v == null) {
                v = new long[2];
                stats.put(key, v);
            }
            v[0]++;
            v[1] += e.getSize();
            if (!e.isUncategorized()) {
                moveCount++;
                moveSize += e.getSize();
            }
        }

        Sheet sheet = workbook.createSheet("分类汇总");
        String[] headers = {"分类", "文件数", "总大小", "处理方式"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header);
        }

        int rowIndex = 1;
        int firstDataRow = rowIndex;
        for (Map.Entry<String, long[]> entry : stats.entrySet()) {
            boolean kept = ReportEntry.UNCATEGORIZED.equals(entry.getKey());
            Row row = sheet.createRow(rowIndex);
            CellStyle base = styles.dataStyle(kept ? KEPT_BG : null, null, false);
            CellStyle center = styles.dataStyle(kept ? KEPT_BG : null, null, true);
            createCell(row, 0, entry.getKey(), base);
            createCell(row, 1, entry.getValue()[0], center);
            createCell(row, 2, ReportManager.humanSize(entry.getValue()[1]), center);
            createCell(row, 3, kept ? ACTION_KEPT : ACTION_MOVE, center);
            rowIndex++;
        }
        int lastDataRow = rowIndex - 1;

        // 合计行
        Row totalMove = sheet.createRow(rowIndex);
        CellStyle bold = styles.totalStyle(null, true);
        createCell(totalMove, 0, "待移动合计", bold);
        createCell(totalMove, 1, moveCount, styles.totalStyle(null, true));
        createCell(totalMove, 2, ReportManager.humanSize(moveSize), styles.totalStyle(null, true));
        createCell(totalMove, 3, "", styles.totalStyle(null, true));
        rowIndex++;

        if (excludedCount > 0) {
            Row ex = sheet.createRow(rowIndex);
            createCell(ex, 0, "已排除（不处理）", styles.totalStyle(EXCLUDED_BG, false));
            createCell(ex, 1, excludedCount, styles.totalStyle(EXCLUDED_BG, false));
            createCell(ex, 2, ReportManager.humanSize(excludedSize),
                    styles.totalStyle(EXCLUDED_BG, false));
            createCell(ex, 3, ACTION_EXCLUDED, styles.totalStyle(EXCLUDED_BG, false));
        }

        int[] widths = {24, 10, 12, 20};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(0, 1);
        // 文件数列加数据条，直观对比各分类文件量
        if (lastDataRow >= firstDataRow) {
            addDataBar(sheet, new CellRangeAddress(firstDataRow, lastDataRow, 1, 1));
        }
    }

    private void writeNotesSheet(Workbook workbook, Styles styles, CategorizeReport report) {
        Sheet sheet = workbook.createSheet("说明");
        sheet.setColumnWidth(0, 100 * 256);
        String[] lines = {
                "分类报告说明",
                "",
                "生成时间：" + nullToEmpty(report.getCreatedAt()),
                "扫描目录：" + nullToEmpty(report.getRootDir()),
                "输出目录：" + nullToEmpty(report.getOutputDir()),
                "",
                "1. 处理方式：",
                "   • 移动到分类目录：执行移动后，文件进入「输出目录/分类名/」；",
                "   • 保持原位（未分类）：识别不出分类的文件不移动，留在原路径；",
                "   • 已排除（不处理）：在报告中勾选排除的文件，本次完全不动。",
                "2. 「移动后路径（预计）」按当前输出目录估算；若目标位置已存在同名文件，",
                "   自动重命名策略下实际文件名为 xxx (2).ext，执行后以结果 Excel 为准。",
                "3. 黄色底纹 = 低置信度文件，建议重点人工复核；",
                "   橙色底纹 = 未分类（保持原位）；灰色底纹 = 已排除。",
                "4. 可直接编辑 JSON 报告修改分类/排除后再执行；执行完成后输出目录下会额外生成",
                "   「分类结果-批次号.xlsx」，记录每个文件的原路径、实际移动后路径和执行状态。",
                "5. 明细按分类分组排列：相同分类的文件排在一起，首列「最终分类」单元格已纵向合并，",
                "   「未分类」统一排在最后。",
        };
        CellStyle title = styles.notesTitle();
        CellStyle body = styles.notesBody();
        for (int i = 0; i < lines.length; i++) {
            Row row = sheet.createRow(i);
            org.apache.poi.ss.usermodel.Cell cell = row.createCell(0);
            cell.setCellValue(lines[i]);
            cell.setCellStyle(i == 0 ? title : body);
        }
    }

    // ------------------------------------------------------------------
    // apply 后：执行结果
    // ------------------------------------------------------------------

    /**
     * 执行移动完成后的结果 Excel：逐文件列出分类、名称、大小、状态、原路径与实际移动后路径；
     * 未分类保持原位和已排除的文件也在其中，状态列区分。
     */
    public void writeResult(BatchManifest manifest, CategorizeReport report,
                            List<ManifestEntry> results, File xlsxFile) throws IOException {
        File parent = xlsxFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        // 同一文件可能因断点续做有多条记录，后执行的为准
        Map<String, ManifestEntry> bySource = new LinkedHashMap<String, ManifestEntry>();
        for (ManifestEntry me : results) {
            bySource.put(me.getSourcePath(), me);
        }

        Workbook workbook = new XSSFWorkbook();
        try {
            Styles styles = new Styles(workbook);
            writeResultDetail(workbook, styles, report, bySource);
            writeResultSummary(workbook, styles, manifest, report, bySource);
            OutputStream out = new FileOutputStream(xlsxFile);
            try {
                workbook.write(out);
            } finally {
                out.close();
            }
        } finally {
            workbook.close();
        }
    }

    private void writeResultDetail(Workbook workbook, Styles styles, CategorizeReport report,
                                   Map<String, ManifestEntry> bySource) {
        Sheet sheet = workbook.createSheet("执行结果明细");
        // 分类固定第一列，同分类纵向合并（与 apply 前报告一致）
        String[] headers = {"分类", "序号", "文件名", "大小", "状态", "原路径", "移动后路径"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header);
        }

        int rowIndex = 1;
        for (List<ReportEntry> group : groupByCategory(report.getEntries())) {
            int startRow = rowIndex;
            String categoryKey = displayCategory(group.get(0));
            for (ReportEntry e : group) {
                String status;
                String targetPath;
                if (e.isExcluded()) {
                    status = RESULT_EXCLUDED;
                    targetPath = "";
                } else {
                    ManifestEntry me = bySource.get(e.getPath());
                    if (me == null) {
                        status = RESULT_EXCLUDED;
                        targetPath = "";
                    } else if (FileMover.DONE.equals(me.getStatus())) {
                        status = RESULT_DONE;
                        targetPath = me.getTargetPath();
                    } else if (FileMover.KEPT.equals(me.getStatus())) {
                        status = RESULT_KEPT;
                        targetPath = me.getTargetPath();
                    } else if (FileMover.SKIPPED.equals(me.getStatus())) {
                        status = RESULT_SKIPPED;
                        targetPath = me.getTargetPath();
                    } else {
                        status = RESULT_FAILED;
                        targetPath = nullToEmpty(me.getTargetPath());
                    }
                }

                Color bg = statusBg(status);
                Color font = RESULT_FAILED.equals(status) ? RED_FONT
                        : RESULT_DONE.equals(status) ? GREEN_FONT
                        : (RESULT_KEPT.equals(status) || RESULT_EXCLUDED.equals(status))
                                ? GRAY_FONT : null;
                CellStyle base = styles.dataStyle(bg, font, false);
                CellStyle center = styles.dataStyle(bg, font, true);

                Row row = sheet.createRow(rowIndex);
                createCell(row, 0, categoryKey, styles.category());
                createCell(row, 1, rowIndex, center);
                createCell(row, 2, e.getName(), base);
                createCell(row, 3, ReportManager.humanSize(e.getSize()), center);
                createCell(row, 4, status, center);
                createCell(row, 5, e.getPath(), base);
                createCell(row, 6, targetPath, base);
                rowIndex++;
            }
            if (rowIndex - 1 > startRow) {
                sheet.addMergedRegion(new CellRangeAddress(startRow, rowIndex - 1, 0, 0));
            }
        }

        int[] widths = {16, 6, 28, 10, 18, 55, 55};
        for (int i = 0; i < widths.length; i++) {
            sheet.setColumnWidth(i, widths[i] * 256);
        }
        sheet.createFreezePane(1, 1);
        sheet.setAutoFilter(new CellRangeAddress(0, 0, 0, headers.length - 1));
    }

    private void writeResultSummary(Workbook workbook, Styles styles, BatchManifest manifest,
                                    CategorizeReport report,
                                    Map<String, ManifestEntry> bySource) {
        Sheet sheet = workbook.createSheet("结果汇总");

        // 批次信息
        String[][] info = {
                {"批次 ID", nullToEmpty(manifest.getBatchId())},
                {"执行时间", nullToEmpty(manifest.getCreatedAt())},
                {"扫描目录", nullToEmpty(manifest.getRootDir())},
                {"输出目录", nullToEmpty(manifest.getOutputDir())},
        };
        int r = 0;
        CellStyle label = styles.infoLabel();
        CellStyle value = styles.infoValue();
        for (String[] pair : info) {
            Row row = sheet.createRow(r);
            org.apache.poi.ss.usermodel.Cell c0 = row.createCell(0);
            c0.setCellValue(pair[0]);
            c0.setCellStyle(label);
            org.apache.poi.ss.usermodel.Cell c1 = row.createCell(1);
            c1.setCellValue(pair[1]);
            c1.setCellStyle(value);
            r++;
        }
        r++;

        // 各状态计数
        String[] statusOrder = {RESULT_DONE, RESULT_KEPT, RESULT_SKIPPED,
                RESULT_FAILED, RESULT_EXCLUDED};
        Map<String, long[]> counts = new LinkedHashMap<String, long[]>();
        for (String s : statusOrder) {
            counts.put(s, new long[2]);
        }
        for (ReportEntry e : report.getEntries()) {
            String status;
            if (e.isExcluded()) {
                status = RESULT_EXCLUDED;
            } else {
                ManifestEntry me = bySource.get(e.getPath());
                if (me == null) {
                    status = RESULT_EXCLUDED;
                } else if (FileMover.DONE.equals(me.getStatus())) {
                    status = RESULT_DONE;
                } else if (FileMover.KEPT.equals(me.getStatus())) {
                    status = RESULT_KEPT;
                } else if (FileMover.SKIPPED.equals(me.getStatus())) {
                    status = RESULT_SKIPPED;
                } else {
                    status = RESULT_FAILED;
                }
            }
            counts.get(status)[0]++;
            counts.get(status)[1] += e.getSize();
        }

        Row header = sheet.createRow(r);
        String[] headers = {"状态", "文件数", "总大小"};
        for (int i = 0; i < headers.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(styles.header);
        }
        r++;
        int firstDataRow = r;
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            if (entry.getValue()[0] == 0) {
                continue;
            }
            Color bg = statusBg(entry.getKey());
            Row row = sheet.createRow(r);
            createCell(row, 0, entry.getKey(), styles.dataStyle(bg, null, false));
            createCell(row, 1, entry.getValue()[0], styles.dataStyle(bg, null, true));
            createCell(row, 2, ReportManager.humanSize(entry.getValue()[1]),
                    styles.dataStyle(bg, null, true));
            r++;
        }
        int lastDataRow = r - 1;
        if (lastDataRow >= firstDataRow) {
            addDataBar(sheet, new CellRangeAddress(firstDataRow, lastDataRow, 1, 1));
        }

        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 10 * 256);
        sheet.setColumnWidth(2, 60 * 256);
    }

    private static Color statusBg(String status) {
        if (RESULT_DONE.equals(status)) {
            return DONE_BG;
        }
        if (RESULT_FAILED.equals(status)) {
            return FAILED_BG;
        }
        if (RESULT_SKIPPED.equals(status)) {
            return SKIPPED_BG;
        }
        return EXCLUDED_BG; // 保持原位 / 已排除
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    /** 分类显示名：未分类（含空分类）统一显示固定标识。 */
    private static String displayCategory(ReportEntry e) {
        return e.isUncategorized() ? ReportEntry.UNCATEGORIZED : e.getCategory();
    }

    /**
     * 按显示分类把报告条目分组：分类按其在报告中首次出现的顺序排列，
     * 「未分类」固定排到最后；组内保持报告原始顺序，保证同分类行连续以便合并首列。
     */
    private static List<List<ReportEntry>> groupByCategory(List<ReportEntry> entries) {
        Map<String, List<ReportEntry>> groups = new LinkedHashMap<String, List<ReportEntry>>();
        for (ReportEntry e : entries) {
            String key = displayCategory(e);
            List<ReportEntry> g = groups.get(key);
            if (g == null) {
                g = new ArrayList<ReportEntry>();
                groups.put(key, g);
            }
            g.add(e);
        }
        List<List<ReportEntry>> ordered = new ArrayList<List<ReportEntry>>();
        List<ReportEntry> uncategorized = groups.remove(ReportEntry.UNCATEGORIZED);
        ordered.addAll(groups.values());
        if (uncategorized != null) {
            ordered.add(uncategorized);
        }
        return ordered;
    }

    private static String actionOf(ReportEntry e) {
        if (e.isExcluded()) {
            return ACTION_EXCLUDED;
        }
        if (e.isUncategorized()) {
            return ACTION_KEPT;
        }
        return ACTION_MOVE;
    }

    /** 预计移动后完整路径：未分类/已排除按规则给出原值或空串。 */
    private static String plannedTarget(CategorizeReport report, ReportEntry e) {
        if (e.isExcluded()) {
            return "";
        }
        if (e.isUncategorized()) {
            return e.getPath();
        }
        File dir = new File(report.getOutputDir(), FileMover.sanitize(e.getCategory()));
        return new File(dir, e.getName()).getAbsolutePath();
    }

    private static void addDataBar(Sheet sheet, CellRangeAddress range) {
        if (!(sheet instanceof XSSFSheet)) {
            return;
        }
        XSSFSheetConditionalFormatting scf =
                (XSSFSheetConditionalFormatting) sheet.getSheetConditionalFormatting();
        // 该重载会直接以传入颜色创建一条数据条规则
        XSSFConditionalFormattingRule rule =
                scf.createConditionalFormattingRule(new XSSFColor(BAR_COLOR, null));
        XSSFDataBarFormatting dataBar = (XSSFDataBarFormatting) rule.getDataBarFormatting();
        dataBar.setWidthMin(0);
        dataBar.setWidthMax(100);
        scf.addConditionalFormatting(new CellRangeAddress[]{range}, rule);
    }

    private static void createCell(Row row, int column, String value, CellStyle style) {
        org.apache.poi.ss.usermodel.Cell cell = row.createCell(column);
        cell.setCellValue(value == null ? "" : value);
        cell.setCellStyle(style);
    }

    private static void createCell(Row row, int column, long value, CellStyle style) {
        org.apache.poi.ss.usermodel.Cell cell = row.createCell(column);
        cell.setCellValue(value);
        cell.setCellStyle(style);
    }

    private static String hitsDetail(ReportEntry e) {
        List<String> parts = new ArrayList<String>();
        for (CategoryHit hit : e.getHits()) {
            String source = "MODEL".equals(hit.getSource()) ? "模型" : "规则";
            parts.add(hit.getDimension() + "=" + hit.getCategory()
                    + "[" + source + "," + trimConfidence(hit.getConfidence()) + "]");
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(p);
        }
        return sb.toString();
    }

    private static String trimConfidence(double confidence) {
        return String.format(java.util.Locale.ROOT, "%.2f", confidence);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** 工作簿内可复用的样式集合（POI 单工作簿样式数有上限，统一在此创建缓存）。 */
    private static class Styles {
        private final Workbook workbook;
        private final CellStyle header;
        private final Map<String, CellStyle> dataCache =
                new LinkedHashMap<String, CellStyle>();
        private final Map<String, CellStyle> totalCache =
                new LinkedHashMap<String, CellStyle>();
        private CellStyle notesTitleCache;
        private CellStyle notesBodyCache;
        private CellStyle infoLabelCache;
        private CellStyle infoValueCache;
        private CellStyle categoryCache;

        Styles(Workbook workbook) {
            this.workbook = workbook;
            this.header = buildHeader();
        }

        private CellStyle buildHeader() {
            XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
            style.setFillForegroundColor(new XSSFColor(HEADER_BG, null));
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            Font font = workbook.createFont();
            font.setBold(true);
            font.setColor(IndexedColors.WHITE.getIndex());
            style.setFont(font);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            applyBorder(style);
            return style;
        }

        /** 数据行样式：bg 为 null 时白底；fontColor 为 null 时默认字体；center 控制对齐。 */
        CellStyle dataStyle(Color bg, Color fontColor, boolean center) {
            String key = String.valueOf(bg) + "|" + String.valueOf(fontColor) + "|" + center;
            CellStyle cached = dataCache.get(key);
            if (cached != null) {
                return cached;
            }
            XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
            if (bg != null) {
                style.setFillForegroundColor(new XSSFColor(bg, null));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            style.setAlignment(center ? HorizontalAlignment.CENTER : HorizontalAlignment.LEFT);
            style.setVerticalAlignment(VerticalAlignment.TOP);
            style.setWrapText(false);
            if (fontColor != null) {
                XSSFFont font = (XSSFFont) workbook.createFont();
                font.setColor(new XSSFColor(fontColor, null));
                style.setFont(font);
            }
            applyBorder(style);
            dataCache.put(key, style);
            return style;
        }

        /** 首列分类单元格样式：加粗深蓝、水平垂直居中、带边框；合并区域内每格同样设置。 */
        CellStyle category() {
            if (categoryCache == null) {
                XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
                style.setAlignment(HorizontalAlignment.CENTER);
                style.setVerticalAlignment(VerticalAlignment.CENTER);
                XSSFFont font = (XSSFFont) workbook.createFont();
                font.setBold(true);
                font.setColor(new XSSFColor(CATEGORY_FONT, null));
                style.setFont(font);
                applyBorder(style);
                categoryCache = style;
                return categoryCache;
            }
            return categoryCache;
        }

        /** 汇总表合计/特殊行样式。 */
        CellStyle totalStyle(Color bg, boolean bold) {
            String key = String.valueOf(bg) + "|" + bold;
            CellStyle cached = totalCache.get(key);
            if (cached != null) {
                return cached;
            }
            XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
            if (bg != null) {
                style.setFillForegroundColor(new XSSFColor(bg, null));
                style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            style.setAlignment(HorizontalAlignment.LEFT);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            Font font = workbook.createFont();
            font.setBold(bold);
            if (bg != null) {
                ((XSSFFont) font).setColor(new XSSFColor(GRAY_FONT, null));
            }
            style.setFont(font);
            applyBorder(style);
            totalCache.put(key, style);
            return style;
        }

        CellStyle notesTitle() {
            if (notesTitleCache == null) {
                CellStyle style = workbook.createCellStyle();
                Font font = workbook.createFont();
                font.setBold(true);
                font.setFontHeightInPoints((short) 14);
                style.setFont(font);
                notesTitleCache = style;
            }
            return notesTitleCache;
        }

        CellStyle notesBody() {
            if (notesBodyCache == null) {
                CellStyle style = workbook.createCellStyle();
                style.setVerticalAlignment(VerticalAlignment.TOP);
                notesBodyCache = style;
            }
            return notesBodyCache;
        }

        CellStyle infoLabel() {
            if (infoLabelCache == null) {
                XSSFCellStyle style = (XSSFCellStyle) workbook.createCellStyle();
                Font font = workbook.createFont();
                font.setBold(true);
                style.setFont(font);
                infoLabelCache = style;
            }
            return infoLabelCache;
        }

        CellStyle infoValue() {
            if (infoValueCache == null) {
                CellStyle style = workbook.createCellStyle();
                infoValueCache = style;
            }
            return infoValueCache;
        }

        private static void applyBorder(CellStyle style) {
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setTopBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setBottomBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setLeftBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
            style.setRightBorderColor(IndexedColors.GREY_25_PERCENT.getIndex());
        }
    }
}
