package com.qian.categorize.model;

/**
 * 扫描阶段发现的一个文件。
 */
public class ScannedFile {

    private String path;
    private String name;
    private long size;
    private long lastModified;

    public ScannedFile() {
    }

    public ScannedFile(String path, String name, long size, long lastModified) {
        this.path = path;
        this.name = name;
        this.size = size;
        this.lastModified = lastModified;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getSize() {
        return size;
    }

    public void setSize(long size) {
        this.size = size;
    }

    public long getLastModified() {
        return lastModified;
    }

    public void setLastModified(long lastModified) {
        this.lastModified = lastModified;
    }
}
