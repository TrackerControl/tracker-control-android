package eu.faircode.netguard;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;

/** Observes VPN DNS validation without polling or changing network routing. */
final class PrivateDnsMonitor {
    private final ConnectivityManager connectivity;
    private final Runnable onChanged;
    private ConnectivityManager.NetworkCallback callback;

    PrivateDnsMonitor(ConnectivityManager connectivity, Runnable onChanged) {
        this.connectivity = connectivity;
        this.onChanged = onChanged;
    }

    synchronized void setEnabled(boolean enabled) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || connectivity == null)
            return;
        if (!enabled) {
            if (callback != null) {
                ConnectivityManager.NetworkCallback previous = callback;
                callback = null;
                connectivity.unregisterNetworkCallback(previous);
            }
        } else if (callback == null) {
            callback = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                    changed(this);
                }

                @Override
                public void onLost(Network network) {
                    changed(this);
                }
            };
            try {
                connectivity.registerNetworkCallback(new NetworkRequest.Builder()
                        // The default request excludes VPNs, and TC's active
                        // network is the underlying network because it excludes itself.
                        .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                        .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                        .build(), callback);
            } catch (RuntimeException ex) {
                callback = null;
                throw ex;
            }
        }
    }

    private synchronized void changed(ConnectivityManager.NetworkCallback source) {
        // An event already queued before unregistering must not revive a warning.
        if (callback == source)
            onChanged.run();
    }
}
