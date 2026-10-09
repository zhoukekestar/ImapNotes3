# 163 登录与笔记同步验证

2026-10-09，`1.4.8-microg.6` / versionCode `10413`，Android 15 / API 35 标准 microG 模拟器。

## 登录

真实账号的邮箱授权码认证成功。仅输入邮箱地址与授权码，界面自动填写 `imap.163.com`、端口 `993`、SSL/TLS 与独立的 `Notes` 文件夹。登录立即显示加载状态；保存账号后重新连接通过。应用在访问文件夹前发送 RFC 2971 客户端 ID，内容仅包含应用信息。

授权码通过 debug 应用私有缓存提供给界面测试，读取后立即删除。账号保存在模拟器 Android AccountManager 中；提交的源码、测试结果文件和发布产物不含实际账号或授权码。

## 发现并修复的兼容问题

163 登录后的 CAPABILITY 宣告 `UIDPLUS`，但 `UID EXPUNGE` 返回 `BAD Parse command error`。旧代码已上传修改后的笔记，却因旧版本的定向清理失败保留了上传队列，并将同步判为失败。

现在先给指定消息设置 `Deleted` 标记，再尝试定向清理。缺少 UIDPLUS 或服务器以 BAD 拒绝该清理指令时，删除标记已完成旧版本退役，同步不再因此失败。权限拒绝（NO）和连接错误仍传播并保留待重试内容；不会退回全量 EXPUNGE。能力检查使用当前文件夹连接，避免额外建立连接。

163 的 `SEARCH HEADER` 会漏掉已经存在的自定义上传 ID。旧版本失败重试时可能再次 APPEND 同一操作，形成重复笔记。现在搜索未找到精确匹配时，批量读取 UID、FLAGS 与上传 ID 邮件头，核对已有上传并复用其 UID。此步骤不读取正文，排除已删除消息，并继续遵守 QQ 普通草稿隔离规则。

## 验证结果

- 真实界面登录、即时加载、保存 Notes 路径、保存账号重连：4 项通过。
- 已有 Notes 文件夹的首次下载通过；原有内容保留。
- 使用生产保存和同步逻辑创建、上传、修改替换，服务端每条测试笔记只有一个有效版本。
- 服务端创建的 multipart Apple Notes 格式笔记下载通过，中文、emoji 与 HTML 内容正确。
- 重复同步不增加消息，本地每条测试笔记只有一条缓存记录；编辑基准哈希与服务端原始 MIME 一致。
- 同一已确认上传连续重放 3 次，均复用同一 UID，没有重复上传。
- 在实际编辑器输入并保存 `ImapNotes3 UI smoke test 163 1009`，核对服务端正文；再通过界面删除并完成生产同步，独立 IMAP 检查确认该测试笔记已不存在或标记删除。
- 62 项 JVM 测试通过，lint 无错误。新增 10 项真实 JavaMail loopback 协议测试覆盖 UIDPLUS 成功、错误宣告、缺失、权限拒绝与断线，以及上传 ID 索引遗漏、精确匹配、已删除记录、普通 QQ 草稿和前缀不匹配。
- 标准 microG 的 7 项 Google 登录检查通过；QQ 与 Gmail 的创建、修改、下载、重复同步、缓存及连续 3 次上传重放通过，QQ 普通草稿隔离继续通过。

最终保留两条明确命名的测试笔记：`ImapNotes3 创建同步测试 1009-091822`、`ImapNotes3 下载同步测试 1009-093444`。临时编辑器测试笔记已删除，只清理过测试程序自身重复消息。

```sh
adb -s emulator-5554 shell am instrument -w \
  -e noteMode roundtrip -e noteServer imap.163.com \
  io.github.zhoukekestar.imapnotes3.test/de.niendo.ImapNotes3.Miscs.GoogleLoginInstrumentation
```

本次覆盖 ImapNotes3 与真实 163 IMAP 服务之间的同步；Apple 备忘录客户端显示没有作为本次通过项。
