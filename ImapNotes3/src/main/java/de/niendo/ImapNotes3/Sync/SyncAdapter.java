/*
 * Copyright (C) 2022-2025 - Peter Korf <peter@niendo.de>
 * Copyright (C)      2016 - Axel Strübing
 * Copyright (C)      2016 - Martin Carpella
 * Copyright (C)      2015 - nb
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
import android.accounts.AccountManager;
import android.content.AbstractThreadedSyncAdapter;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.Intent;
import android.content.SyncResult;
import android.net.Uri;
import android.os.Bundle;

import androidx.annotation.NonNull;

import android.util.Log;

import de.niendo.ImapNotes3.BuildConfig;
import de.niendo.ImapNotes3.Data.ConfigurationFieldNames;
import de.niendo.ImapNotes3.Data.ImapNotesAccount;
import de.niendo.ImapNotes3.Data.NotesDb;
import de.niendo.ImapNotes3.Data.OneNote;
import de.niendo.ImapNotes3.Data.Security;
import de.niendo.ImapNotes3.ImapNotes3;
import de.niendo.ImapNotes3.ListActivity;
import de.niendo.ImapNotes3.Miscs.ImapNotesResult;
import de.niendo.ImapNotes3.Miscs.Utilities;
import de.niendo.ImapNotes3.Miscs.HtmlNote;

import com.sun.mail.imap.AppendUID;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import javax.mail.Flags;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.internet.MimeMessage;

/// A SyncAdapter provides methods to be called by the Android
/// framework when the framework is ready for the synchronization to
/// occur.  The application does not need to consider threading
/// because the sync happens under Android control not under control
/// of the application.
class SyncAdapter extends AbstractThreadedSyncAdapter {
    private static final String TAG = "SyncAdapter";
    private static final int THREAD_ID = 0xF00C;
    @NonNull
    private final Context applicationContext;
    private NotesDb storedNotes;
    private ImapNotesAccount account;

    private final SyncUtils syncUtils;

    SyncAdapter(@NonNull Context applicationContext) {
        super(applicationContext, true, false);
        Log.d(TAG, "SyncAdapter");
        syncUtils = new SyncUtils();

        //mContentResolver = applicationContext.getContentResolver();
        // TODO: do we really need a copy of the applicationContext reference?
        this.applicationContext = applicationContext;
    }


    @Override
    public void onPerformSync(@NonNull Account accountArg,
                              Bundle extras,
                              String authority,
                              ContentProviderClient provider,
                              SyncResult syncResult) {
        synchronized (de.niendo.ImapNotes3.Miscs.AccountLocks.forAccount(accountArg.name)) {
            try { performSync(accountArg, extras, syncResult); }
            catch (Exception e) {
                syncResult.stats.numIoExceptions++;
                NotifySyncFinished(true, false, "Sync failed; local changes retained");
                Log.w(TAG, "Sync failed", e);
            } finally { syncUtils.DisconnectFromRemote(); }
        }
    }

    private void performSync(Account accountArg, Bundle extras, SyncResult syncResult) {
        Log.d(TAG, "Beginning network synchronization of account: " + accountArg.name);
        // TODO: should the account be static?  Should it be local?  If static then why do we not
        // provide it in the constructor?  What happens if we allow parallel syncs?
        account = new ImapNotesAccount(accountArg, applicationContext);

        //SyncUtils.CreateLocalDirectories(accountArg.name, applicationContext);
        account.CreateLocalDirectories();
        storedNotes = NotesDb.getInstance(applicationContext);

        // Connect to remote and get UIDValidity
        ImapNotesResult res = ConnectToRemote();
        String errorMessage = "";

        if (res.returnCode != ImapNotesResult.ResultCodeSuccess &&
                res.returnCode != ImapNotesResult.ResultCodeImapFolderCreated) {
            syncResult.stats.numIoExceptions++;
            NotifySyncFinished(false, false, res.errorMessage);
            return;
        } else if (!(res.UIDValidity.equals(
                syncUtils.GetUIDValidity(accountArg, applicationContext)))) {
            // Compare UIDValidity to old saved one
            // Replace local data by remote.  UIDs are no longer valid.
            try {
                // Keep pending notes, deletion markers and a recoverable archive of the old namespace.
                File root = account.GetRootDirAccount();
                File archive = new File(root, "uid-archive-" + System.currentTimeMillis());
                if (!archive.mkdirs()) throw new IOException("Cannot archive old UID namespace");
                File[] local = root.listFiles();
                if (local != null) for (File f : local) {
                    if (f.isFile() && !f.renameTo(new File(archive, f.getName())))
                        throw new IOException("Cannot archive note");
                }
                File deleted = new File(root, "deleted");
                File[] removals = deleted.listFiles();
                if (removals != null) for (File f : removals) {
                    if (!f.renameTo(new File(archive, "deleted-" + f.getName())))
                        throw new IOException("Cannot archive deletion marker");
                }
                storedNotes.ClearDb(accountArg.name);
                // Rebuild pending local entries independently of old IMAP UIDs.
                File[] pending = new File(root, "new").listFiles();
                if (pending != null) for (File f : pending) {
                    if (!f.getName().endsWith(".eml")) continue;
                    Message m = SyncUtils.ReadMailFromFile(f);
                    if (m == null) throw new IOException("Unreadable pending note");
                    storedNotes.InsertANoteInDb(new OneNote(m.getSubject(),
                            Utilities.internalDateFormat.format(new java.util.Date()),
                            "-" + Utilities.removeMailExt(f.getName()), accountArg.name,
                            HtmlNote.GetNoteFromMessage(m).color, OneNote.SAVE_STATE_OK));
                }
                syncUtils.GetNotes(accountArg, root, applicationContext, storedNotes);
                SyncUtils.SetUIDValidity(accountArg, res.UIDValidity, applicationContext);
            } catch (MessagingException | IOException e) {
                syncResult.stats.numIoExceptions++;
                errorMessage = "UID namespace changed; local changes retained for retry";
                Log.w(TAG, errorMessage, e);
            }
            // Notify ListActivity that it's finished, and that it can refresh display
            Log.d(TAG, "end on perform :" + errorMessage);
            NotifySyncFinished(true, errorMessage.isEmpty(), errorMessage);
            return;
        }



        // Send new local messages to remote, move them to local folder
        // and update uids in database
        boolean isChanged = handleNewNotes();

        // Delete on remote messages that are deleted locally (if necessary)
        if (handleDeletedNotes()) isChanged = true;

        // handle notes created or removed on remote
        boolean remoteNotesManaged = false;
        try {
            remoteNotesManaged = syncUtils.handleRemoteNotes(account.GetRootDirAccount(),
                    storedNotes, accountArg.name);
        } catch (MessagingException | IOException e) {
            errorMessage = e.getLocalizedMessage();
            syncResult.stats.numIoExceptions++;
            Log.e(TAG, "onPerformSync handleRemoteNotes failed", e);
        }
        if (remoteNotesManaged) isChanged = true;

        // Disconnect from remote
        syncUtils.DisconnectFromRemote();
        //Log.d(TAG, "Network synchronization complete of account: "+account.name);
        // Notify ListActivity that it's finished, and that it can refresh display
        boolean refreshTags = extras.getBoolean(ListActivity.REFRESH_TAGS);
        if (refreshTags) {
            //Log.d(TAG, "refreshTags");
            File directory = ImapNotes3.GetAccountDir(account.accountName);
            File[] listOfFiles = directory.listFiles();
            for (File file : Objects.requireNonNull(listOfFiles)) {
                if (file.isFile()) {
                    String uid = Utilities.removeMailExt(file.getName());
                    Log.d(TAG, "FilterResults: " + file.getName());
                    List<String> tags = ListActivity.searchHTMLTags(directory, uid, Utilities.HASHTAG_PATTERN, true);
                    storedNotes.UpdateTags(tags, uid, account.accountName);
                }
            }
        }
        Log.d(TAG, "Finish network synchronization of account: " + accountArg.name + " Msg: " + errorMessage);
        NotifySyncFinished(isChanged, errorMessage.isEmpty(), errorMessage);
    }

    private void NotifySyncFinished(boolean isChanged,
                                    boolean isSynced,
                                    String errorMessage) {
        Log.d(TAG, "NotifySyncFinished: " + isChanged + " " + isSynced);
        if (ImapNotes3.intent == null) ImapNotes3.intent = new Intent(SyncService.SYNC_FINISHED);
        ImapNotes3.intent.putExtra(ListActivity.EDIT_ITEM_ACCOUNTNAME, account.accountName);
        ImapNotes3.intent.putExtra(ListActivity.CHANGED, isChanged);
        ImapNotes3.intent.putExtra(ListActivity.SYNCED, isSynced);
        ImapNotes3.intent.putExtra(ListActivity.SYNCINTERVAL, account.syncInterval.name());
        ImapNotes3.intent.putExtra(ListActivity.SYNCED_ERR_MSG, errorMessage);
        getContext().getContentResolver().notifyChange(Uri.parse("content://" + BuildConfig.APPLICATION_ID + "/"), null, false);

    }

    /* It is possible for this function to throw exceptions; the original code caught
    MessagingException but just logged it instead of handling it.  This results in a possibility of
    returning null.  Removing the catch fixes the possible null reference but of course means that
    the caller becomes responsible.  This is the correct approach.
     */
    @NonNull
    private ImapNotesResult ConnectToRemote() {
        Log.d(TAG, "ConnectToRemote");
        ImapNotesResult res;
        try {
            res = syncUtils.ConnectToRemote(account, applicationContext, THREAD_ID);
            if (res.returnCode != ImapNotesResult.ResultCodeSuccess) {
                // TODO: Notify the user?
                Log.e(TAG, "Connection problem: " + res.errorMessage);
            }

        } catch (Exception e) {
            Log.e(TAG, "ConnectToRemote Exception: ", e);
            res = new ImapNotesResult(ImapNotesResult.ResultCodeException,
                    "Unknown Error: " + e.getLocalizedMessage(), -1);
        };
        return res;
    }

    private boolean handleNewNotes() {
        Log.d(TAG, "handleNewNotes");
        //Message message = null;
        boolean newNotesManaged = false;
        File accountDir = account.GetRootDirAccount();
        File dirNew = new File(accountDir, "new");
        Log.d(TAG, "dn path: " + dirNew.getAbsolutePath());
        Log.d(TAG, "dn exists: " + dirNew.exists());
        String[] listOfNew = dirNew.list();
        for (String fileNew : Objects.requireNonNull(listOfNew)) {
            if (!fileNew.endsWith(".eml")) continue;
            String suidFileNew = Utilities.removeMailExt(fileNew);
            Log.d(TAG, "New Note to process:" + fileNew);
            newNotesManaged = true;
            // Read local new message from file
            File fileInNew = new File(dirNew, fileNew);
            storedNotes.SetSaveState("-" + suidFileNew, OneNote.SAVE_STATE_SYNCING, account.accountName);
            Message message = SyncUtils.ReadMailFromNoteFile(dirNew, suidFileNew);
            try {
                Log.d(TAG, "handleNewNotes message: " + Objects.requireNonNull(message).getSize());
                Log.d(TAG, "handleNewNotes message: " + fileInNew.length());
            } catch (Exception e) {
                storedNotes.SetSaveState("-" + suidFileNew, OneNote.SAVE_STATE_FAILED, account.accountName);
                throw new IllegalStateException("Unreadable queued note retained", e);
            }
            try {
                message.setFlag(Flags.Flag.SEEN, true); // set message as seen
            } catch (MessagingException e) {
                throw new IllegalStateException("Cannot prepare queued note", e);
            }
            // Send this new message to remote

            String newuid;
            try {
                UploadTransaction.ensureId(message);
                de.niendo.ImapNotes3.Miscs.NoteMime.writeAtomic(fileInNew, message);
                newuid = Long.toString(syncUtils.uploadNote(message));
            } catch (Exception e) {
                storedNotes.SetSaveState("-" + suidFileNew, OneNote.SAVE_STATE_OK, account.accountName);
                throw new IllegalStateException("Upload failed; replacement retained", e);
            }
            Log.d(TAG, "handleNewNotes uid: " + newuid);

            File to = new File(accountDir, Utilities.addMailExt(newuid));
            if (fileInNew.renameTo(to)) {
                // move new note from new dir, one level up
                storedNotes.DeleteANote("-" + suidFileNew, account.accountName);
                storedNotes.DeleteANote(newuid, account.accountName);
                try {
                    storedNotes.InsertANoteInDb(new OneNote(message.getSubject(),
                            Utilities.internalDateFormat.format(new java.util.Date()), newuid,
                            account.accountName, HtmlNote.GetNoteFromMessage(message).color, OneNote.SAVE_STATE_OK));
                } catch (MessagingException e) { throw new IllegalStateException(e); }
                List<String> tags = ListActivity.searchHTMLTags(accountDir, newuid, Utilities.HASHTAG_PATTERN, true);
                storedNotes.UpdateTags(tags, newuid, account.accountName);
                storedNotes.SetSaveState(newuid, OneNote.SAVE_STATE_OK, account.accountName);
            } else {
                throw new IllegalStateException("Cannot finalize uploaded note; pending file retained");
            }
        }
        return newNotesManaged;
    }

    private boolean handleDeletedNotes() {
        //Message message = null;
        Log.d(TAG, "handleDeletedNotes");
        boolean deletedNotesManaged = false;
        File dirDeleted = new File(account.GetRootDirAccount(), "deleted");
        String[] listOfDeleted = dirDeleted.list();
        for (String fileDeleted : Objects.requireNonNull(listOfDeleted)) {
            try {
                syncUtils.DeleteNote(fileDeleted);
            } catch (Exception e) {
                throw new IllegalStateException("Delete failed; marker retained", e);
            }

            // remove file from deleted
            File toDelete = new File(dirDeleted, fileDeleted);
            //noinspection ResultOfMethodCallIgnored
            toDelete.delete();
            deletedNotesManaged = true;
        }
        return deletedNotesManaged;
    }

}
