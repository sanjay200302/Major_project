package com.android.sheguard.ui.fragment;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.android.sheguard.R;
import com.android.sheguard.databinding.FragmentBluetoothBinding;
import com.android.sheguard.config.Prefs;
import com.android.sheguard.common.Constants;
import com.android.sheguard.util.AppUtil;
import com.android.sheguard.util.SosUtil;
import com.android.sheguard.service.BluetoothSosService;
import com.android.sheguard.ui.view.LoadingDialog;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class BluetoothFragment extends Fragment {

    private FragmentBluetoothBinding binding;
    private BluetoothAdapter bluetoothAdapter;
    private ClientThread clientThread;
    private static final UUID SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private static final long MULTI_PRESS_WINDOW_MS = 2000;
    private static final int REQUIRED_PRESSES = 3;
    private int pressCount = 0;
    private long windowStartMs = 0L;
    private LoadingDialog connectingDialog;
    private SimpleDeviceAdapter deviceAdapter;
    private String connectedAddress = null;
    private String connectedAddressFromPrefs() {
        if (connectedAddress != null) return connectedAddress;
        boolean isConnected = com.android.sheguard.config.Prefs.getBoolean(com.android.sheguard.common.Constants.PREF_BLUETOOTH_CONNECTED, false);
        if (!isConnected) return null;
        return com.android.sheguard.config.Prefs.getString(com.android.sheguard.common.Constants.PREF_BLUETOOTH_LAST_DEVICE_ADDRESS, null);
    }
    private final android.content.BroadcastReceiver btStateReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // Refresh list binding on connect/disconnect
            if (deviceAdapter != null) {
                deviceAdapter.setConnectedAddress(connectedAddressFromPrefs());
                deviceAdapter.notifyDataSetChanged();
            }
        }
    };

    private final ActivityResultLauncher<String[]> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> refreshDevices()
    );

    private final ActivityResultLauncher<String[]> sosPermissionsLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> triggerSosIfReady()
    );

    private final ActivityResultLauncher<Intent> enableBtLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                // After enabling BT, immediately refresh devices and auto-connect last device
                refreshDevices();
            }
    );

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        binding = FragmentBluetoothBinding.inflate(inflater, container, false);
        View view = binding.getRoot();

        ((AppCompatActivity) requireActivity()).setSupportActionBar(binding.header.toolbar);
        ActionBar actionBar = ((AppCompatActivity) requireActivity()).getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setDisplayShowHomeEnabled(true);
            binding.header.collapsingToolbar.setTitle(getString(R.string.bluetooth_devices));
            binding.header.collapsingToolbar.setSubtitle(getString(R.string.bluetooth));
        }

        BluetoothManager bluetoothManager = (BluetoothManager) requireContext().getSystemService(Context.BLUETOOTH_SERVICE);
        bluetoothAdapter = bluetoothManager != null ? bluetoothManager.getAdapter() : null;

        binding.btnRefresh.setOnClickListener(v -> refreshDevices());
        connectingDialog = new LoadingDialog(requireContext());

        deviceAdapter = new SimpleDeviceAdapter(new ArrayList<>(), device -> {
            Prefs.putString(Constants.PREF_BLUETOOTH_LAST_DEVICE_ADDRESS, device.getAddress());
            Prefs.putString(Constants.PREF_BLUETOOTH_LAST_DEVICE_NAME, device.getName());
            connectToDevice(device);
            // Start background service to keep connection alive when leaving fragment
            requireContext().startService(new Intent(requireContext(), BluetoothSosService.class).setAction(BluetoothSosService.ACTION_CONNECT_LAST));
        });
        binding.devicesList.setAdapter(deviceAdapter);

        refreshDevices();
        // Register to refresh UI on state changes
        requireContext().registerReceiver(btStateReceiver, new android.content.IntentFilter(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED));
        return view;
    }

    private void refreshDevices() {
        // Show refresh animation
        binding.progressRefresh.setVisibility(View.VISIBLE);
        if (bluetoothAdapter == null) {
            binding.emptyView.setText(R.string.bluetooth_enable_prompt);
            binding.emptyView.setVisibility(View.VISIBLE);
            binding.progressRefresh.setVisibility(View.GONE);
            return;
        }

        if (!bluetoothAdapter.isEnabled()) {
            Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            enableBtLauncher.launch(enableBtIntent);
            binding.progressRefresh.setVisibility(View.GONE);
            return;
        }

        if (!hasBluetoothPermissions()) {
            requestBluetoothPermissions();
            binding.progressRefresh.setVisibility(View.GONE);
            return;
        }

        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        List<BluetoothDevice> list = new ArrayList<>(pairedDevices);
        deviceAdapter.setConnectedAddress(connectedAddress);
        deviceAdapter.submit(list);

        binding.emptyView.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
        binding.progressRefresh.setVisibility(View.GONE);

        // Auto-connect attempt to last selected device if bonded
        String lastAddress = Prefs.getString(Constants.PREF_BLUETOOTH_LAST_DEVICE_ADDRESS, null);
        if (lastAddress != null) {
            for (BluetoothDevice d : list) {
                if (lastAddress.equals(d.getAddress())) {
                    // For classic devices, createBond ensures bonded; actual RFCOMM connection is app-specific.
                    // Trigger bonding again if needed.
                    try { d.createBond(); } catch (Exception ignored) {}
                    connectToDevice(d);
                    requireContext().startService(new Intent(requireContext(), BluetoothSosService.class).setAction(BluetoothSosService.ACTION_CONNECT_LAST));
                    break;
                }
            }
        }
    }

    private boolean hasBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                    || ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissionLauncher.launch(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT});
        } else {
            permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION});
        }
    }

    private void requestSosPermissionsIfNeeded() {
        if (!AppUtil.permissionsGranted(requireContext())) {
            sosPermissionsLauncher.launch(AppUtil.REQUIRED_PERMISSIONS);
        } else {
            triggerSosIfReady();
        }
    }

    private void triggerSosIfReady() {
        if (!AppUtil.permissionsGranted(requireContext())) {
            return;
        }
        if (!SosUtil.isGPSEnabled(requireContext())) {
            SosUtil.turnOnGPS(requireContext());
        }
        SosUtil.activateInstantSosMode(requireContext());
    }

    private void connectToDevice(BluetoothDevice device) {
        if (clientThread != null) {
            clientThread.cancel();
            clientThread = null;
        }
        // Show connecting dialog
        if (connectingDialog != null) {
            connectingDialog.show(getString(R.string.connecting));
        }
        clientThread = new ClientThread(device);
        clientThread.start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (clientThread != null) {
            clientThread.cancel();
            clientThread = null;
        }
        if (connectingDialog != null) {
            connectingDialog.hide();
        }
        connectedAddress = null;
        try { requireContext().unregisterReceiver(btStateReceiver); } catch (Exception ignored) {}
        binding = null;
    }

    private class ClientThread extends Thread {
        private final BluetoothDevice device;
        private android.bluetooth.BluetoothSocket socket;
        private volatile boolean cancelled = false;

        ClientThread(BluetoothDevice device) {
            this.device = device;
        }

        @Override
        public void run() {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
                    && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return;
            }
            try {
                socket = device.createRfcommSocketToServiceRecord(SPP_UUID);
                bluetoothAdapter.cancelDiscovery();
                socket.connect();
                requireActivity().runOnUiThread(() -> {
                    if (connectingDialog != null) connectingDialog.hide();
                    connectedAddress = device.getAddress();
                    Prefs.putBoolean(Constants.PREF_BLUETOOTH_CONNECTED, true);
                    if (deviceAdapter != null) {
                        deviceAdapter.setConnectedAddress(connectedAddress);
                        deviceAdapter.notifyDataSetChanged();
                    }
                });

                InputStream inputStream = socket.getInputStream();
                byte[] buffer = new byte[256];
                StringBuilder lineBuffer = new StringBuilder();
                while (!cancelled) {
                    int n = inputStream.read(buffer);
                    if (n > 0) {
                        lineBuffer.append(new String(buffer, 0, n));
                        int idx;
                        while ((idx = lineBuffer.indexOf("\n")) >= 0) {
                            String line = lineBuffer.substring(0, idx);
                            lineBuffer.delete(0, idx + 1);
                            handleIncomingFrameUi(line);
                        }
                        if (lineBuffer.length() > 512) {
                            handleIncomingFrameUi(lineBuffer.toString());
                            lineBuffer.setLength(0);
                        }
                    }
                }
            } catch (Exception ignored) {
                requireActivity().runOnUiThread(() -> {
                    if (connectingDialog != null) connectingDialog.hide();
                    connectedAddress = null;
                    Prefs.putBoolean(Constants.PREF_BLUETOOTH_CONNECTED, false);
                    if (deviceAdapter != null) {
                        deviceAdapter.setConnectedAddress(null);
                        deviceAdapter.notifyDataSetChanged();
                    }
                });
            } finally {
                try { if (socket != null) socket.close(); } catch (Exception ignored) {}
            }
        }

        private String connectedAddressFromPrefs() {
            // Removed: now defined at fragment scope
            return null;
        }

        void cancel() {
            cancelled = true;
            try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        }
    }

    private void onTriplePress() {
        requestSosPermissionsIfNeeded();
    }

    private void handleIncomingFrameUi(String frameRaw) {
        String frame = frameRaw.trim();
        if (frame.isEmpty()) return;

        if (frame.contains("TRIPLE_PRESS_ACTION")) {
            pressCount = 0;
            windowStartMs = 0L;
            requireActivity().runOnUiThread(BluetoothFragment.this::onTriplePress);
            return;
        }

        long now = System.currentTimeMillis();
        if (now - windowStartMs > MULTI_PRESS_WINDOW_MS) {
            windowStartMs = now;
            pressCount = 0;
        }
        pressCount++;
        if (pressCount >= REQUIRED_PRESSES) {
            pressCount = 0;
            windowStartMs = 0L;
            requireActivity().runOnUiThread(BluetoothFragment.this::onTriplePress);
        }
    }
}

