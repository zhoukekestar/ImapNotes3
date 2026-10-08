# Google / microG 登录复测

## 准备

使用独立 Android 模拟器，安装官方 microG Services 和 Companion，并在 microG 中登录 Google 账号。Google Cloud 中注册当前 APK 的包名和签名 SHA-1，配置 Gmail `https://mail.google.com/` 范围，并将该账号加入 OAuth 测试用户。

构建并安装 debug APK：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest
adb -s <device> install -r ImapNotes3/build/outputs/apk/debug/ImapNotes3-1.4.8-microg.2-debug.apk
```

## 界面验证

1. 添加账号，确认“选择 Google 账号”、取消和创建按钮都可见。
2. 选择系统 Google 账号，确认 Gmail、993 端口和 TLS 自动配置。
3. 点击检查并创建，确认 microG 授权页打开。首次出现权限页时，由账号持有人确认完整 Gmail 邮箱访问权限。
4. 拒绝授权应回到设置页且不创建账号，再次点击创建应能够重新授权。
5. 允许授权后应完成真实 IMAP 登录、保存账号并返回列表。
6. 关闭并重新启动应用，确认账号仍在，并能重新连接。

## 已授权账号的集成验证

此测试使用应用保存的第一个 Google 登录账号，通过生产代码连接已有笔记文件夹，不读取邮件正文或发送、删除邮件。它检查账号使用 OAuth 且没有保存密码、令牌可获取、Gmail IMAP 可连接，并清除本地令牌缓存后再取令牌和连接。

```sh
adb -s <device> install -r ImapNotes3/build/outputs/apk/androidTest/debug/ImapNotes3-debug-androidTest.apk
adb -s <device> shell am instrument -w -e googleLogin true io.github.zhoukekestar.imapnotes3.test/de.niendo.ImapNotes3.Miscs.GoogleLoginInstrumentation
```

预期结果是 `checksPassed=6`、`result=PASS...`、`INSTRUMENTATION_CODE: -1`。最后一项检查以没有 action 的 Intent 打开列表页，覆盖空 Intent 启动崩溃。未传入 `googleLogin=true` 时跳过，避免未准备账号的 CI 自动连接真实 Gmail。测试输出不会包含账号、令牌或邮件内容。

## 2026-10-08 设备测试结果

环境：独立 AOSP Android 15 / API 35 ARM64 模拟器 `ImapNotes3_microG_API35`，官方 microG Services `0.3.17.252432`、Companion `84022634`，ImapNotes3 `1.4.8-microg.1` debug APK。Google OAuth 项目保持外部应用测试模式，Android 客户端注册当前 debug 签名。

已通过：账号选择及登录按钮可见；首次 Gmail 授权页打开；持有人点击允许后完成实际 IMAP 登录并保存账号；最终集成验证返回 `checksPassed=6` 和 `INSTRUMENTATION_CODE: -1`，包括缓存令牌登录、清除本地缓存后的重新取令牌登录，以及没有 action 的 Intent 启动列表页；没有保存 OAuth 密码。构建、26 项 JVM 单元测试、lint 均通过（lint 无错误，保留既有警告）。设备集成验证在非 root 的 ADB 状态下执行。

授权拒绝分支尚未执行。其他 Android 版本、Google Play 服务、release 签名和真实设备需要分别复测。
