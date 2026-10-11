# Root Explorer Pro 项目文档

> 双栏 Root 文件管理器 · Kotlin + Jetpack Compose + Material 3 · MVVM
>
> 版本 `0.4.0-P3` · 文档日期 2026-10-11

---

## 1. 是否全部完成

**阶段任务 P0–P3 已全部实现，代码无 TODO / 占位桩。** 但"完成"要分成三层说清楚，不能笼统讲：

| 层面 | 状态 |
|---|---|
| **代码实现** | ✅ 全部完成。10 个功能模块、26 个包、54 个主源码文件（15 880 行）。无 `TODO`、无 `NotImplementedError`、无假成功状态 |
| **单元测试验证** | ✅ 304 个测试全部通过（16 个测试文件） |
| **真机行为验证** | ⚠️ **大部分完成**。设备中途接入，root 相关核心路径（列表、chmod 全特殊位、chown、只读分区、终端分帧）已实测；余下为 UI 点击类流程 |

### 唯一的"未完成"是四类额外功能，且它们不在本项目需求范围内

它们**不是被偷偷省略**——侧边栏里每一项都显式置灰并标注了原因：

| 功能 | 现状 | 说明 |
|---|---|---|
| 插件管理 | 置灰，标注「未实现」 | 原需求书未要求 |
| 远程管理 | 置灰，标注「未实现」 | 原需求书未要求 |
| MCP 服务 | 置灰，标注「未实现」 | 原需求书未要求 |
| Activity 记录 | 置灰，标注「未实现」 | 原需求书未要求 |
| Open-with / 分享（Intent + FileProvider） | 置灰，标注 P3 | 规划中 |
| 设置界面 | 置灰，标注 P4 | 规划中 |

### 真机验证缺口（诚实列出）

以下功能**已实现且单元测试通过，但没有在真机上点击验证过**：

- 权限编辑器的 **UI 接线**——规则、八进制形式与命令构造现已钉在真机实测值上（见 §6.2），
  但没有人点过那些勾选框
- uid/gid **名称**解析（`id -nu 0` → `root`、`id -u shell` → `2000` 已实测，其他查找未测）
- 复制/移动的进度 UI、冲突提示、任务条
- 回收站三个界面（移入 / 还原 / 永久删除）与真实卷上的索引
- 哈希对话框、压缩/解压对话框、编辑器保存路径
- 已安装应用界面与 APK 导出
- **换一个 ROM 的行为**——下面记录的多数 bug 是本机 toybox 特性，busybox 设备可能表现不同

> 我无法自己完成这些验证：这台 ROM 拒绝 adb 注入触摸事件（`INJECT_EVENTS` 被拒），
> 所以点击类验证必须由人操作。§8 给了逐项清单。

---

## 2. 这个项目是什么

给 **已 root 的安卓手机** 用的专业双栏文件管理器。不是静态 UI 演示：文件列表读的是真实文件系统，没有任何硬编码的演示数据。

### 技术栈与版本（实测锁定）

| 项 | 版本 | 备注 |
|---|---|---|
| AGP | 9.4.1 | AndroidX 2026 要求 ≥ 9.1.0 |
| Gradle | 9.8.1 | 走腾讯镜像（本机直连 gradle.org 失败） |
| Kotlin | 2.4.21 | **AGP 9 内置 Kotlin 支持**：绝不能同时 apply `org.jetbrains.kotlin.android`，否则构建失败 |
| Compose BOM | 2026.09.00 | |
| compileSdk | 37 | 本机 SDK 只有 `platforms/android-37.0` |
| buildToolsVersion | 36.0.0 | SDK 目录只读，必须显式钉住，否则回落到不存在的 35.0.0 |
| targetSdk / minSdk | 36 / 26 | |
| coroutines | 1.11.0 | |
| commons-compress | 1.28.0 | tar 系列归档（ZIP 用 JDK 自带 `java.util.zip`） |

**依赖镜像**：本机无法直连 `dl.google.com` / `repo1.maven.org`，`settings.gradle.kts` 里配置了阿里云 google/public/gradle-plugin + 腾讯 gradle 镜像。

### 目标设备（实测环境）

