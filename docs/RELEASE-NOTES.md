# ImapNotes3 1.4.8-microg.4

- Google 授权与 IMAP 登录立即显示加载动画和当前步骤，按钮切换为“正在授权…”或“正在连接…”。登录期间禁止重复提交；失败后恢复表单，取消仍可关闭页面。
- QQ / 163 邮箱根据地址自动填写 IMAP 服务器、993 端口、SSL/TLS、Notes 文件夹与账号名称，只需填写邮箱地址和邮箱授权码。
- 默认收起服务器等高级配置，在“更多设置”中保留手动调整入口；手动指定的服务器不会被邮箱地址变化覆盖。
- 邮箱授权码输入提示与获取说明，避免误用网页版邮箱密码；先在邮箱设置中开启 IMAP 并生成授权码。
- 添加 RFC 2971 客户端识别信息，兼容 163 的 IMAP 安全检查；仅在服务器声明支持 ID 时发送应用信息，不发送用户邮箱或设备标识。

沿用原发布签名，保留 microG 优先策略与现有 Google 登录。39 项 JVM 单元测试、8 项账号界面检查及标准 microG 的 7 项登录检查通过，lint 无错误。账号设置与设备验证见 `docs/ACCOUNT-SETUP-TEST.md`；QQ / 163 的真实账号登录与笔记同步需要用户后续使用自己的授权码测试。

# ImapNotes3 1.4.8-microg.3

- 修复真机安装 MicroG RE 后应用仍选择 Google Play 账号的问题：优先识别经过签名验证的 microG，支持 Morphe MicroG RE 7.1.1 的 `app.revanced` 账号。
- 保存选择的认证器账号类型，登录、令牌刷新与同步始终使用同一认证器；已有标准 microG 账号保留 `com.google` 类型。
- 验证 MicroG RE 的官方发布签名与账号认证服务归属，不根据包名直接信任未知安装包。
- 修复关闭登录页后异步回调仍弹窗导致的 BadTokenException 崩溃，并保留页面重建时的 Google 登录状态。
- 区分 OAuth 注册、网络连接、授权、认证服务启动和认证器签名错误；被小米自启动策略拦截时，提示检查后台运行与自启动。

33 项 JVM 单元测试通过，lint 无错误。标准 microG 的 Android 15 模拟器 7 项登录检查通过；Android 16 真机通过 MicroG RE 登录并同步 6 条 Gmail Notes，重启应用后再次同步成功。详情记录于 `docs/PHONE-LOGIN-TEST.md`。

本版沿用上一版发布签名。Android 7.1 或更新版本；包名 `io.github.zhoukekestar.imapnotes3`。Google OAuth 项目保持测试模式，账号需加入测试用户。仅支持明确校验过的 microG 签名；Apple 客户端显示新建笔记与其他设备需要单独验证。
