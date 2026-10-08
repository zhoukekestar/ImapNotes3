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
    // Official microG/NOGAPPS signing certificate, also used by its GitHub releases.
    private static final byte[] CERT_SHA256 = Base64.decode(
            "m9BnJ+YnlsATDrbas5tzFXRRWCy9E46GxGisw5XRQWU=", Base64.DEFAULT);

    private MicroGConsent() {}

    static boolean isOfficialMicroG(Context context) {
        PackageManager packages = context.getPackageManager();
        if (Build.VERSION.SDK_INT >= 28) {
            return packages.hasSigningCertificate(PACKAGE, CERT_SHA256, PackageManager.CERT_INPUT_SHA256);
        }
        try {
            @SuppressWarnings("deprecation")
            Signature[] signatures = packages.getPackageInfo(PACKAGE, PackageManager.GET_SIGNATURES).signatures;
            if (signatures == null || signatures.length != 1) return false;
            return MessageDigest.isEqual(CERT_SHA256,
                    MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray()));
        } catch (Exception e) {
            return false;
        }
    }

    /** Called off the main thread; the bound service verifies our package and Binder UID. */
    static PendingIntent request(Context context, String email) throws Exception {
        if (!isOfficialMicroG(context)) throw new IOException("Unsupported Google authenticator");
        ArrayBlockingQueue<IBinder> connection = new ArrayBlockingQueue<>(1);
        ServiceConnection service = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder binder) {
                connection.offer(binder);
            }
            @Override public void onServiceDisconnected(ComponentName name) {}
        };
        Intent intent = new Intent().setComponent(new ComponentName(
                PACKAGE, "com.google.android.gms.auth.GetToken"));
        if (!context.bindService(intent, service, Context.BIND_AUTO_CREATE)) {
            throw new IOException("Unable to connect to microG authentication service");
        }
        try {
            IBinder binder = connection.poll(10, TimeUnit.SECONDS);
            if (binder == null) throw new IOException("microG authentication service timed out");
            Bundle extras = new Bundle();
            extras.putString(AccountManager.KEY_ANDROID_PACKAGE_NAME, context.getPackageName());
            Bundle result = IAuthManagerService.Stub.asInterface(binder).getTokenWithAccount(
                    new Account(email, GoogleAccountAuth.ACCOUNT_TYPE),
                    GoogleAccountAuth.TOKEN_TYPE, extras);
            PendingIntent consent = result == null ? null : result.getParcelable("userRecoveryPendingIntent");
            if (consent == null || !PACKAGE.equals(consent.getCreatorPackage())) {
                throw new IOException("microG did not return a consent request");
            }
            return consent;
        } finally {
            context.unbindService(service);
        }
    }
}
