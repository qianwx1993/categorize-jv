package com.qian.categorize.report;

import com.qian.categorize.classifier.ClassificationEngine;
import com.qian.categorize.classifier.NullModelClient;
import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.BatchManifest;
import com.qian.categorize.model.CategorizeReport;
import com.qian.categorize.model.ManifestEntry;
import com.qian.categorize.model.ScannedFile;
import com.qian.categorize.ops.FileMover;
import com.qian.categorize.scanner.FileScanner;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExcelReportWriterTest {

    private CategorizeReport buildReport(Path root, AppConfig config) throws Exception {
        List<ScannedFile> files = new FileScanner(config).scan(root.toFile());
        ClassificationEngine engine = new ClassificationEngine(config, new NullModelClient());
        return new ReportManager(config, engine).build(root.toFile(), files);
    }

    @Test
    void writesDetailSummaryAndNotesSheets(@TempDir Path root) throws Exception {
        Files.write(root.resolve("教父1.mp4"), new byte[1024]);
        Files.write(root.resolve("教父2.mp4"), new byte[2048]);
        Files.write(root.resolve("家庭录像.mp4"), new byte[2048]);

        AppConfig config = new AppConfig();
        config.setDimensions(Collections.singletonList("series"));
        config.setOutputDir(root.resolve("categorized").toString());
        config.getRules().put("series",
                Collections.singletonMap("教父系列", Arrays.asList("教父")));

        CategorizeReport report = buildReport(root, config);

        File xlsx = root.resolve("report.xlsx").toFile();
        new ExcelReportWriter().write(report, xlsx);
        assertTrue(xlsx.isFile());
        assertTrue(xlsx.length() > 0);

        InputStream in = new FileInputStream(xlsx);
        try {
            Workbook workbook = new XSSFWorkbook(in);
            try {
                Sheet detail = workbook.getSheet("分类明细");
                // 表头 + 三个文件，共 4 行（行号 0~3）
                assertEquals(3, detail.getLastRowNum());
                Row header = detail.getRow(0);
                assertEquals("最终分类", header.getCell(0).getStringCellValue());
                assertEquals("文件名", header.getCell(2).getStringCellValue());
                assertEquals("处理方式", header.getCell(3).getStringCellValue());
                assertEquals("移动后路径（预计）", header.getCell(7).getStringCellValue());

                // 同分类的两行连续排列在最前，首列单元格纵向合并（第 1~2 行、第 0 列）
                assertEquals("教父系列", detail.getRow(1).getCell(0).getStringCellValue());
                assertEquals("教父系列", detail.getRow(2).getCell(0).getStringCellValue());
                assertEquals(1, detail.getNumMergedRegions());
                assertEquals(0, detail.getMergedRegion(0).getFirstColumn());
                assertEquals(0, detail.getMergedRegion(0).getLastColumn());
                assertEquals(1, detail.getMergedRegion(0).getFirstRow());
                assertEquals(2, detail.getMergedRegion(0).getLastRow());

                // 未分类行排在最后：处理方式=保持原位，预计移动后路径=原路径
                Row uncategorized = findRowByName(detail, "家庭录像.mp4");
                assertEquals(3, uncategorized.getRowNum());
                assertEquals("未分类", uncategorized.getCell(0).getStringCellValue());
                assertEquals("保持原位（未分类）", uncategorized.getCell(3).getStringCellValue());
                assertEquals(uncategorized.getCell(6).getStringCellValue(),
                        uncategorized.getCell(7).getStringCellValue());

                // 命中规则行：预计路径落在 输出目录/教父系列/ 下
                Row moved = findRowByName(detail, "教父1.mp4");
                assertEquals("移动到分类目录", moved.getCell(3).getStringCellValue());
                assertTrue(moved.getCell(7).getStringCellValue()
                        .replace(File.separatorChar, '/').contains("categorized/教父系列/教父1.mp4"));

                assertTrue(workbook.getSheet("分类汇总") != null);
                assertTrue(workbook.getSheet("说明") != null);
            } finally {
                workbook.close();
            }
        } finally {
            in.close();
        }
    }

    @Test
    void resultWorkbookContainsActualTargetAndKeptStatus(@TempDir Path root) throws Exception {
        byte[] content = new byte[2048];
        Files.write(root.resolve("教父1.mp4"), content);
        Files.write(root.resolve("教父2.mp4"), content);
        Files.write(root.resolve("杂项.txt"), new byte[10]);

        AppConfig config = new AppConfig();
        config.setDimensions(Collections.singletonList("series"));
        config.setOutputDir(root.resolve("categorized").toString());
        config.getRules().put("series",
                Collections.singletonMap("教父系列", Arrays.asList("教父")));
        CategorizeReport report = buildReport(root, config);

        FileMover mover = new FileMover(config);
        BatchManifest manifest = mover.newBatch(report);
        List<ManifestEntry> results = mover.apply(report, manifest);

        File xlsx = root.resolve("result.xlsx").toFile();
        new ExcelReportWriter().writeResult(manifest, report, results, xlsx);
        assertTrue(xlsx.isFile());

        InputStream in = new FileInputStream(xlsx);
        try {
            Workbook workbook = new XSSFWorkbook(in);
            try {
                Sheet sheet = workbook.getSheet("执行结果明细");
                Row header = sheet.getRow(0);
                assertEquals("分类", header.getCell(0).getStringCellValue());
                assertEquals("状态", header.getCell(4).getStringCellValue());
                assertEquals("移动后路径", header.getCell(6).getStringCellValue());

                // 两个教父系列文件排最前且首列合并，未分类排最后
                assertEquals("教父系列", sheet.getRow(1).getCell(0).getStringCellValue());
                assertEquals("教父系列", sheet.getRow(2).getCell(0).getStringCellValue());
                assertEquals(1, sheet.getNumMergedRegions());
                assertEquals(1, sheet.getMergedRegion(0).getFirstRow());
                assertEquals(2, sheet.getMergedRegion(0).getLastRow());
                assertEquals(3, findRowByName(sheet, "杂项.txt").getRowNum());

                Row moved = findRowByName(sheet, "教父1.mp4");
                assertEquals("成功移动", moved.getCell(4).getStringCellValue());
                assertTrue(moved.getCell(6).getStringCellValue()
                        .replace(File.separatorChar, '/').contains("categorized/教父系列/教父1.mp4"));

                Row kept = findRowByName(sheet, "杂项.txt");
                assertEquals("未分类·保持原位", kept.getCell(4).getStringCellValue());
                // 保持原位：移动后路径记录的就是原路径，文件确实没动
                assertEquals(kept.getCell(5).getStringCellValue(),
                        kept.getCell(6).getStringCellValue());
                assertTrue(Files.exists(root.resolve("杂项.txt")));

                assertTrue(workbook.getSheet("结果汇总") != null);
            } finally {
                workbook.close();
            }
        } finally {
            in.close();
        }
    }

    private static Row findRowByName(Sheet sheet, String name) {
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && name.equals(row.getCell(2).getStringCellValue())) {
                return row;
            }
        }
        throw new AssertionError("Excel 中缺少文件行: " + name);
    }
}
