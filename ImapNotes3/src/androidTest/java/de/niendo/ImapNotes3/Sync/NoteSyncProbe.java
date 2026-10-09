package de.niendo.ImapNotes3.Sync;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Instrumentation;
import android.content.Context;
import android.content.SyncResult;
import android.os.Bundle;

import de.niendo.ImapNotes3.Data.ConfigurationFieldNames;
import de.niendo.ImapNotes3.AccountConfigurationActivity;
import de.niendo.ImapNotes3.Data.ImapNotesAccount;
import de.niendo.ImapNotes3.Data.NotesDb;
import de.niendo.ImapNotes3.Data.OneNote;
import de.niendo.ImapNotes3.Miscs.GoogleAccountAuth;
import de.niendo.ImapNotes3.Miscs.ImapNotesResult;
import de.niendo.ImapNotes3.Miscs.NoteMime;
import de.niendo.ImapNotes3.Miscs.UpdateThread;
import de.niendo.ImapNotes3.Miscs.Utilities;
import de.niendo.ImapNotes3.R;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Properties;
import java.util.Date;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javax.mail.Folder;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Message;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeBodyPart;
import javax.mail.search.HeaderTerm;

/** Explicitly requested device diagnostics; never emits private note text or OAuth tokens. */
public final class NoteSyncProbe {
    private NoteSyncProbe() {}

    public static Bundle run(Instrumentation instrumentation, String mode, String requestedServer) {
        return run(instrumentation, mode, requestedServer, null);
    }