| 项 | 值 |
|---|---|
| 型号 | Xiaomi `2211133C`（fuxi） |
| 系统 | Android **17** / API **37** |
| 屏幕 | 1080×2400，density 440（2.75×），font_scale 1.0 |
| Root | **KernelSU**（`su -c 'id'` → `uid=0(root) … context=u:r.ksu:s0`） |
| 状态栏 inset | **121 px ≈ 44 dp**（曾导致顶栏被时间栏盖住） |

---

## 3. 功能清单与验证状态

### P0 · 骨架与浏览

| 功能 | 状态 | 说明 |
|---|---|---|
| 双栏布局 | ✅ | 可切换单栏/双栏 |
| 真实目录读取 | ✅ 真机验证 | Java 引擎（应用自身权限）+ Root 引擎（`su`）双通道 |
| 进入 / 返回 / 前进 / 上一级 | ✅ | 带历史记录，前进历史会被新导航截断 |
| **系统返回键逐级返回** | ✅ | 优先级：关对话框 → 关侧边栏 → 退出多选 → 上一级 → 才退出应用 |
| `..` 上级条目 | ✅ | 每栏列表顶部，与参考实现一致 |
| 路径导航（可点击面包屑） | ✅ | 跳任意祖先、复制路径、手输路径 |
| 刷新 | ✅ | 分栏独立 |
| 排序：名称/大小/日期/类型，升降序 | ✅ | 未知值恒排最后 |
| 过滤：名称/扩展名/类型 | ✅ | 会显示隐藏了多少条 |
| 隐藏文件开关 | ✅ | 持久化，顶栏有提示 |
| 多选 / 全选 / 反选 | ✅ | 长按进入多选 |
| 布局模式 | ✅ | 列表 / 网格 / 树形 |
| 总量统计 | ✅ | 文件夹数、文件数、已用/总容量 |

### P1 · 文件操作、回收站、搜索

| 功能 | 状态 | 说明 |
|---|---|---|
| 复制 / 移动 | ✅ | 同文件系统用 `rename`（原子），跨文件系统退化为复制后删源 |
| 冲突策略 | ✅ | 覆盖 / 跳过 / 重命名 / 全部应用 |
| 进度与取消 | ✅ | 字节级进度，可取消 |
| 新建文件夹 / 文件 | ✅ | |
| 重命名 / 删除 | ✅ | |
| 回收站 | ✅ | 索引存于 `/data/media/0/.RootExplorerPro/trash`（root）或应用私有目录 |
| 还原 / 永久删除 / 清空 | ✅ | 支持按规则清空 |
| 递归搜索 | ✅ | 名称、扩展名、类别；报告部分结果与不可读清单 |
| 属性对话框 | ✅ | 大小、时间、权限、属主、符号链接目标 |

### P2 · Root 能力

| 功能 | 状态 | 说明 |
|---|---|---|
| Root 探测 | ✅ **真机验证** | 真实 `su` 探测，到达 KernelSU；绝不用"文件是否存在"判断 |
| Root 目录读取 | ✅ **真机验证** | `/` 与 `/data` 实测可列 |
| `chmod`（含特殊位） | ✅ **真机验证** | 644/755/600/777/000、setuid/setgid/sticky、`-R` 均已实测；回读比较 `modeBits` 防止提权假成功 |
| `chown`（属主/属组） | ✅ **真机验证** | 按名称与按数字 ID 都实测成功；错误名称会失败且不改动 |
| **回读校验** | ✅ **真机验证** | 退出码为 0 绝不作为成功依据；只读分区场景已实测 |
| 权限矩阵编辑器 | ✅ | 位可视化勾选 + 八进制输入 |
| uid/gid 名称解析 | ⚠️ 部分 | 走设备自身账号库（`id`） |
| 敏感路径二次确认 | ✅ | `/system`、`/vendor`、`/data/adb`、`/metadata` 等 |
| 绝不 remount 系统分区 | ✅ **设计约束** | 见 §5 |

### P3 · 工具集

