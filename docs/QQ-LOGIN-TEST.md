# QQ 登录与 Notes 文件夹修复

版本 `1.4.8-microg.5`，versionCode `10412`，2026-10-09。

## 原因

真实 QQ 账号的授权码认证成功，个人 NAMESPACE 为 `(("" "/"))`。但 `CREATE Notes` 返回 `OK CREATE completed` 后，`LIST "" Notes` 没有返回文件夹；JavaMail 的 `IMAPFolder.create()` 会再次检查 LIST，因而返回 false。旧代码把该结果包装成笼统的“文件夹不存在且创建失败”，使认证成功看起来像登录失败。

继续测试发现，`其他文件夹/Notes` 可以创建和读取，但 APPEND 返回 `NO Not allow save mail to custom folder!`；草稿移动和复制到该文件夹分别返回 `NO Not allow to move mail!`、`NO Not allow to copy mail!`。Sent Messages 的 APPEND 返回 OK 后没有保存测试消息。Drafts 的 APPEND 能保存并保留笔记标记与上传操作 ID。

## 修复

QQ 默认使用 `Drafts` 存储笔记，并且只同步 `X-Uniform-Type-Identifier: com.apple.mail-note` 标记的消息。普通草稿不会显示为笔记，不读取其正文，也不能通过笔记删除入口删除。账号界面明确显示这项存储方式。

普通 IMAP 与 Google 继续使用专用 Notes 文件夹。成功登录后保存实际完整路径；编辑账号切换文件夹时重建 UID 命名空间，保留待上传内容。后续连接不重复添加命名空间前缀。CREATE 保留服务器拒绝原因，并检查 LIST 和文件夹的消息存储能力。

上传后的笔记回读服务端 MIME 作为本地缓存。QQ 的 BODY[TEXT] 响应会多出 CRLF，而完整 RFC 邮件没有；直接比较 IMAP 正文投影与本地 MIME，会误判为远端内容冲突。内容哈希现在解析原始 RFC 邮件，使相同内容一致，实际正文或附件修改仍触发冲突保护。缓存按账号和 UID 替换，避免服务端日期变化形成重复本地记录。

## 验证

52 项 JVM 测试通过：8 项真实 JavaMail loopback 协议测试覆盖 QQ 默认存储及复用、自定义路径、其他邮箱、已有普通 Notes、命名空间完整路径重连、服务器 NO 原因、假成功和不可存储消息的目录；4 项笔记范围测试验证普通草稿、精确笔记标记、无关标记和邮箱范围；另有 IMAP 正文投影附加 CRLF 的冲突回归测试。lint 无错误。

Android 15 / API 35 标准 microG 模拟器已通过真实 QQ 界面登录、即时加载、保存解析路径和保存账号重连。授权码通过 debug 应用私有缓存提供给测试，读取后删除；源码、输出和版本库均不含授权码。

真实 QQ 完整笔记测试通过：生产保存、上传、修改后替换、服务端 multipart 笔记下载、重复同步不增加消息、本地缓存单行，以及普通草稿不显示且拒绝被笔记删除。实际正文与原始服务器 MIME 的基准哈希一致。保留 2 条明确命名的测试笔记；临时普通草稿和独立协议消息仅按自身标记清理。

标准 microG 已通过 7 项 Google 登录回归：保存账号、无 OAuth 密码、缓存和刷新令牌的 IMAP 登录、列表启动和关闭页面后的回调保护。Gmail 创建、修改、双向同步、重复同步与本地缓存检查通过；账号界面 8 项回归通过（默认配置、切换邮箱、自定义服务器、即时加载、防重复点击、失败恢复、重试、取消和 Google 授权加载）。

真实 QQ 笔记探针使用生产保存与同步代码：

```sh
adb -s emulator-5554 shell am instrument -w \
  -e noteMode roundtrip -e noteServer imap.qq.com \
  io.github.zhoukekestar.imapnotes3.test/de.niendo.ImapNotes3.Miscs.GoogleLoginInstrumentation
```

仅发布正式 APK；instrumentation APK 留在本地用于测试。

QQ 在 Apple 备忘录客户端中是否识别共享草稿箱的笔记尚未验证；本次验证覆盖 ImapNotes3 与 QQ IMAP 服务之间的同步。

模拟器最终保留 QQ 的 2 条测试笔记。普通草稿隔离探针的临时草稿已按精确自身标记清理，测试缓存凭据文件已删除。
