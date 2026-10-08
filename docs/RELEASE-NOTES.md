# ImapNotes3 1.4.8-microg.2

## 登录与同步

- 修复官方 microG 0.3.17 首次 Gmail 授权无法打开的问题，通过 microG 提供的 PendingIntent 完成授权，并校验官方签名。
- 显示 Google OAuth 包名和签名未注册的具体错误，账号仍由系统认证器管理，不保存 Google 密码。
- 修复 JavaMail 返回文本流时同步中断的问题，恢复 Gmail 中已有 Apple Notes 笔记的下载。
- 修复中文、UTF-8 和 emoji 标题的解码，首次同步自动修复本地缓存标题。
- 修复编辑后 HTML 标签被当作正文显示的问题，保留粗体、段落和原有附件。
- 读取笔记使用只读 IMAP 模式；编辑继续使用持久队列、上传确认、冲突保护和防重机制。

## 界面

- Material 3 暖白与深色主题、圆角笔记卡片、琥珀色操作按钮。
- 列表显示同步进度、上次成功时间、失败重试和待同步状态。
- 改进账号设置的浮动标签、Google 登录区域、空列表和新建入口。
- 编辑器增加留白、行距和自适应图片，格式工具栏适配浅色与深色。
- 修复切换主题或字体后账号栏空白，以及返回键关闭搜索后列表未恢复的问题。

## 验证与安装

26 项 JVM 单元测试通过，lint 无错误。独立 Android 15 / API 35 模拟器、官方 microG 下，Google 登录 6 项检查、真实 Gmail Notes 创建、编辑、上传、下载、重复同步和编辑器界面保存均通过。浅色、深色、大字体和搜索空结果已检查。

本版沿用 `v1.4.8-microg.1` 的发布签名，支持从该版覆盖升级。Android 7.1 或更新版本；包名 `io.github.zhoukekestar.imapnotes3`。APK 签名、版本、非调试标记和 SHA-256 校验随发布验证。

Google OAuth 项目仍处于测试模式，登录账号需要加入测试用户，当前 APK 的签名 SHA-1 需要注册为 Android OAuth 客户端。真实设备、其他 Android 版本以及 Apple 客户端显示新建笔记仍需分别验证。ReVanced GmsCore 不受支持；iCloud 原生笔记协议不在本次验证范围内。

完整测试步骤和结果见仓库中的 `docs/GOOGLE-LOGIN-TEST.md` 与 `docs/NOTES-SYNC-TEST.md`。
