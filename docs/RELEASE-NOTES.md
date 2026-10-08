# ImapNotes3 1.4.8-microg.3

- 修复真机安装 MicroG RE 后应用仍选择 Google Play 账号的问题：优先识别经过签名验证的 microG，支持 Morphe MicroG RE 7.1.1 的 `app.revanced` 账号。
- 保存选择的认证器账号类型，登录、令牌刷新与同步始终使用同一认证器；已有标准 microG 账号保留 `com.google` 类型。
- 验证 MicroG RE 的官方发布签名与账号认证服务归属，不根据包名直接信任未知安装包。
- 修复关闭登录页后异步回调仍弹窗导致的 BadTokenException 崩溃，并保留页面重建时的 Google 登录状态。
- 区分 OAuth 注册、网络连接、授权、认证服务启动和认证器签名错误；被小米自启动策略拦截时，提示检查后台运行与自启动。

33 项 JVM 单元测试通过，lint 无错误。标准 microG 的 Android 15 模拟器 7 项登录检查通过；Android 16 真机通过 MicroG RE 登录并同步 6 条 Gmail Notes，重启应用后再次同步成功。详情记录于 `docs/PHONE-LOGIN-TEST.md`。

本版沿用上一版发布签名。Android 7.1 或更新版本；包名 `io.github.zhoukekestar.imapnotes3`。Google OAuth 项目保持测试模式，账号需加入测试用户。仅支持明确校验过的 microG 签名；Apple 客户端显示新建笔记与其他设备需要单独验证。
