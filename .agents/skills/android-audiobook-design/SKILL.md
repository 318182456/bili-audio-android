---
name: android-audiobook-design
description: Design and review the native Android Views UI in 哔哩听单. Use for changes to playback, subtitles, episode selection, downloads, file management, accounts, or appearance, especially when the request concerns layout, density, navigation, or visual polish.
---

# 哔哩听单 Android 设计

先看目标页面的现有实现和真实内容。以 Android 手机单手操作为起点，从用户最常做的动作决定首屏顺序。`MainActivity` 组织主页面，`AudioUi` 定义通用颜色和控件样式，`CaptionScrubber` 负责字幕，`BookSectionList` 负责书籍和分组。沿用本项目的原生 Java Views 和资源主题。涉及布局适配时参考 [Android Views 响应式布局](https://developer.android.com/develop/ui/views/layout/responsive-adaptive-design-with-views)，涉及文字、对比度和操作范围时参考 [Android 无障碍设计](https://developer.android.com/design/ui/mobile/guides/foundations/accessibility)。

## 页面取舍

- 播放首页的主要面积给字幕。书名、当前集和进度可一眼定位，播放暂停、上下集和快进放在拇指易触及的下方。压缩行距时先缩内边距，再核对字幕字号、两行换行和阅读可辨性。保留拖动、点按字幕定位和独立进度条。
- 选集单独打开，直接定位当前书和分组。按每 100 集分组，在手机宽度足够时一行三组。当前组、当前集和待分集状态需要一眼识别。用真实长书名和四位数集号检验。
- 下载页固定书籍切换，保留各书分 P 选择。批量入口和下载状态靠近当前书的操作，导入、搜索与收藏需要容易找到。
- 管理按书分组，普通手机宽度下测试两列。整体卡片可点，选中与任务状态分别有文字或图标。暂停、继续和取消的含义容易区分。听完记录和可能删除的本地文件在确认时说清楚。
- 账号与外观归入设置。使用系统、手动浅色和手动深色同一套颜色角色。高亮、播放主按钮和错误状态要经过两种主题检查。

## 视觉检查

复用统一的文字层级、间距和按钮反馈。主要操作只有一个视觉中心，相关的次要操作放在一起。先修内容密度、对齐和层级，再调整颜色与圆角。对中文长标题、动态字号和底部系统手势区留出空间。独立操作按钮按 Android 的 48 dp 触控目标设计。紧凑字幕还应能通过播放进度条定位。

改动前后对照同一内容和近似视口。至少看一个普通手机竖屏与较窄视口，覆盖长书名、长字幕、空状态与任务状态中和改动相关的场景。有真机或模拟器时检查按钮、返回和拖动效果，并在结果中区分视觉检查和编译检查。截图用 `app/build` 下的临时目录存放。

Android 官方技能可辅助系统栏、折叠屏和实现问题。挑选与当前改动相符的条目。音频界面参考用于观察层级、密度和操作习惯，具体布局保持项目自己的设计。[Android 官方技能](https://github.com/android/skills) · [VoltAgent 音频界面设计参考目录](https://github.com/VoltAgent/awesome-design-md)