| 功能 | 状态 | 说明 |
|---|---|---|
| 哈希 MD5 / SHA-1 / SHA-256 | ✅ 已测 | 多算法一次读取、校验值比对 |
| 归档 ZIP / tar 系列 | ✅ 已测 | 压缩、解压、冲突策略、Zip Slip 防御 |
| 文本编辑器 | ✅ 已测 | UTF-8 + GB18030 回退、BOM、换行风格、二进制拒绝、原子保存 |
| 已安装应用 | ✅ 已测 | 图标、搜索、排序、系统/用户过滤、详情、导出 APK |
| **终端模拟器** | ✅ **真机验证帧格式** | 持久 `sh`/`su` 会话、真实 stdout 与退出码、停止、历史 |

---

## 4. 代码结构

```
app/src/main/java/io/rootexplorer/pro/
├── MainActivity.kt              636 行(含 Application)   入口、屏幕路由、inset
├── RootExplorerApplication.kt                            AppContainer 依赖容器
│
├── core/                          核心能力层（不依赖 UI）
│   ├── filesystem/    9 文件 1632 行  FileEntry/FsEngine/JavaFsEngine/RootFsEngine/
│   │                                  SystemFileSystem/StorageVolumes/FileSort/FileTypeDetector
│   ├── root/          3 文件  315 行  RootShell/RootStatus/RootAccess
│   ├── permissions/   2 文件  617 行  LinuxPermission/PermissionChanger
│   ├── shell/         1 文件  341 行  ShellSession（终端会话）
│   ├── crypto/        1 文件  287 行  HashEngine
│   ├── archive/       1 文件  738 行  ArchiveEngine
│   ├── text/          1 文件  316 行  TextFileEngine
│   └── apk/           1 文件  321 行  InstalledAppRepository
│
├── service/                       有状态服务层
│   ├── fileoperation/ 2 文件  818 行  复制/移动计划与执行
│   ├── taskmanager/   1 文件  373 行  任务条状态机、冲突等待
│   └── trash/         1 文件  411 行  回收站仓库 + 索引编解码
│
├── feature/                       功能屏（MVVM）
│   ├── filebrowser/   2 文件  852 行  浏览状态与 ViewModel
│   ├── search/        3 文件  839 行  搜索引擎 + 视图
│   ├── editor/        2 文件  713 行
│   ├── installedapps/ 2 文件  702 行
│   ├── archive/       2 文件  610 行
│   ├── terminal/      2 文件  563 行
│   ├── hash/          2 文件  443 行
│   ├── permissioneditor/ 2 文件 412 行
│   ├── trash/         1 文件  380 行
│   └── properties/    1 文件  225 行
│
├── ui/
│   ├── components/    6 文件 2919 行  RootExplorerScreen/AppShell/FileListPane/对话框/任务条
│   └── theme/         1 文件  202 行  紧凑字号体系、颜色
│
├── data/repository/   2 文件  144 行  设置持久化
└── util/              1 文件   71 行  Formatter（大小/日期/名称）

测试：app/src/test/ 16 文件 3131+ 行，304 个用例
```

**分层原则**：`core` 不依赖 UI，所以全部可在 JVM 单元测试里覆盖；`feature` 只做状态与渲染；`service` 承载跨屏的生命周期（例如复制任务在旋转后仍继续）。

---

## 5. 关键设计决策与安全约束

这一节是项目的核心。原需求书明确禁止了若干做法，这里是逐条的落实方式。

### 5.1 绝不拼接不安全的 Shell 命令

**所有**插值进 shell 的字符串都经过 `ShellQuote`（POSIX 单引号规则，内嵌单引号转义为 `'\''`）。

有个反直觉的细节值得记录：`changeMode` 原来在**校验路径之前**先做了路径规范化，而规范化会补上前导 `/`，于是"必须是绝对路径"这条规则**永远不触发**——相对路径被静默变成了绝对路径。已修正为先校验后规范化。

### 5.2 不谎报成功

这是最容易骗过自己的地方。**退出码为 0 从来不作为成功依据**，一律回读确认：

- `chmod` 回读 `stat` 的实际 mode 位
- `chown` 回读实际 uid/gid
- 复制/移动比对写入字节数

两个已修的真实缺陷：

