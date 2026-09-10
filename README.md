# MarkNote

轻量 Android Markdown 编辑器 · 面向真实文件 · Kotlin + Jetpack Compose + Material 3

## 定位

MarkNote 是一个**文件优先**的 Markdown 编辑器：不建私有笔记库，直接读写设备上任意目录的 `.md` 文件。在文件管理器里点击 `.md` 文件即可选择用 MarkNote 打开，改完自动保存回原位置。

## 功能（v0.8.0）

- **设置页**：首页顶栏齿轮进入（宽屏显示在右栏）；主题模式（跟随系统/浅色/深色）、编辑器与预览字号独立调节（小/标准/大）、自动保存开关（关闭后顶栏出现手动保存按钮，有未保存修改时高亮）、关于版本信息
- **文件管理器直接打开**：注册 `text/markdown` / `text/plain` / `.md` / `.markdown` 扩展名的 VIEW/EDIT intent-filter，系统"打开方式"中可选 MarkNote（singleTask，重复打开复用同一实例）
- **SAF 读写任意目录**：基于 Storage Access Framework，系统文档选择器打开/新建文件，无需任何存储权限；编辑内容自动写回原文件位置
- **最近打开列表**：打开过的文件持久化记录（含权限持久化 takePersistableUriPermission），重启应用后可直接继续编辑；可从列表移除（不删文件），不可访问的文件有明确提示
- **可收起的左侧栏**：双栏模式下最近文件栏可一键收起为窄条（保留展开入口），编辑空间最大化
- **宽屏大纲右侧面板**：横屏/平板下大纲从右侧滑出，与左侧栏风格统一；窄屏为底部弹层
- **符号化工具栏**：H1/H2/B/I/S + 引用/列表/链接/代码/分割线图标，键盘上方常驻，支持选中文字包裹
- **平板/横屏双栏**：宽屏（≥840dp）自动切换为左栏最近文件 + 右栏编辑器同屏布局（windowSizeClass 驱动，旋转实时切换）
- **大屏限宽居中**：编辑器与预览内容最大宽度 840dp，超宽屏两侧留白
- **字数统计**：顶栏标题下实时显示「N 字 · M 行」
- **搜索替换**：命中计数（n/m）、上/下一个循环跳转高亮、单个替换、全部替换
- **编辑区语法高亮**：纯正则轻量实现（VisualTransformation），标题/加粗/斜体/删除线/引用/代码/链接/列表标记实时着色
- **一键预览**：右上角切换，Markwon 渲染（含删除线、GFM 表格扩展），链接可点击跳转
- **图片显示**：预览支持相对路径图片（`![](img/a.png)`）——首次打开含图文档时按引导条授权文档所在文件夹即可，授权长期有效；同时支持 `content://`、`file://` 与 base64 内嵌图（`data:image/...`）；大图自动降采样防 OOM
- **自动保存**：输入停顿 800ms 写回原文件，返回/切预览前强制落盘
- **Material You**：Android 12+ 动态壁纸取色，浅色/深色跟随系统，边到边布局

<details>
<summary>历史版本</summary>

### v0.8.0

- 预览新增图片显示：相对路径图片经自定义 scheme + SAF 目录树授权解析（预览页顶部出现一次性引导条，点「去授权」选中文档所在文件夹即可，授权持久化）；支持 content://、file://、data: base64 内嵌图；超 4096px 大图自动降采样
- 预览新增 GFM 表格渲染（markwon ext-tables），含表头、斑马纹、列对齐

### v0.7.2

- 首页悬浮按钮统一：「打开文件」去掉文字，改为与「新建」同款的小号纯图标 FAB（文件夹图标），两按钮垂直排列、配色一致

### v0.7.1

- 横屏/平板修复：侧栏列表底部留白 176dp，文件卡片不再被「新建/打开文件」悬浮按钮遮挡；大纲长标题单行省略号截断，不再挤压面板
- 宽屏交互修复：设置页打开时点击左侧文件（或从文件管理器外部打开），右栏现在会正确从设置切回编辑器
- 设置页底部避让手势导航条

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

## 图标

自适应图标（Adaptive Icon）：Markdown「M↓」记号，白色 M + 琥珀色下箭头（#FFD54F），深靛蓝底（#4A5ACF）。矢量前景 + 纯色背景，支持 Android 13+ 主题图标（monochrome 自动单色化）；各密度 legacy PNG 与 Play Store 512 图由 `tools/render_icon.py` 重新生成。

## 技术栈

| 项 | 选型 |
|---|---|
| 语言 | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3（边到边、LargeTopAppBar） |
| Markdown 渲染 | Markwon 4.6.2（core + ext-strikethrough），经 AndroidView 嵌入 |
| 架构 | MVVM（ViewModel + Compose State），单 Activity + 轻量状态导航 |
| 存储 | SAF（Storage Access Framework）+ SharedPreferences 最近列表，免存储权限 |
| 兼容 | minSdk 26 / targetSdk 35 |

## 目录结构

```
app/src/main/java/com/marknote/app/
├── MainActivity.kt              # 入口 + 外部打开 intent + 轻量导航
├── data/
│   ├── DocumentRepository.kt    # SAF 文档读写 + 最近打开列表
│   └── SettingsRepository.kt    # 设置项（SharedPreferences + Compose 状态）
└── ui/
    ├── theme/Theme.kt           # M3 动态取色主题（支持手动锁定浅/深）
    ├── files/                   # 最近打开列表页 + ViewModel
    ├── settings/                # 设置页
    └── editor/                  # 编辑器页、工具栏、语法高亮、大纲、Markwon 预览
```

## 构建

```bash
./gradlew assembleDebug        # 需要 JDK 17+ 与 Android SDK（local.properties 配置 sdk.dir）
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

也可以直接用 Android Studio 打开本目录。

## 路线（规划）

- v1.1：导出 HTML/PDF、「另存为」、图片插入
- v2.0：WebDAV 同步、自定义主题、多标签编辑
