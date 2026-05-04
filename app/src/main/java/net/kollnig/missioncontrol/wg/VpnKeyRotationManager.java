package net.kollnig.missioncontrol.wg;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;
import android.util.Log;

import androidx.preference.PreferenceManager;

import net.kollnig.missioncontrol.wgbridge.Wgbridge;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.faircode.netguard.ServiceSinkhole;

public class VpnKeyRotationManager {
    private static final String TAG = "TrackerControl.KeyRotation";
    private static final long ROTATION_INTERVAL_MS = 30L * 24L * 60L * 60L * 1000L;
    private static final long RETRY_INTERVAL_MS = 60L * 60L * 1000L;
    private static final long HANDSHAKE_TIMEOUT_MS = 15_000L;
    private static final int MULLVAD_PUBKEY_RETRY_LIMIT = 3;

    private static final String PROVIDER_MULLVAD = "mullvad";
    private static final String PROVIDER_IVPN = "ivpn";

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor();
    private static boolean running;

    interface MullvadApi {
        String findDeviceIdForPubkey(String accountNumber, String publicKey) throws Exception;

        boolean deviceHasPubkey(String accountNumber, String deviceId, String publicKey)
                throws Exception;

        void rotateDevicePubkey(String accountNumber, String deviceId, String publicKey)
                throws Exception;
    }

    interface IvpnApi {
        WgProfileManager.IvpnSession rotateSessionKey(WgProfileManager.IvpnSession session,
                                                      String newPrivateKey,
                                                      String newPublicKey,
                                                      String connectedPublicKey)
                throws Exception;
    }

    interface KeyApi {
        String generatePrivateKey() throws Exception;

        String publicKey(String privateKey) throws Exception;
    }

    interface RuntimeHooks {
        long now();

        void reload(String reason, Context context);

        void sleep(long millis) throws InterruptedException;

        Long latestHandshakeMillisOrNull();
    }

    static class Dependencies {
        final MullvadApi mullvad;
        final IvpnApi ivpn;
        final KeyApi keys;
        final RuntimeHooks runtime;

        Dependencies(MullvadApi mullvad, IvpnApi ivpn, KeyApi keys, RuntimeHooks runtime) {
            this.mullvad = mullvad;
            this.ivpn = ivpn;
            this.keys = keys;
            this.runtime = runtime;
        }
    }

    private static final Dependencies DEFAULT_DEPENDENCIES = new Dependencies(
            new RealMullvadApi(),
            new RealIvpnApi(),
            new RealKeyApi(),
            new RealRuntimeHooks());

    public interface Callback {
        void onComplete(String summary);
    }

