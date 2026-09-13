package com.qian.categorize;

import com.qian.categorize.cli.CommandLine;

public class Application {

    public static void main(String[] args) throws Exception {
        int code = new CommandLine().run(args);
        if (code != 0) {
            System.exit(code);
        }
    }
}
