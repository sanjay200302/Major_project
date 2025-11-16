package com.android.sheguard;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import com.google.android.material.color.DynamicColors;
import com.google.gson.Gson;
import com.android.sheguard.common.Constants;
import com.android.sheguard.config.Prefs;
import com.android.sheguard.service.BluetoothSosService;
import androidx.core.content.ContextCompat;

public class SheGuard extends Application {

    public static final Gson GSON = new Gson();
    @SuppressLint("StaticFieldLeak")
    public static Context context;

    public static Context getAppContext() {
        return context;
    }

    public void onCreate() {
        super.onCreate();
        context = getApplicationContext();
        DynamicColors.applyToActivitiesIfAvailable(this);

        // Auto-start Bluetooth foreground listener if a device was previously selected
        String last = Prefs.getString(Constants.PREF_BLUETOOTH_LAST_DEVICE_ADDRESS, null);
        if (last != null && !last.isEmpty()) {
            boolean canPostNotifications = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            try {
                if (canPostNotifications) {
                    startForegroundService(new Intent(this, BluetoothSosService.class).setAction(BluetoothSosService.ACTION_CONNECT_LAST));
                }
            } catch (Exception ignored) {}
        }
    }
}