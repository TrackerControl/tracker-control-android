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

import androidx.annotation.NonNull;

import com.reandroid.apk.ApkModule;
import com.reandroid.archive.ByteInputSource;
import com.reandroid.arsc.chunk.PackageBlock;
import com.reandroid.arsc.chunk.TableBlock;
import com.reandroid.arsc.chunk.xml.AndroidManifestBlock;
import com.reandroid.arsc.chunk.xml.ResXmlAttribute;
import com.reandroid.arsc.chunk.xml.ResXmlDocument;
import com.reandroid.arsc.chunk.xml.ResXmlElement;
import com.reandroid.arsc.chunk.xml.ResXmlPullSerializer;
import com.reandroid.arsc.value.Entry;
import com.reandroid.arsc.value.ValueType;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Edits an APK's binary Android resources so the platform's default
 * {@code TrustManagerImpl} trusts user-installed certificate authorities (and
 * overrides declared pin sets). This is the resource-level half of defeating
 * certificate pinning — it complements {@link DexPatcher} (which handles
 * code-level pinning) by injecting a {@code network_security_config.xml}
 * that trusts user CAs with {@code overridePins="true"}, and makes the
 * application debuggable so the debug trust-anchors apply.
 *
 * <p>This is the static (compile-time) equivalent of httptoolkit's Frida
 * {@code TrustedCertificateIndex} injection script: instead of hooking
 * Conscrypt at runtime, we patch the app's resource config once so the
 * platform itself accepts the MITM CA on every subsequent launch.</p>
 *
 * <p>Uses ARSCLib (Apache-2.0), which replaces aapt/aapt2 and runs on-device.</p>
 */
final class ResourcePatcher {

    /** Framework attribute resource id for {@code android:networkSecurityConfig}. */
    private static final int ATTR_NETWORK_SECURITY_CONFIG = 0x01010271;

    /** Path of the config file inside the APK. */
    private static final String CONFIG_PATH = "res/xml/network_security_config.xml";

    private static final String CONFIG_XML =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
                    + "<network-security-config>\n"
                    + "  <base-config cleartextTrafficPermitted=\"true\">\n"
                    + "    <trust-anchors>\n"
                    + "      <certificates src=\"system\" />\n"
                    + "      <certificates src=\"user\" overridePins=\"true\" />\n"
                    + "    </trust-anchors>\n"
                    + "  </base-config>\n"
                    + "  <debug-overrides>\n"
                    + "    <trust-anchors>\n"
                    + "      <certificates src=\"system\" />\n"
                    + "      <certificates src=\"user\" overridePins=\"true\" />\n"
                    + "    </trust-anchors>\n"
                    + "  </debug-overrides>\n"
                    + "</network-security-config>\n";

    interface Progress {
        void onMessage(@NonNull String message);
    }

    /**
     * Add a user-CA-trusting {@code network_security_config.xml}, point the
     * manifest at it, and mark the application debuggable. Returns true if any
     * resource-level change was made.
     */
    static boolean patchResources(@NonNull ApkModule apk, @NonNull Progress progress)
            throws IOException {
        boolean changed = false;

        TableBlock table = apk.getTableBlock(true);
        if (table == null) {
            progress.onMessage("No resources.arsc; skipping resource patch.");
            return false;
        }

        PackageBlock pkg = firstAppPackage(table);
        if (pkg == null) {
            progress.onMessage("No app package in table; skipping resource patch.");
            return false;
        }

        // 1. Create / fetch the res/xml entry and assign a resource id.
        Entry entry = pkg.getOrCreate("", "xml", "network_security_config");
        int resId = entry.getResourceId();

        // 2. Compile the source XML to a binary Android XML resource.
        byte[] binaryXml = compileBinaryXml(CONFIG_XML, pkg);
        apk.add(new ByteInputSource(binaryXml, CONFIG_PATH));
        progress.onMessage("Added " + CONFIG_PATH);
        changed = true;

        // 3. Point <application android:networkSecurityConfig="@xml/..."> at it.
        AndroidManifestBlock manifest = apk.getAndroidManifestBlock();
        if (manifest != null) {
            ResXmlElement app = manifest.getOrCreateApplicationElement();
            ResXmlAttribute attr = app.getOrCreateAndroidAttribute(
                    "networkSecurityConfig", ATTR_NETWORK_SECURITY_CONFIG);
            attr.setValueType(ValueType.REFERENCE);
            attr.setData(resId);

            manifest.setDebuggable(true);
            progress.onMessage("Manifest: networkSecurityConfig=@xml/"
                    + "network_security_config, debuggable=true");
        }

        apk.refreshTable();
        apk.refreshManifest();
        return changed;
    }

    private static byte[] compileBinaryXml(@NonNull String xml, @NonNull PackageBlock pkg)
            throws IOException {
        ResXmlPullSerializer serializer = new ResXmlPullSerializer();
        serializer.setCurrentPackage(pkg);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        serializer.setOutput(out, "utf-8");
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new ByteArrayInputStream(
                    xml.getBytes(StandardCharsets.UTF_8)), "utf-8");
            copyXml(parser, serializer);
        } catch (XmlPullParserException e) {
            throw new IOException("Failed to parse network_security_config XML", e);
        }
        serializer.endDocument();
        ResXmlDocument doc = serializer.getResultDocument();
        // ResXmlDocument.writeBytes only writes to a File; round-trip via temp.
        File tmp = File.createTempFile("nsc", ".xml");
        try {
            doc.writeBytes(tmp);
            return readAll(tmp);
        } finally {
            tmp.delete();
        }
    }

    private static byte[] readAll(@NonNull File f) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (java.io.InputStream is = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    /** Stream a XmlPullParser into a XmlSerializer, preserving structure. */
    private static void copyXml(@NonNull XmlPullParser parser,
                                @NonNull org.xmlpull.v1.XmlSerializer serializer)
            throws XmlPullParserException, IOException {
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            switch (event) {
                case XmlPullParser.START_DOCUMENT:
                    serializer.startDocument("utf-8", null);
                    break;
                case XmlPullParser.START_TAG: {
                    // Resolve namespace/prefix; network-security-config uses no ns.
                    String ns = parser.getNamespace();
                    String name = parser.getName();
                    serializer.startTag(ns, name);
                    int n = parser.getAttributeCount();
                    for (int i = 0; i < n; i++) {
                        String ans = parser.getAttributeNamespace(i);
                        String an = parser.getAttributeName(i);
                        String av = parser.getAttributeValue(i);
                        serializer.attribute(ans, an, av == null ? "" : av);
                    }
                    break;
                }
                case XmlPullParser.END_TAG:
                    serializer.endTag(parser.getNamespace(), parser.getName());
                    break;
                case XmlPullParser.TEXT: {
                    String t = parser.getText();
                    if (t != null) serializer.text(t);
                    break;
                }
                default:
                    break;
            }
            event = parser.next();
        }
    }

    private static PackageBlock firstAppPackage(@NonNull TableBlock table) {
        // The app package is the non-framework (id 0x7f*) one.
        for (PackageBlock pkg : table) {
            int id = pkg.getId();
            if (id != 0 && (id & 0xff000000) == 0x7f000000) return pkg;
        }
        // Fallback: first package overall.
        for (PackageBlock pkg : table) return pkg;
        return null;
    }

    private ResourcePatcher() {
    }
}
