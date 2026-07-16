[Uploading README.md…]()
# 羊小跳

羊小跳是一款 Android 开屏广告与弹窗处理工具。它通过无障碍服务识别当前应用中的目标控件，并自动执行点击或返回操作，帮助减少重复等待和干扰。

> 当前版本：1.3.0。羊小跳完全在设备本地运行，应用未申请联网权限，也不收集或上传用户数据。

## 功能

- 内置 328 个应用、795 条弹窗规则
- 按应用包名哈希隔离规则，避免跨应用执行
- 支持文本、控件 ID、精确匹配、组合条件和层级选择器
- 支持无障碍点击、坐标手势、延迟操作和返回键动作
- 通用识别可处理常见的「跳过」「Skip」和关闭按钮
- 本地统计累计跳过次数
- 前台服务通知显示运行状态
- 暖色 Material 3 界面，集中展示服务和规则状态

## 系统要求

- Android 7.0（API 24）或更高版本
- JDK 17
- Android SDK 36

## 权限说明

- **无障碍服务**：读取当前界面的控件信息并点击匹配到的广告跳过按钮。
- **通知权限**：显示服务运行状态和开机提醒。
- **开机广播**：设备启动后检查服务状态；应用不会自行开启无障碍权限。
- **振动权限**：跳过成功后提供短暂振动反馈。

羊小跳不会自动授予系统权限。系统设置、权限控制器、安装器和桌面等敏感应用会被排除，导入规则中的授权、安装、购买和支付动作也会被阻止。

## 构建

项目使用 Gradle 和 Kotlin 构建：

```powershell
.\gradlew.bat :app:assembleDebug
```

macOS 或 Linux：

```bash
./gradlew :app:assembleDebug
```

生成的 APK 位于：

```text
app/build/outputs/apk/debug/app-debug.apk
```

## 测试与检查

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug
```

推送到 GitHub 后，[Android CI](.github/workflows/android-ci.yml) 会自动运行单元测试、Android Lint 和 Debug 构建。

## 项目结构

```text
app/src/main/java/com/skip/app/   应用与无障碍服务源码
app/src/main/assets/              内置弹窗规则
app/src/main/res/                 Android 资源
app/src/test/                     JVM 单元测试
```

## 发布说明

`assembleRelease` 默认生成未签名 APK。正式发布前，请使用自己的 Android 签名密钥配置 Release 签名；不要把密钥或密码提交到仓库。

## 注意事项

应用需要用户手动开启无障碍服务后才能工作。不同厂商系统可能还需要关闭电池优化或加入后台白名单。第三方应用更新后，其界面结构可能改变，规则需要随之维护。

规则数据来自项目维护者导入的配置。公开分发规则数据前，请确认你拥有相应的使用和再分发权利。