1. **回读只比较三位八进制**——会丢掉 setuid/setgid/sticky。`chmod 4755` 如果 setuid 位没生效，也会被报成"已应用"。现在比较数值 mode。
2. **复制报 `itemsCopied = 0`**——计数器写在了"仅移动"分支里，普通复制虽然字节已写入，却报告没干活。

### 5.3 未知就是未知，绝不猜

- 属主/属组解析不出来时显示 **「未知」**，绝不默认成 `root`。
- 修改时间读不到时显示「未知」，不填一个看起来合理的日期。
- 权限位缺失时显示「未知」。

`Formatter.name()` 的实现就是这一条：`value?.takeIf { it.isNotBlank() } ?: "未知"`。

### 5.4 读取失败绝不显示成"空目录"

- 权限不足 → 「权限不足」
- 路径不存在 → 「路径不存在」
- 需要 root → 「需要 Root 权限」并提供授权入口
- 只有**确认成功且为空**时才显示「此目录为空」

这条在真机上是有代价的：`ls -la` 的解析 bug（§6 bug 6）曾让 `/storage/emulated/0` 显示成空目录，而这恰恰是需求书点名禁止的行为。

### 5.5 绝不 remount 系统分区

不做任何 `mount -o remount,rw`。对 `/system`、`/vendor` 等路径的修改操作一律二次确认，且只做用户显式要求的单次变更。

### 5.6 归档的 Zip Slip 防御

解压时逐条校验目标路径，拒绝：`..`、绝对路径、Windows 盘符、反斜杠、NUL 字符。

### 5.7 部分结果如实报告

搜索报告"部分结果"和不可读清单，不假装完整。Root 遍历有 2000 目录预算上限，命中时明确标记结果不完整。

### 5.8 失效状态尽早发现

终端会话若已超时/终止，**下一次执行前**先探测；探测失败则如实报告，而不是让命令挂在一个死掉的进程上。

---

## 6. 验证记录

### 6.1 单元测试（304 个，全部通过）

| 测试套件 | 数量 | 覆盖 |
|---|---|---|
| `LinuxPermissionTest` | 17 | 644/755/600/777/000、特殊位、往返、非法输入拒绝 |
| `ShellQuoteTest` | 13 | 命令注入安全 |
| `FsEnginePathTest` | 14 | 路径规范化、`..`、`//`、Unicode、换行 |
| `LsLineParsingTest` | 9 | `ls -la` 解析（针对真机原始输出） |
| `RootListingIntegrationTest` | 6 | Root 列表端到端：目录必须在、任何条目标题不得以 `->` 开头 |
| `SortAndFilterTest` | 17 | 目录优先、升降序下未知值恒最后、过滤器 |
| `FileOperationPlanTest` | 24 | 复制/移动计划、冲突、策略、真实字节级读写、符号链接环 |
| `TrashIndexCodecTest` | 14 | 制表符/换行/反斜杠免疫的索引往返 |
| `PermissionChangerTest` | 30 | 校验规则、特殊位、名称与 uid 等价、敏感路径 |
| `SearchEngineTest` | 19 | 递归与单层、过滤、隐藏文件、部分失败、批处理、结果上限 |
| `HashEngineTest` | 21 | 公开 MD5/SHA-1/SHA-256 向量、多算法一致性、校验 |
| `ArchiveEngineTest` | 30 | 往返、Zip Slip 攻击、冲突策略、损坏输入 |
| `TextFileEngineTest` | 31 | 编码、BOM、换行风格、二进制拒绝、原子保存、替换 |
| `InstalledAppLogicTest` | 22 | 应用过滤/排序规则、导出命名、拆分 APK |
| `ShellSessionFramingTest` | 20 | 终端命令分帧、退出码解析、输出上限 |
| `PermissionDeviceEvidenceTest` | 17 | 真机实测的 chmod/chown 值：setuid/setgid/sticky 解析、四位八进制、只读文件系统、bad user |

### 6.2 真机验证结论

**已实测通过：**

