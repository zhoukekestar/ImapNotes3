# 登录反馈与 QQ / 163 自动配置

版本 `1.4.8-microg.4`，versionCode `10411`，2026-10-08。

## 行为

填写邮箱地址时自动识别精确域名 `qq.com` / `163.com`（忽略大小写和首尾空格），填好相应 IMAP 服务器、端口 993、SSL/TLS、Notes 文件夹与账号名称。账号名称、服务器等配置默认收在“更多设置”。用户手动指定的服务器保留，防止地址变化覆盖自定义设置。

两个邮箱的密码栏显示“邮箱授权码”，提供开启 IMAP 和生成授权码的说明。QQ 官方帮助链接见下方；163 的入口指向网页版邮箱。用户不需要填写服务器，但必须先开启邮箱的 IMAP 服务并生成授权码。

点击登录后立即显示加载动画、步骤文本和按钮反馈，区分 Google 授权与连接邮箱。禁止重复提交和修改连接参数，取消仍可关闭页面。失败或取消授权后恢复按钮；关闭页面会取消登录任务，已取消的任务不会继续保存账号。

IMAP 认证成功后、访问文件夹之前，仅向声明支持 ID 的服务器发送应用名称、版本、开发者和支持 URL。163 官方说明要求客户端发送 IMAP ID，否则可能返回 Unsafe Login；内容不包含用户邮箱或设备信息。

## 自动化验证

本地 JVM：39 项测试通过，包含邮箱预设的精确域名判断及客户端 ID 协议测试。协议测试使用 loopback IMAP 服务，验证登录 → ID → 文件夹访问顺序，以及未声明 ID 的服务器不会收到 ID 命令。lint 无错误。

账号界面使用独立 Android 15 / API 35 模拟器和 loopback 服务测试，8 项检查通过，不使用真实 QQ / 163 账号。Google 加载检查使用已授权的真实 Google 账号，并在响应前关闭界面，避免不存在的测试账号触发添加 Google 账号页面。运行前启动应用到前台：

```sh
adb -s emulator-5554 shell am start -n \
  io.github.zhoukekestar.imapnotes3/de.niendo.ImapNotes3.ListActivity
adb -s emulator-5554 shell am instrument -w -e accountSetup true \
  io.github.zhoukekestar.imapnotes3.test/de.niendo.ImapNotes3.Miscs.GoogleLoginInstrumentation
```

测试检查默认配置、切换邮箱更新预设、自定义服务器保留、即时加载与重复提交保护、失败恢复、重试、取消及 Google 授权加载与取消。测试截图写入 debug 应用缓存。

Google 回归使用已授权的标准 microG 账号，7 项检查通过，命令参数为 `-e googleLogin true`；检查保存的认证器类型、无 OAuth 密码、缓存与刷新的令牌登录、列表启动及关闭页面后的回调保护。关闭页面检查使用已有账号的异步授权请求。测试输出不包含令牌或笔记内容。

## 服务器验证与范围

`imap.qq.com:993` 与 `imap.163.com:993` 的 TLS 证书校验、IMAP greeting 和 CAPABILITY 均通过；两者均声明支持 ID。该检查未登录邮箱，不等同于真实账号登录或笔记同步成功。

本记录描述 `.4` 的验证范围。QQ 的真实账号验证与文件夹路径修复随后在 `.5` 完成，见 [QQ 测试记录](QQ-LOGIN-TEST.md)。163 的真实账号登录、创建、修改、双向同步、上传重试与删除在 `.6` 完成，见 [163 测试记录](163-LOGIN-TEST.md)。现有 Google / microG 路径继续保留。

## 依据

- [QQ 官方授权码说明](https://help.mail.qq.com/detail/106/985)
- [163 官方 Unsafe Login / IMAP ID 说明](https://help.mail.163.com/faqDetail.do?code=d7a5dc8471cd0c0e8b4b8f4f8e49998b374173cfe9171305fa1ce630d7f67ac2eda07326646e6eb0)
- [RFC 2971](https://www.rfc-editor.org/rfc/rfc2971.html)
- [JavaMail IMAPStore API](https://javaee.github.io/javamail/docs/api/com/sun/mail/imap/IMAPStore.html)
