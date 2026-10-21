# 空间日程墙

基于 PICO Spatial SDK 6.1.9 的 Kotlin MR 示例工程。应用以 `Stage` 运行在 Mixed Passthrough 空间中：

- 使用 SpatialUI `TextField` 和 `Button` 创建待办，支持标题、正文、截止日期、优先级和标签。
- 使用 `PlaneTrackingManager` 扫描真实平面，并在有效平面事件到达后贴附便签。
- 使用 `WorldTrackingManager` 创建、加载和删除持久空间锚点。
- 使用应用私有 `SharedPreferences` 保存待办内容与空间位置，跨次进入恢复便签内容。
- 笔记库支持搜索、完成状态、今日/高优先筛选，并显示待办统计。
- 默认容量为 40 条；清空或摘下会同时删除本地记录和空间锚点。

## 全球化

界面文案使用 Android 资源，默认语言为英文，并提供简体中文、繁体中文、日文、韩文、西班牙文、法文和德文资源。系统会根据设备语言自动选择资源；未单独翻译的辅助状态会回退到英文。

应用内部使用稳定的 `normal`、`high`、`low` 优先级值，不依赖显示语言；旧版本中文优先级数据会在读取时自动迁移。

## 构建

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

## 上传前检查

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
git status --short
```

本地 Gradle 缓存、构建目录、模拟器截图、临时目录和签名文件均已加入 `.gitignore`。推送前请确认远端地址、提交身份和仓库可见性符合项目要求；本项目不会在构建脚本中保存密钥。

## 真机验收重点

需要在支持 PICO OS 6 MR 的设备或 PICO Emulator 中验证：平面网格事件、真实平面上的释放贴附、退出重进后的重定位，以及最终的手势摘取。当前 MVP 已提供可验证的“摘下”按钮删除路径；真正的手部抓取和语音转文字应在设备 API 与产品交互方案确认后接入。
