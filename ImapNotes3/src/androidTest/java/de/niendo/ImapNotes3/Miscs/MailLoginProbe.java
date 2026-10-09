package de.niendo.ImapNotes3.Miscs;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.widget.TextView;
import de.niendo.ImapNotes3.AccountConfigurationActivity;
import de.niendo.ImapNotes3.Data.ConfigurationFieldNames;
import de.niendo.ImapNotes3.Data.ImapNotesAccount;
import de.niendo.ImapNotes3.R;
import de.niendo.ImapNotes3.Sync.SyncUtils;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/** Opt-in real mailbox UI login. Credentials are supplied privately in the debug app cache. */
final class MailLoginProbe {
    static Bundle run(Instrumentation test) {
        Bundle result = new Bundle();
        File credentials = new File(test.getTargetContext().getCacheDir(), "mail-login-fixture.json");
        AccountConfigurationActivity setup = null;
        try {
            JSONObject input = new JSONObject(new String(Files.readAllBytes(credentials.toPath()), java.nio.charset.StandardCharsets.UTF_8));
            if (!credentials.delete()) throw new IllegalStateException("Cannot remove temporary credentials");
            String username = input.getString("username"), password = input.getString("password");
            AccountManager accounts = AccountManager.get(test.getTargetContext());
            Intent intent = new Intent(test.getTargetContext(), AccountConfigurationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            for (Account candidate : accounts.getAccountsByType(Utilities.PackageName)) {
                if (username.equals(accounts.getUserData(candidate, ConfigurationFieldNames.UserName))) {
                    intent.putExtra(AccountConfigurationActivity.ACTION, AccountConfigurationActivity.Actions.EDIT_ACCOUNT);
                    intent.putExtra(AccountConfigurationActivity.ACCOUNTNAME, candidate.name);
                }
            }
            setup = (AccountConfigurationActivity) test.startActivitySync(intent);
            final AccountConfigurationActivity page = setup;
            test.runOnMainSync(() -> {
                ((TextView) page.findViewById(R.id.usernameEdit)).setText(username);
                ((TextView) page.findViewById(R.id.passwordEdit)).setText(password);
                ((TextView) page.findViewById(R.id.folderEdit)).setText("Notes");
                page.findViewById(R.id.accountLoginButton).performClick();
                if (page.findViewById(R.id.loginProgressPanel).getVisibility() != View.VISIBLE)
                    throw new IllegalStateException("Login loading feedback missing");
            });
            long deadline = SystemClock.elapsedRealtime() + 90000;
            Account saved = null;
            while (SystemClock.elapsedRealtime() < deadline) {
                for (Account candidate : accounts.getAccountsByType(Utilities.PackageName))
                    if (username.equals(accounts.getUserData(candidate, ConfigurationFieldNames.UserName))) saved = candidate;
                if (saved != null && (page.isFinishing() || page.isDestroyed())) break;
                AtomicBoolean finished = new AtomicBoolean();
                test.runOnMainSync(() -> finished.set(!page.isFinishing() && !page.isDestroyed() &&
                        page.findViewById(R.id.loginProgressPanel).getVisibility() != View.VISIBLE));
                if (finished.get()) throw new IllegalStateException("Mailbox UI login failed");
                SystemClock.sleep(200);
            }
            if (saved == null) throw new IllegalStateException("Mailbox login timed out");
            // Account creation precedes configuration writes; wait for the successful UI callback.
            deadline = SystemClock.elapsedRealtime() + 15000;
            while (SystemClock.elapsedRealtime() < deadline && !page.isFinishing() && !page.isDestroyed()) SystemClock.sleep(100);
            ImapNotesAccount account = new ImapNotesAccount(saved, test.getTargetContext());
            if ("imap.qq.com".equals(account.server) && !"Drafts".equals(account.GetImapFolder()))
                throw new IllegalStateException("Resolved QQ path was not saved");
            SyncUtils sync = new SyncUtils();
            try {
                ImapNotesResult connected = sync.ConnectToRemote(account, test.getTargetContext(), 0xF00D);
                if (connected.returnCode != ImapNotesResult.ResultCodeSuccess) throw new IllegalStateException("Saved account reconnect failed");
                result.putString("configuredFolder", account.GetImapFolder());
            } finally { sync.DisconnectFromRemote(); }
            result.putBoolean("passed", true);
            result.putInt("checksPassed", 4);
            result.putString("result", "PASS: real mailbox UI login, immediate loading, saved resolved folder, saved-account reconnect");
        } catch (Exception error) {
            result.putBoolean("passed", false);
            result.putString("result", "FAIL: " + error.getClass().getSimpleName() + (error instanceof IllegalStateException ? ": " + error.getMessage() : ""));
        } finally {
            credentials.delete();
            if (setup != null) { AccountConfigurationActivity page = setup; test.runOnMainSync(page::finish); }
        }
        return result;
    }
}