- `su` 探测到达 KernelSU，返回真实 root shell（`uid=0(root)`，`context=u:r.ksu:s0`）
- `/` 与 `/data` 的 root 目录列表
- `ls -la` 解析针对真机原始输出（已用 `RootListingIntegrationTest` 钉死，格式来自实测而非假设）
- **`chmod` 全部特殊位实测**：`4755` → `-rwsr-xr-x|4755`、`2755` → `-rwxr-sr-x|2755`、`1755` → `-rwxr-xr-t|1755`，`-R` 递归对目录树生效。这同时确立了 `stat -c %a` 返回**四位**八进制，从而使 setuid-safe 的回读比较是有效的
- **`chown` 实测**：按名称（`shell:shell` → uid/gid 2000）与按数字（`0:0` → root）都成功；错误名称 `chown: bad user '…'` 退出码 1 且属主不变
- **只读分区实测**：`chmod` 报 `Read-only file system`、退出码 1、权限保持不变——这正是"只看退出码会说谎"的场景
- 终端命令分帧：`printf` 正确报告 `$?`（`0` 和 `1` 都实测过）、多行输出只由一行状态行收尾、`cd` 在同一个 root 会话内跨命令保持
- 应用自身 UID 在需要 root 处被**正确拒绝**（"能提权"和"不会被拒"是两件事，两者都验证了）
- 安装、启动、多次重建后持续运行无崩溃
- `MANAGE_EXTERNAL_STORAGE: allow`，共享存储可读

**仍未实测：** 见 §1 的缺口清单。权限编辑器只验证到"规则与命令构造"，没有人点过那些勾选框。

### 6.3 真机才暴露的 bug（4 个，全部已修）

这四个的共性值得记录：**它们全部通过了单元测试**，因为测试止步于 shell 边界。

#### bug 6 · Root 列表只显示符号链接，且标题是链接目标

`line.split(Regex("\\s+"), limit = 9)` 的行为和我的假设相反——它**不是**"8 列 + 余下全部"，而是总共最多 9 个元素：

```
符号链接：["lrw-r--r--","1","root","root","11","2009-01-01","08:00","bin","-> /system/bin"]
                                                                    ↑ 名字在这里  ↑ 目标被当成名字
目录：    ["drwxr-xr-x","82","root","root","1660","1970-01-04","05:29","apex"]   ← 只有 8 个元素
```

后果是两重的：

1. 目录和普通文件因为 `parts.size < 9` 被**全部丢弃**
2. 符号链接的 `parts[8]` 拿到的是 `-> 目标`，所以每一行都显示成 `-> /system/bin`

用户看到的现象是：`/` 只有符号链接、`/storage/emulated/0` 是"空目录"、`/data` 打不开。

**修复**：改为**定位日期/时间列组的结束位置，其后全部内容即文件名**。同时适配 8 列与 9 列两种布局，并保留文件名中的空格。顺带把 `ls` 已给出的修改时间解析出来（此前一律显示"未知"）。

#### bug 7 · Root 搜索返回空结果

原实现用 `find … -exec stat`。这台设备的 toybox `find` **在自己的 `--help` 里列出了 `-exec`、`-printf`、`-mindepth`、`-print0`，但运行时全部拒绝**：

```
find: bad arg '-exec'
find: bad arg '-printf'
find: bad arg '-mindepth'
```

**修复**：改为不依赖 `find` 可选参数的**迭代式 BFS + `ls`**。`ls` 是唯一在真机上端到端验证过的工具，而且路径不再回传 shell，换行符文件名的问题自动消失。代价是每目录一次 `su`，所以加了 2000 目录预算。

#### bug 8 · 已授权 root 仍报权限不足，必须重启应用

`activeEngine()` **每次请求只采样一次**缓存的 root 状态，而应用启动时刻意不探测（探测会弹授权框）。在那个窗口里发出的请求走 Java 引擎 → 被拒 → 而缓存的 `Unknown` **不会被重新评估**，于是**一直失败直到重启**。

**修复**：`list`/`stat` 在遇到"权限不足 + 当前是 Java 引擎 + root 未确认"时，**探测一次并重试**；`scan` 改为**遍历开始前**就决定引擎（失败后重试会把已交给调用方的条目重复一遍）。

#### bug 9 · `chmod` 永远无法应用 setuid/setgid/sticky

`LinuxPermission` 有**两个**八进制访问器，名字很像但语义关键不同：

