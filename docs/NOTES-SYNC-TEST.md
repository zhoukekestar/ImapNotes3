# 笔记同步与界面验证

测试日期：2026-10-08。环境：独立 Android 15 / API 35 ARM64 模拟器，官方 microG，已授权的 Google 测试账号。测试直接连接真实 Gmail 的 `Notes` 文件夹。

## 缺失笔记的原因与修复

服务端原有 2 条带 Apple Notes 标识的笔记，本地列表却为空。同步在重建待上传笔记时抛出类型转换异常：Android JavaMail 返回 `SharedByteArrayInputStream`，原代码假设 HTML 内容必为 `String`，因此整次同步中断。

现已显式注册 JavaMail MIME 处理器，并在文本流回退路径中按 Content-Type 的 charset 解码。同步读取使用只读模式，上传才切换为读写模式。修复后原有 2 条笔记下载成功，原有本地待上传笔记保留并上传。

中文测试还发现下载标题的旧编码处理会破坏大写 `UTF-8` 等 RFC 2047 标题，导致中文变为问号。现保留 JavaMail 已解码的标题，仅对未编码且符合 UTF-8 的原始字节做兼容修复；升级后的首次正常同步会从本地原始文件恢复缓存标题，不修改服务端原笔记。

编辑回归还修复了未保存的 JavaMail HTML 对象缺少 Content-Type 头时被误判为纯文本的问题。判断 MIME 类型时优先使用该对象已知的 DataHandler 类型，避免编辑后把 HTML 标签转义成正文。设备验证同时核对修改后的粗体元素和重新打开时的实际排版；已通过，编辑器显示中文、emoji、粗体与第二版内容，没有字面的 HTML 标签。

## 实际验证

- 生产保存逻辑创建本地笔记并加入待同步队列。
- 上传到真实 Gmail，核对中文、emoji、正文和 Apple Notes 标识。
- 修改后验证替换的新版本，旧版本退役，服务端不重复。
- 从 Gmail 下载 multipart/alternative 格式的测试笔记，核对中文标题与正文。
- 再次同步并检查没有重复消息。
- 在编辑器界面实际输入 `ImapNotes3 UI smoke test 1008`，点击保存，核对服务端正文 `Saved through the editor`。

保留的测试笔记名称：`ImapNotes3 创建同步测试 1008-194957`、`ImapNotes3 下载同步测试 1008-195548`、`ImapNotes3 UI smoke test 1008`。只清理了测试程序早期尝试产生的自身重复消息。

Apple Notes → Android 的已有笔记导入已验证。Android → Gmail 上传已验证，Apple 客户端是否显示新笔记仍等待账号持有人确认，不能把 IMAP 服务端通过等同于 Apple 客户端显示通过。

## 界面

采用 Material 3，暖白/深色背景、圆角笔记卡片、琥珀色操作按钮、48dp 操作触区。账号页使用单一浮动标签，Google 登录时隐藏密码字段。编辑器增加留白、行距和自适应图片，格式栏适配浅色与深色。

列表显示同步进行中、上次成功时间、失败重试入口，以及本地待同步提示。空列表提供说明和新建入口。设备检查包含浅色、深色、1.3 倍字体、搜索空结果、键盘与实际保存。修复切换配置时旧账号监听器未移除、账号栏偶尔空白的问题，以及关闭搜索后的列表刷新；搜索使用 AppCompat 控件，返回键先关闭搜索并恢复列表。

设备集成测试最终返回 `passed=true` 与 `INSTRUMENTATION_CODE: -1`，原始结果见 [设备测试输出](NOTES-SYNC-RESULT.txt)。

## 自动复测

先按照 [Google 登录步骤](GOOGLE-LOGIN-TEST.md) 构建、安装并授权。以下命令会创建和修改带 `ImapNotes3` 测试前缀的笔记并同步到真实账号；仅在需要真实账号测试时显式执行。

```sh
adb -s <device> shell am instrument -w -e noteMode audit io.github.zhoukekestar.imapnotes3.test/de.niendo.ImapNotes3.Miscs.GoogleLoginInstrumentation
adb -s <device> shell am instrument -w -e noteMode sync io.github.zhoukekestar.imapnotes3.test/de.niendo.ImapNotes3.Miscs.GoogleLoginInstrumentation
adb -s <device> shell am instrument -w -e noteMode roundtrip io.github.zhoukekestar.imapnotes3.test/de.niendo.ImapNotes3.Miscs.GoogleLoginInstrumentation
```

`audit` 只输出文件夹名称和笔记数量；`sync` 调用生产同步代码；`roundtrip` 检查保存、上传、修改、下载与防重，不输出令牌或用户笔记正文。重试会复用已创建的测试笔记，并只重置下载测试笔记的本地缓存。

最终 APK 再次完成 Google 登录集成检查：6 项通过，包含本地令牌缓存清除后的重新登录。

26 项 JVM 单元测试通过；lint 无错误（346 项警告，含已有翻译和布局警告）；debug APK 和设备测试 APK 构建成功。其他 Android 版本、物理设备、release 签名以及 iCloud 原生笔记协议未纳入本次设备验证。
