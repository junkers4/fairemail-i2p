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

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.PowerManager;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

// Synchronize only while the device is unlocked: locking pauses synchronization like the schedule does,
// unlocking resumes it at once. On by default: with every account paused the sync service, and with it
// its notification, quits.
public class ScreenLock {
    static final String PREF = "sync_unlocked";

    static void init(Context context) {
        Context app = context.getApplicationContext();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_USER_PRESENT);

        try {
            ContextCompat.registerReceiver(app,
                    new BroadcastReceiver() {
                        @Override
                        public void onReceive(Context context, Intent intent) {
                            if (isEnabled(context))
                                onChanged(context, intent.getAction());
                        }
                    },
                    filter,
                    ContextCompat.RECEIVER_NOT_EXPORTED);
        } catch (Throwable ex) {
            Log.e(ex);
        }
    }

    static boolean isEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean(PREF, true);
    }

    // Screen off, or the lock screen showing
    static boolean isPaused(Context context) {
        if (!isEnabled(context))
            return false;
        PowerManager pm = Helper.getSystemService(context, PowerManager.class);
        KeyguardManager km = Helper.getSystemService(context, KeyguardManager.class);
        return ((pm != null && !pm.isInteractive()) || (km != null && km.isKeyguardLocked()));
    }

    static void onChanged(Context context, String reason) {
        boolean paused = isPaused(context);
        EntityLog.log(context, EntityLog.Type.Scheduling, "Screen lock " + reason + " paused=" + paused);

        // Like a schedule boundary: disconnects or reconnects, and polls at once after unlocking
        ServiceSynchronize.reschedule(context);
    }
}
