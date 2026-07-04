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
 *
 * Smali patch definitions ported from apk-mitm
 * (https://github.com/shroudedcode/apk-mitm) and the declarative subset of
 * httptoolkit/frida-interception-and-unpinning. Both disable certificate
 * pinning by replacing method bodies of X509TrustManager / HostnameVerifier /
 * OkHttp CertificatePinner / common pinning libraries with no-op /
 * always-accept stubs. Only classes that live in the target app's own dex
 * files are patchable; framework classes (com.android.okhttp.*,
 * com.android.org.conscrypt.*, javax.net.ssl.*) cannot be patched this way
 * and rely on the network_security_config resource step + a trusted CA.
 */

package net.kollnig.missioncontrol.patch;

import androidx.annotation.NonNull;

import org.jf.dexlib2.Opcode;
import org.jf.dexlib2.iface.reference.Reference;
import org.jf.dexlib2.iface.reference.TypeReference;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction10x;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11n;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction11x;
import org.jf.dexlib2.immutable.instruction.ImmutableInstruction22c;
import org.jf.dexlib2.immutable.reference.ImmutableTypeReference;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Declarative method-body replacements, mirroring apk-mitm's {@code SmaliPatch}
 * and the no-op/return-true subset of Frida's unpinning script. Selectors
 * target either an interface (any implementor found in the app's dex) or a
 * concrete class, and list methods whose bodies should be replaced with a
 * fixed instruction sequence.
 */
final class SmaliPatches {

    enum Replacement {
        RETURN_VOID,
        RETURN_TRUE,
        RETURN_FALSE,
        RETURN_NULL,
        RETURN_EMPTY_CERT_ARRAY;

        List<ImmutableInstruction> instructions(@NonNull String returnType) {
            switch (this) {
                case RETURN_VOID:
                    return Collections.singletonList(
                            new ImmutableInstruction10x(Opcode.RETURN_VOID));
                case RETURN_TRUE:
                    return Arrays.asList(
                            new ImmutableInstruction11n(Opcode.CONST_4, 0, 1),
                            new ImmutableInstruction11x(Opcode.RETURN, 0));
                case RETURN_FALSE:
                    return Arrays.asList(
                            new ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                            new ImmutableInstruction11x(Opcode.RETURN, 0));
                case RETURN_NULL:
                    return Arrays.asList(
                            new ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                            new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
                case RETURN_EMPTY_CERT_ARRAY: {
                    Reference arrayType = new ImmutableTypeReference(
                            "[Ljava/security/cert/X509Certificate;");
                    return Arrays.asList(
                            new ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                            new ImmutableInstruction22c(Opcode.NEW_ARRAY, 0, 0, arrayType),
                            new ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0));
                }
                default:
                    throw new IllegalStateException();
            }
        }

        int registerCount(int original) {
            switch (this) {
                case RETURN_VOID:
                    return Math.max(original, 0);
                case RETURN_TRUE:
                case RETURN_FALSE:
                case RETURN_NULL:
                case RETURN_EMPTY_CERT_ARRAY:
                    return Math.max(original, 1);
                default:
                    throw new IllegalStateException();
            }
        }
    }

    static final class MethodPatch {
        final String name;
        final List<String> paramTypes; // null = match any
        final String returnType;       // null = match any
        final Replacement replacement;

        MethodPatch(String name, List<String> paramTypes,
                    String returnType, Replacement replacement) {
            this.name = name;
            this.paramTypes = paramTypes;
            this.returnType = returnType;
            this.replacement = replacement;
        }
    }

    static final class Selector {
        final boolean isInterface;
        final String type; // vm type, e.g. "Ljavax/net/ssl/X509TrustManager;"
        final List<MethodPatch> methods;

        Selector(boolean isInterface, String type, List<MethodPatch> methods) {
            this.isInterface = isInterface;
            this.type = type;
            this.methods = methods;
        }
    }

    @NonNull
    static List<Selector> all() {
        return Arrays.asList(
                // --- X509TrustManager interface: any implementor
                new Selector(true, "Ljavax/net/ssl/X509TrustManager;", Arrays.asList(
                        new MethodPatch("checkClientTrusted",
                                Arrays.asList("[Ljava/security/cert/X509Certificate;", "Ljava/lang/String;"),
                                "V", Replacement.RETURN_VOID),
                        new MethodPatch("checkServerTrusted",
                                Arrays.asList("[Ljava/security/cert/X509Certificate;", "Ljava/lang/String;"),
                                "V", Replacement.RETURN_VOID),
                        new MethodPatch("getAcceptedIssuers",
                                Collections.emptyList(),
                                "[Ljava/security/cert/X509Certificate;",
                                Replacement.RETURN_EMPTY_CERT_ARRAY))),
                // --- HostnameVerifier interface: any implementor
                new Selector(true, "Ljavax/net/ssl/HostnameVerifier;", Arrays.asList(
                        new MethodPatch("verify",
                                Arrays.asList("Ljava/lang/String;", "Ljavax/net/ssl/SSLSession;"),
                                "Z", Replacement.RETURN_TRUE))),
                // --- OkHttp 2.x (SquareUp, bundled in app dex)
                new Selector(false, "Lcom/squareup/okhttp/CertificatePinner;", Arrays.asList(
                        new MethodPatch("check",
                                Arrays.asList("Ljava/lang/String;", "Ljava/util/List;"),
                                "V", Replacement.RETURN_VOID),
                        new MethodPatch("check",
                                Arrays.asList("Ljava/lang/String;", "Ljava/security/cert/Certificate;"),
                                "V", Replacement.RETURN_VOID))),
                // --- OkHttp 3.x / 4.x
                new Selector(false, "Lokhttp3/CertificatePinner;", Arrays.asList(
                        new MethodPatch("check",
                                Arrays.asList("Ljava/lang/String;", "Ljava/util/List;"),
                                "V", Replacement.RETURN_VOID),
                        new MethodPatch("check",
                                Arrays.asList("Ljava/lang/String;", "Ljava/security/cert/Certificate;"),
                                "V", Replacement.RETURN_VOID),
                        new MethodPatch("check",
                                Arrays.asList("Ljava/lang/String;", "[Ljava/security/cert/Certificate;"),
                                "V", Replacement.RETURN_VOID),
                        new MethodPatch("check$okhttp",
                                Arrays.asList("Ljava/lang/String;", "Lkotlin/jvm/functions/Function0;"),
                                "V", Replacement.RETURN_VOID))),
                // --- Trustkit
                new Selector(false, "Lcom/datatheorem/android/trustkit/pinning/PinningTrustManager;", Arrays.asList(
                        new MethodPatch("checkServerTrusted",
                                Arrays.asList("[Ljava/security/cert/X509Certificate;", "Ljava/lang/String;"),
                                "V", Replacement.RETURN_VOID))),
                // --- Appcelerator
                new Selector(false, "Lappcelerator/https/PinningTrustManager;", Arrays.asList(
                        new MethodPatch("checkServerTrusted",
                                Arrays.asList("[Ljava/security/cert/X509Certificate;", "Ljava/lang/String;"),
                                "V", Replacement.RETURN_VOID))),
                // --- IBM WorkLight
                new Selector(false, "Lcom/worklight/wlclient/certificatepinning/HostNameVerifierWithCertificatePinning;", Arrays.asList(
                        new MethodPatch("verify",
                                null, "Z", Replacement.RETURN_TRUE))),
                new Selector(false, "Lcom/worklight/androidgap/plugin/WLCertificatePinningPlugin;", Arrays.asList(
                        new MethodPatch("execute",
                                null, "Z", Replacement.RETURN_TRUE))),
                // --- CWAC-Netsecurity
                new Selector(false, "Lcom/commonsware/cwac/netsecurity/conscrypt/CertPinManager;", Arrays.asList(
                        new MethodPatch("isChainValid",
                                null, "Z", Replacement.RETURN_TRUE))),
                // --- Netty
                new Selector(false, "Lio/netty/handler/ssl/util/FingerprintTrustManagerFactory;", Arrays.asList(
                        new MethodPatch("checkTrusted",
                                null, "V", Replacement.RETURN_VOID))),
                // --- PhoneGap sslCertificateChecker
                new Selector(false, "Lnl/xservices/plugins/sslCertificateChecker;", Arrays.asList(
                        new MethodPatch("execute",
                                Arrays.asList("Ljava/lang/String;", "Lorg/json/JSONArray;",
                                        "Lorg/apache/cordova/CallbackContext;"),
                                "Z", Replacement.RETURN_TRUE))),
                // --- Appmattus Cert Transparency
                new Selector(false, "Lcom/appmattus/certificatetransparency/internal/verifier/CertificateTransparencyHostnameVerifier;", Arrays.asList(
                        new MethodPatch("verify",
                                null, "Z", Replacement.RETURN_TRUE))));
    }

    @NonNull
    static TypeReference certArrayType() {
        return new ImmutableTypeReference("[Ljava/security/cert/X509Certificate;");
    }

    private SmaliPatches() {
    }
}
