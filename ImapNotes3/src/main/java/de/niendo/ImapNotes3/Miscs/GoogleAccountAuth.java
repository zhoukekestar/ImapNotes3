package de.niendo.ImapNotes3.Miscs;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.os.Bundle;
import java.io.IOException;
import java.util.concurrent.TimeUnit;

/** Uses the system authenticator, including standard microG. No Google SDK is required. */
public final class GoogleAccountAuth {
    public static final String ACCOUNT_TYPE = "com.google";
    public static final String TOKEN_TYPE = "oauth2:https://mail.google.com/";
    private GoogleAccountAuth() {}

    public static String preferredAccountType(Context context) {
        return MicroGConsent.preferredAccountType(context);
    }

    public static boolean supportedAccountType(String type) {
        return ACCOUNT_TYPE.equals(type) || MicroGConsent.RE_ACCOUNT_TYPE.equals(type);
    }

    private static void checkProvider(Context context, String type) throws IOException {
        if (!supportedAccountType(type) || (MicroGConsent.RE_ACCOUNT_TYPE.equals(type)
                && MicroGConsent.packageForAccountType(context, type) == null))
            throw new IOException("Unsupported Google authenticator");
    }

    public static void authorize(Activity activity, String email, String accountType, int consentRequestCode, Runnable success,
                                 java.util.function.Consumer<String> failure) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        try {
            checkProvider(activity, accountType);
            AccountManager.get(activity).getAuthToken(new Account(email, accountType),
                TOKEN_TYPE, new Bundle(), false, future -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    try {
                        Bundle result = future.getResult();
                        String token = result.getString(AccountManager.KEY_AUTHTOKEN);
                        Intent consent = result.getParcelable(AccountManager.KEY_INTENT);
                        if (token != null && !token.isEmpty()) {
                            success.run(); // The authenticator owns token caching; never store it as a password.
                        } else if (consent != null) {
                            ActivityInfo target = consent.resolveActivityInfo(activity.getPackageManager(), 0);
                            if (target != null && target.exported) {
                                activity.startActivityForResult(consent, consentRequestCode);
                            } else if (MicroGConsent.packageForAccountType(activity, accountType) != null) {
                                requestMicroGConsent(activity, new Account(email, accountType), consentRequestCode, failure);
                            } else {
                                failure.accept(activity.getString(de.niendo.ImapNotes3.R.string.google_auth_required));
                            }
                        } else {
                            failure.accept(activity.getString(de.niendo.ImapNotes3.R.string.google_auth_required));
                        }
                    } catch (Exception e) {
                        failure.accept(errorMessage(activity, e));
                    }
                }, null);
        } catch (Exception e) {
            failure.accept(errorMessage(activity, e));
        }
    }

    private static void requestMicroGConsent(Activity activity, Account account, int requestCode,
                                             java.util.function.Consumer<String> failure) {
        new Thread(() -> {
            try {
                PendingIntent consent = MicroGConsent.request(activity.getApplicationContext(), account);
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed()) return;
                    try {
                        activity.startIntentSenderForResult(consent.getIntentSender(), requestCode,
                                null, 0, 0, 0);
                    } catch (Exception e) {
                        failure.accept(errorMessage(activity, e));
                    }
                });
            } catch (Exception e) {
                activity.runOnUiThread(() -> {
                    if (!activity.isFinishing() && !activity.isDestroyed()) {
                        failure.accept(errorMessage(activity, e));
                    }
                });
            }
        }, "microG-consent").start();
    }

    /** Must be called off the main thread. Consent is handled in account setup. */
    public static String token(Context context, String email, String accountType) throws IOException {
        try {
            checkProvider(context, accountType);
            Bundle result = AccountManager.get(context).getAuthToken(
                    new Account(email, accountType), TOKEN_TYPE, new Bundle(), true,
                    null, null).getResult(60, TimeUnit.SECONDS);
            String token = result.getString(AccountManager.KEY_AUTHTOKEN);
            if (token == null || token.isEmpty()) throw new IOException("Google authorization required; reopen account settings");
            return token;
        } catch (Exception e) {
            throw new IOException(errorMessage(context, e), e);
        }
    }

    private static String errorMessage(Context context, Exception error) {
        // Log only the exception class, never the Bundle, token or authenticator response.
        android.util.Log.w("IN_GoogleAuth", "Authorization failed: " + error.getClass().getSimpleName());
        switch (GoogleAuthError.classify(error)) {
            case REGISTRATION: return context.getString(de.niendo.ImapNotes3.R.string.google_auth_not_registered);
            case NETWORK: return context.getString(de.niendo.ImapNotes3.R.string.google_auth_network_error);
            case UNSUPPORTED_PROVIDER: return context.getString(de.niendo.ImapNotes3.R.string.google_auth_unsupported_provider);
            default: return context.getString(de.niendo.ImapNotes3.R.string.google_auth_required);
        }
    }

    public static void invalidate(Context context, String token, String accountType) {
        AccountManager.get(context).invalidateAuthToken(accountType, token);
    }
}
