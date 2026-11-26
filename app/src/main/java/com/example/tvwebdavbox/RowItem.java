package com.example.tvwebdavbox;

import android.graphics.drawable.Drawable;

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
    /** 本地下载目录里是否已经有这个名字的包。 */
    public final boolean downloaded;
    /** 自定义图标（能匹配到已安装应用时用它的图标）。 */
    public Drawable icon;

    public RowItem(String name, String subtitle, boolean directory, Object tag) {
        this(name, subtitle, directory, tag, null, 0);
    }

    public RowItem(String name, String subtitle, boolean directory, Object tag,
                   String badge, int badgeColor) {
        this(name, subtitle, directory, tag, badge, badgeColor, false);
    }

    public RowItem(String name, String subtitle, boolean directory, Object tag,
                   String badge, int badgeColor, boolean downloaded) {
        this.name = name;
        this.subtitle = subtitle;
        this.directory = directory;
        this.tag = tag;
        this.badge = badge;
        this.badgeColor = badgeColor;
        this.downloaded = downloaded;
    }
}