| 访问器 | 实现 | 结果（以 4755 为例） |
|---|---|---|
| `octal` | `modeBits and 0xFFF` | `"4755"` ✅ 保留特殊位 |
| `octalSimple` | `modeBits and 0x1FF` | `"755"` ❌ **把特殊位掩掉了** |

而 `changeMode` 把 `octalSimple` 传给了 `chmod`。后果：用户在权限编辑器里勾选 setuid，实际执行的是 `chmod 755 /path`，**setuid 永远落不下去**。

编辑器还放大了这个问题：

- 显示的是 `八进制：${octalSimple}（完整 ${octal}）`——**把三位形式放在前面**，而三位形式恰好是被应用的那个
- 八进制输入框用 `octalSimple` 作为初始值——编辑一个已有的 `4755` 文件，输入框显示 `755`，直接确定就会**静默清掉特殊位**
- 而它上方的 setuid 勾选框仍然显示为"已勾选"

**发现方式值得记录**：不是读代码看出来的，而是**先量真机**——在设备上确认 `chmod 4755` 可用、且 `stat -c %a` 返回**四位**（`4755`/`2755`/`1755`）。第二个事实同时证明了一件事：那个**绝不能产生提权假成功**的回读比较，比较的确实是正确的东西（如果 `stat` 只返回三位，那么每次 setuid 变更都会被误报为失败）。

**修复**：命令与编辑器统一改用 `octal`。`PermissionDeviceEvidenceTest`（17 个用例）把每一个实测数值都钉住了。

#### 附带发现 · 终端分帧的自我缺陷

哨兵前缀是 Kotlin `const val`（会被内联），而我原本还用**同名的 shell 变量**存退出码，导致 `__DSH_STATUS` 在命令文本里出现两次、语义歧义。被 `ShellSessionFramingTest` 抓出来后，改用 `printf` 传参，标记只出现一次。

### 6.4 早期由测试/lint 抓到的 bug（5 个）

1. **启动即崩溃**——manifest 声明 `.RootExplorerApp`，实际类名 `RootExplorerApplication`。lint 抓到，修复后 dump 构建产物 manifest 复核。
2. **权限显示错误**——`4644`（有 setuid、无执行位，`ls` 显示 `rwSr--r--`）被解析成有执行位，权限编辑器会把它显示成 `rwxr--r--`。
3. **结果计数错误**——见 §5.2 第 2 条。
4. **安全相关的假成功**——见 §5.2 第 1 条。
5. **校验被规范化绕过**——见 §5.1。

另有 2 个测试期望本身写错了，改的是测试而不是代码：`parentOf("/data/adb/..")` 正确回答的是规范化后的路径；`0xFFFFF333` 的低 12 位是 `0o463` 而非 `0o333`。

---

## 7. 构建与安装

### 环境变量（每次构建都必须设置）

```powershell
$ws = 'I:\mt'
$env:JAVA_HOME          = 'I:\jdk-21.0.12.1+1'
$env:GRADLE_USER_HOME   = "$ws\.gradle-home"    # 必须：沙箱 TEMP 会导致 Gradle native services 初始化失败
$env:TEMP               = "$ws\.gradle-tmp"
$env:TMP                = "$ws\.gradle-tmp"
$env:ANDROID_USER_HOME  = "$ws\.android-home"   # 否则尝试写 C:\Users\ASUS\.android 被拒
```

### 常用命令

```powershell
cd $ws
.\gradlew.bat :app:testDebugUnitTest   # 304 个单元测试
.\gradlew.bat :app:assembleDebug       # 产出 APK
.\gradlew.bat :app:lintDebug           # 静态检查
```

**注意**：不要加 `-i`，也不要把 `--console=plain` 和 `Select-String` 混用，否则输出会被吞。

### 产物与安装

```powershell
# APK: I:\mt\app\build\outputs\apk\debug\app-debug.apk  (约 20.55 MB)
$adb = 'H:\Android studio SDK\platform-tools\adb.exe'
& $adb install -r -t 'I:\mt\app\build\outputs\apk\debug\app-debug.apk'
& $adb shell am start -n io.rootexplorer.pro.debug/io.rootexplorer.pro.MainActivity
```

