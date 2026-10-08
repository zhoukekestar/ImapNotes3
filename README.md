# zhoukekestar / ImapNotes3

此 fork 增加系统 microG / Google 账号的 Gmail OAuth 登录，并修复附件保留和同步失败时的数据处理。

在账号设置中点击“选择 Google 账号”，选择系统 microG 中已有的账号，再授权 Gmail 邮箱访问。已支持官方标准 microG，以及官方签名的 Morphe MicroG RE 7.1.1（`app.revanced` 账号）；安装受支持的 microG 时优先选择它。其他改签或未知来源的 GmsCore 不会被自动信任。普通 IMAP 邮箱仍可使用密码或应用专用密码。

Google 登录还需要在 Google Cloud 的 Google Auth Platform 中创建 **Android OAuth 客户端**，注册包名 `io.github.zhoukekestar.imapnotes3` 和当前 APK 签名证书的 SHA-1。可用 `apksigner verify --print-certs <APK路径>` 获取指纹；debug 和 release 签名需分别注册。测试模式下，将登录账号加入测试用户，并配置 `https://mail.google.com/` 邮箱访问范围。仅安装 microG 不会完成应用注册，Google 会返回 `UNREGISTERED_ON_API_CONSOLE`。[Google 配置文档](https://support.google.com/cloud/answer/15549257)

microG 0.3.17 的授权页面不再允许其他应用直接启动。本应用先请求系统账号令牌；需要授权时，对官方 microG 校验签名，再通过其认证服务提供的 PendingIntent 打开授权页，不需要 Google SDK 或修改 microG。Google Play 服务及旧版 microG 继续使用系统认证器返回的授权入口。[microG 认证服务源码](https://github.com/microg/GmsCore/blob/v0.3.17.252432/play-services-core/src/main/java/org/microg/gms/auth/AuthManagerServiceImpl.java)

编辑时保留原邮件附件；笔记菜单中的“附件”可以打开图片、PDF 等文件。新版本上传确认后，只有原内容和 UID 命名空间仍匹配才删除旧版本，否则保留两份。前台列表每分钟尝试同步；后台由 Android 调度，不保证实时同步。

## 构建与发布

需要完整 JDK 21、Android SDK platform 37.0、build-tools 37.0.0；使用仓库 Gradle wrapper 9.7.0：

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --no-configuration-cache --max-workers=2
```

Release 构建从环境变量读取 `ANDROID_KEYSTORE_PATH`、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`，缺少签名配置会拒绝构建。签名文件不应提交到 Git。

GitHub Actions 已包含测试和签名 APK 发布流程。在仓库 Secrets 中配置 `ANDROID_KEYSTORE_BASE64`（签名文件的 Base64）、`ANDROID_KEYSTORE_PASSWORD`、`ANDROID_KEY_ALIAS`、`ANDROID_KEY_PASSWORD`，然后推送版本 tag，或手动运行 Signed APK release 并指定已有 tag。Release 作为预发布提供 APK 与 SHA256SUMS。后续版本必须沿用同一签名密钥。

运行 `scripts/verify-release-apk.sh <APK路径> v1.4.8-microg.3` 检查签名、包名、版本和非调试标记。

回归测试覆盖 MIME 往返、附件字节、CID 图片、纯文本转义、原文件写入失败、上传确认丢失、删除重试和冲突判断。既有翻译缺失保留为 lint 警告。microG 登录及 Gmail Notes 创建、修改和双向同步已在独立 Android 15 模拟器验证，物理设备和 Apple 客户端显示仍需分别验证。[发布说明](docs/RELEASE-NOTES.md)

Google 登录的设备复测见 [测试步骤](docs/GOOGLE-LOGIN-TEST.md)，笔记同步与新版界面的验证见 [同步测试记录](docs/NOTES-SYNC-TEST.md)。该测试需要先在应用界面完成授权，检查已保存账号、令牌复用和清除本地缓存后的 Gmail IMAP 重新登录，不打印令牌或邮件内容。

---


<p style="text-align: center;">
  <img src="https://github.com/niendo1/ImapNotes3/blob/master/fastlane/metadata/android/en-US/images/featureGraphic.png" alt="Logo"/>
</p>
<p style="text-align: center;">
<a href="https://github.com/niendo1/ImapNotes3/releases"><img alt="GitHub Release ImapNote3" src="https://img.shields.io/github/v/release/niendo1/ImapNotes3"></a>
<a href="https://github.com/niendo1/richeditor-android"><img alt="GitHub Release RichEditor" src="https://img.shields.io/github/v/release/niendo1/richeditor-android?label=RichEditor"></a>
<a href="https://www.gnu.org/licenses/gpl-3.0"><img src="https://img.shields.io/badge/License-GPLv3-blue.svg" alt="License_GPL_v3"/></a>
<a href="https://f-droid.org/packages/de.niendo.ImapNotes3/"><img alt="F-Droid Version" src="https://img.shields.io/f-droid/v/de.niendo.ImapNotes3"></a>
<a href="https://hosted.weblate.org/projects/ImapNotes3/"><img src="https://img.shields.io/weblate/progress/ImapNotes3" alt="Translation state"/></a>
</p>

ImapNotes3
==========

A notes and checklist Android app that synchronizes in your IMAP mailbox.

### Screenshots

<img width="29%" style="border:5px"
src="https://github.com/niendo1/ImapNotes3/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/1.png"/>
<img width="29%" style="border:5px"
src="https://github.com/niendo1/ImapNotes3/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/2.png"/>
<img width="29%" style="border:5px"
src="https://github.com/niendo1/ImapNotes3/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/3.png"/>
<img width="29%" style="border:5px"
src="https://github.com/niendo1/ImapNotes3/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/4.png"/>
<img width="29%" style="border:5px"
src="https://github.com/niendo1/ImapNotes3/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/5.png"/>
<img width="29%" style="border:5px"
src="https://github.com/niendo1/ImapNotes3/blob/master/fastlane/metadata/android/en-US/images/phoneScreenshots/6.png"/>

### Features

A free and fast notes editor for Android that allows for synchronization with IMAP servers.

__General Attributes__

* compatible with various IMAP servers (~~Gmail~~, Yahoo, AOL, Posteo, and your server)
* **OAuth2/XOauth2 is currently not supported, but it will in one of the next releases**
* security options include _plain text_, _SSL/TLS_, or _STARTTLS_
* save notes as standard HTML E-mails
* manage multiple IMAP accounts and/or notepads
* works offline (with background synchronization)

__Feature-Rich HTML Editor__

* versatile text formatting
* image integration
* create checklists
* full-text search
* font colors

__Additional Capabilities__

* no tracking or advertising
* light and dark mode support
* filter and organize your notes by _#HashTags_
* note sharing with other apps (import/export)
* backup notes as local zip archive
* restore all or just a single note from a local archive

All of these features are contained in this fully open source app, which has no tracking or
advertising, and requires only minimal user permissions.

#### Related Tools

* [IOS Imap Notes](https://addons.thunderbird.net/de/thunderbird/addon/ios-imap-notes/) a
  Thunderbird-Plugin which allows you to edit notes on your desktop.

### Translation

This app can be translated at: <a href="https://hosted.weblate.org/projects/ImapNotes3/">
weblate.org</a>.
Your help is greatly appreciated.
If your favourite language is missing, just drop me an email.

_Catalan:_ polrojas<br>
_Chinese:_ sr093906, hamburger2048 (大王叫我来巡山), pyccl, Xue Xuan, bj y<br>
_Czech:_ LibreTranslate<br>
_French:_ z97febao, e2jk<br>
_German:_ niendo1<br>
_Italian:_ 77nnit<br>
_Norwegian:_ kwhitefood<br>
_Portuguese:_ weblate<br>
_Russian:_ pazengaz<br>
_Spanish:_ gallegonovato<br>
_Ukrainian:_ Maxim Reznik<br>

#### Contributors

nb(enm), N0uri, c0238, Axel Strübing, 7nnit, Poussinou, woheller69, Martin Carpella (carpi),
cocklemon, john-p-williams, wdl923, Ulli Rainer Heist, Andreas Hotz, midros, ...

### History

This project is a clone from https://github.com/nbenm/ImapNote2. It also uses some changes
from https://github.com/kwhitefoot/ImapNote2 and maybe others

Sync your notes between Android, iOs devices and different accounts like Gmail, iCloud and others

This project is a fork of "imapnote" one created by boemianrapsodi (boemianrapsody@gmail.com).
Probably Pasquale Boemio. Some things didn't work correctly but it was impossible to contact him to
correct these bugs.

Original app is named "imapnote", and is available at https://code.google.com/p/imapnote/, So I
decided to name this one "imapnote2". It is under the GPL v3 License, same as "imapnote"

It is based on Apple way to manage notes. They are stored in an imap folder named "Notes". imapnote
uses Gmail for syncing. As I use my own imap server, I have modified it to be used with any imap
server that respects Apple method. It has been tested with Gmail, iCloud (imap.mail.me.com),
Yahoo! (imap.mail.yahoo.com), AOL (imap.aol.com), posteo.de and of course my server. Even if not
still tested, it should work with others.

Main changes in ImapNotes3 are:

- uses a customized version of [RichEditor-Android](https://github.com/niendo1/richeditor-android)
  with a lot of new features
- developed with android studio 2023 and latest tool chain
- HTML Editor with tables, images, sections, check boxes and much more
- color support
- full text search
- sdk version 34
- sharing / receiving data
- notes are saved as standard HTML E-mails
- notes from posteo.de and gmail.com are working again

Main changes in ImapNote2 are :

- app can be used with other servers, not only gmail
- it's possible to open notes
- no permissions are needed by the app
- it works in landscape and portrait modes. Landscape is useful with some devices
- it's possible to delete notes (trash option on detail screen)
- it's possible to create new notes ("new note" option on list screen)
- it's possible to modify notes (change note on detail screen, then save it with disk icon)
- navigation uses ActionBar (minSdkVersion=14)
- even if not recommended, untrusted certificates can be used.
- imap port number can be chosen. 993 is no more the only one accepted
- security can be plain text, SSL/TLS or STARTTLS
- multiple accounts can be used. Android account manager is used
- works offline and sync is done in the background. Sync interval can be different for each account

### Download

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png"
alt="Get it on F-Droid"
height="80">](https://f-droid.org/packages/de.niendo.ImapNotes3/)

Or get the APK from the [Releases Section](https://github.com/niendo1/ImapNotes3/releases/latest).
