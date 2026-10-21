package dev.youbal.callrec.lib;

import android.content.Context;
import android.os.Build;

import io.github.muntashirakon.adb.AbsAdbConnectionManager;

import org.conscrypt.Conscrypt;

import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.util.concurrent.TimeUnit;

/** This phone's own connection to its wireless debugging, using the identity from {@link AdbKeys}. */
final class AdbManager extends AbsAdbConnectionManager {

    private static AdbManager instance;

    private final PrivateKey privateKey;
    private final Certificate certificate;

    static synchronized AdbManager get(Context context) throws Exception {
        if (instance == null) {
            // Wireless debugging's TLS handshake needs key export that only the bundled Conscrypt exposes.
            if (Security.getProvider("Conscrypt") == null) {
                Security.insertProviderAt(Conscrypt.newProvider(), 1);
            }
            instance = new AdbManager(context.getApplicationContext());
        }
        return instance;
    }

    /** Drops the saved identity and the open connection, so the next use starts from a fresh pairing. */
    static synchronized void forget(Context context) {
        if (instance != null) {
            try { instance.close(); } catch (Exception ignored) { /* Already closed. */ }
            instance = null;
        }
        AdbKeys.forget(context);
    }

    private AdbManager(Context context) throws Exception {
        setApi(Build.VERSION.SDK_INT);
        setTimeout(10, TimeUnit.SECONDS);
        AdbKeys keys = AdbKeys.load(context);
        privateKey = keys.privateKey;
        certificate = keys.certificate;
    }

    @Override
    protected PrivateKey getPrivateKey() {
        return privateKey;
    }

    @Override
    protected Certificate getCertificate() {
        return certificate;
    }

    @Override
    protected String getDeviceName() {
        return "callrec";
    }
}
