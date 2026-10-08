package de.niendo.ImapNotes3.Miscs;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Bundle;
import android.os.Build;
import android.os.IBinder;
import android.util.Base64;

import com.google.android.auth.IAuthManagerService;

import java.io.IOException;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Resolves consent through microG's own PendingIntent, without exporting its private UI. */
final class MicroGConsent {
    private static final String PACKAGE = "com.google.android.gms";
    private static final String RE_PACKAGE = "app.revanced.android.gms";
    static final String RE_ACCOUNT_TYPE = "app.revanced";
    // Official microG/NOGAPPS signing certificate, also used by its GitHub releases.
    private static final byte[] CERT_SHA256 = Base64.decode(
            "m9BnJ+YnlsATDrbas5tzFXRRWCy9E46GxGisw5XRQWU=", Base64.DEFAULT);
    // MorpheApp/MicroG-RE 7.1.1 official release certificate, independently verified.
    private static final byte[] RE_CERT_SHA256 = Base64.decode(
            "C2yVFa+xlfrFlgFpa6CnkHoLIXzPcgtDFIQnzPZDQ+c=", Base64.DEFAULT);

    private MicroGConsent() {}

    static boolean isOfficialMicroG(Context context) {
        return hasCertificate(context, PACKAGE, CERT_SHA256);
    }

    static String preferredAccountType(Context context) {
        if (isOfficialMicroG(context)) return GoogleAccountAuth.ACCOUNT_TYPE;
        if (hasCertificate(context, RE_PACKAGE, RE_CERT_SHA256) && ownsAccountType(context, RE_ACCOUNT_TYPE, RE_PACKAGE))
            return RE_ACCOUNT_TYPE;
        return GoogleAccountAuth.ACCOUNT_TYPE;
    }

    static String packageForAccountType(Context context, String accountType) {
        if (GoogleAccountAuth.ACCOUNT_TYPE.equals(accountType) && isOfficialMicroG(context)) return PACKAGE;
        if (RE_ACCOUNT_TYPE.equals(accountType) && hasCertificate(context, RE_PACKAGE, RE_CERT_SHA256)
                && ownsAccountType(context, accountType, RE_PACKAGE)) return RE_PACKAGE;
        return null;
    }

    private static boolean ownsAccountType(Context context, String type, String packageName) {
        for (android.accounts.AuthenticatorDescription authenticator : AccountManager.get(context).getAuthenticatorTypes())
            if (type.equals(authenticator.type)) return packageName.equals(authenticator.packageName);
        return false;
    }

    private static boolean hasCertificate(Context context, String packageName, byte[] digest) {
        PackageManager packages = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= 28) {
            return packages.hasSigningCertificate(packageName, digest, PackageManager.CERT_INPUT_SHA256);
        }
        try {
            @SuppressWarnings("deprecation")
            Signature[] signatures = packages.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures;
            if (signatures == null || signatures.length != 1) return false;
            return MessageDigest.isEqual(digest,
                    MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
        } catch (Exception e) {
            return false;
        }
    }

    /** Called off the main thread; the bound service verifies our package and Binder UID. */
    static PendingIntent request(Context context, Account account) throws Exception {
        String packageName = packageForAccountType(context, account.type);
        if (packageName == null) throw new IOException("Unsupported Google authenticator");
        ArrayBlockingQueue<IBinder> connection = new ArrayBlockingQueue<>(1);
        ServiceConnection service = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                connection.offer(binder);
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        Intent intent = new Intent().setComponent(new ComponentName(
                packageName, "com.google.android.gms.auth.GetToken"));
        if (!context.bindService(intent, service, Context.BIND_AUTO_CREATE)) {
            throw new IOException("Unable to connect to microG authentication service");
        }
        try {
            IBinder binder = connection.poll(10, TimeUnit.SECONDS);
            if (binder == null) throw new IOException("microG authentication service timed out");
            Bundle extras = new Bundle();
            extras.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, context.getPackageName());
            Bundle result = IAuthManagerService.Stub.asInterface(binder).getTokenWithAccount(
                    account,
                    GoogleAccountAuth.TOKEN_TYPE, extras);
            PendingIntent consent = result == null ? null : result.getParcelable("userRecoveryPendingIntent");
            if (consent == null || !packageName.equals(consent.getCreatorPackage())) {
                throw new IOException("microG did not return a consent request");
            }
            return consent;
        } finally {
            context.unbindService(service);
        }
    }
}
