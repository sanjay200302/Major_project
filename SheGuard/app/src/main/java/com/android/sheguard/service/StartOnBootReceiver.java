package com.android.sheguard.service;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class StartOnBootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Start Bluetooth listening service on boot if a device was previously selected
        context.startForegroundService(new Intent(context, BluetoothSosService.class).setAction(BluetoothSosService.ACTION_CONNECT_LAST));
    }
}