// Simple adapter classes
class SimpleDeviceAdapter extends android.widget.BaseAdapter {
    interface OnClick {
        void onSelect(BluetoothDevice device);
    }

    private final List<BluetoothDevice> devices;
    private final OnClick onClick;
    private String connectedAddress;

    SimpleDeviceAdapter(List<BluetoothDevice> devices, OnClick onClick) {
        this.devices = devices;
        this.onClick = onClick;
    }

    void submit(List<BluetoothDevice> newDevices) {
        devices.clear();
        devices.addAll(newDevices);
        notifyDataSetChanged();
    }

    void setConnectedAddress(String address) {
        this.connectedAddress = address;
    }

    @Override public int getCount() { return devices.size(); }
    @Override public Object getItem(int position) { return devices.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        Context ctx = parent.getContext();
        android.widget.LinearLayout row = convertView instanceof android.widget.LinearLayout ? (android.widget.LinearLayout) convertView : new android.widget.LinearLayout(ctx);
        row.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * ctx.getResources().getDisplayMetrics().density);
        row.setPadding(pad, pad, pad, pad);

        android.widget.TextView title = new android.widget.TextView(ctx);
        title.setText(devices.get(position).getName());
        android.widget.TextView subtitle = new android.widget.TextView(ctx);
        String addr = devices.get(position).getAddress();
        if (connectedAddress != null && connectedAddress.equals(addr)) {
            subtitle.setText(addr + "\n" + ctx.getString(R.string.connected));
        } else {
            subtitle.setText(addr);
        }

        row.removeAllViews();
        row.addView(title);
        row.addView(subtitle);

        row.setOnClickListener(v -> onClick.onSelect(devices.get(position)));
        return row;
    }
}


