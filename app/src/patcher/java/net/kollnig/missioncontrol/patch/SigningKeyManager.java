/*
 * TrackerControl is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * TrackerControl is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with TrackerControl. If not, see <http://www.gnu.org/licenses/>.
 */

package net.kollnig.missioncontrol.patch;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import androidx.annotation.NonNull;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Owns the RSA signing key used to sign patched APKs. The key is generated and
 * stored in the AndroidKeyStore (non-exportable, persistent across the app's
 * lifetime), and a self-signed certificate is produced automatically. Using
 * AndroidKeyStore avoids bundling an additional crypto provider.
 */
final class SigningKeyManager {

    private static final String ALIAS = "tc-apk-patcher-signing-key";
    private static final String KEYSTORE = "AndroidKeyStore";

    private final KeyStore keyStore;

    SigningKeyManager() throws Exception {
        keyStore = KeyStore.getInstance(KEYSTORE);
        keyStore.load(null);
        if (!keyStore.containsAlias(ALIAS)) {
            generate();
        }
    }

    private void generate() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_RSA, KEYSTORE);
        kpg.initialize(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setKeySize(2048)
                .setDigests(KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA512)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setCertificateSubject(new javax.security.auth.x500.X500Principal(
                        "CN=TrackerControl Patcher"))
                .setCertificateNotBefore(new java.util.Date())
                .setCertificateNotAfter(new java.util.Date(
                        System.currentTimeMillis() + 100L * 365 * 24 * 3600 * 1000L))
                .build());
        kpg.generateKeyPair();
    }

    @NonNull
    Signer signer() throws Exception {
        PrivateKey priv = (PrivateKey) keyStore.getKey(ALIAS, null);
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(ALIAS);
        return new Signer(priv, cert);
    }

    static final class Signer {
        final PrivateKey privateKey;
        final X509Certificate certificate;

        Signer(PrivateKey privateKey, X509Certificate certificate) {
            this.privateKey = privateKey;
            this.certificate = certificate;
        }
    }

    @SuppressWarnings("unused")
    private void touch(Context ctx) {
    }
}
