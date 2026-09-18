# MangaReader —— EPUB 漫画阅读器（Android）

一个本地漫画翻译阅读器：导入 EPUB → 书库（封面=首页）→ 阅读（日漫右→左 / 普通左→右）→
每页翻译 → 右下角切原图/译文图 → 自动预翻后 3 页。翻译走后端 `mit-app-api`（8020）。

## 功能

- **书库**：封面网格；右下 `+` 导入 EPUB（复制进 app 专属目录 `filesDir/library/<id>/`，含原 epub + 抽出的页图）。
  长按封面删除：本地文件 + 本地译文缓存 + 服务端该书全部记录一起删。
- **阅读**：日漫模式右→左横滑，普通模式左→右；顶栏显示 `x / 总数`。
- **翻译**：底栏「翻译本页」→ 走 `POST /v1/pages/translate?async_mode=1` → 轮询 `GET /v1/jobs/{id}`；
  完成后译文图下载到本地缓存（`filesDir/translated/<书id>/<页>.png`）。右下角按钮切原图/译文图。
- **自动模式**：顶栏开关，开启后进入阅读器/翻页时预翻「当前页起后 3 页」。
- **设置**：服务器地址（http/https + 域名 + 端口均可）+ API Key + 测试连接。

## 后端配合

后端就是仓库里的 `mit-app-api`（端口 8020）。两件事要和 App 对上：

1. **鉴权**：`app.env` 里 `MIT_API_TOKEN` 已填（`aFm7-O4B5VD457MKVDNpDK9QFDkjIHbd`），
   App 的「设置 → API Key」填同一串。留空则服务端不校验。
2. **服务器地址**：默认已指向本机 `http://192.168.0.90:8020`（见 `ServerConfig.kt`）。
   - 真机与电脑同一局域网即可直连（后端已绑 0.0.0.0；若连不上多半是 **Windows 防火墙**没放行 8020 入站，加一条放行）。
   - 换电脑/端口：App 里「设置」页改。
   - Android **模拟器**：把地址改成 `http://10.0.2.2:8020`（10.0.2.2 = 宿主机）。
   - 公网：`https://你的域名`（需自行反代/TLS，`MIT_API_TOKEN` 必开）。

## 构建运行

**APK 已在本机打好**：`android/app/build/outputs/apk/debug/app-debug.apk`（9.6MB，debug 签名）。
直接拷到手机安装即可（手机允许"未知来源安装"）。

**本机命令行重新打包**（已装好工具链在 `D:\android-toolchain` + SDK 在 `D:\android-sdk`）：

```powershell
cd "D:\mayuq\OneDrive - bupt.edu.cn\Projects\manga-image-translator\android"
$env:JAVA_HOME='D:\android-toolchain\jdk-17.0.20.1+1'
$env:ANDROID_HOME='D:\android-sdk'; $env:ANDROID_SDK_ROOT='D:\android-sdk'
D:\android-toolchain\gradle-8.7\bin\gradle.bat assembleDebug --no-daemon
```

也可以直接用 Android Studio（Ladybug 或更新）打开本目录构建。

> 依赖版本（`build.gradle.kts`）：AGP 8.5.2 / Kotlin 2.0.20 / Compose BOM 2024.09.00。
> 若 Gradle sync 报版本相关错误，多半是本地 Gradle/JDK 版本与上面不一致，按 Studio 提示对齐即可。

## 目录

```
android/app/src/main/java/com/mit/reader/
  ReaderApp.kt           Application：初始化 ServerConfig / 仓库 / API
  MainActivity.kt        入口（Compose）
  ReaderViewModel.kt     阅读态 + 翻译 job 轮询 + 自动预翻
  data/EpubParser.kt     EPUB(spine 顺序)抽图
  data/LibraryRepository.kt  书库 + 本地译文缓存 + index.json
  data/TranslationApi.kt app_api 客户端（OkHttp，带 X-API-Token）
  data/ServerConfig.kt   服务器地址 / API Key（SharedPreferences）
  ui/AppNav.kt           路由
  ui/LibraryScreen.kt    书库
  ui/ReaderScreen.kt     阅读器
  ui/SettingsScreen.kt   设置
```
