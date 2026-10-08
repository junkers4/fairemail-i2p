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
import android.content.res.AssetManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// An embedded i2pd (PurpleI2P) for Postman mail (mail.i2p).
// The binary is shipped as libi2pd.so, so Android extracts it executable into the native library dir.
// Everything listens on loopback only: SMTP 7659, POP3 7660, HTTP proxy 4444, console 7070.
public class I2pRouter {
    static final String HOST = "127.0.0.1";
    static final int SMTP_PORT = 7659;
    static final int POP3_PORT = 7660;
    static final int PROXY_PORT = 4444;
    static final int CONSOLE_PORT = 7070;

    static final String DOMAIN = "mail.i2p";
    static final String DOMAIN_CLEARNET = "i2pmail.org";

    // I2P connections take long to set up: a stream to Postman can need a minute
    static final int TIMEOUT = 180; // seconds

    // How long to keep trying while a fresh router looks for Postman
    static final long POSTMAN_WAIT = 5 * 60 * 1000L; // milliseconds
    static final long POSTMAN_RETRY = 10 * 1000L; // milliseconds

    private static final String PREF_ENABLED = "i2p_router";
    private static final String DIR = "i2pd";
    private static final String BINARY = "libi2pd.so";
    private static final int ASSETS_VERSION = 1;

    // Postman by b32 address: a fresh address book does not know its names yet
    private static final String SMTP_DEST = "3nrunsrgeo6grhx6y6vsx7vibm5vabtockdbys3sqdmj6vha7k5q.b32.i2p";
    private static final String POP3_DEST = "i7vd76psp3oyocljiqkoyz7fpr4fy2xq2asclf7qih6k57aj5xrq.b32.i2p";

    private static Process process = null;

    // The router is built for Android 7 and later (getifaddrs)
    static boolean isSupported() {
        return (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N);
    }

    static boolean isI2p(String host, int port) {
        return HOST.equals(host) && (port == SMTP_PORT || port == POP3_PORT);
    }

    static boolean isEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(PREF_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        prefs.edit().putBoolean(PREF_ENABLED, enabled).apply();
        if (enabled)
            Helper.getParallelExecutor().submit(new Runnable() {
                @Override
                public void run() {
                    try {
                        start(context);
                    } catch (Throwable ex) {
                        Log.e(ex);
                    }
                }
            });
        else
            stop(context);
    }

    static void init(Context context) {
        if (isSupported() && isEnabled(context))
            setEnabled(context, true);
    }