调试包名是 `io.rootexplorer.pro.debug`（release 为 `io.rootexplorer.pro`）。

### Lint 基线（5 条，全部有意保留）

| 规则 | 原因 |
|---|---|
| `SdCardPath` ×2 | 需要按固定路径访问外置存储 |
| `ScopedStorage` | `MANAGE_EXTERNAL_STORAGE` 是本应用的核心能力，不是疏忽 |
| `ObsoleteSdkInt` | `mipmap-anydpi-v26` 是有意保留的图标变体 |
| `OldTargetApi` | targetSdk 36，低于本机平台版本，但不影响运行 |

---

## 8. 手动验收清单

以下步骤我无法自动执行（本 ROM 拒绝 adb 注入触摸事件，`adb shell input tap` 报 `SecurityException: Injecting input events requires ... INJECT_EVENTS`）。请逐项操作。

### 8.1 已验证项（复核即可）

- [x] 顶栏不再被状态栏盖住
- [x] `/storage/emulated/0` 正常列出 17 个文件夹
- [ ] 输入 `/` 跳转 → 应同时看到 `apex`、`data`、`dev` 等**目录**和 `bin`、`etc` 等**链接**（链接标题是名字，目标在副标题）
- [ ] 输入 `/data` 跳转 → 应能列出
- [ ] 进入子目录后按**系统返回键** → 逐级返回，到 `/` 再按才退出
- [ ] 对话框打开时按返回键 → 只关对话框，不跳目录

### 8.2 待验证项

**终端**
- [ ] ☰ → 终端模拟器 → `pwd`、`ls -la`、`id`、`df -h`
- [ ] 点 **Root Shell** → KernelSU 弹框允许 → `id` 应显示 `uid=0(root)`
- [ ] `cd /data/adb` 然后 `pwd` → 应显示 `/data/adb`（验证会话持久）
- [ ] 执行 `ls /nonexistent` → 底部应**明确报出非零退出码**
- [ ] 执行 `ping -c 100 127.0.0.1` → 点「停止」应能终止

**文件操作**
- [ ] 长按文件 → 复制到另一栏 → 进度条走到 100% → 目标处文件存在且大小一致
- [ ] 复制到已有同名文件处 → 冲突对话框出现 → 试「重命名」和「跳过」
- [ ] 复制大文件时点取消 → 已复制的部分应保留，且提示明确
- [ ] 移动文件到另一目录 → 源消失、目标出现

**回收站**
- [ ] 长按 → 移入回收站 → 文件从列表消失
- [ ] ☰ → 回收站 → 能看到刚才的文件 → 还原 → 回到原位置
- [ ] 再移入一次 → 永久删除 → 确认后不再出现

**搜索**
- [ ] ☰ → 搜索 → 选 `/data/adb` → 勾选包含子目录 → 应能出结果（这是 bug 7 的修复点）
- [ ] 搜索一个无权访问的目录 → 应显示"权限不足"，而非"无结果"

**Root 权限**
- [ ] 属性 → 权限 → 把某个测试文件改成 755 → 确定 → 重新打开属性确认真的变成 755
- [ ] 改成 4755 → 确认 setuid 位真的生效（这是回读校验的关键点）
- [ ] 对 `/system` 下路径做修改 → 应弹出**二次确认**
- [ ] 改属主/属组 → 重新打开确认真的变了

**其他工具**
- [ ] 长按文本文件 → 编辑 → 修改 → 保存 → 重新打开确认内容已变
- [ ] 长按文件 → 校验值 → MD5 与电脑上算的一致
- [ ] 多选几个文件 → 压缩为 ZIP → 解压到别处 → 内容一致
- [ ] ☰ → 已安装应用 → 列表有图标、可搜索、可导出 APK

**稳定性**
- [ ] 双栏都在深层目录时旋转屏幕 → 状态不丢
- [ ] 复制进行中按 Home 再回来 → 任务仍在
- [ ] 连续快速点击各栏 → 不应出现"权限不足"（bug 8 的修复点）

---

## 9. 已知限制（明说）

**功能边界**

