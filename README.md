# 空间日程墙

基于 PICO Spatial SDK 6.1.9 的 Kotlin MR 示例工程。应用以 `Stage` 运行在 Mixed Passthrough 空间中：

- 使用 SpatialUI `TextField` 和 `Button` 创建纯文字待办。
- 使用 `PlaneTrackingManager` 扫描真实平面，并在有效平面事件到达后贴附便签。
- 使用 `WorldTrackingManager` 创建、加载和删除持久空间锚点。
- 使用应用私有 `SharedPreferences` 保存 `{anchorUUID, text}`，跨次进入恢复便签内容。
- 默认容量为 40 条；清空或摘下会同时删除本地记录和空间锚点。

## 构建

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
```

APK 输出在 `app/build/outputs/apk/debug/app-debug.apk`。

## 真机验收重点

需要在支持 PICO OS 6 MR 的设备或 PICO Emulator 中验证：平面网格事件、真实平面上的释放贴附、退出重进后的重定位，以及最终的手势摘取。当前 MVP 已提供可验证的“摘下”按钮删除路径；真正的手部抓取和语音转文字应在设备 API 与产品交互方案确认后接入。
