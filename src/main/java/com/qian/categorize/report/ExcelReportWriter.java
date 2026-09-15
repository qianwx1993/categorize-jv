package com.qian.categorize.report;

import com.qian.categorize.model.CategoryHit;
import com.qian.categorize.model.CategorizeReport;
import com.qian.categorize.model.ReportEntry;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生成供人工检查的 Excel(.xlsx) 报告：仅用于查看，apply 不读此文件（权威输入是 JSON）。
 * 两个工作表：明细（逐文件）+ 汇总（按分类统计）。
 */
public class ExcelReportWriter {

    public void write(CategorizeReport report, File xlsxFile) throws IOException {
        File parent = xlsxFile.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Workbook workbook = new XSSFWorkbook();
        try {
            CellStyle headerStyle = headerStyle(workbook);
            writeDetailSheet(workbook, headerStyle, report);
            writeSummarySheet(workbook, headerStyle, report);
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

    private void writeDetailSheet(Workbook workbook, CellStyle headerStyle,
                                  CategorizeReport report) {
        Sheet sheet = workbook.createSheet("明细");
        String[] headers = {"序号", "文件路径", "文件名", "大小", "最终分类",
                "低置信度", "是否排除", "命中明细（维度=分类[来源]）"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }

        int rowIndex = 1;
        for (ReportEntry e : report.getEntries()) {
            Row row = sheet.createRow(rowIndex);
            row.createCell(0).setCellValue(rowIndex);
            row.createCell(1).setCellValue(e.getPath());
            row.createCell(2).setCellValue(e.getName());
            row.createCell(3).setCellValue(ReportManager.humanSize(e.getSize()));
            row.createCell(4).setCellValue(e.getCategory());
            row.createCell(5).setCellValue(e.isLowConfidence() ? "是" : "");
            row.createCell(6).setCellValue(e.isExcluded() ? "是" : "");
            row.createCell(7).setCellValue(hitsDetail(e));
            rowIndex++;
        }

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.createFreezePane(0, 1);
        if (rowIndex > 1) {
            sheet.setAutoFilter(new org.apache.poi.ss.util.CellRangeAddress(
                    0, 0, 0, headers.length - 1));
        }
    }

    private void writeSummarySheet(Workbook workbook, CellStyle headerStyle,
                                   CategorizeReport report) {
        Map<String, long[]> stats = new LinkedHashMap<String, long[]>();
        long totalActive = 0;
        long totalSize = 0;
        for (ReportEntry e : report.getEntries()) {
            if (e.isExcluded()) {
                continue;
            }
            long[] countAndSize = stats.get(e.getCategory());
            if (countAndSize == null) {
                countAndSize = new long[2];
                stats.put(e.getCategory(), countAndSize);
            }
            countAndSize[0]++;
            countAndSize[1] += e.getSize();
            totalActive++;
            totalSize += e.getSize();
        }

        Sheet sheet = workbook.createSheet("分类汇总");
        String[] headers = {"分类", "文件数", "总大小"};
        Row header = sheet.createRow(0);
        for (int i = 0; i < headers.length; i++) {
            org.apache.poi.ss.usermodel.Cell cell = header.createCell(i);
            cell.setCellValue(headers[i]);
            cell.setCellStyle(headerStyle);
        }
        int rowIndex = 1;
        for (Map.Entry<String, long[]> entry : stats.entrySet()) {
            Row row = sheet.createRow(rowIndex++);
            row.createCell(0).setCellValue(entry.getKey());
            row.createCell(1).setCellValue(entry.getValue()[0]);
            row.createCell(2).setCellValue(ReportManager.humanSize(entry.getValue()[1]));
        }
        Row total = sheet.createRow(rowIndex);
        total.createCell(0).setCellValue("合计（不含排除）");
        total.createCell(1).setCellValue(totalActive);
        total.createCell(2).setCellValue(ReportManager.humanSize(totalSize));

        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.createFreezePane(0, 1);
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

    private static CellStyle headerStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        Font font = workbook.createFont();
        font.setBold(true);
        style.setFont(font);
        return style;
    }
}
