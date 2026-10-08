# 真机 Google / microG 登录修复

2026-10-08，Xiaomi Android 16 / API 36，安装 `1.4.8-microg.2` release，包名与签名均匹配已配置的 Google OAuth Android 客户端。

## 原因

设备同时安装 Google Play 服务和 Morphe MicroG RE 7.1.1。MicroG RE 的包名为 `app.revanced.android.gms`，账号类型为 `app.revanced`。原应用仅查询和请求 `com.google`，因此账号选择与登录没有使用已安装的 MicroG RE。

日志还记录到 `AccountConfigurationActivity.showGoogleError` 的 BadTokenException：异步认证错误回调返回时，页面已关闭，但仍尝试创建对话框。

## 修复

优先选择受信任的 microG；将选择结果中的账号类型保存在应用账号配置中，后续取令牌、失效处理及 IMAP 同步使用该类型。标准 microG 仍使用 `com.google`。Google Play 仍作为没有受支持 microG 时的系统认证器。

Morphe MicroG RE 7.1.1 签名 SHA-256：`0b6c9515afb195fac59601696ba0a7907a0b217ccf720b43148427ccf64343e7`。从 MorpheApp 官方 GitHub 7.1.1 发布下载 APK，核对发布 SHA-256 并提取证书，与真机安装 APK 一致；还核对 AccountManager 注册的认证器包名。

认证回调、IMAP 登录回调与弹窗入口都检查页面是否已关闭；保存 Google 登录开关与账号类型以支持页面重建。错误分类不输出原始账号响应或令牌。

## 当前验证

32 项 JVM 单元测试通过，lint 无错误。模拟器回归与原签名真机覆盖安装测试进行中，结果完成后补充。真机用户笔记、Google Play 服务和 MicroG RE 安装均保留。

## 复测

标准 microG 已授权模拟器：构建 debug 与 androidTest APK，执行 `am instrument -w -e googleLogin true`。该测试覆盖缓存登录、清除本地缓存后的登录、列表启动与关闭设置页后的异步回调。

真机：用原发布密钥构建 release APK，覆盖升级后点击选择 Google 账号，确认选择器使用 MicroG RE；授权并创建账号，检查 Gmail Notes 列表和手动同步，然后重启应用再次同步。
