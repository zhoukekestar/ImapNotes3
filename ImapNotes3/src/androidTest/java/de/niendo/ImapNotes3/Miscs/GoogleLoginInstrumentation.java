package de.niendo.ImapNotes3.Miscs;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;

import de.niendo.ImapNotes3.ListActivity;
import de.niendo.ImapNotes3.AccountConfigurationActivity;
import de.niendo.ImapNotes3.Data.ConfigurationFieldNames;
import de.niendo.ImapNotes3.Data.ImapNotesAccount;
import de.niendo.ImapNotes3.Sync.SyncUtils;

/** Opt-in integration probe using a Google account already authorized in the app's UI. */
public final class GoogleLoginInstrumentation extends Instrumentation {
    private String noteMode;
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null) noteMode = arguments.getString("noteMode");
        if (noteMode == null && (arguments == null || !"true".equals(arguments.getString("googleLogin")))) {
            Bundle result = new Bundle();
            result.putString("result", "Skipped: requires -e googleLogin true and prior UI consent");
            finish(Activity.RESULT_CANCELED, result);
            return;
        }
        start();
    }

    @Override public void onStart() {
        if (noteMode != null) {
            Bundle result = de.niendo.ImapNotes3.Sync.NoteSyncProbe.run(this, noteMode);
            finish(result.getBoolean("passed") ? Activity.RESULT_OK : Activity.RESULT_CANCELED, result);
            return;
        }
        Bundle result = new Bundle();
        int passed = 0;
        try {
            AccountManager accounts = AccountManager.get(getTargetContext());
            Account saved = null;
            for (Account account : accounts.getAccountsByType(Utilities.PackageName)) {
                if ("google".equals(accounts.getUserData(account, ConfigurationFieldNames.Authentication))) {
                    saved = account;
                    break;
                }
            }
            if (saved == null) throw new IllegalStateException("No authorized Google login saved");
            if (accounts.getPassword(saved) != null) throw new IllegalStateException("OAuth password stored");
            passed++;
            ImapNotesAccount account = new ImapNotesAccount(saved, getTargetContext());
            String cached = GoogleAccountAuth.token(getTargetContext(), account.username, account.googleAccountType);
            if (cached.isEmpty()) throw new IllegalStateException("No cached token");
            passed++;
            connect(account);
            passed++;
            GoogleAccountAuth.invalidate(getTargetContext(), cached, account.googleAccountType);
            String refreshed = GoogleAccountAuth.token(getTargetContext(), account.username, account.googleAccountType);
            if (refreshed.isEmpty()) throw new IllegalStateException("No refreshed token");
            passed++;
            connect(account);
            passed++;
            // Explicit launches, including navigation after setup, may have no Intent action.
            Activity list = startActivitySync(new Intent(getTargetContext(), ListActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            waitForIdleSync();
            if (list.isFinishing() || list.isDestroyed()) throw new IllegalStateException("List launch failed");
            passed++;
            Activity setup = startActivitySync(new Intent(getTargetContext(), AccountConfigurationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.util.concurrent.atomic.AtomicInteger staleCallbacks = new java.util.concurrent.atomic.AtomicInteger();
            runOnMainSync(() -> {
                // Force an asynchronous authenticator failure, then close its recipient page.
                GoogleAccountAuth.authorize(setup, "missing-account@invalid.example", account.googleAccountType, 9999,
                        staleCallbacks::incrementAndGet, message -> staleCallbacks.incrementAndGet());
                setup.finish();
            });
            android.os.SystemClock.sleep(1500);
            waitForIdleSync();
            if (staleCallbacks.get() != 0) throw new IllegalStateException("Callback delivered to closed setup page");
            passed++;
            result.putString("accountType", account.googleAccountType);
            result.putString("result", "PASS: saved account, no stored password, cached IMAP login, refreshed IMAP login, list launch, closed-page callback");
            result.putInt("checksPassed", passed);
            finish(Activity.RESULT_OK, result);
        } catch (Exception error) {
            // Never print account identifiers, mailbox contents, or OAuth tokens.
            result.putString("result", "FAIL: " + error.getClass().getSimpleName());
            result.putInt("checksPassed", passed);
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void connect(ImapNotesAccount account) throws Exception {
        SyncUtils sync = new SyncUtils();
        try {
            ImapNotesResult result = sync.ConnectToRemote(account, getTargetContext(), 0xF00D);
            if (result.returnCode != ImapNotesResult.ResultCodeSuccess) {
                throw new IllegalStateException("IMAP login failed");
            }
        } finally {
            sync.DisconnectFromRemote();
        }
    }
}
