package com.example.tvwebdavbox;

/** 远端目录中的一条记录。 */
public final class RemoteEntry {

    public String name = "";
    /** 服务器 <displayname> 原值，仅作兜底。 */
    public String displayName = "";
    /** 该条目的完整可访问 URL（目录以 / 结尾）。 */
    public String url = "";
    public boolean directory;
    public long size;
    /** 毫秒时间戳，0 表示未知。 */
    public long lastModified;

    public RemoteEntry() {
    }

    public RemoteEntry(String name, String url, boolean directory, long size, long lastModified) {
        this.name = name;
        this.url = url;
        this.directory = directory;
        this.size = size;
        this.lastModified = lastModified;
    }
}
