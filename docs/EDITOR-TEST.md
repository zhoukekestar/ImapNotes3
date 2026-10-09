# 笔记编辑器验证

版本：1.4.8-microg.8（10415）  
日期：2026-10-09  
设备：Android 15 / API 35，标准 microG 0.3.17。覆盖安装保留原账号和笔记，仅使用本次新建的测试笔记进行修改。

## 使用方式

- 打开已有笔记进入只读模式，点击“编辑”后可点击正文输入；新建笔记直接编辑。
- 可切换富文本、HTML 源码和 Markdown；HTML / Markdown 点击“预览”后可返回源码。
- “阅读”保留当前草稿并禁止修改；有未保存的内容时仍可保存，退出会提示保存或放弃。
- 保存沿用现有 IMAP 事务、附件与冲突保护。Markdown 以兼容备忘录客户端的 HTML 同步，并携带经过内容摘要校验的原始源码；外部正文修改后不再使用过期源码。

## 本地检查

`testDebugUnitTest`：71 项通过，0 失败。新增 9 项覆盖格式切换不改写原 HTML、常用 Markdown 渲染、MIME 换行规范化后的源码往返、外部编辑使旧源码失效、文档样式保留、空内容与 Unicode、HTML 表格和内嵌图片、相同图片与 CID 前缀的区分、分享时图片可独立显示。

`lintDebug`：0 错误。Debug APK 与 opt-in 设备探针 APK 构建通过。

## 实际邮箱与设备检查

QQ、163、Gmail 分别通过以下 12 项，共 36 项：

1. 新建笔记实际点击正文并通过键盘输入。
2. 页面重建后保留未保存的富文本草稿。
3. 切换 HTML、通过键盘修改源码、预览粗体与 Unicode；预览禁止输入。
4. 返回 HTML 源码保持内容，实际点击保存菜单，核对本地 MIME。
5. 已有笔记默认只读，格式工具隐藏，点击复选框不改变正文。
6. 点击“编辑”后正文可通过键盘修改。
7. Markdown 预览正确显示粗体、任务清单、表格和代码块。
8. Markdown / HTML 切换和阅读模式保留草稿，源码只读时没有输入能力。
9. 保存 Markdown 后本地 MIME 保留原始源码。
10. 邮箱同步后重新打开，Markdown 源码逐字一致；查看格式不产生未保存提示。
11. 页面重建后保留未保存的 Markdown 草稿、格式选择与编辑能力。
12. 重复运行生产 `SyncAdapter` 后远端只有一条测试笔记，HTML 正文和 Markdown 源码均正确；下载缓存继续保留源码。

探针通过已保存的账号连接邮箱，不写入账号密码或授权码。为避免 Android 后台调度影响验证，探针在保存后直接调用与同步服务相同的生产 `SyncAdapter` 实现。Gmail 使用已授权的标准 microG 账号。

运行方式（邮箱已由用户在界面授权）：

```sh
adb -s emulator-5554 shell am instrument -w -e editor true -e noteServer imap.qq.com \
  io.github.zhoukekestar.imapnotes3.test/de.niendo.ImapNotes3.Miscs.GoogleLoginInstrumentation
```

163 / Gmail 将 `noteServer` 替换为 `imap.163.com` / `imap.gmail.com`。本机私有测试输出和截图不加入发布资产。
