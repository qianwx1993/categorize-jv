package com.qian.categorize.report;

import com.qian.categorize.classifier.ClassificationEngine;
import com.qian.categorize.classifier.NullModelClient;
import com.qian.categorize.config.AppConfig;
import com.qian.categorize.model.CategorizeReport;
import com.qian.categorize.model.ScannedFile;
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

    @Test
    void writesDetailAndSummarySheets(@TempDir Path root) throws Exception {
        Files.write(root.resolve("教父1.mp4"), new byte[1024]);
        Files.write(root.resolve("家庭录像.mp4"), new byte[2048]);

        AppConfig config = new AppConfig();
        config.setDimensions(Collections.singletonList("series"));
        config.setOutputDir(root.resolve("categorized").toString());
        config.getRules().put("series",
                Collections.singletonMap("教父系列", Arrays.asList("教父")));

        List<ScannedFile> files = new FileScanner(config).scan(root.toFile());
        ClassificationEngine engine = new ClassificationEngine(config, new NullModelClient());
        CategorizeReport report = new ReportManager(config, engine).build(root.toFile(), files);

        File xlsx = root.resolve("report.xlsx").toFile();
        new ExcelReportWriter().write(report, xlsx);
        assertTrue(xlsx.isFile());
        assertTrue(xlsx.length() > 0);

        InputStream in = new FileInputStream(xlsx);
        try {
            Workbook workbook = new XSSFWorkbook(in);
            try {
                Sheet detail = workbook.getSheet("明细");
                // 表头 + 两个文件，共 3 行（行号 0~2）
                assertEquals(2, detail.getLastRowNum());
                Row header = detail.getRow(0);
                assertEquals("最终分类", header.getCell(4).getStringCellValue());

                Sheet summary = workbook.getSheet("分类汇总");
                assertTrue(summary.getLastRowNum() >= 2, "汇总表至少含表头和两个分类");
            } finally {
                workbook.close();
            }
        } finally {
            in.close();
        }
    }
}