    public static void maybeRotateDue(Context context) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> run(app, false, null));
    }

    public static void rotateAllForDebug(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        EXECUTOR.execute(() -> run(app, true, callback));
    }

    static String rotateProviderForTest(Context context, String provider, boolean force,
                                        Dependencies dependencies) {
        return rotateProvider(context, provider, force, dependencies);
    }

    private static void run(Context context, boolean force, Callback callback) {
        synchronized (LOCK) {
            if (running) {
                if (callback != null)
                    callback.onComplete("VPN key rotation already running");
                return;
            }
            running = true;
        }

        try {
            List<String> results = new ArrayList<>();
            results.add(rotateProvider(context, PROVIDER_MULLVAD, force, DEFAULT_DEPENDENCIES));
            results.add(rotateProvider(context, PROVIDER_IVPN, force, DEFAULT_DEPENDENCIES));
            if (callback != null)
                callback.onComplete(TextUtils.join("; ", results));
        } finally {
            synchronized (LOCK) {
                running = false;
            }
        }
    }

    private static String rotateProvider(Context context, String provider, boolean force,
                                         Dependencies dependencies) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        long now = dependencies.runtime.now();
        String label = label(provider);

        if (!isProviderConfigured(context, provider))
            return label + " skipped: not configured";
        long rotatedAt = prefs.getLong(key(provider, "key_rotated_at"), 0L);
        if (!force && rotatedAt <= 0L && !hasPendingKey(prefs, provider)) {
            prefs.edit().putLong(key(provider, "key_rotated_at"), now).apply();
            return label + " skipped: fresh";
        }
        if (!force && rotatedAt + ROTATION_INTERVAL_MS > now)
            return label + " skipped: fresh";
        if (!force && prefs.getLong(key(provider, "key_rotation_last_attempt"), 0L) + RETRY_INTERVAL_MS > now)
            return label + " skipped: retry later";

        prefs.edit().putLong(key(provider, "key_rotation_last_attempt"), now).apply();

        try {
            String result;
            if (PROVIDER_MULLVAD.equals(provider))
                result = rotateMullvad(context, dependencies);
            else
                result = rotateIvpn(context, dependencies);

            if (result.endsWith("rotated") || result.endsWith("pending committed"))
                prefs.edit().putLong(key(provider, "key_rotated_at"),
                        dependencies.runtime.now()).apply();
            return result;
        } catch (RollbackException ex) {
            Log.w(TAG, ex.getMessage());
            return ex.getMessage();
        } catch (Throwable ex) {
            Log.w(TAG, label + " key rotation failed: " + ex.getMessage());
            return label + " failed: " + safeMessage(ex);
        }
    }

    private static boolean isProviderConfigured(Context context, String provider) {
        WgProfileManager manager = new WgProfileManager(context);
        if (PROVIDER_MULLVAD.equals(provider)) {
            String account = manager.getLastMullvadAccount();
            return !TextUtils.isEmpty(account) &&
                    !TextUtils.isEmpty(manager.getProviderConfig(PROVIDER_MULLVAD, account));
        }

        String account = manager.getLastIvpnAccount();
        return !TextUtils.isEmpty(account) &&
                manager.getIvpnSession(account) != null &&
                !TextUtils.isEmpty(manager.getProviderConfig(PROVIDER_IVPN, account));
    }

    private static String rotateMullvad(Context context, Dependencies dependencies) throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        WgProfileManager manager = new WgProfileManager(context);
        String account = manager.getLastMullvadAccount();
        String config = manager.getProviderConfig(PROVIDER_MULLVAD, account);
        if (TextUtils.isEmpty(account) || TextUtils.isEmpty(config))
            return "Mullvad skipped: not configured";

        WgConfig parsed = WgConfigParser.INSTANCE.parse(config);
        String currentPrivate = parsed.getPrivateKey();
        String currentPublic = dependencies.keys.publicKey(currentPrivate);

        String cachedDeviceId = manager.getMullvadDeviceId();
        if (!TextUtils.isEmpty(cachedDeviceId) &&
                resolveMullvadPending(context, manager, dependencies, account,
                        cachedDeviceId, currentPrivate, currentPublic))
            return "Mullvad pending committed";

        String deviceId = resolveMullvadDeviceId(manager, dependencies.mullvad, account,
                currentPublic);
        if (TextUtils.isEmpty(deviceId))
            return "Mullvad skipped: no device";

        if (!deviceId.equals(cachedDeviceId) &&
                resolveMullvadPending(context, manager, dependencies, account, deviceId,
                        currentPrivate, currentPublic))
            return "Mullvad pending committed";

        MullvadProfileGenerator.ApiRejectedException lastRejected = null;
        for (int attempt = 0; attempt < MULLVAD_PUBKEY_RETRY_LIMIT; attempt++) {
            String newPrivate = dependencies.keys.generatePrivateKey();
            String newPublic = dependencies.keys.publicKey(newPrivate);
            try {
                dependencies.mullvad.rotateDevicePubkey(account, deviceId, newPublic);
            } catch (MullvadProfileGenerator.ApiRejectedException ex) {
                if (!ex.isPublicKeyInUse())
                    throw ex;
                if (dependencies.mullvad.deviceHasPubkey(account, deviceId, newPublic)) {
                    commitProviderKey(context, manager, dependencies, PROVIDER_MULLVAD,
                            account, newPrivate, null, currentPrivate, currentPublic,
                            newPublic, deviceId);
                    return "Mullvad rotated";
                }
                lastRejected = ex;
                continue;
            } catch (IOException ex) {
                storePending(prefs, PROVIDER_MULLVAD, newPrivate, newPublic);
                throw ex;
            }
            try {
                if (!dependencies.mullvad.deviceHasPubkey(account, deviceId, newPublic))
                    throw new IOException("Mullvad key verification failed");
            } catch (IOException ex) {
                storePending(prefs, PROVIDER_MULLVAD, newPrivate, newPublic);
                throw ex;
            }

            commitProviderKey(context, manager, dependencies, PROVIDER_MULLVAD, account,
                    newPrivate, null, currentPrivate, currentPublic, newPublic, deviceId);
            return "Mullvad rotated";
        }

        throw lastRejected == null ?
                new IOException("Mullvad could not generate an unused public key") :
                lastRejected;
    }

    private static String rotateIvpn(Context context, Dependencies dependencies) throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        WgProfileManager manager = new WgProfileManager(context);
        String account = manager.getLastIvpnAccount();
        WgProfileManager.IvpnSession session = manager.getIvpnSession(account);
        String config = manager.getProviderConfig(PROVIDER_IVPN, account);
        if (TextUtils.isEmpty(account) || session == null || TextUtils.isEmpty(config))
            return "IVPN skipped: no session";

        WgConfig parsed = WgConfigParser.INSTANCE.parse(config);
        String currentPrivate = parsed.getPrivateKey();
        String currentPublic = dependencies.keys.publicKey(currentPrivate);

        String pendingPrivate = prefs.getString(key(PROVIDER_IVPN, "pending_privkey"), "");
        String pendingPublic = prefs.getString(key(PROVIDER_IVPN, "pending_pubkey"), "");
        if (!TextUtils.isEmpty(pendingPrivate) && !TextUtils.isEmpty(pendingPublic)) {
            WgProfileManager.IvpnSession next;
            try {
                next = dependencies.ivpn.rotateSessionKey(session, pendingPrivate,
                        pendingPublic, currentPublic);
            } catch (IOException ex) {
                if (pendingPublic.equals(currentPublic))
                    throw ex;
                next = dependencies.ivpn.rotateSessionKey(session, pendingPrivate,
                        pendingPublic, pendingPublic);
            }
            manager.saveIvpnSession(next);
            commitProviderKey(context, manager, dependencies, PROVIDER_IVPN, account,
                    pendingPrivate, addressWithCidr(next.address), currentPrivate,
                    currentPublic, pendingPublic, "");
            clearPending(prefs, PROVIDER_IVPN);
            return "IVPN pending committed";
        }

        String newPrivate = dependencies.keys.generatePrivateKey();
        String newPublic = dependencies.keys.publicKey(newPrivate);
        WgProfileManager.IvpnSession next;
        try {
            next = dependencies.ivpn.rotateSessionKey(session, newPrivate, newPublic,
                    currentPublic);
        } catch (IvpnProfileGenerator.ApiRejectedException ex) {
            throw ex;
        } catch (IOException ex) {
            storePending(prefs, PROVIDER_IVPN, newPrivate, newPublic);
            throw ex;
        }

        manager.saveIvpnSession(next);
        commitProviderKey(context, manager, dependencies, PROVIDER_IVPN, account,
                newPrivate, addressWithCidr(next.address), currentPrivate, currentPublic,
                newPublic, "");
        return "IVPN rotated";
    }

    private static boolean resolveMullvadPending(Context context, WgProfileManager manager,
                                                 Dependencies dependencies, String account,
                                                 String deviceId, String currentPrivate,
                                                 String currentPublic) throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String pendingPrivate = prefs.getString(key(PROVIDER_MULLVAD, "pending_privkey"), "");
        String pendingPublic = prefs.getString(key(PROVIDER_MULLVAD, "pending_pubkey"), "");
        if (TextUtils.isEmpty(pendingPrivate) || TextUtils.isEmpty(pendingPublic))
            return false;

        if (dependencies.mullvad.deviceHasPubkey(account, deviceId, pendingPublic)) {
            commitProviderKey(context, manager, dependencies, PROVIDER_MULLVAD, account,
                    pendingPrivate, null, currentPrivate, currentPublic, pendingPublic,
                    deviceId);
            clearPending(prefs, PROVIDER_MULLVAD);
            return true;
        }
        if (dependencies.mullvad.deviceHasPubkey(account, deviceId, currentPublic)) {
            clearPending(prefs, PROVIDER_MULLVAD);
            return false;
        }
        throw new IOException("Mullvad pending key could not be verified");
    }

    private static String resolveMullvadDeviceId(WgProfileManager manager, MullvadApi api,
                                                 String account, String currentPublic) throws Exception {
        String deviceId = manager.getMullvadDeviceId();
        if (!TextUtils.isEmpty(deviceId) &&
                api.deviceHasPubkey(account, deviceId, currentPublic))
            return deviceId;
        deviceId = api.findDeviceIdForPubkey(account, currentPublic);
        manager.saveMullvadDeviceId(deviceId);
        return deviceId;
    }

    private static void commitProviderKey(Context context, WgProfileManager manager,
                                          Dependencies dependencies, String provider,
                                          String account, String newPrivate, String newAddress,
                                          String previousPrivate, String previousPublic,
                                          String newPublic, String mullvadDeviceId)
            throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putString(key(provider, "previous_privkey"), previousPrivate)
                .putString(key(provider, "previous_address"),
                        currentAddress(manager.getProviderConfig(provider, account)))
                .apply();

        long before = dependencies.runtime.now();
        boolean activeChanged = manager.rewriteProviderInterface(provider, account, newPrivate, newAddress);
        if (!activeChanged || !prefs.getBoolean("wg_enabled", false)) {
            clearPrevious(prefs, provider);
            clearPending(prefs, provider);
            return;
        }

        dependencies.runtime.reload("vpn provider key rotated", context);
        dependencies.runtime.sleep(HANDSHAKE_TIMEOUT_MS);
        Long latest = dependencies.runtime.latestHandshakeMillisOrNull();
        if (latest != null && latest >= before) {
            clearPrevious(prefs, provider);
            clearPending(prefs, provider);
            return;
        }

        rollbackProvider(context, manager, dependencies, provider, account, previousPrivate,
                previousPublic, newPublic, mullvadDeviceId);
    }

    private static void rollbackProvider(Context context, WgProfileManager manager,
                                         Dependencies dependencies, String provider,
                                         String account, String previousPrivate,
                                         String previousPublic, String connectedPublic,
                                         String mullvadDeviceId) throws Exception {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (PROVIDER_MULLVAD.equals(provider)) {
            dependencies.mullvad.rotateDevicePubkey(account, mullvadDeviceId, previousPublic);
            manager.rewriteProviderInterface(provider, account, previousPrivate, null);
        } else {
            WgProfileManager.IvpnSession session = manager.getIvpnSession(account);
            WgProfileManager.IvpnSession rollback =
                    dependencies.ivpn.rotateSessionKey(session, previousPrivate,
                            previousPublic, connectedPublic);
            manager.saveIvpnSession(rollback);
            manager.rewriteProviderInterface(provider, account, previousPrivate,
                    addressWithCidr(rollback.address));
        }
        dependencies.runtime.reload("vpn provider key rotation rollback", context);
        clearPrevious(prefs, provider);
        throw new RollbackException(label(provider) + " rolled back: missing handshake");
    }

    private static String currentAddress(String config) {
        try {
            WgConfig parsed = WgConfigParser.INSTANCE.parse(config);
            return TextUtils.join(", ", parsed.getAddress());
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static void storePending(SharedPreferences prefs, String provider,
                                     String privateKey, String publicKey) {
        prefs.edit()
                .putString(key(provider, "pending_privkey"), privateKey)
                .putString(key(provider, "pending_pubkey"), publicKey)
                .apply();
    }

    private static boolean hasPendingKey(SharedPreferences prefs, String provider) {
        return !TextUtils.isEmpty(prefs.getString(key(provider, "pending_privkey"), "")) &&
                !TextUtils.isEmpty(prefs.getString(key(provider, "pending_pubkey"), ""));
    }

    private static void clearPending(SharedPreferences prefs, String provider) {
        prefs.edit()
                .remove(key(provider, "pending_privkey"))
                .remove(key(provider, "pending_pubkey"))
                .apply();
    }

    private static void clearPrevious(SharedPreferences prefs, String provider) {
        prefs.edit()
                .remove(key(provider, "previous_privkey"))
                .remove(key(provider, "previous_address"))
                .apply();
    }

    private static String addressWithCidr(String address) {
        String trimmed = address == null ? "" : address.trim();
        if (TextUtils.isEmpty(trimmed) || trimmed.contains("/"))
            return trimmed;
        return trimmed + "/32";
    }

    private static String key(String provider, String suffix) {
        return provider + "_" + suffix;
    }

    private static String label(String provider) {
        return PROVIDER_IVPN.equals(provider) ? "IVPN" : "Mullvad";
    }

    private static String safeMessage(Throwable ex) {
        String message = ex.getMessage();
        return TextUtils.isEmpty(message) ? ex.getClass().getSimpleName() : message;
    }

    private static class RollbackException extends IOException {
        RollbackException(String message) {
            super(message);
        }
    }

    private static class RealMullvadApi implements MullvadApi {
        private final MullvadProfileGenerator api = new MullvadProfileGenerator();

        @Override
        public String findDeviceIdForPubkey(String accountNumber, String publicKey)
                throws Exception {
            return api.findDeviceIdForPubkey(accountNumber, publicKey);
        }

        @Override
        public boolean deviceHasPubkey(String accountNumber, String deviceId, String publicKey)
                throws Exception {
            return api.deviceHasPubkey(accountNumber, deviceId, publicKey);
        }

        @Override
        public void rotateDevicePubkey(String accountNumber, String deviceId, String publicKey)
                throws Exception {
            api.rotateDevicePubkey(accountNumber, deviceId, publicKey);
        }
    }

    private static class RealIvpnApi implements IvpnApi {
        private final IvpnProfileGenerator api = new IvpnProfileGenerator();

        @Override
        public WgProfileManager.IvpnSession rotateSessionKey(WgProfileManager.IvpnSession session,
                                                             String newPrivateKey,
                                                             String newPublicKey,
                                                             String connectedPublicKey)
                throws Exception {
            return api.rotateSessionKey(session, newPrivateKey, newPublicKey,
                    connectedPublicKey);
        }
    }

    private static class RealKeyApi implements KeyApi {
        @Override
        public String generatePrivateKey() throws Exception {
            return Wgbridge.generatePrivateKey();
        }

        @Override
        public String publicKey(String privateKey) throws Exception {
            return Wgbridge.publicKey(privateKey);
        }
    }

    private static class RealRuntimeHooks implements RuntimeHooks {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }

        @Override
        public void reload(String reason, Context context) {
            ServiceSinkhole.reload(reason, context, false);
        }

        @Override
        public void sleep(long millis) throws InterruptedException {
            Thread.sleep(millis);
        }

        @Override
        public Long latestHandshakeMillisOrNull() {
            return WgEgress.INSTANCE.latestHandshakeMillisOrNull();
        }
    }
}
