package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/


import android.content.Context;
import android.content.SharedPreferences;
import android.security.KeyChain;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509TrustManager;

// Postman mail (mail.i2p) over an I2P router of one's own (a VPS, a Raspberry Pi), so the phone runs none:
// see i2p-server/ for its setup. Its Postman tunnels are reached over TLS: SMTP 7669, POP3 7670,
// and it lets in only phones holding a client certificate it made.
public class I2pRouter {
    static final int REMOTE_SMTP_PORT = 7669;
    static final int REMOTE_POP3_PORT = 7670;

    // Where the router once built into the app listened: such accounts are moved to one's own router
    static final String LEGACY_HOST = "127.0.0.1";
    static final int LEGACY_SMTP_PORT = 7659;
    static final int LEGACY_POP3_PORT = 7660;

    static final String DOMAIN = "mail.i2p";

    // I2P connections take long to set up: a stream to Postman can need a minute
    static final int TIMEOUT = 180; // seconds

    // How long to keep trying while a fresh router looks for Postman
    static final long POSTMAN_WAIT = 5 * 60 * 1000L; // milliseconds
    static final long POSTMAN_RETRY = 10 * 1000L; // milliseconds

    private static final String PREF_REMOTE = "i2p_remote";
    private static final String PREF_REMOTE_CERTIFICATE = "i2p_remote_certificate";

    static boolean isI2p(Context context, String host, int port) {
        String remote = getRemote(context);
        return (remote != null && remote.equalsIgnoreCase(host) &&
                (port == REMOTE_SMTP_PORT || port == REMOTE_POP3_PORT));
    }

    static boolean isLegacy(String host, int port) {
        return (LEGACY_HOST.equals(host) && (port == LEGACY_SMTP_PORT || port == LEGACY_POP3_PORT));
    }

    // The host name of one's own router
    static String getRemote(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String remote = prefs.getString(PREF_REMOTE, null);
        return (TextUtils.isEmpty(remote) ? null : remote);
    }

    // The client certificate one's own router lets in, an alias in the Android key chain
    static String getRemoteCertificateAlias(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getString(PREF_REMOTE_CERTIFICATE, null);
    }

    static void setRemote(Context context, String host, String certificate) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit()
                .putString(PREF_REMOTE, host)
                .putString(PREF_REMOTE_CERTIFICATE, certificate)
                .remove("i2p_router") // the router once built into the app
                .apply();
    }

    // The certificate of one's own router, read without trusting it: the user compares
    // its fingerprint with the one the server setup printed, after which it is pinned.
    // A server that lets in only its own client certificate is shown the selected one.
    static X509Certificate getRemoteCertificate(Context context, String host, int port, String certificate) throws Exception {
        TrustManager[] any = new TrustManager[]{new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {
            }

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        KeyManager[] keys = null;
        if (certificate != null) {
            final PrivateKey key = KeyChain.getPrivateKey(context, certificate);
            final X509Certificate[] chain = KeyChain.getCertificateChain(context, certificate);
            if (key == null || chain == null)
                throw new IllegalArgumentException(context.getString(R.string.title_no_key_selected));
            keys = new KeyManager[]{new X509ExtendedKeyManager() {
                @Override
                public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                    return certificate;
                }

                @Override
                public X509Certificate[] getCertificateChain(String alias) {
                    return chain;
                }

                @Override
                public PrivateKey getPrivateKey(String alias) {
                    return key;
                }

                @Override
                public String[] getClientAliases(String keyType, Principal[] issuers) {
                    return new String[]{certificate};
                }

                @Override
                public String[] getServerAliases(String keyType, Principal[] issuers) {
                    return null;
                }

                @Override
                public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                    return null;
                }
            }};
        }

        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(keys, any, null);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 20 * 1000);
            socket.setSoTimeout(20 * 1000);
            try (SSLSocket s = (SSLSocket) ssl.getSocketFactory().createSocket(socket, host, port, false)) {
                s.startHandshake();
                return (X509Certificate) s.getSession().getPeerCertificates()[0];
            }
        }
    }

    // The tunnel closed the stream before the server said anything
    static boolean isUnreachable(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof java.io.EOFException)
                return true;
            String msg = t.getMessage();
            if (msg != null && msg.contains("EOF on socket"))
                return true;
            if (t.getCause() == t)
                break;
        }
        return false;
    }
}
