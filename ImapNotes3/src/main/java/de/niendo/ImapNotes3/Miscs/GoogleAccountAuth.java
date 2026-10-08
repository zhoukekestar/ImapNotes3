package de.niendo.ImapNotes3.Miscs;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Uses the system authenticator, including standard microG. No Google SDK is required. */
public final class GoogleAccountAuth {
    public static final String ACCOUNT_TYPE = "com.google";
    public static final String TOKEN_TYPE = "oauth2:https://mail.google.com/";
    private GoogleAccountAuth() {}

    public static void authorize(Activity activity, String email, Runnable success,
                                 java.util.function.Consumer<String> failure) {
        try {
        AccountManager.get(activity).getAuthToken(new Account(email, ACCOUNT_TYPE),
                TOKEN_TYPE, new Bundle(), activity, future -> {
                    try {
                        Bundle result = future.getResult();
                        String token = result.getString(AccountManager.KEY_AUTHTOKEN);
                        if (token == null || token.isEmpty()) {
                            failure.accept(activity.getString(de.niendo.ImapNotes3.R.string.google_auth_required));
                        } else {
                            success.run(); // The authenticator owns token caching; never store it as a password.
                        }
                    } catch (Exception e) {
                        failure.accept(activity.getString(de.niendo.ImapNotes3.R.string.google_auth_required));
                    }
                }, null);
        } catch (RuntimeException e) {
            failure.accept(activity.getString(de.niendo.ImapNotes3.R.string.google_auth_required));
        }
    }

    /** Must be called off the main thread. Consent is handled in account setup. */
    public static String token(Context context, String email) throws IOException {
        try {
            Bundle result = AccountManager.get(context).getAuthToken(
                    new Account(email, ACCOUNT_TYPE), TOKEN_TYPE, new Bundle(), true,
                    null, null).getResult(60, TimeUnit.SECONDS);
            String token = result.getString(AccountManager.KEY_AUTHTOKEN);
            if (token == null || token.isEmpty()) throw new IOException("Google authorization required; reopen account settings");
            return token;
        } catch (Exception e) {
            throw new IOException("Google authorization required; reopen account settings");
        }
    }

    public static void invalidate(Context context, String token) {
        AccountManager.get(context).invalidateAuthToken(ACCOUNT_TYPE, token);
    }
}
