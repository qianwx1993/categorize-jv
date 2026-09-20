package com.qian.categorize;

import com.qian.categorize.cli.CommandLine;
import com.qian.categorize.ui.DesktopApp;

import java.awt.GraphicsEnvironment;

public class Application {

    public static void main(String[] args) throws Exception {
        // 无参数且非 headless → 桌面窗口；带命令行参数 → CLI 工作流
        if (args.length == 0 && !GraphicsEnvironment.isHeadless()) {
            DesktopApp.launch();
            return;
        }
        int code = new CommandLine().run(args);
        if (code != 0) {
            System.exit(code);
        }
    }
}