    public static Bundle run(Instrumentation instrumentation, String mode, String requestedServer, String editorTitle) {
        Bundle result = new Bundle();
        Context context = instrumentation.getTargetContext();
        try {
            AccountManager accounts = AccountManager.get(context);
            Account saved = null;
            for (Account candidate : accounts.getAccountsByType(Utilities.PackageName)) {
                if (requestedServer == null ? "google".equals(accounts.getUserData(candidate, ConfigurationFieldNames.Authentication)) :
                        requestedServer.equals(accounts.getUserData(candidate, ConfigurationFieldNames.Server))) {
                    saved = candidate;
                    break;
                }
            }
            if (saved == null) throw new IllegalStateException("No matching saved mailbox account");
            ImapNotesAccount account = new ImapNotesAccount(saved, context);
            if ("editor".equals(mode)) {
                if (editorTitle == null || !editorTitle.startsWith("ImapNotes3 Editor test "))
                    throw new IllegalArgumentException("Requires editor fixture title");
                sync(context, saved);
            }
            result.putString("configuredFolder", account.GetImapFolder());
            ArrayList<OneNote> local = new ArrayList<>();
            NotesDb.getInstance(context).GetStoredNotes(local, saved.name, "date DESC", null);
            result.putInt("localNotes", local.size());
            if ("sync".equals(mode)) {
                for (int pass = 0; pass < 2; pass++) {
                    SyncResult sync = new SyncResult();
                    new SyncAdapter(context).onPerformSync(saved, new Bundle(),
                            AccountConfigurationActivity.AUTHORITY, null, sync);
                    if (sync.hasError()) throw new IllegalStateException("Production sync failed");
                }
                local.clear();
                NotesDb.getInstance(context).GetStoredNotes(local, saved.name, "date DESC", null);
                result.putInt("localNotesAfterSync", local.size());
                if (local.size() < 2) throw new IllegalStateException("Existing Apple notes missing");
            }
            Properties properties = new Properties();
            properties.setProperty("mail.imaps.ssl.checkserveridentity", "true");
            if (account.googleOAuth) {
                properties.setProperty("mail.imaps.auth.mechanisms", "XOAUTH2");
                properties.setProperty("mail.imaps.auth.xoauth2.disable", "false");
            }
            properties.setProperty("mail.imaps.connectiontimeout", "15000");
            properties.setProperty("mail.imaps.timeout", "30000");
            try (Store store = Session.getInstance(properties).getStore("imaps")) {
                store.connect(account.server, Integer.parseInt(account.portnum), account.username,
                        account.googleOAuth ? GoogleAccountAuth.token(context, account.username, account.googleAccountType) : account.password);
                ImapClientIdentity.send((com.sun.mail.imap.IMAPStore) store, de.niendo.ImapNotes3.BuildConfig.VERSION_NAME);
                if ("editor".equals(mode)) {
                    Folder folder = store.getFolder(account.GetImapFolder());
                    folder.open(Folder.READ_ONLY);
                    try {
                        int count = 0;
                        for (Message message : folder.getMessages()) {
                            if (message.isSet(javax.mail.Flags.Flag.DELETED) || !editorTitle.equals(message.getSubject())) continue;
                            count++;
                            String html = NoteMime.html(NoteMime.copy(message));
                            String markdown = new de.niendo.ImapNotes3.Miscs.NoteEditorDocument(html).markdown();
                            if (!markdown.startsWith("# " + editorTitle + "\n\n") || !markdown.contains("- [x] 创建\n- [ ] 同步") ||
                                    !markdown.contains("| --- | --- |") || !markdown.contains("```html\n<div>source</div>\n```\n") ||
                                    !html.contains("<strong>中文与 emoji ✅</strong>"))
                                throw new IllegalStateException("Editor remote content mismatch");
                        }
                        if (count != 1) throw new IllegalStateException("Editor sync duplicate or missing fixture");
                        OneNote cached = find(context, saved, editorTitle);
                        String html = NoteMime.html(SyncUtils.ReadMailFromFileRootAndNew(cached.GetUid(), account.GetRootDirAccount()));
                        if (!new de.niendo.ImapNotes3.Miscs.NoteEditorDocument(html).markdown().contains("| --- | --- |"))
                            throw new IllegalStateException("Downloaded Markdown source lost");
                        result.putString("editorSync", "PASS: production sync twice, one remote note, HTML display and verbatim Markdown retained");
                    } finally { folder.close(false); }
                }
                if ("compare".equals(mode)) {
                    com.sun.mail.imap.IMAPFolder folder = (com.sun.mail.imap.IMAPFolder) store.getFolder(account.GetImapFolder());
                    folder.open(Folder.READ_ONLY);
                    try {
                        for (OneNote note : local) {
                            if (!note.GetTitle().startsWith("ImapNotes3 创建同步测试 ") || note.GetUid().startsWith("-")) continue;
                            Message original = SyncUtils.ReadMailFromFileRootAndNew(note.GetUid(), account.GetRootDirAccount());
                            Message remote = folder.getMessageByUID(Long.parseLong(note.GetUid()));
                            if (original == null || remote == null) continue;
                            Message parsed = NoteMime.copy(remote);
                            result.putString("imapContentType", remote.getContentType());
                            result.putString("parsedContentType", parsed.getContentType());
                            result.putBoolean("directHashMatches", NoteMime.hash(original).equals(NoteMime.hash(remote)));
                            result.putBoolean("parsedHashMatches", NoteMime.hash(original).equals(NoteMime.hash(parsed)));
                            result.putInt("cachedHtmlLength", NoteMime.html(original).length());
                            result.putInt("imapHtmlLength", NoteMime.html(remote).length());
                            result.putInt("parsedHtmlLength", NoteMime.html(parsed).length());
                            break;
                        }
                    } finally { folder.close(false); }
                }
                if ("roundtrip".equals(mode)) {
                    if (QQNotesScope.applies(account.server, account.GetImapFolder()))
                        qqRoundtripWithOrdinaryDraft(instrumentation, saved, account, store, result);
                    else roundtrip(instrumentation, saved, account, store, result);
                }
                ArrayList<String> folders = new ArrayList<>();
                for (Folder folder : store.getDefaultFolder().list("*")) {
                    String name = folder.getFullName();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!name.equals(account.GetImapFolder()) && !lower.contains("note") && !name.contains("笔记") && !name.contains("備忘") && !name.contains("备忘")) continue;
                    if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
                    folder.open(Folder.READ_ONLY);
                    try {
                        Message[] messages = folder.getMessages();
                        javax.mail.FetchProfile headers = new javax.mail.FetchProfile();
                        headers.add("X-Uniform-Type-Identifier");
                        headers.add(javax.mail.FetchProfile.Item.FLAGS);
                        folder.fetch(messages, headers);
                        int appleNotes = 0;
                        for (Message message : messages) if (!message.isExpunged() &&
                                !message.isSet(javax.mail.Flags.Flag.DELETED) && QQNotesScope.isNote(message)) appleNotes++;
                        folders.add(name + ": messages=" + folder.getMessageCount() + ", appleNotes=" +
                                appleNotes);
                    } finally { folder.close(false); }
                }
                result.putString("notesFolders", String.join("; ", folders));
            }
            if (!"editor".equals(mode) && !"compare".equals(mode) && !"audit".equals(mode) && !"sync".equals(mode) && !"roundtrip".equals(mode)) throw new IllegalArgumentException("Unknown noteMode");
            result.putBoolean("passed", true);
            result.putString("result", "PASS: " + mode + " note folder check");
        } catch (Exception error) {
            if (error.getStackTrace().length > 0) result.putString("failureLocation", error.getStackTrace()[0].toString());
            if (error.getMessage() != null && error.getMessage().contains("Unsafe Login"))
                result.putString("serverFailure", "NetEase rejected the mailbox connection as Unsafe Login");
            result.putString("failureTrace", java.util.Arrays.stream(error.getStackTrace()).limit(6)
                    .map(Object::toString).collect(java.util.stream.Collectors.joining("\n")));
            result.putBoolean("passed", false);
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() +
                    (error instanceof IllegalStateException ? ": " + error.getMessage() : ""));
        }
        return result;
    }

    public static void sync(Context context, Account account) {
        // The first connection can rebuild a changed UID namespace; the next drains pending notes.
        for (int pass = 0; pass < 2; pass++) {
            SyncResult result = new SyncResult();
            new SyncAdapter(context).onPerformSync(account, new Bundle(),
                    AccountConfigurationActivity.AUTHORITY, null, result);
            if (result.hasError()) throw new IllegalStateException("Production sync failed");
        }
    }

    private static OneNote find(Context context, Account account, String title) {
        ArrayList<OneNote> notes = new ArrayList<>();
        NotesDb.getInstance(context).GetStoredNotes(notes, account.name, "date DESC", null);
        for (OneNote note : notes) if (title.equals(note.GetTitle())) return note;
        throw new IllegalStateException("Test note absent from local database");
    }

    private static void save(Instrumentation instrumentation, Account account, String uid,
                             String html, File original) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        boolean[] saved = {false};
        instrumentation.runOnMainSync(() -> new UpdateThread(account.name, (ok, error) -> {
            saved[0] = ok;
            done.countDown();
        }, new ArrayList<>(), null, R.string.save, uid, html, "none",
                instrumentation.getTargetContext(), uid.isEmpty() ? UpdateThread.Action.Insert :
                UpdateThread.Action.Update).withOriginalSnapshot(original).execute());
        if (!done.await(30, TimeUnit.SECONDS) || !saved[0])
            throw new IllegalStateException("Production local save failed");
    }

    private static void verifyRemote(Folder folder, String title, String expected) throws Exception {
        folder.open(Folder.READ_ONLY);
        try {
            ArrayList<Message> found = new ArrayList<>();
            for (Message message : folder.getMessages())
                if (!message.isSet(javax.mail.Flags.Flag.DELETED) && title.equals(message.getSubject())) found.add(NoteMime.copy(message));
            if (found.size() != 1) throw new IllegalStateException("Synthetic upload count: " + found.size());
            String html = NoteMime.html(found.get(0));
            if (!html.contains(expected))
                throw new IllegalStateException("Test upload content mismatch");
            if ("编辑已同步 · 第二版".equals(expected) &&
                    !org.jsoup.Jsoup.parse(html).select("b").text().contains("创建并上传 IMAP Notes"))
                throw new IllegalStateException("Edited HTML markup was escaped");
            if (found.get(0).getHeader("X-Uniform-Type-Identifier") == null)
                throw new IllegalStateException("Apple note header missing");
        } finally { folder.close(false); }
    }

    private static void roundtrip(Instrumentation instrumentation, Account saved,
                                  ImapNotesAccount account, Store store, Bundle result) throws Exception {
        Context context = instrumentation.getTargetContext();
        String stamp = new java.text.SimpleDateFormat("MMdd-HHmmss", Locale.ROOT).format(new Date());
        String androidTitle = "ImapNotes3 创建同步测试 " + stamp;
        String remoteTitle = "ImapNotes3 下载同步测试 " + stamp;
        String body = "<div>" + androidTitle + "</div><div>中文与 emoji ✅</div><div><b>创建并上传 IMAP Notes</b></div>";
        ArrayList<OneNote> existing = new ArrayList<>();
        NotesDb.getInstance(context).GetStoredNotes(existing, saved.name, "date DESC", null);
        for (OneNote note : existing) if (note.GetTitle().startsWith("ImapNotes3 创建同步测试 ")) {
            androidTitle = note.GetTitle();
            body = "<div>" + androidTitle + "</div><div>中文与 emoji ✅</div><div><b>创建并上传 IMAP Notes</b></div>";
            break;
        }
        boolean previouslyCreated = false;
        for (OneNote note : existing) if (note.GetTitle().equals(androidTitle)) previouslyCreated = true;
        if (previouslyCreated) {
            Folder remoteFolder = store.getFolder(account.GetImapFolder());
            remoteFolder.open(Folder.READ_ONLY);
            boolean present = false;
            try {
                for (Message message : remoteFolder.getMessages())
                    if (!message.isSet(javax.mail.Flags.Flag.DELETED) && androidTitle.equals(message.getSubject())) present = true;
            } finally { remoteFolder.close(false); }
            if (!present) {
                // Recover only our synthetic fixture if an interrupted diagnostic removed its remote copy.
                for (OneNote note : existing) if (note.GetTitle().equals(androidTitle) && !note.GetUid().startsWith("-")) {
                    Message cached = SyncUtils.ReadMailFromFileRootAndNew(note.GetUid(), account.GetRootDirAccount());
                    if (cached != null && NoteMime.html(cached).contains("创建并上传 IMAP Notes")) {
                        NotesDb.getInstance(context).DeleteANote(note.GetUid(), saved.name);
                        new File(account.GetRootDirAccount(), Utilities.addMailExt(note.GetUid())).delete();
                        previouslyCreated = false;
                    }
                }
            }
        }
        if (!previouslyCreated) save(instrumentation, saved, "", body, null);
        Folder folder = store.getFolder(account.GetImapFolder());
        // Retire only duplicate fixtures left by an interrupted earlier probe.
        folder.open(Folder.READ_WRITE);
        try {
            ArrayList<Message> fixtures = new ArrayList<>();
            for (Message message : folder.getMessages()) if (!message.isSet(javax.mail.Flags.Flag.DELETED) &&
                    androidTitle.equals(message.getSubject()) &&
                    (NoteMime.html(message).contains("创建并上传 IMAP Notes") || NoteMime.html(message).contains("创建并上传 Gmail Notes")) &&
                    message.getHeader(NoteMime.UPLOAD_ID) != null) fixtures.add(message);
            if (fixtures.size() > 1) {
                com.sun.mail.imap.IMAPFolder imap = (com.sun.mail.imap.IMAPFolder) folder;
                fixtures.sort((left, right) -> {
                    try { return Long.compare(imap.getUID(left), imap.getUID(right)); }
                    catch (Exception error) { throw new IllegalStateException("Cannot order test fixtures"); }
                });
                fixtures.remove(fixtures.size() - 1);
                Message[] retired = fixtures.toArray(new Message[0]);
                for (Message message : retired) message.setFlag(javax.mail.Flags.Flag.DELETED, true);
                SelectiveExpunge.expunge(imap, retired);
            }
        } finally { folder.close(false); }
        OneNote pending = find(context, saved, androidTitle);
        if (!previouslyCreated && !pending.GetUid().startsWith("-")) throw new IllegalStateException("Offline note not queued");
        result.putString("create", "PASS: local save and pending queue");
        instrumentation.sendStatus(1, result);
        sync(context, saved);
        OneNote uploaded = find(context, saved, androidTitle);
        if (uploaded.GetUid().startsWith("-")) throw new IllegalStateException("Upload not acknowledged");
        verifyRemote(folder, androidTitle, "中文与 emoji ✅");
        result.putString("upload", "PASS: server content, Apple header and unique message");
        instrumentation.sendStatus(2, result);

        File snapshot = File.createTempFile("note-probe-", ".eml", context.getCacheDir());
        try {
            Message original = SyncUtils.ReadMailFromFileRootAndNew(uploaded.GetUid(), account.GetRootDirAccount());
            folder.open(Folder.READ_ONLY);
            try {
                Message remoteBase = ((com.sun.mail.imap.IMAPFolder) folder).getMessageByUID(Long.parseLong(uploaded.GetUid()));
                result.putBoolean("baseHashMatchesServer", remoteBase != null && NoteMime.hash(original).equals(NoteMime.hash(remoteBase)));
                instrumentation.sendStatus(2, result);
            } finally { folder.close(false); }
            Message copy = NoteMime.copy(original);
            copy.setHeader(NoteMime.REPLACES, uploaded.GetUid());
            copy.setHeader(NoteMime.BASE_HASH, NoteMime.hash(original));
            long validity = context.getSharedPreferences(
                    de.niendo.ImapNotes3.ImapNotes3.RemoveReservedChars(saved.name), Context.MODE_PRIVATE)
                    .getLong("UIDValidity", -1L);
            copy.setHeader(NoteMime.VALIDITY, Long.toString(validity));
            NoteMime.writeAtomic(snapshot, copy);
            String edited = body + "<div>编辑已同步 · 第二版</div>";
            save(instrumentation, saved, uploaded.GetUid(), edited, snapshot);
            sync(context, saved);
            verifyRemote(folder, androidTitle, "编辑已同步 · 第二版");
            OneNote updated = find(context, saved, androidTitle);
            if (updated.GetUid().startsWith("-") || updated.GetUid().equals(uploaded.GetUid()))
                throw new IllegalStateException("Replacement upload not acknowledged");
            result.putString("edit", "PASS: saved edit, verified replacement, no duplicate");
            instrumentation.sendStatus(3, result);
        } finally { snapshot.delete(); }

        existing.clear();
        NotesDb.getInstance(context).GetStoredNotes(existing, saved.name, "date DESC", null);
        OneNote previousDownload = null;
        for (OneNote note : existing) if (note.GetTitle().startsWith("ImapNotes3 下载同步测试 ")) {
            Message cached = SyncUtils.ReadMailFromFileRootAndNew(note.GetUid(), account.GetRootDirAccount());
            if (cached != null && NoteMime.html(cached).contains("服务器端创建的中文笔记 ✅")) {
                previousDownload = note;
                remoteTitle = note.GetTitle();
                break;
            }
        }
        MimeMessage remote = new MimeMessage(Session.getInstance(new Properties()));
        remote.setSubject(remoteTitle, "UTF-8");
        remote.setSentDate(new Date());
        remote.setHeader("X-Uniform-Type-Identifier", "com.apple.mail-note");
        MimeMultipart alternative = new MimeMultipart("alternative");
        MimeBodyPart plain = new MimeBodyPart();
        plain.setText(remoteTitle + "\n服务器端创建的中文笔记", "UTF-8");
        alternative.addBodyPart(plain);
        MimeBodyPart html = new MimeBodyPart();
        html.setContent("<div>" + remoteTitle + "</div><div>服务器端创建的中文笔记 ✅</div>", "text/html; charset=UTF-8");
        alternative.addBodyPart(html);
        remote.setContent(alternative);
        remote.saveChanges();
        if (previousDownload == null) folder.appendMessages(new Message[]{remote});
        else {
            verifyRemote(folder, remoteTitle, "服务器端创建的中文笔记 ✅");
            // Remove only this synthetic local cache, exercising a fresh server download on retry.
            File localFile = new File(account.GetRootDirAccount(), Utilities.addMailExt(previousDownload.GetUid()));
            if (!localFile.delete()) throw new IllegalStateException("Cannot reset fixture cache");
            NotesDb.getInstance(context).DeleteANote(previousDownload.GetUid(), saved.name);
        }
        sync(context, saved);
        OneNote downloaded = find(context, saved, remoteTitle);
        Message stored = SyncUtils.ReadMailFromFileRootAndNew(downloaded.GetUid(), account.GetRootDirAccount());
        if (!NoteMime.html(stored).contains("服务器端创建的中文笔记 ✅"))
            throw new IllegalStateException("Downloaded multipart content mismatch");
        result.putString("download", "PASS: remote multipart Apple-format note downloaded with Unicode");
        instrumentation.sendStatus(4, result);
        sync(context, saved);
        verifyRemote(folder, androidTitle, "编辑已同步 · 第二版");
        verifyRemote(folder, remoteTitle, "服务器端创建的中文笔记 ✅");
        result.putString("repeatSync", "PASS: no duplicate messages");
        existing.clear();
        NotesDb.getInstance(context).GetStoredNotes(existing, saved.name, "date DESC", null);
        int createRows = 0, downloadRows = 0;
        for (OneNote note : existing) {
            if (androidTitle.equals(note.GetTitle())) createRows++;
            if (remoteTitle.equals(note.GetTitle())) downloadRows++;
        }
        if (createRows != 1 || downloadRows != 1) throw new IllegalStateException("Duplicate synthetic local cache rows");
        result.putString("localCache", "PASS: one cached row per test note after repeat sync");
        OneNote acknowledged = find(context, saved, androidTitle);
        Message replay = SyncUtils.ReadMailFromFileRootAndNew(acknowledged.GetUid(), account.GetRootDirAccount());
        if (replay == null || replay.getHeader(NoteMime.UPLOAD_ID) == null)
            throw new IllegalStateException("Acknowledged fixture upload ID missing");
        SyncUtils retry = new SyncUtils();
        try {
            if (retry.ConnectToRemote(account, context, 0xF00D).returnCode != ImapNotesResult.ResultCodeSuccess)
                throw new IllegalStateException("Upload replay reconnect failed");
            for (int pass = 0; pass < 3; pass++) {
                if (!acknowledged.GetUid().equals(Long.toString(retry.uploadNote(replay))))
                    throw new IllegalStateException("Acknowledged upload was appended again");
            }
        } finally { retry.DisconnectFromRemote(); }
        verifyRemote(folder, androidTitle, "编辑已同步 · 第二版");
        result.putString("uploadRetry", "PASS: three acknowledged upload replays reuse the same UID without duplicates");
        for (OneNote note : existing) if (note.GetTitle().startsWith("ImapNotes3 UI smoke test ")) {
            if (note.GetUid().startsWith("-")) throw new IllegalStateException("UI save still pending");
            verifyRemote(folder, note.GetTitle(), "Saved through the editor");
            result.putString("uiSave", "PASS: editor UI save uploaded with matching content");
        }
        result.putString("createdTestNotes", androidTitle + "; " + remoteTitle);
    }

    private static void qqRoundtripWithOrdinaryDraft(Instrumentation test, Account saved,
                                                     ImapNotesAccount account, Store store, Bundle result) throws Exception {
        com.sun.mail.imap.IMAPFolder folder = (com.sun.mail.imap.IMAPFolder) store.getFolder(account.GetImapFolder());
        String marker = java.util.UUID.randomUUID().toString();
        String title = "ImapNotes3 ordinary draft isolation test";
        MimeMessage ordinary = new MimeMessage(Session.getInstance(new Properties()));
        ordinary.setSubject(title);
        ordinary.setHeader("X-ImapNotes3-Isolation-Test", marker);
        ordinary.setText("Synthetic ordinary draft, never a note", "UTF-8");
        ordinary.saveChanges();
        folder.appendMessages(new Message[]{ordinary});
        try {
            roundtrip(test, saved, account, store, result);
            ArrayList<OneNote> notes = new ArrayList<>();
            NotesDb.getInstance(test.getTargetContext()).GetStoredNotes(notes, saved.name, "date DESC", null);
            for (OneNote note : notes) if (title.equals(note.GetTitle()))
                throw new IllegalStateException("Ordinary draft appeared as a note");
            folder.open(Folder.READ_ONLY);
            long uid;
            try {
                Message[] matches = folder.search(new HeaderTerm("X-ImapNotes3-Isolation-Test", marker));
                matches = exactMarker(matches, "X-ImapNotes3-Isolation-Test", marker);
                if (matches.length != 1 || matches[0].isSet(javax.mail.Flags.Flag.DELETED))
                    throw new IllegalStateException("Ordinary draft was modified or deleted");
                uid = folder.getUID(matches[0]);
            } finally { folder.close(false); }
            SyncUtils sync = new SyncUtils();
            try {
                if (sync.ConnectToRemote(account, test.getTargetContext(), 0xF00D).returnCode != ImapNotesResult.ResultCodeSuccess)
                    throw new IllegalStateException("Isolation reconnect failed");
                try { sync.DeleteNote(Long.toString(uid)); throw new IllegalStateException("Ordinary draft delete guard missing"); }
                catch (javax.mail.MessagingException expected) {
                    if (!expected.getMessage().contains("ordinary QQ draft")) throw expected;
                }
            } finally { sync.DisconnectFromRemote(); }
            result.putString("ordinaryDraftIsolation", "PASS: ordinary draft excluded, preserved, and protected from note deletion");
        } finally {
            if (folder.isOpen()) folder.close(false);
            folder.open(Folder.READ_WRITE);
            try {
                // Remove only this synthetic draft, never any user's drafts or messages.
                Message[] matches = folder.search(new HeaderTerm("X-ImapNotes3-Isolation-Test", marker));
                matches = exactMarker(matches, "X-ImapNotes3-Isolation-Test", marker);
                for (Message message : matches) message.setFlag(javax.mail.Flags.Flag.DELETED, true);
                if (matches.length > 0) folder.expunge(matches);
            } finally { folder.close(false); }
        }
    }

    private static Message[] exactMarker(Message[] candidates, String header, String marker) throws Exception {
        ArrayList<Message> found = new ArrayList<>();
        for (Message message : candidates) {
            String[] values = message.getHeader(header);
            if (values != null && marker.equals(values[0])) found.add(message);
        }
        return found.toArray(new Message[0]);
    }
}
