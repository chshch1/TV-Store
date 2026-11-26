package com.example.tvwebdavbox;

/** 列表行数据，远端目录、本地目录、应用列表共用同一套适配器。 */
public final class RowItem {

    public final String name;
    public final String subtitle;
    public final boolean directory;
    /** 关联的原始对象（RemoteEntry / File / InstalledApp）。 */
    public final Object tag;
    /** 右侧角标文字，null 表示不显示。 */
    public final String badge;
    /** 角标颜色，0 表示使用默认色。 */
    public final int badgeColor;

    public RowItem(String name, String subtitle, boolean directory, Object tag) {
        this(name, subtitle, directory, tag, null, 0);
    }

    public RowItem(String name, String subtitle, boolean directory, Object tag,
                   String badge, int badgeColor) {
        this.name = name;
        this.subtitle = subtitle;
        this.directory = directory;
        this.tag = tag;
        this.badge = badge;
        this.badgeColor = badgeColor;
    }
}
