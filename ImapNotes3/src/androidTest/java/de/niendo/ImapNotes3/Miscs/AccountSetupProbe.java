package de.niendo.ImapNotes3.Miscs;

import android.accounts.AccountManager;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;
import de.niendo.ImapNotes3.AccountConfigurationActivity;
import de.niendo.ImapNotes3.Data.Security;
import de.niendo.ImapNotes3.R;
import java.net.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Exercises account setup with loopback connections; never uses real mailbox credentials. */
final class AccountSetupProbe {
    static Bundle run(Instrumentation test) {
        Bundle result = new Bundle();
        int passed = 0;
        AccountConfigurationActivity activity = null;
        try (BlockedConnections connections = new BlockedConnections()) {
            android.accessibilityservice.AccessibilityServiceInfo info = test.getUiAutomation().getServiceInfo();
            info.flags |= android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            test.getUiAutomation().setServiceInfo(info);
            AccountManager accounts = AccountManager.get(test.getTargetContext());
            android.accounts.Account[] savedAccounts = accounts.getAccountsByType(Utilities.PackageName);
            int accountCount = savedAccounts.length;
            String googleEmail = null;
            for (android.accounts.Account saved : savedAccounts) {
                if ("google".equals(accounts.getUserData(saved, de.niendo.ImapNotes3.Data.ConfigurationFieldNames.Authentication))) {
                    googleEmail = accounts.getUserData(saved, de.niendo.ImapNotes3.Data.ConfigurationFieldNames.UserName);
                    break;
                }
            }
            if (googleEmail == null) throw new IllegalStateException("Requires a previously authorized Google account");
            final String authorizedGoogleEmail = googleEmail;
            activity = (AccountConfigurationActivity) test.startActivitySync(new Intent(test.getTargetContext(), AccountConfigurationActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            final AccountConfigurationActivity setup = activity;
            test.runOnMainSync(() -> {
                text(setup, R.id.usernameEdit).setText("fixture@QQ.COM");
                require(text(setup, R.id.serverEdit).getText().toString().equals("imap.qq.com"));
                require(text(setup, R.id.portnumEdit).getText().toString().equals("993"));
                require(((Spinner) setup.findViewById(R.id.securitySpinner)).getSelectedItemPosition() == Security.SSL_TLS.ordinal());
                require(text(setup, R.id.folderEdit).getText().toString().equals("Notes"));
                require(setup.findViewById(R.id.ViewExtendedAccountSettings).getVisibility() == View.GONE);
                require(setup.findViewById(R.id.authorizationHelp).getVisibility() == View.VISIBLE);
            });
            passed++;
            screenshot(test, "setup-qq.png");
            test.runOnMainSync(() -> {
                text(setup, R.id.usernameEdit).setText("fixture@163.com");
                require(text(setup, R.id.serverEdit).getText().toString().equals("imap.163.com"));
                require(text(setup, R.id.accountnameEdit).getText().toString().equals("fixture@163.com"));
            });
            passed++;
            screenshot(test, "setup-163.png");
            test.runOnMainSync(() -> {
                text(setup, R.id.serverEdit).setText("imap.custom.example");
                text(setup, R.id.usernameEdit).setText("fixture@qq.com");
                require(text(setup, R.id.serverEdit).getText().toString().equals("imap.custom.example"));
                text(setup, R.id.serverEdit).setText("127.0.0.1");
                setup.findViewById(R.id.BtnExpandAccountSettings).performClick();
                ((Spinner) setup.findViewById(R.id.securitySpinner)).setSelection(Security.None.ordinal());
            });
            passed++;
            test.waitForIdleSync();
            test.runOnMainSync(() -> {
                text(setup, R.id.portnumEdit).setText(Integer.toString(connections.server.getLocalPort()));
                text(setup, R.id.passwordEdit).setText("fixture-password");
                setup.findViewById(R.id.accountLoginButton).performClick();
                require(busy(setup));
                require(!setup.findViewById(R.id.accountLoginButton).isEnabled());
                require(!setup.findViewById(R.id.usernameEdit).isEnabled());
                require(setup.findViewById(R.id.accountCancelButton).isEnabled());
                setup.findViewById(R.id.accountLoginButton).performClick();
            });
            require(connections.accepted[0].await(25, TimeUnit.SECONDS));
            require(connections.count.get() == 1);
            passed++;
            screenshot(test, "setup-loading.png");
            connections.release[0].countDown();
            waitUntil(test, () -> !busy(setup) && setup.findViewById(R.id.accountLoginButton).isEnabled());
            require(connections.count.get() == 1);
            dismissError(test);
            passed++;
            test.runOnMainSync(() -> {
                require(text(setup, R.id.portnumEdit).getText().toString().equals(Integer.toString(connections.server.getLocalPort())));
                setup.findViewById(R.id.accountLoginButton).performClick();
                require(busy(setup));
            });
            require(connections.accepted[1].await(25, TimeUnit.SECONDS));
            require(connections.count.get() == 2);
            connections.release[1].countDown();
            waitUntil(test, () -> !busy(setup));
            dismissError(test);
            passed++;
            test.runOnMainSync(() -> setup.findViewById(R.id.accountLoginButton).performClick());
            require(connections.accepted[2].await(25, TimeUnit.SECONDS));
            test.runOnMainSync(() -> setup.findViewById(R.id.accountCancelButton).performClick());
            connections.release[2].countDown();
            test.waitForIdleSync();
            require(setup.isFinishing() || setup.isDestroyed());
            require(accountCount == AccountManager.get(test.getTargetContext()).getAccountsByType(Utilities.PackageName).length);
            passed++;
            final AccountConfigurationActivity googleSetup = (AccountConfigurationActivity) test.startActivitySync(
                    new Intent(test.getTargetContext(), AccountConfigurationActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            activity = googleSetup;
            test.runOnMainSync(() -> {
                text(googleSetup, R.id.usernameEdit).setText(authorizedGoogleEmail);
                android.widget.LinearLayout controls = googleSetup.findViewById(R.id.googleAuthControls);
                ((CheckBox) controls.getChildAt(0)).setChecked(true);
                googleSetup.findViewById(R.id.accountLoginButton).performClick();
                require(busy(googleSetup));
                require(text(googleSetup, R.id.loginProgressText).getText().toString().equals(googleSetup.getString(R.string.login_google)));
                googleSetup.findViewById(R.id.accountCancelButton).performClick();
            });
            test.waitForIdleSync();
            require(googleSetup.isFinishing() || googleSetup.isDestroyed());
            passed++;
            result.putBoolean("passed", true);
            result.putString("result", "PASS: QQ/163 defaults, provider switch, custom settings, loading, duplicate-submit guard, error recovery, retry, cancel, Google loading and cancel");
        } catch (Throwable error) {
            result.putBoolean("passed", false);
            result.putString("result", "FAIL: " + error.getClass().getSimpleName());
            for (StackTraceElement frame : error.getStackTrace()) {
                if (frame.getClassName().equals(AccountSetupProbe.class.getName())) {
                    result.putString("failureLocation", frame.getMethodName() + ":" + frame.getLineNumber());
                    if (!frame.getMethodName().equals("require")) break;
                }
            }
        } finally {
            if (activity != null) {
                AccountConfigurationActivity setup = activity;
                test.runOnMainSync(setup::finish);
            }
        }
        result.putInt("checksPassed", passed);
        return result;
    }

    private static TextView text(AccountConfigurationActivity setup, int id) { return setup.findViewById(id); }
    private static boolean busy(AccountConfigurationActivity setup) { return setup.findViewById(R.id.loginProgressPanel).getVisibility() == View.VISIBLE; }
    private static void require(boolean value) { if (!value) throw new AssertionError("Account setup check failed"); }
    private static void waitUntil(Instrumentation test, BooleanSupplier condition) {
        long end = SystemClock.elapsedRealtime() + 45000;
        while (SystemClock.elapsedRealtime() < end) {
            AtomicBoolean done = new AtomicBoolean();
            test.runOnMainSync(() -> done.set(condition.getAsBoolean()));
            if (done.get()) { test.waitForIdleSync(); return; }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Account setup timed out");
    }
    private static void dismissError(Instrumentation test) {
        long end = SystemClock.elapsedRealtime() + 10000;
        while (SystemClock.elapsedRealtime() < end) {
            AccessibilityNodeInfo root = test.getUiAutomation().getRootInActiveWindow();
            if (root != null) {
                java.util.List<AccessibilityNodeInfo> buttons = root.findAccessibilityNodeInfosByViewId("android:id/button1");
                if (!buttons.isEmpty() && buttons.get(0).performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    test.waitForIdleSync(); return;
                }
            }
            SystemClock.sleep(100);
        }
        throw new AssertionError("Error dialog not available");
    }

    private static void screenshot(Instrumentation test, String name) throws Exception {
        test.waitForIdleSync();
        android.graphics.Bitmap bitmap = test.getUiAutomation().takeScreenshot();
        require(bitmap != null);
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(new java.io.File(test.getTargetContext().getCacheDir(), name))) {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out);
        } finally { bitmap.recycle(); }
    }

    private static final class BlockedConnections implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 3, InetAddress.getByName("127.0.0.1"));
        final AtomicInteger count = new AtomicInteger();
        final CountDownLatch[] accepted = {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        final CountDownLatch[] release = {new CountDownLatch(1), new CountDownLatch(1), new CountDownLatch(1)};
        final Thread worker;
        BlockedConnections() throws Exception {
            worker = new Thread(() -> {
                for (int i = 0; i < 3; i++) {
                    try (Socket socket = server.accept()) {
                        count.incrementAndGet();
                        socket.setSoTimeout(30000);
                        java.io.BufferedReader input = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.US_ASCII));
                        java.io.PrintWriter output = new java.io.PrintWriter(new java.io.OutputStreamWriter(socket.getOutputStream(), java.nio.charset.StandardCharsets.US_ASCII), true);
                        output.print("* OK [CAPABILITY IMAP4rev1] Fixture ready\r\n"); output.flush();
                        for (String line; (line = input.readLine()) != null;) {
                            String[] fields = line.split(" ", 3);
                            if (fields[1].equals("CAPABILITY")) {
                                output.print("* CAPABILITY IMAP4rev1\r\n" + fields[0] + " OK Capability complete\r\n"); output.flush();
                            } else if (fields[1].equals("LOGIN")) {
                                accepted[i].countDown(); release[i].await(45, TimeUnit.SECONDS);
                                output.print(fields[0] + " NO [AUTHENTICATIONFAILED] Fixture login rejected\r\n"); output.flush();
                                break;
                            } else {
                                output.print(fields[0] + " BAD Unexpected fixture command\r\n"); output.flush();
                            }
                        }
                    } catch (Exception error) { return; }
                }
            }, "account-setup-fixture");
            worker.start();
        }
        @Override public void close() throws Exception {
            for (CountDownLatch latch : release) latch.countDown();
            server.close(); worker.join(1000);
        }
    }
}