- 搜索不搜**文件内容**，只搜名称/扩展名/类别。内容搜索需要不同的引擎，大文件还需要索引。
- 递归搜索是单前台操作，无队列：开始新搜索会取消上一个。并行搜索会让进度数字失去意义。
- 复制/移动一次只跑一个任务。并发传输到同一存储会让吞吐与部分失败统计失去意义。
- 跨文件系统移动 = 复制 + 删源。真实且已验证，但**不是原子的**：中途断电会留下副本且源未删（安全的方向）。
- 传输引擎优先用应用自身凭据，只在删不掉源时回退到 root shell。所以从 root-only 路径**复制出来**不会走 `su` 加速。
- `chmod`/`chown` 每次操作一次 `su`，没有持久 root 会话，所以在大树上递归改权限比理论上慢。
- 权限编辑器还没有"应用于所选"：变更作用于打开它的那一个条目（勾选递归时含子项）。
- 设置用 `SharedPreferences`；回收站索引是扁平文件而非数据库。原需求允许 DataStore/Room，迁移列为 P4。
- UI 字符串是 Kotlin 内联中文，未抽到 `strings.xml`（P4 本地化工作）。
- `AndroidManifest.xml` 申请了 `QUERY_ALL_PACKAGES`，为"已安装应用"功能所需。
- 名称排序不区分大小写但**不是 locale-aware**，不是完整 collator。

**Root 遍历的代价**

- Root 搜索用 `ls` 逐目录走（每目录一次 `su`），因为本机 toybox `find` 拒绝 `-exec`/`-printf`/`-mindepth`（§6 bug 7）。这在所有设备上都正确，但在目录很多的树上比单次 `find` 慢；走到 2000 目录会停止并**如实标记结果不完整**。

**换 ROM 的风险**

本机多数坑来自 **MIUI 的 toybox 0.8.13** 特性。busybox 设备可能表现完全不同——`stat`/`ls` 的字段格式、`find` 支持的参数都要重新确认。§6.2 列出的真机结论**只对本机成立**。

---

## 10. 后续维护建议

按优先级：

1. **先做 §8.2 的手动验证。** 这是唯一还没被覆盖的部分，比加新功能重要得多。
2. **在第二台设备上复测 `ls`/`stat` 解析。** 如果某台设备的 `ls` 格式不同，`LsLineParsingTest` 的写法可以照搬：把该机的真实输出粘进测试，先让测试失败，再改代码。
3. **抽出 `strings.xml`**（P4），当前中文内联在 Kotlin 里，无法本地化。
4. **考虑持久 root shell 会话**，把递归 `chmod`/搜索的每次 `su` 开销降下来。终端的 `ShellSession` 已经是一个可复用的实现。
5. **回收站索引换数据库**，当前扁平文件在条目很多时会有性能问题。

### 修改代码时的注意事项

- **动 `RootFsEngine.parseLsLine` 之前先看 `LsLineParsingTest`**——那个函数的行为边界完全由真机输出决定。
- **动 `SystemFileSystem` 的引擎选择之前先看 §6.3 bug 8**——状态过期问题很容易重新引入。
- **任何新的 shell 命令插值都必须过 `ShellQuote`**，并且要有对应的注入测试。
- **任何 mutation 都必须回读校验**，不能用退出码判断成功。
- **不要用 `octalSimple` 作为 `chmod` 参数**——它被 `0x1FF` 掩过，会丢掉 setuid/setgid/sticky（见 §6.3 bug 9）。要传给 `chmod` 的永远是 `octal`。
- 单元测试数量改变时记得同步 README 与本文件。

---

## 附：项目文件索引

| 文件 | 内容 |
|---|---|
| `README.md` | 面向开发者的状态说明、阶段记录、真机 bug 记录 |
| `PROJECT.md`（本文件） | 完整项目文档：功能清单、架构、设计决策、验证记录、验收清单 |
| `app/src/main/java/io/rootexplorer/pro/` | 主源码，54 文件 |
| `app/src/test/java/io/rootexplorer/pro/` | 单元测试，16 文件 / 304 用例 |
| `app/build/outputs/apk/debug/app-debug.apk` | 构建产物 |
| `settings.gradle.kts` | 依赖镜像配置（本机必需） |
| `local.properties` | `sdk.dir`（未纳入版本控制） |
