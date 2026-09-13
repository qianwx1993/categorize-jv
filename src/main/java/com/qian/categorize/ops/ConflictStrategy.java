package com.qian.categorize.ops;

/**
 * 目标位置已存在同名文件时的处理策略。
 */
public enum ConflictStrategy {
    /** 自动重命名为 name (2).ext（默认，永不静默覆盖） */
    RENAME,
    /** 跳过该文件 */
    SKIP,
    /** 覆盖已有文件 */
    OVERWRITE;

    public static ConflictStrategy fromConfig(String value) {
        if (value == null) {
            return RENAME;
        }
        try {
            return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RENAME;
        }
    }
}
