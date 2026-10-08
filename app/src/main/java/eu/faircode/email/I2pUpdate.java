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

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.preference.PreferenceManager;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HttpsURLConnection;

// FairEmail I2P updates itself from the releases of its own repository: the official update check
// only trusts M66B's signature. Releases are tagged i2p-<version>-b<build>, the build number comes
// from the release workflow. When M66B releases and the fork has not followed for a day, the merge
// probably needs a hand, so say so.
public class I2pUpdate extends Worker {
    private static final long CHECK_INTERVAL = 4; // hours
    private static final long UPSTREAM_GRACE = 24 * 3600 * 1000L; // milliseconds
    private static final int TIMEOUT = 20 * 1000; // milliseconds
    private static final Pattern TAG_BUILD = Pattern.compile("-b(\\d+)$");

    private static final int NOTIFICATION_UPDATED = NotificationHelper.NOTIFICATION_UPDATE + 1;
    private static final int NOTIFICATION_UPSTREAM = NotificationHelper.NOTIFICATION_UPDATE + 2;

    public I2pUpdate(@NonNull Context context, @NonNull WorkerParameters args) {
        super(context, args);
    }

    @NonNull
    @Override
    public Result doWork() {
        try {
            check(getApplicationContext());
        } catch (Throwable ex) {
            Log.w(ex);
        }
        return Result.success();
    }

    static boolean enabled() {
        return (BuildConfig.I2P_BUILD > 0 && !BuildConfig.DEBUG);
    }

    static void init(Context context) {
        if (!enabled())
            return;

        try {
            notifyUpdated(context);

            Constraints constraints = new Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build();
            PeriodicWorkRequest workRequest =
                    new PeriodicWorkRequest.Builder(I2pUpdate.class, CHECK_INTERVAL, TimeUnit.HOURS)
                            .setConstraints(constraints)
                            .build();
            WorkManager.getInstance(context)
                    .enqueueUniquePeriodicWork(getName(), ExistingPeriodicWorkPolicy.KEEP, workRequest);
        } catch (Throwable ex) {
            Log.w(ex);
        }
    }

    // Check for updates from the menu: returns false for builds which update the official way
    static boolean checkNow(Context context) {
        if (!enabled())
            return false;

        OneTimeWorkRequest workRequest = new OneTimeWorkRequest.Builder(I2pUpdate.class).build();
        WorkManager.getInstance(context)
                .enqueueUniqueWork(getName() + ":now", ExistingWorkPolicy.REPLACE, workRequest);
        return true;
    }

    private static String getName() {
        return I2pUpdate.class.getSimpleName();
    }

    private static void check(Context context) throws Throwable {
        JSONObject release = getJson(context, BuildConfig.I2P_RELEASES_API);
        String tag = release.getString("tag_name");
        Matcher m = TAG_BUILD.matcher(tag);
        Log.i("I2P update latest=" + tag + " build=" + BuildConfig.I2P_BUILD);
        if (m.find() && Integer.parseInt(m.group(1)) > BuildConfig.I2P_BUILD) {
            JSONArray assets = release.getJSONArray("assets");
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (asset.optString("name").endsWith(".apk")) {
                    install(context, tag, asset.getString("browser_download_url"));
                    return;
                }
            }
        }

        JSONObject upstream = getJson(context, BuildConfig.I2P_UPSTREAM_API);
        String version = upstream.getString("tag_name");
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        Date published = iso.parse(upstream.getString("published_at"));

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        if (Double.parseDouble(version) > Double.parseDouble(BuildConfig.VERSION_NAME) &&
                published.getTime() + UPSTREAM_GRACE < new Date().getTime() &&
                !version.equals(prefs.getString("i2p_upstream_notified", null))) {
            prefs.edit().putString("i2p_upstream_notified", version).apply();
            showNotification(context, NOTIFICATION_UPSTREAM,
                    context.getString(R.string.title_i2p_upstream_pending, version),
                    viewReleases(context));
        }
    }

    private static void install(Context context, String tag, String url) throws IOException {
        File apk = new File(context.getCacheDir(), "i2p-update.apk");
        HttpsURLConnection connection = open(context, url);
        try (InputStream is = connection.getInputStream();
             OutputStream os = new FileOutputStream(apk)) {
            Helper.copy(is, os);
        } finally {
            connection.disconnect();
        }

        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);

        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream is = new FileInputStream(apk);
                 OutputStream os = session.openWrite("base.apk", 0, apk.length())) {
                Helper.copy(is, os);
                session.fsync(os);
            }

            Intent result = new Intent(context, Receiver.class).putExtra("tag", tag);
            PendingIntent pi = PendingIntent.getBroadcast(context, id, result,
                    PendingIntent.FLAG_UPDATE_CURRENT |
                            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0));
            Log.i("I2P update installing " + tag);
            session.commit(pi.getIntentSender());
        } finally {
            apk.delete();
        }
    }

    // The new version starts with the same preferences: a higher build is a finished update
    private static void notifyUpdated(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        int last = prefs.getInt("i2p_build", 0);
        if (last == BuildConfig.I2P_BUILD)
            return;
        prefs.edit().putInt("i2p_build", BuildConfig.I2P_BUILD).apply();
        if (last > 0 && last < BuildConfig.I2P_BUILD)
            showNotification(context, NOTIFICATION_UPDATED,
                    context.getString(R.string.title_i2p_updated,
                            BuildConfig.VERSION_NAME + " (b" + BuildConfig.I2P_BUILD + ")"),
                    viewReleases(context));
    }

    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            String tag = intent.getStringExtra("tag");
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            Log.i("I2P update " + tag + " status=" + status);

            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                // Not the installer of record (yet): Android asks, from a notification
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm == null)
                    return;
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                showNotification(context, NOTIFICATION_UPDATED,
                        context.getString(R.string.title_i2p_update_confirm, tag),
                        PendingIntent.getActivity(context, 0, confirm,
                                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
            } else if (status != PackageInstaller.STATUS_SUCCESS)
                showNotification(context, NOTIFICATION_UPDATED,
                        context.getString(R.string.title_i2p_update_failed, tag,
                                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)),
                        viewReleases(context));
        }
    }

    private static PendingIntent viewReleases(Context context) {
        Intent view = new Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.I2P_RELEASES_URI))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return PendingIntent.getActivity(context, 0, view,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void showNotification(Context context, int id, String title, PendingIntent pi) {
        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, "update")
                        .setSmallIcon(R.drawable.baseline_get_app_white_24)
                        .setContentTitle(title)
                        .setContentIntent(pi)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setCategory(NotificationCompat.CATEGORY_STATUS)
                        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE);
        NotificationManager nm = Helper.getSystemService(context, NotificationManager.class);
        if (NotificationHelper.areNotificationsEnabled(nm))
            nm.notify(id, builder.build());
    }

    private static JSONObject getJson(Context context, String url) throws Throwable {
        HttpsURLConnection connection = open(context, url);
        try {
            int status = connection.getResponseCode();
            if (status != HttpsURLConnection.HTTP_OK)
                throw new IOException("HTTP " + status + " " + url);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(connection.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null)
                    sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            connection.disconnect();
        }
    }

    private static HttpsURLConnection open(Context context, String url) throws IOException {
        HttpsURLConnection connection = (HttpsURLConnection) new java.net.URL(url).openConnection();
        connection.setReadTimeout(TIMEOUT);
        connection.setConnectTimeout(TIMEOUT);
        ConnectionHelper.setUserAgent(context, connection);
        connection.connect();
        return connection;
    }
}
