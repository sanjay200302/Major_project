package com.android.sheguard.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.android.sheguard.R;
import com.android.sheguard.common.Constants;
import com.android.sheguard.config.Prefs;
import com.android.sheguard.util.SosUtil;
import com.android.sheguard.ui.activity.MainActivity;

import java.io.InputStream;
import java.util.UUID;

public class BluetoothSosService extends Service {

    public static final String ACTION_CONNECT_LAST = "CONNECT_LAST";
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long MULTI_PRESS_WINDOW_MS = 2000;
    private static final int REQUIRED_PRESSES = 3;

    private BluetoothAdapter bluetoothAdapter;
    private android.bluetooth.BluetoothSocket socket;
    private Thread readerThread;
    private volatile boolean cancelled = false;
    private int pressCount = 0;
    private long windowStartMs = 0L;
    private static final long RECONNECT_DELAY_MS = 3000L;
    private PowerManager.WakeLock wakeLock;

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        ensureForegroundNotification();
        acquireWakeLock();

        if (intent == null || ACTION_CONNECT_LAST.equals(intent.getAction())) {
            connectLastDevice();
        } else {
            connectLastDevice();
        }

        return START_STICKY;
    }

    private void connectLastDevice() {
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) return;
        if (!hasBtConnectPermission()) return;

        String last = Prefs.getString(Constants.PREF_BLUETOOTH_LAST_DEVICE_ADDRESS, null);
        if (last == null) return;
        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(last);
        startReader(device);
    }

    private boolean hasBtConnectPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    private synchronized void startReader(BluetoothDevice device) {
        stopReader();
        readerThread = new Thread(() -> {
            cancelled = false;
            while (!cancelled) {
                if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled()) {
                    sleepQuiet(RECONNECT_DELAY_MS);
                    continue;
                }
                if (!hasBtConnectPermission()) {
                    sleepQuiet(RECONNECT_DELAY_MS);
                    continue;
                }
                try {
                    socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                    bluetoothAdapter.cancelDiscovery();
                    socket.connect();
                    Prefs.putBoolean(Constants.PREF_BLUETOOTH_CONNECTED, true);

                    InputStream inputStream = socket.getInputStream();
                    byte[] buffer = new byte[256];
                    StringBuilder lineBuffer = new StringBuilder();
                    while (!cancelled && socket.isConnected()) {
                        int n = inputStream.read(buffer);
                        if (n > 0) {
                            lineBuffer.append(new String(buffer, 0, n));
                            int idx;
                            while ((idx = lineBuffer.indexOf("\n")) >= 0) {
                                String line = lineBuffer.substring(0, idx);
                                lineBuffer.delete(0, idx + 1);
                                handleIncomingFrame(line);
                            }
                            // Also handle frames without newline if buffer grows too big
                            if (lineBuffer.length() > 512) {
                                handleIncomingFrame(lineBuffer.toString());
                                lineBuffer.setLength(0);
                            }
                        }
                    }
                } catch (Exception ignored) {
                } finally {
                    try { if (socket != null) socket.close(); } catch (Exception ignored) {}
                    socket = null;
                    Prefs.putBoolean(Constants.PREF_BLUETOOTH_CONNECTED, false);
                }
                // If we reached here, we are disconnected or failed to connect. Retry after delay.
                sleepQuiet(RECONNECT_DELAY_MS);
            }
        });
        readerThread.start();
    }

    private synchronized void stopReader() {
        cancelled = true;
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
    }

    private void handleIncomingFrame(String frameRaw) {
        String frame = frameRaw.trim();
        if (frame.isEmpty()) return;

        if (frame.contains("TRIPLE_PRESS_ACTION")) {
            pressCount = 0;
            windowStartMs = 0L;
            SosUtil.activateInstantSosMode(this);
            return;
        }

        // Count per-press frames (e.g., "P")
        long now = System.currentTimeMillis();
        if (now - windowStartMs > MULTI_PRESS_WINDOW_MS) {
            windowStartMs = now;
            pressCount = 0;
        }
        pressCount++;
        if (pressCount >= REQUIRED_PRESSES) {
            pressCount = 0;
            windowStartMs = 0L;
            SosUtil.activateInstantSosMode(this);
        }
    }

    private void sleepQuiet(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopReader();
        try {
            stopForeground(true);
        } catch (Exception ignored) {}
        releaseWakeLock();
    }

    private void ensureForegroundNotification() {
        try {
            String channelId = getString(R.string.notification_channel_emergency);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Reuse emergency channel to avoid creating a new one
                NotificationChannel existing = nm.getNotificationChannel(channelId);
                if (existing == null) {
                    NotificationChannel channel = new NotificationChannel(channelId, getString(R.string.notification_channel_emergency), NotificationManager.IMPORTANCE_LOW);
                    nm.createNotificationChannel(channel);
                }
            }

            Intent openIntent = new Intent(this, MainActivity.class);
            openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            PendingIntent pi = PendingIntent.getActivity(this, 2, openIntent, PendingIntent.FLAG_IMMUTABLE);

            Notification notification = new Notification.Builder(this, channelId)
                    .setContentTitle(getString(R.string.bluetooth))
                    .setContentText(getString(R.string.bluetooth_devices))
                    .setSmallIcon(R.drawable.ic_bluetooth)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();

            // For Android 14+, specify foreground type via manifest; just startForeground here
            startForeground(2, notification);
        } catch (Exception ignored) {}
    }

    private void acquireWakeLock() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getPackageName()+":bt_sos");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Exception ignored) {}
        wakeLock = null;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // Ensure service is restarted if swiped away
        startService(new Intent(this, BluetoothSosService.class).setAction(ACTION_CONNECT_LAST));
        super.onTaskRemoved(rootIntent);
    }
}


