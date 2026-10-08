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

    public static Bundle run(Instrumentation instrumentation, String mode) {
        Bundle result = new Bundle();
        Context context = instrumentation.getTargetContext();
        try {
            AccountManager accounts = AccountManager.get(context);
            Account saved = null;
            for (Account candidate : accounts.getAccountsByType(Utilities.PackageName)) {
                if ("google".equals(accounts.getUserData(candidate, ConfigurationFieldNames.Authentication))) {
                    saved = candidate;
                    break;
                }
            }
            if (saved == null) throw new IllegalStateException("No saved Google account");
            ImapNotesAccount account = new ImapNotesAccount(saved, context);
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
            properties.setProperty("mail.imaps.auth.mechanisms", "XOAUTH2");
            properties.setProperty("mail.imaps.auth.xoauth2.disable", "false");
            properties.setProperty("mail.imaps.connectiontimeout", "15000");
            properties.setProperty("mail.imaps.timeout", "30000");
            try (Store store = Session.getInstance(properties).getStore("imaps")) {
                store.connect(account.server, Integer.parseInt(account.portnum), account.username,
                        GoogleAccountAuth.token(context, account.username, account.googleAccountType));
                if ("roundtrip".equals(mode)) roundtrip(instrumentation, saved, account, store, result);
                ArrayList<String> folders = new ArrayList<>();
                for (Folder folder : store.getDefaultFolder().list("*")) {
                    String name = folder.getFullName();
                    String lower = name.toLowerCase(Locale.ROOT);
                    if (!lower.contains("note") && !name.contains("笔记") && !name.contains("備忘") && !name.contains("备忘")) continue;
                    if ((folder.getType() & Folder.HOLDS_MESSAGES) == 0) continue;
                    folder.open(Folder.READ_ONLY);
                    try {
                        folders.add(name + ": messages=" + folder.getMessageCount() + ", appleNotes=" +
                                folder.search(new HeaderTerm("X-Uniform-Type-Identifier", "com.apple.mail-note")).length);
                    } finally { folder.close(false); }
                }
                result.putString("notesFolders", String.join("; ", folders));
            }
            if (!"audit".equals(mode) && !"sync".equals(mode) && !"roundtrip".equals(mode)) throw new IllegalArgumentException("Unknown noteMode");
            result.putBoolean("passed", true);
            result.putString("result", "PASS: " + mode + " note folder check");
        } catch (Exception error) {
            if (error.getStackTrace().length > 0) result.putString("failureLocation", error.getStackTrace()[0].toString());
            result.putBoolean("passed", false);
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() +
                    (error instanceof IllegalStateException ? ": " + error.getMessage() : ""));
        }
        return result;
    }

    private static void sync(Context context, Account account) {
        SyncResult result = new SyncResult();
        new SyncAdapter(context).onPerformSync(account, new Bundle(),
                AccountConfigurationActivity.AUTHORITY, null, result);
        if (result.hasError()) throw new IllegalStateException("Production sync failed");
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
                if (title.equals(message.getSubject())) found.add(message);
            if (found.size() != 1) throw new IllegalStateException("Missing or duplicate test upload");
            String html = NoteMime.html(found.get(0));
            if (!html.contains(expected))
                throw new IllegalStateException("Test upload content mismatch");
            if ("编辑已同步 · 第二版".equals(expected) &&
                    !org.jsoup.Jsoup.parse(html).select("b").text().contains("创建并上传 Gmail Notes"))
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
        String body = "<div>" + androidTitle + "</div><div>中文与 emoji ✅</div><div><b>创建并上传 Gmail Notes</b></div>";
        ArrayList<OneNote> existing = new ArrayList<>();
        NotesDb.getInstance(context).GetStoredNotes(existing, saved.name, "date DESC", null);
        for (OneNote note : existing) if (note.GetTitle().startsWith("ImapNotes3 创建同步测试 ")) {
            androidTitle = note.GetTitle();
            body = "<div>" + androidTitle + "</div><div>中文与 emoji ✅</div><div><b>创建并上传 Gmail Notes</b></div>";
            break;
        }
        boolean previouslyCreated = false;
        for (OneNote note : existing) if (note.GetTitle().equals(androidTitle)) previouslyCreated = true;
        if (!previouslyCreated) save(instrumentation, saved, "", body, null);
        Folder folder = store.getFolder(account.GetImapFolder());
        // Retire only duplicate fixtures left by an interrupted earlier probe.
        folder.open(Folder.READ_WRITE);
        try {
            ArrayList<Message> fixtures = new ArrayList<>();
            for (Message message : folder.getMessages()) if (androidTitle.equals(message.getSubject()) &&
                    NoteMime.html(message).contains("创建并上传 Gmail Notes") &&
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
                imap.expunge(retired);
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
        for (OneNote note : existing) if (note.GetTitle().startsWith("ImapNotes3 UI smoke test ")) {
            if (note.GetUid().startsWith("-")) throw new IllegalStateException("UI save still pending");
            verifyRemote(folder, note.GetTitle(), "Saved through the editor");
            result.putString("uiSave", "PASS: editor UI save uploaded with matching content");
        }
        result.putString("createdTestNotes", androidTitle + "; " + remoteTitle);
    }
}
