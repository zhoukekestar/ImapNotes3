/*
 * Copyright (C) 2022-2025 - Peter Korf <peter@niendo.de>
 * Copyright (C)      2016 - Axel Strübing
 * Copyright (C)      2016 - Martin Carpella
 * Copyright (C) 2015-2016 - nb
 * and Contributors.
 *
 * This file is part of ImapNotes3.
 *
 * ImapNotes3 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.niendo.ImapNotes3.Sync;

import android.accounts.Account;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import android.net.TrafficStats;
import android.util.Log;

import de.niendo.ImapNotes3.Data.NotesDb;
import de.niendo.ImapNotes3.Data.OneNote;
import de.niendo.ImapNotes3.Data.Security;
import de.niendo.ImapNotes3.ImapNotes3;
import de.niendo.ImapNotes3.ListActivity;
import de.niendo.ImapNotes3.Miscs.HtmlNote;
import de.niendo.ImapNotes3.Miscs.ImapNotesResult;

import com.sun.mail.imap.AppendUID;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.util.MailSSLSocketFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.MimeMessage;

import de.niendo.ImapNotes3.Miscs.Utilities;


public class SyncUtils {

    private static final String TAG = "IN_SyncUtils";
    private final Object myLock = new Object();
    // TODO: Why do we have two folder fields and why are they both nullable?
    private Store store;
    private boolean oauth;
    private boolean authenticationFailed;
    private boolean notesOnly;
    @Nullable
    private IMAPFolder remoteIMAPNotesFolder;
    private String copyImapFolderName;
    private Long UIDValidity;

    /**
     * Do we really need the Context argument or could we call getApplicationContext instead?
     *
     * @param rootDirAccount Name of the account as defined by the user, this is not the email address.
     */
    @SuppressWarnings("ResultOfMethodCallIgnored")
    public static void CreateLocalDirectories(@NonNull File rootDirAccount) {
        Log.d(TAG, "CreateDirs(String: " + rootDirAccount);
        (new File(rootDirAccount, "new")).mkdirs();
        (new File(rootDirAccount, "deleted")).mkdirs();
    }

    /**
     * @param uid     ID of the message as created by the IMAP server
     * @param nameDir Name of the account with which this message is associated, used to find the
     *                directory in which it is stored.
     * @return A Java mail message object.
     */
    @Nullable
    public static Message ReadMailFromNoteFile(@NonNull File nameDir,
                                               @NonNull String uid) {
        Log.d(TAG, "ReadMailFromFile: " + nameDir.getPath() + " " + Utilities.addMailExt(uid));

        File mailFile;
        mailFile = new File(nameDir, Utilities.addMailExt(uid));
        if (!mailFile.exists()) {
            // old: only UID is used
            mailFile = new File(nameDir, uid);
            if (!mailFile.exists()) {
                mailFile = new File(nameDir, uid);
                if (!mailFile.exists()) {
                    Log.e(TAG, "ReadMailFromFile: file not found.." + mailFile);
                    return null;
                }
            }
        }
        return (ReadMailFromFile(mailFile));
    }

    // Put values in shared preferences
    synchronized public static void SetUIDValidity(@NonNull Account account,
                                            Long UIDValidity,
                                            @NonNull Context ctx) {
        Log.d(TAG, "SetUIDValidity: " + account.name);
        SharedPreferences preferences = ctx.getSharedPreferences(ImapNotes3.RemoveReservedChars(account.name), Context.MODE_MULTI_PROCESS);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("Name", "valid_data");
        editor.putLong("UIDValidity", UIDValidity);
        editor.apply();
    }

    synchronized private boolean IsConnected() {
        return store != null && store.isConnected();
    }

    // Retrieve values from shared preferences:
    synchronized Long GetUIDValidity(@NonNull Account account,
                                            @NonNull Context ctx) {
        Log.d(TAG, "GetUIDValidity: " + account.name);
        UIDValidity = (long) -1;
        SharedPreferences preferences = ctx.getSharedPreferences(ImapNotes3.RemoveReservedChars(account.name), Context.MODE_MULTI_PROCESS);
        String name = preferences.getString("Name", "");
        if (!name.equalsIgnoreCase("")) {
            UIDValidity = preferences.getLong("UIDValidity", -1);
        }
        return UIDValidity;
    }

    /**
     * @param uid     ID of the message as created by the IMAP server
     * @param fileDir Name of the account with which this message is associated, used to find the
     *                directory in which it is stored.
     * @return A Java mail message object.
     */
    @Nullable
    public static Message ReadMailFromFileRootAndNew(@NonNull String uid,
                                                     @NonNull File fileDir) {
        Log.d(TAG, "ReadMailFromFileRootAndNew: " + fileDir.getPath() + " " + uid);

        // new or changed file
        if (uid.startsWith("-")) {
            uid = uid.substring(1);
            fileDir = new File(fileDir, "new");
        }
        return ReadMailFromNoteFile(fileDir, uid);
    }

    /**
     * @param mailFile Name of the account with which this message is associated, used to find the
     *                 directory in which it is stored.
     * @return A Java mail message object.
     */
    @Nullable
    public static Message ReadMailFromFile(@NonNull File mailFile) {
        try (InputStream mailFileInputStream = new FileInputStream(mailFile)) {
            try {
                Properties props = new Properties();
                Session session = Session.getDefaultInstance(props);
                Message message = new MimeMessage(session, mailFileInputStream);
                Log.d(TAG, "ReadMailFromFile return new MimeMessage.");
                return message;
            } catch (Exception e) {
                Log.e(TAG, "ReadMailFromFile failed", e);
            } finally {
                mailFileInputStream.close();
            }
        } catch (FileNotFoundException e) {
            Log.e(TAG, "File not found opening mailFile: " + mailFile.getAbsolutePath(), e);
        } catch (IOException exIO) {
            Log.e(TAG, "IO exception opening mailFile: " + mailFile.getAbsolutePath(), exIO);
        }
        Log.e(TAG, "ReadMailFromFile return null.");
        return null;
    }

    /**
     * @param msgString
     * @return A Java mail message object.
     */
    @Nullable
    public static Message ReadMailFromString(String msgString) {
            try {
                Properties props = new Properties();
                Session session = Session.getDefaultInstance(props);
                return new MimeMessage(session, new ByteArrayInputStream(msgString.getBytes()));
            } catch (Exception e) {
                Log.e(TAG, "ReadMailFromString failed", e);
        }
        return null;
    }

    /**
     * @param outfile      Name of local file in which to store the note.
     * @param notesMessage The note in the form of a mail message.
     */
    private static void SaveNote(@NonNull File outfile,
                                 @NonNull Message notesMessage) {
        try {
            de.niendo.ImapNotes3.Miscs.NoteMime.writeAtomic(outfile, notesMessage);
        } catch (IOException | MessagingException e) {
            throw new IllegalStateException("Cannot persist downloaded note", e);
        }

    }

    public ImapNotesResult ConnectToRemote(de.niendo.ImapNotes3.Data.ImapNotesAccount account,
                                            Context context, int threadID) throws IOException {
        oauth = account.googleOAuth;
        if (oauth && (!"imap.gmail.com".equalsIgnoreCase(account.server) ||
                !"993".equals(account.portnum) || account.security != Security.SSL_TLS))
            throw new IOException("Google OAuth requires imap.gmail.com:993 with TLS");
        String secret = oauth ? de.niendo.ImapNotes3.Miscs.GoogleAccountAuth.token(context, account.username, account.googleAccountType) : account.password;
        ImapNotesResult result = ConnectToRemote(account.username, secret, account.server,
                account.portnum, account.security, account.GetImapFolder(), account.GetCopyImapFolderName(), threadID);
        if (oauth && authenticationFailed) {
            de.niendo.ImapNotes3.Miscs.GoogleAccountAuth.invalidate(context, secret, account.googleAccountType);
            secret = de.niendo.ImapNotes3.Miscs.GoogleAccountAuth.token(context, account.username, account.googleAccountType);
            result = ConnectToRemote(account.username, secret, account.server, account.portnum,
                    account.security, account.GetImapFolder(), account.GetCopyImapFolderName(), threadID);
        }
        return result;
    }

    @NonNull
    public ImapNotesResult ConnectToRemote(@NonNull String username,
                                           @NonNull String password,
                                           @NonNull String server,
                                           String portnum,
                                           @NonNull Security security,
                                           @NonNull String ImapFolderName,
                                           @NonNull String copyImapFolderName,
                                           int threadID
    ) {
        authenticationFailed = false;

        this.copyImapFolderName = copyImapFolderName;

        TrafficStats.setThreadStatsTag(threadID);

        //final ImapNotesResult res = new ImapNotesResult();
        if (IsConnected()) {
            try {
                store.close();
            } catch (MessagingException e) {
                // Log the error but do not propagate the exception because the connection is now
                // closed even if an exception was thrown.
                Log.w(TAG, "ConnectToRemote Store.Close(): ", e);
            }
        }
        //boolean acceptcrt = security.acceptcrt;

        MailSSLSocketFactory sf;
        try {
            sf = new MailSSLSocketFactory();
        } catch (GeneralSecurityException e) {
            Log.e(TAG, "MailSSLSocketFactory failed: ", e);
            return new ImapNotesResult(ImapNotesResult.ResultCodeCantConnect,
                    "Can't connect to server: " + e.getLocalizedMessage(), -1);
        }
        Properties props = new Properties();

        String proto = security.proto;
        props.setProperty(String.format("mail.%s.host", proto), server);
        props.setProperty(String.format("mail.%s.port", proto), portnum);
        props.setProperty("mail.store.protocol", proto);
        if (security.acceptcrt) {
            sf.setTrustedHosts(server);
            if (proto.equals("imap")) {
                props.put("mail.imap.ssl.socketFactory", sf);
                props.put("mail.imap.starttls.enable", "true");
            }
        } else if (security != Security.None) {
            props.put(String.format("mail.%s.ssl.checkserveridentity", proto), "true");
            if (proto.equals("imap")) {
                props.put("mail.imap.starttls.enable", "true");
            }
        }

        // FIXME: spelling error?
        if (proto.equals("imaps")) {
            props.put("mail.imaps.socketFactory", sf);
        }
        props.setProperty("mail." + proto + ".connectiontimeout", "15000");
        props.setProperty("mail." + proto + ".timeout", "30000");
        props.setProperty("mail." + proto + ".writetimeout", "30000");
        if (oauth) {
            props.setProperty("mail." + proto + ".auth.mechanisms", "XOAUTH2");
            props.setProperty("mail." + proto + ".auth.xoauth2.disable", "false");
            props.setProperty("mail." + proto + ".auth.login.disable", "true");
            props.setProperty("mail." + proto + ".auth.plain.disable", "true");
        }

        /*
        TODO: use user defined proxy.
        Boolean useProxy = false;
        if (useProxy) {
            props.put("mail.imap.socks.host", "10.0.2.2");
            props.put("mail.imap.socks.port", "1080");
        }
         */
        try {
            Session session = Session.getInstance(props, null);
            //session.setDebug(true);
            store = session.getStore(proto);
            store.connect(server, username, password);
            ImapClientIdentity.send((com.sun.mail.imap.IMAPStore) store,
                    de.niendo.ImapNotes3.BuildConfig.VERSION_NAME);
            //res.hasUIDPLUS = ((IMAPStore) store).hasCapability("UIDPLUS");
            //Log.v(TAG, "has UIDPLUS="+res.hasUIDPLUS);

            NotesFolder.Prepared prepared = NotesFolder.prepare(
                    (com.sun.mail.imap.IMAPStore) store, server, ImapFolderName);
            remoteIMAPNotesFolder = prepared.folder;
            notesOnly = QQNotesScope.applies(server, remoteIMAPNotesFolder.getFullName());
            return new ImapNotesResult(prepared.created ? ImapNotesResult.ResultCodeImapFolderCreated :
                    ImapNotesResult.ResultCodeSuccess, "", remoteIMAPNotesFolder.getUIDValidity(),
                    remoteIMAPNotesFolder.getFullName());
        } catch (Exception e) {
            authenticationFailed = e instanceof javax.mail.AuthenticationFailedException;
            DisconnectFromRemote();
            return new ImapNotesResult(ImapNotesResult.ResultCodeException,
                    e.getLocalizedMessage(),
                    -1);
        }

    }

    public synchronized void DisconnectFromRemote() {
        Log.d(TAG, "DisconnectFromRemote");
        try {
            if (store != null) store.close();
        } catch (MessagingException e) {
            Log.e(TAG, "DisconnectFromRemote failed", e);
        }
    }

    synchronized static void RemoveAccount(@NonNull Context context, @NonNull String accountName) {
        Log.d(TAG, "RemoveAccount: " + accountName);
        // Delete account name entries in database
        NotesDb storedNotes = NotesDb.getInstance(context);
        storedNotes.ClearDb(accountName);
        // remove Shared Preference file
        File toDelete = new File(ImapNotes3.GetSharedPrefsDir(), ImapNotes3.RemoveReservedChars(accountName) + ".xml");
        //noinspection ResultOfMethodCallIgnored
        toDelete.delete();
        try {
            org.apache.commons.io.FileUtils.deleteDirectory(ImapNotes3.GetAccountDir(accountName));
        } catch (IOException e) { Log.w(TAG, "Cannot remove account cache", e); }

    }

    AppendUID[] sendMessageToRemote(@NonNull Message[] message) throws MessagingException {
        assert remoteIMAPNotesFolder != null;
        synchronized (myLock) {
            OpenRemoteIMAPNotesFolder(Folder.READ_WRITE);
            return (remoteIMAPNotesFolder.appendUIDMessages(message));
        }
    }

    long uploadNote(Message message) throws MessagingException, IOException {
        return UploadTransaction.upload(message, new UploadTransaction.Remote() {
            public long find(String operationId) throws MessagingException {
                OpenRemoteIMAPNotesFolder(Folder.READ_WRITE);
                return UploadIdLookup.find(remoteIMAPNotesFolder, operationId, notesOnly);
            }
            public long append(Message note) throws MessagingException {
                AppendUID[] ids = sendMessageToRemote(new Message[]{note});
                return ids == null || ids.length == 0 || ids[0] == null ||
                        ids[0].uidvalidity != remoteIMAPNotesFolder.getUIDValidity() ? -1 : ids[0].uid;
            }
            public void retireOriginal(Message note) throws MessagingException, IOException {
                String[] old = note.getHeader(de.niendo.ImapNotes3.Miscs.NoteMime.REPLACES);
                String[] validity = note.getHeader(de.niendo.ImapNotes3.Miscs.NoteMime.VALIDITY);
                String[] hash = note.getHeader(de.niendo.ImapNotes3.Miscs.NoteMime.BASE_HASH);
                // Different UID namespace or changed content: leave both versions as conflict copies.
                if (old == null || validity == null || hash == null ||
                        !validity[0].equals(Long.toString(remoteIMAPNotesFolder.getUIDValidity()))) return;
                Message original = remoteIMAPNotesFolder.getMessageByUID(Long.parseLong(old[0]));
                if (UploadTransaction.canRetire(note, original, remoteIMAPNotesFolder.getUIDValidity()))
                    DeleteNote(old[0]);
            }
        });
    }

    synchronized private void SaveNoteAndUpdateDatabase(@NonNull File directory,
                                                        @NonNull Message notesMessage,
                                                        @NonNull Message canonical,
                                                        @NonNull NotesDb storedNotes,
                                                        @NonNull String accountName,
                                                        @NonNull String suid,
                                                        @NonNull String bgColor) throws IOException {
        File outfile = new File(directory, Utilities.addMailExt(suid));
        Log.d(TAG, "SaveNoteAndUpdateDatabase: " + outfile.getCanonicalPath() + " " + accountName);
        SaveNote(outfile, canonical);

        // Now update or save the metadata about the message

        String title = "";
        try {
            title = de.niendo.ImapNotes3.Miscs.NoteMime.subject(canonical);
        } catch (Exception e) {
            Log.e(TAG, "getSubject failed", e);
        }

        // Get INTERNALDATE
        String internaldate = Utilities.internalDateFormatString;
        try {
            Date MessageInternaldate = notesMessage.getReceivedDate();
            internaldate = Utilities.internalDateFormat.format(MessageInternaldate);
        } catch (Exception e) {
            Log.e(TAG, "getReceivedDate failed", e);
        }

        if (title == null)
            title = "no title";

        OneNote aNote = new OneNote(
                title,
                internaldate,
                suid,
                accountName,
                bgColor,
                OneNote.SAVE_STATE_OK);

        // The server may change INTERNALDATE while canonicalizing an uploaded draft.
        // Replace the cached row by account and UID, independently of its previous date.
        storedNotes.DeleteANote(suid, accountName);
        storedNotes.InsertANoteInDb(aNote);
        List<String> tags = ListActivity.searchHTMLTags(directory, suid, Utilities.HASHTAG_PATTERN, true);
        storedNotes.UpdateTags(tags, suid, accountName);
    }

    synchronized boolean handleRemoteNotes(@NonNull File rootFolderAccount,
                                           @NonNull NotesDb storedNotes,
                                           @NonNull String accountName)
            throws MessagingException, IOException {
        return handleRemoteNotes(rootFolderAccount, storedNotes, accountName, false);
    }

    synchronized boolean handleRemoteNotes(@NonNull File rootFolderAccount,
                                           @NonNull NotesDb storedNotes,
                                           @NonNull String accountName, boolean refreshCanonical)
            throws MessagingException, IOException {
        assert remoteIMAPNotesFolder != null;
        Log.d(TAG, "handleRemoteNotes: " + remoteIMAPNotesFolder.getFullName() + " " + accountName);

        Message notesMessage;
        boolean result = false;
        ArrayList<Long> uids = new ArrayList<>();
        ArrayList<String> localListOfNotes = new ArrayList<>();

        // Get local list of notes uids
        File[] files = rootFolderAccount.listFiles();
        for (File file : files) {
            if (file.isFile() && (file.length() > 1)) {
                localListOfNotes.add(Utilities.removeMailExt(file.getName()));
            }
        }
        synchronized (myLock) {
            OpenRemoteIMAPNotesFolder(Folder.READ_ONLY);

            // Add to local device, new notes added to remote
            Message[] notesMessages = remoteIMAPNotesFolder.getMessagesByUID(1, UIDFolder.LASTUID);
            for (int index = notesMessages.length - 1; index >= 0; index--) {
                    notesMessage = notesMessages[index];
                    if (notesOnly && !QQNotesScope.isNote(notesMessage)) continue;
                    long uid = remoteIMAPNotesFolder.getUID(notesMessage);
                    // Get FLAGS
                    //flags = notesMessage.getFlags();
                    boolean deleted = notesMessage.isSet(Flags.Flag.DELETED);
                    // Builds remote list while in the loop, but only if not deleted on remote


                    if (!deleted) {
                        uids.add(remoteIMAPNotesFolder.getUID(notesMessage));
                    }
                    String suid = Long.toString(uid);
                    if (!deleted && (refreshCanonical || !localListOfNotes.contains(suid) ||
                            storedNotes.GetSaveState(suid, accountName).equals(OneNote.SAVE_STATE_SYNCING))) {
                        Message canonical = notesOnly ? de.niendo.ImapNotes3.Miscs.NoteMime.copy(notesMessage) : notesMessage;
                        String bgColor = HtmlNote.GetNoteFromMessage(canonical).color;
                        SaveNoteAndUpdateDatabase(rootFolderAccount, notesMessage, canonical, storedNotes, accountName, suid, bgColor);
                        result = true;
                    }
            }
        }

        // Remove from local device, notes removed from remote
        for (String suid : localListOfNotes) {
            Long uid = Long.valueOf(suid);
            if (!(uids.contains(uid))) {
                // Remove note from database
                storedNotes.DeleteANote(suid, accountName);
                // remove file from deleted
                File toDelete;
                toDelete = new File(rootFolderAccount, Utilities.addMailExt(suid));
                //noinspection ResultOfMethodCallIgnored
                toDelete.delete();
                // old: only SUID is used
                toDelete = new File(rootFolderAccount, suid);
                //noinspection ResultOfMethodCallIgnored
                toDelete.delete();
                result = true;
            }
        }

        return result;
    }

    private void OpenRemoteIMAPNotesFolder(int mode) throws MessagingException {
        // FIX for Race Condition..needs more work
        // sendMessageToRemote sometime closes the working folder
        if (remoteIMAPNotesFolder.isOpen() && remoteIMAPNotesFolder.getMode() != mode)
            remoteIMAPNotesFolder.close(false);
        if (!remoteIMAPNotesFolder.isOpen()) remoteIMAPNotesFolder.open(mode);
    }

    /* Copy all notes from the IMAP server to the local directory using the UID as the file name.
     */
    void GetNotes(@NonNull Account account,
                               @NonNull File RootDirAccount,
                               @NonNull Context applicationContext,
                               @NonNull NotesDb storedNotes) throws MessagingException, IOException {
        Log.d(TAG, "GetNotes: " + account.name);
        assert remoteIMAPNotesFolder != null;
        synchronized (myLock) {
            OpenRemoteIMAPNotesFolder(Folder.READ_ONLY);


            // From the docs: "Folder implementations are expected to provide light-weight Message
            // objects, which get filled on demand. "
            // This means that at this point we can ask for the subject without getting the rest of the
            // message.
            Message[] notesMessages = remoteIMAPNotesFolder.getMessages();
            //Log.d(TAG,"number of messages in folder="+(notesMessages.length));
            // TODO: explain why we enumerate the messages in descending order of index.
            for (int index = notesMessages.length - 1; index >= 0; index--) {
                Message notesMessage = notesMessages[index];
                if (notesOnly && !QQNotesScope.isNote(notesMessage)) continue;
                if (notesMessage.isSet(Flags.Flag.DELETED)) continue;
                // write every message in files/{accountname} directory
                // filename is the original message uid
                long UIDM = remoteIMAPNotesFolder.getUID(notesMessage);
                String suid = Long.toString(UIDM);
                Message canonical = notesOnly ? de.niendo.ImapNotes3.Miscs.NoteMime.copy(notesMessage) : notesMessage;
                String bgColor = HtmlNote.GetNoteFromMessage(canonical).color;
                SaveNoteAndUpdateDatabase(RootDirAccount, notesMessage, canonical, storedNotes, account.name, suid, bgColor);
            }
        }
    }

    void DeleteNote(String fileName) throws MessagingException {
        Log.d(TAG, "DeleteNote: " + fileName);
        assert remoteIMAPNotesFolder != null;
        long numMessage = Long.parseLong(Utilities.removeMailExt(fileName));
        synchronized (myLock) {
            OpenRemoteIMAPNotesFolder(Folder.READ_WRITE);
            Message old = remoteIMAPNotesFolder.getMessageByUID(numMessage);
            if (old == null) return;
            if (notesOnly && !QQNotesScope.isNote(old))
                throw new MessagingException("Refusing to delete an ordinary QQ draft");
            Message[] msgs = {old};

            Folder saveFolder;
            if (!copyImapFolderName.isEmpty()) {
                try {
                    saveFolder = remoteIMAPNotesFolder.getParent().getFolder(copyImapFolderName);
                    remoteIMAPNotesFolder.copyMessages(msgs, saveFolder);
                } catch (Exception e) {
                    Log.e(TAG, "DeleteNote Cannot move note:", e);
                }
            }
            remoteIMAPNotesFolder.setFlags(msgs, new Flags(Flags.Flag.DELETED), true);
            SelectiveExpunge.expunge(remoteIMAPNotesFolder, msgs);
            // Keep deletion scoped to these messages even if UID EXPUNGE is unavailable.
        }
    }

    @Override
    protected void finalize() {
     /*   if (IsConnected()) {
            try {
                store.close();
            } catch (MessagingException e) {
                Log.e(TAG, "store.close() failed", e);
            }
        }

      */
    }

}
