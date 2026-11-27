# WebDAV 电视盒 (TVWebDavBox)

面向 **Android 6.0（API 23）及以上** 电视盒子 / 电视的轻量 WebDAV 客户端。
参考 [PushTV](https://github.com/ZhhHugoChang/PushTV) 的 WebDAV 传输链路与
[TVFileBox](https://github.com/w1lli4m666-droid/TVFileBox) 的电视端交互形态，
**只保留 WebDAV 相关能力**，不含局域网 HTTP 上传服务、不含二维码、不含 APK 备份仓库等其它模块。

## 功能

| 分类 | 说明 |
| --- | --- |
| 连接配置 | 服务器地址、用户名、密码、远端根目录；支持自签名 HTTPS（可选） |
| 目录浏览 | PROPFIND `Depth: 1` 列目录，目录优先、中文排序，显示大小与修改时间 |
| 中文文件名 | 文件名以 href 的 percent 编码还原为准（displayname 仅兜底），并做 UTF-8 / GBK / Latin-1 乱码修复 |
| 下载 | 流式下载到本机私有目录，实时进度，支持临时文件（`.part`） |
| 播放 | 内置 `VideoView` 播放器，不依赖第三方播放器；遥控器方向键控制进度 |
| 安装 | 下载 `.apk` 后交给系统安装器（含未知来源权限引导） |
| 应用备份 | 「应用」页可**多选本机已安装应用**，批量上传为 `<应用名>_v<版本号>.apk` |
| 上传文件 | 内置本地文件选择器，PUT 上传当前目录，实时进度 |
| 版本比较 | 远端 APK 行显示角标：未安装 / 已安装·同版本 / 可升级 / 本机更高 / 版本无法比较 |
| 新建目录 | MKCOL |
| 删除 | DELETE（二次确认） |

遥控器操作：方向键选择 · 确认键打开 · 长按确认键或 **菜单键/设置键** 弹出操作菜单 · 返回键上一级。

### 版本比较规则

- 文件名约定（与 PushTV 完全兼容，可直接复用其历史备份）：`<应用名>_v<版本号>.apk`，例如 `微信_v8.0.42.apk`
- 按点分段做数值比较，末位补 0：`1.10 > 1.9`、`1.2 == 1.2.0`、`1.2.3-beta` 取 `1.2.3`
- 应用名中的非法文件名字符与空格转换为 `_`，比较时归一化（`Google_Play` ↔ `Google Play`）

### 中文乱码的处理链路

1. 响应体按 `Content-Type: charset` → XML 声明 `encoding` → UTF-8 的顺序解码；
2. 文件名取 href 末段并做 percent 还原；若 UTF-8 还原出替换字符，再用 GBK 还原一次；
3. 若结果仍不含中日韩字符，尝试按 ISO-8859-1 / windows-1252 回退字节后按 UTF-8 重解（修复 `ä¸­æ–‡` 类乱码）；
4. `<displayname>` 只在前三步都拿不到名字时使用，避免服务器把 displayname 输出成未还原的编码串。

## 兼容性

- `minSdk 23`（Android 6.0），`targetSdk 28`（保留旧存储模型，便于侧载）
- 未包含任何原生 `.so`，单 APK 通用（armeabi-v7a / arm64-v8a / x86 均可安装）
- 唯一第三方依赖：`okhttp:3.12.13`（兼容 API 21+ 的最后一代，体积小）

## 目录结构

```
app/src/main/java/com/example/tvwebdavbox/
├── MainActivity.java          # 主界面：浏览、下载、上传、删除、建目录、遥控器按键、版本角标
├── SettingsActivity.java      # WebDAV 服务器配置 + 连接测试
├── LocalPickerActivity.java   # 本机文件选择器（用于上传单个文件）
├── AppListActivity.java       # 已安装应用多选页（批量备份上传）
├── PlayerActivity.java        # 内置视频播放器
├── WebDavClient.java          # PROPFIND / MKCOL / PUT / GET / DELETE
├── VersionComparator.java     # 版本号比较
├── ApkFileNames.java          # `<应用名>_v<版本号>.apk` 命名与解析
├── Mojibake.java              # 中文乱码修复
├── InstalledApp.java / InstalledAppRepository.java  # 已安装应用索引
├── WebDavConfig.java          # 连接参数
├── PreferenceStore.java       # 配置持久化
├── RemoteEntry.java / RowItem.java / EntryAdapter.java / AppAdapter.java
├── LocalFileProvider.java     # content:// 提供下载文件
└── FileUtils.java
```

## 本地构建

```bash
# 需要 JDK 17 + Android SDK（platform 35、build-tools 34.0.0）
echo "sdk.dir=/path/to/android-sdk" > local.properties
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/TVWebDavBox_v1.1.1_release.apk
```

Release 默认使用 debug 签名，便于直接侧载；正式分发请自备 keystore。

## 常见服务器配置示例

| 服务 | 地址示例 | 备注 |
| --- | --- | --- |
| 群晖 WebDAV Server | `http://192.168.1.10:5005` | 端口在套件里设置，路径通常为 `/` |
| 坚果云 | `https://dav.jianguoyun.com/dav` | 密码填「应用密码」而非登录密码 |
| Nextcloud | `https://nas.example.com/remote.php/dav/files/<用户名>` | 远端目录填 `/` |
| Alist | `http://192.168.1.10:5244/dav` | 需在 Alist 里给对应账号 WebDAV 读写权限 |
| Nginx DAV | `http://192.168.1.10/dav` | 需要 `dav_methods PUT DELETE MKCOL PROPFIND` |

连接失败排查顺序：地址与端口 → 账号密码 → 远端根目录是否存在（会自动 MKCOL 创建）→ HTTPS 证书（勾选「忽略证书校验」验证）。

## 与 PushTV 的关系

PushTV 的「手机推送 APK 到电视」在 WebDAV 场景下等价于：手机/电脑把文件上传到同一个 WebDAV 目录，
电视端下拉刷新即可看到并下载安装。本应用保留这条链路，去掉了 GitHub 代理、软件市场、备份对比等非 WebDAV 模块。

## 许可

仅供个人学习与自用；参考项目的版权归原作者所有。


## 签名与默认配置

- **固定签名**：`keystore/tvwebdavbox.jks`（alias `tvwebdavbox`），`app/build.gradle` 的 release 签名指向它。本地与 CI 出的包证书一致，可直接覆盖升级。仓库公开，此 keystore 仅供自用侧载。
- **默认 WebDAV 配置**：`gradle.properties` 的 `defaultDavUrl` / `defaultDavUser` / `defaultDavPassword` / `defaultDavRoot` / `defaultDavInsecure` 在编译期写入 `BuildConfig`，首次运行时一次性植入 `SharedPreferences`。CI 可用 Secrets（`DAV_URL`/`DAV_USER`/`DAV_PASSWORD`/`DAV_ROOT`）覆盖，优先级高于 gradle.properties。
