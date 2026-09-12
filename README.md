# MarkNote

轻量 Android Markdown 编辑器 · 文件优先 · Kotlin + Jetpack Compose + Material 3

<p>
  <img src="docs/screenshots/list.png" width="19%" alt="最近文件">
  <img src="docs/screenshots/editor.png" width="19%" alt="编辑器">
  <img src="docs/screenshots/preview.png" width="19%" alt="预览：图片与表格">
  <img src="docs/screenshots/dark.png" width="19%" alt="深色模式">
  <img src="docs/screenshots/landscape.png" width="19%" alt="横屏双栏">
</p>

MarkNote 是一个**文件优先**的 Markdown 编辑器：不建私有笔记库，直接读写设备上任意目录的 `.md` 文件。在文件管理器里点击 `.md` 文件即可选择用 MarkNote 打开，改完自动保存回原位置。

## 下载

在 [Releases](https://github.com/groundgrounder/MarkNote/releases) 页下载最新 APK（`MarkNote-vX.Y.Z.apk`），minSdk 26（Android 8.0+）。

## 功能

**文件**

- 文件管理器直接打开：注册 `text/markdown` / `text/plain` / `.md` / `.markdown` 的 VIEW/EDIT intent，系统「打开方式」可选 MarkNote（singleTask，重复打开复用同一实例）
- SAF 读写任意目录：系统文档选择器打开/新建，无需存储权限，编辑内容自动写回原位置
- 最近打开列表：含权限持久化，重启后可继续编辑；可移除条目（不删文件），失效文件在列表中直接标红并可一键「重新授权」
- 权限来源提示：文件管理器「打开方式」、聊天记录等外部应用传入的文件，系统常常不给长期权限（FileProvider / MediaStore 不支持持久化授权），应用会在打开时立即说明并引导用系统选择器重选一次，换取长期授权
- 只读打开会提示：没有写权限时编辑器顶部提示「修改不会被保存」，不会静默丢失改动
- 自动保存：输入停顿 800ms 落盘，返回/切预览前强制保存；可改为手动保存（顶栏保存按钮在有未保存修改时高亮）

**编辑**

- 编辑区语法高亮：纯正则轻量实现，标题/加粗/斜体/删除线/引用/代码/链接/列表标记实时着色
- 符号化工具栏：H1/H2/B/I/S + 引用/列表/链接/代码/分割线，键盘上方常驻，支持选中文字包裹
- 搜索替换：命中计数（n/m）、循环跳转高亮、单个/全部替换
- 大纲导航：横屏/平板从右侧滑出面板，窄屏为底部弹层，点击跳转（预览态同样可调出）
- 字数统计：顶栏实时显示「N 字 · M 行」

**预览**

- 一键切换，Markwon 渲染；支持 GFM 表格（表头/斑马纹/列对齐）、删除线、可点击链接
- 只读预览同样能搜索、也能调大纲：搜索跑在**渲染结果**上（所见即所搜），命中逐个高亮、当前命中实心高亮并自动滚入视野、n/m 计数与上下跳转；大纲点击直接把正文滚到对应标题。预览是只读视图，所以搜索面板不提供替换
- 图片显示：相对路径图片首次按引导条授权文档所在文件夹即可（授权长期有效）；支持 `content://`、`file://`、base64 内嵌图；大图自动降采样防 OOM

**界面与适配**

- Material You：Android 12+ 动态取色，浅色/深色可跟随系统或手动锁定，边到边布局
- 平板/横屏双栏：宽屏（≥840dp）自动切换为左栏文件列表 + 右栏编辑器，旋转实时切换；侧栏可收起为窄条
- 设置页：主题模式、编辑器与预览字号独立调节、自动保存开关

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3（边到边、LargeTopAppBar） |
| Markdown 渲染 | Markwon 4.6.2（core + ext-strikethrough + ext-tables + image），经 AndroidView 嵌入 |
| 架构 | MVVM（ViewModel + Compose State），单 Activity + 轻量状态导航 |
| 存储 | SAF + SharedPreferences（最近列表与设置），免存储权限 |
| 兼容 | minSdk 26 / targetSdk 35 |

## 构建

```bash
./gradlew assembleDebug        # 需要 JDK 17+ 与 Android SDK（local.properties 配置 sdk.dir）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。也可以直接用 Android Studio 打开本目录。

已配置 GitHub Actions：push 到 main 自动构建并上传 APK 构件；打 `v*` 标签自动创建 Release 并附带 APK。

## 目录结构

```
app/src/main/java/com/marknote/app/
├── MainActivity.kt              # 入口 + 外部打开 intent + 轻量导航
├── data/
│   ├── DocumentRepository.kt    # SAF 文档读写 + 最近列表 + 图片文件夹授权
│   └── SettingsRepository.kt    # 设置项（SharedPreferences + Compose 状态）
└── ui/
    ├── theme/Theme.kt           # M3 动态取色主题（支持手动锁定浅/深）
    ├── files/                   # 最近打开列表页 + ViewModel
    ├── settings/                # 设置页
    └── editor/                  # 编辑器页、工具栏、语法高亮、大纲、Markwon 预览
```

## 图标

自适应图标（Adaptive Icon）：Markdown「M↓」记号，白色 M + 琥珀色下箭头（#FFD54F），深靛蓝底（#4A5ACF）；支持 Android 13+ 主题图标（monochrome）。各密度 PNG 由 `tools/render_icon.py` 生成。

## 路线（规划）

- v1.1：「另存为」、图片插入
- v2.0：WebDAV 同步、自定义主题、多标签编辑

<details>
<summary>历史版本</summary>

### v0.9.0

- 预览（只读视图）也能搜索、调大纲：此前预览态顶栏只留「编辑/预览」，搜索与大纲入口被隐藏。现在两者在预览下同样可用——搜索跑在**渲染结果**上（所见即所搜），全部命中浅色高亮、当前命中实心高亮并自动滚入视野，带 n/m 计数与上下跳转；大纲点击把正文直接滚到对应标题（窄屏底部弹层 / 宽屏右侧面板）。预览是只读视图，故不提供替换
- 预览正文避让输入法：搜索时键盘弹起不再遮挡命中内容
- 精简「这个文件无法长期访问」弹窗文案（两段 90 字 → 一段 41 字），去掉可由按钮自解释的操作说明

### v0.8.1

- 修复「打开文件后退出应用，重新进入提示文件不存在」：外部应用传入的 `content://` Uri 大多无法持久化授权（FileProvider / MediaStore 都不支持），此前申请失败被静默忽略，授权随进程结束失效。现在会即时提示，并引导用系统文件选择器重选一次该文件，换成可长期授权的 Uri
- 打开时检测写权限：只读授权（或保存失败）在编辑器顶部显示提示条 + 「重新授权」，不再静默丢改动
- 读取失败不再伪装成空文档：改为明确的错误页（「重新授权 / 重试 / 返回」），并禁止在读取失败状态下写盘，避免空内容覆盖原文件
- 最近列表改用 JSON 存储（Uri、文件名、时间、图片文件夹授权）：条目时间与顺序不再丢失、条目不再只增不减；重新授权后自动替换旧条目，失效条目不再显示成资源 id（如「72」）

### v0.8.0

- 预览新增图片显示：相对路径图片经自定义 scheme + SAF 目录树授权解析（预览页顶部出现一次性引导条，点「去授权」选中文档所在文件夹即可，授权持久化）；支持 content://、file://、data: base64 内嵌图；超 4096px 大图自动降采样
- 预览新增 GFM 表格渲染（markwon ext-tables），含表头、斑马纹、列对齐

### v0.7.2

- 首页悬浮按钮统一：「打开文件」去掉文字，改为与「新建」同款的小号纯图标 FAB（文件夹图标），两按钮垂直排列、配色一致

### v0.7.1

- 横屏/平板修复：侧栏列表底部留白 176dp，文件卡片不再被「新建/打开文件」悬浮按钮遮挡；大纲长标题单行省略号截断，不再挤压面板
- 宽屏交互修复：设置页打开时点击左侧文件（或从文件管理器外部打开），右栏现在会正确从设置切回编辑器
- 设置页底部避让手势导航条

### v0.7.0

- 新增设置页：主题模式（跟随系统/浅色/深色）、编辑器与预览字号独立调节（小/标准/大）、自动保存开关、关于版本信息

### v0.6.2

- 深色模式修复：主题显式 `forceDarkAllowed=false`，防止 ROM（MIUI/HyperOS 等）对深色界面二次反色导致编辑器显示为白色；新增 values-night 主题变体，深色下冷启动不再白屏闪烁

### v0.6.1

- UI 统一：全部图标改 Outlined 风格，大纲改用专属 Toc 图标（不再与列表图标撞车），备用主题色与图标同色系（靛蓝），工具栏避让手势导航条，收起侧栏避让状态栏
- Bug 修复：保存协程串行化（防旧内容覆盖新内容）、搜索替换后选区/计数正确、从编辑器返回后列表摘要自动刷新、预览链接可点击、文件名查询缓存

### v0.5.0

- 可收起左侧栏、宽屏大纲右侧面板、符号化工具栏

### v0.4.0

- 双栏布局、限宽居中、宽屏网格列表

### v0.3.0

- 字数统计、搜索替换

### v0.2.0

- 编辑区语法高亮、大纲跳转；Compose BOM 升级 2024.12.01（修复 BottomSheet 键盘后错位）

### v0.1.0（MVP）

- 纯源码编辑 + 切换预览、Markwon 渲染、文件管理、自动保存、Material You 主题

</details>