    static synchronized void start(Context context) throws IOException {
        if (isAlive(process))
            return;

        File dir = new File(context.getFilesDir(), DIR);
        if (isOurs(dir)) {
            Log.i("I2P router already running");
            return;
        }

        prepare(context, dir);

        File bin = new File(context.getApplicationInfo().nativeLibraryDir, BINARY);
        if (!bin.exists())
            throw new IOException("I2P router missing: " + bin);

        // The shell records the router's pid, so a restarted app finds it again
        String cmd = "echo $$ > " + quote(new File(dir, "i2pd.pid")) +
                "; exec " + quote(bin) +
                " --datadir=" + quote(dir) +
                " --conf=" + quote(new File(dir, "i2pd.conf")) +
                " --tunconf=" + quote(new File(dir, "tunnels.conf")) +
                " --certsdir=" + quote(new File(dir, "certificates"));
        ProcessBuilder pb = new ProcessBuilder("/system/bin/sh", "-c", cmd);
        pb.directory(dir);
        pb.redirectErrorStream(true);
        process = pb.start();
        EntityLog.log(context, "I2P router started");

        final Process p = process;
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = br.readLine()) != null)
                        Log.i("i2pd: " + line);
                    EntityLog.log(context, "I2P router exited=" + p.waitFor());
                } catch (Throwable ex) {
                    Log.w(ex);
                }
            }
        }, "i2pd");
        reader.setDaemon(true);
        reader.start();
    }

    static synchronized void stop(Context context) {
        File dir = new File(context.getFilesDir(), DIR);
        Integer pid = getPid(dir);
        if (pid != null && isOurs(dir))
            android.os.Process.sendSignal(pid, 15); // SIGTERM: i2pd closes its tunnels
        if (process != null)
            process.destroy();
        process = null;
        EntityLog.log(context, "I2P router stopped");
    }

    // State from the router console: null = not running
    static Status getStatus() {
        try {
            URL url = new URL("http://" + HOST + ":" + CONSOLE_PORT + "/");
            HttpURLConnection c = (HttpURLConnection) url.openConnection(java.net.Proxy.NO_PROXY);
            c.setConnectTimeout(5 * 1000);
            c.setReadTimeout(5 * 1000);
            try (InputStream is = c.getInputStream()) {
                return Status.parse(Helper.readStream(is));
            } finally {
                c.disconnect();
            }
        } catch (IOException ex) {
            return null;
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

    static boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(HOST, port), 1000);
            return true;
        } catch (IOException ex) {
            return false;
        }
    }

    static class Status {
        String network;
        int routers;
        int tunnels;
        String uptime;

        boolean isReady() {
            // Behind NAT i2pd says Firewalled, which works the same for a client
            return (network != null &&
                    (network.startsWith("OK") || network.startsWith("Firewalled")) &&
                    tunnels > 0);
        }

        static Status parse(String html) {
            Status status = new Status();
            status.network = find("Network status:</b>\\s*([^<]+)", html);
            status.uptime = find("Uptime:</b>\\s*([^<]+)", html);
            status.routers = number(find("Routers:</b>\\s*(\\d+)", html));
            status.tunnels = number(find("Client Tunnels:</b>\\s*(\\d+)", html));
            return status;
        }

        private static String find(String regex, String html) {
            Matcher m = Pattern.compile(regex).matcher(html);
            return (m.find() ? m.group(1).trim() : null);
        }

        private static int number(String value) {
            return (value == null ? 0 : Integer.parseInt(value));
        }

        @Override
        public String toString() {
            return "network=" + network + " routers=" + routers + " tunnels=" + tunnels + " uptime=" + uptime;
        }
    }

    private static void prepare(Context context, File dir) throws IOException {
        if (!dir.exists() && !dir.mkdirs())
            throw new IOException("Cannot create " + dir);

        write(new File(dir, "i2pd.conf"), getConfig(dir));
        write(new File(dir, "tunnels.conf"), getTunnels());

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (prefs.getInt("i2p_assets", 0) != ASSETS_VERSION) {
            AssetManager am = context.getAssets();
            copyAssets(am, DIR + "/certificates", new File(dir, "certificates"));
            // Read once by i2pd, when its address book is still empty
            copyAsset(am, DIR + "/hosts.txt", new File(dir, "hosts.txt"));
            prefs.edit().putInt("i2p_assets", ASSETS_VERSION).apply();
        }
    }

    private static String getConfig(File dir) {
        return "log = file\n" +
                "logfile = " + new File(dir, "i2pd.log") + "\n" +
                "loglevel = warn\n" +
                "ipv4 = true\n" +
                "ipv6 = true\n" +
                "bandwidth = L\n" +
                // A phone on mobile data should not carry other people's traffic
                "notransit = true\n" +
                "\n" +
                "[http]\nenabled = true\naddress = " + HOST + "\nport = " + CONSOLE_PORT + "\n" +
                "\n" +
                "[httpproxy]\nenabled = true\naddress = " + HOST + "\nport = " + PROXY_PORT + "\n" +
                "\n" +
                "[socksproxy]\nenabled = false\n" +
                "[sam]\nenabled = false\n" +
                "[bob]\nenabled = false\n" +
                "[i2cp]\nenabled = false\n" +
                "[i2pcontrol]\nenabled = false\n" +
                "[upnp]\nenabled = false\n" +
                "\n" +
                "[addressbook]\nenabled = true\n" +
                "\n" +
                "[reseed]\nverify = true\n";
    }

    private static String getTunnels() {
        return "[postman-smtp]\n" +
                "type = client\n" +
                "address = " + HOST + "\n" +
                "port = " + SMTP_PORT + "\n" +
                "destination = " + SMTP_DEST + "\n" +
                "destinationport = 25\n" +
                "keys = fairemail-mail.dat\n" +
                "\n" +
                "[postman-pop3]\n" +
                "type = client\n" +
                "address = " + HOST + "\n" +
                "port = " + POP3_PORT + "\n" +
                "destination = " + POP3_DEST + "\n" +
                "destinationport = 110\n" +
                "keys = fairemail-mail.dat\n";
    }

    private static boolean isAlive(Process p) {
        if (p == null)
            return false;
        try {
            p.exitValue(); // Process.isAlive needs API 26
            return false;
        } catch (IllegalThreadStateException ignored) {
            return true;
        }
    }

    private static Integer getPid(File dir) {
        try {
            String pid = Helper.readText(new File(dir, "i2pd.pid")).trim();
            return (TextUtils.isEmpty(pid) ? null : Integer.parseInt(pid));
        } catch (Throwable ex) {
            return null;
        }
    }

    private static boolean isOurs(File dir) {
        Integer pid = getPid(dir);
        if (pid == null)
            return false;
        try {
            String cmdline = Helper.readText(new File("/proc/" + pid + "/cmdline"));
            return cmdline.contains(BINARY);
        } catch (Throwable ex) {
            return false;
        }
    }

    private static String quote(File file) {
        return "'" + file.getAbsolutePath().replace("'", "'\\''") + "'";
    }

    private static void write(File file, String text) throws IOException {
        try (OutputStream os = new FileOutputStream(file)) {
            os.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void copyAssets(AssetManager am, String path, File target) throws IOException {
        String[] names = am.list(path);
        if (names == null || names.length == 0) {
            copyAsset(am, path, target);
            return;
        }
        if (!target.exists() && !target.mkdirs())
            throw new IOException("Cannot create " + target);
        for (String name : names)
            copyAssets(am, path + "/" + name, new File(target, name));
    }

    private static void copyAsset(AssetManager am, String path, File target) throws IOException {
        try (InputStream is = am.open(path); OutputStream os = new FileOutputStream(target)) {
            Helper.copy(is, os);
        }
    }
}
