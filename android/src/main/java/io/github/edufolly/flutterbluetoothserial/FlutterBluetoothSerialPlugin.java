package io.github.edufolly.flutterbluetoothserial;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.util.Log;
import android.util.SparseArray;
import android.os.AsyncTask;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.plugin.common.EventChannel.StreamHandler;
import io.flutter.plugin.common.EventChannel.EventSink;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;
import io.flutter.plugin.common.MethodChannel.MethodCallHandler;
import io.flutter.plugin.common.MethodChannel.Result;
import io.flutter.plugin.common.PluginRegistry.Registrar;
import io.flutter.plugin.common.PluginRegistry.RequestPermissionsResultListener;

public class FlutterBluetoothSerialPlugin implements MethodCallHandler, RequestPermissionsResultListener {
    // Plugin
    private static final String TAG = "FlutterBluePlugin";
    private static final String PLUGIN_NAMESPACE = "flutter_bluetooth_serial";
    private final Registrar registrar;
    private Result pendingResultForActivityResult = null;
    
    // Permissions
    private static final int REQUEST_COARSE_LOCATION_PERMISSIONS = 1451;
    private static final int REQUEST_ENABLE_BLUETOOTH = 2137;
    
    // General Bluetooth
    private BluetoothAdapter bluetoothAdapter;
    private BluetoothManager bluetoothManager;
    
    // State
    private final BroadcastReceiver stateReceiver;
    private EventSink stateSink;

    // Discovery
    private EventChannel discoveryChannel;
    private EventSink discoverySink;
    private StreamHandler discoveryStreamHandler;
    private BroadcastReceiver discoveryReceiver;

    // Connections
    /// Contains all active connections. Maps ID of the connection with plugin data channels. 
    private SparseArray<BluetoothConnectionWrapper> connections = new SparseArray<>(2);

    /// Last ID given to any connection, used to avoid duplicate IDs 
    private int lastConnectionId = 0;



    /// Registers plugin in Flutter plugin system
    public static void registerWith(Registrar registrar) {
        final FlutterBluetoothSerialPlugin instance = new FlutterBluetoothSerialPlugin(registrar);
        registrar.addRequestPermissionsResultListener(instance);
    }

    /// Constructs the plugin instance
    FlutterBluetoothSerialPlugin(Registrar registrar) {
        // Plugin
        {
            this.registrar = registrar;
            
            MethodChannel methodChannel = new MethodChannel(registrar.messenger(), PLUGIN_NAMESPACE + "/methods");
            methodChannel.setMethodCallHandler(this);
        }
        
        // General Bluetooth
        {
            this.bluetoothManager = (BluetoothManager) registrar.activity().getSystemService(Context.BLUETOOTH_SERVICE);
            assert this.bluetoothManager != null;

            this.bluetoothAdapter = bluetoothManager.getAdapter();
        }

        // State
        {
            stateReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (stateSink == null) {
                        return;
                    }
                    
                    final String action = intent.getAction();
                    switch (action) {
                        case BluetoothAdapter.ACTION_STATE_CHANGED:
                            // Disconnect all connections
                            int size = connections.size();
                            for (int i = 0; i < size; i++) {
                                BluetoothConnection connection = connections.valueAt(i);
                                connection.disconnect();
                            }
                            connections.clear();
                            
                            stateSink.success(intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1));
                            break;
                    }
                }
            };

            EventChannel stateChannel = new EventChannel(registrar.messenger(), PLUGIN_NAMESPACE + "/state");

            stateChannel.setStreamHandler(new StreamHandler() {
                @Override
                public void onListen(Object o, EventSink eventSink) {
                    stateSink = eventSink;

                    // @TODO . leak :C
                    registrar.activeContext().registerReceiver(stateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));
                }
                @Override
                public void onCancel(Object o) {
                    stateSink = null;
                    try {
                        registrar.activeContext().unregisterReceiver(stateReceiver);
                    }
                    catch (IllegalArgumentException ex) {
                        // Ignore `Receiver not registered` exception
                    }
                }
            });
        }

        // Discovery
        {
            discoveryReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    final String action = intent.getAction();
                    switch (action) {
                        case BluetoothDevice.ACTION_FOUND:
                            final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                            //final BluetoothClass deviceClass = intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS); // @TODO . !BluetoothClass!
                            //final String extraName = intent.getStringExtra(BluetoothDevice.EXTRA_NAME); // @TODO ? !EXTRA_NAME!
                            final int deviceRSSI = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);

                            Map<String, Object> discoveryResult = new HashMap<>();
                            discoveryResult.put("address", device.getAddress());
                            discoveryResult.put("name", device.getName());
                            discoveryResult.put("type", device.getType());
                            //discoveryResult.put("class", deviceClass); // @TODO . it isn't my priority for now !BluetoothClass!
                            // @TODO ? maybe "connected" - look for each of connection instances etc; There is `BluetoothManage.getConnectedDevice` 
                            discoveryResult.put("bonded", device.getBondState() == BluetoothDevice.BOND_BONDED);
                            //discoveryResult.put("extraName", extraName); // @TODO ? !EXTRA_NAME! Is there a reason for `EXTRA_NAME`? https://stackoverflow.com/q/56315991/4880243
                            discoveryResult.put("rssi", deviceRSSI);

                            Log.d(TAG, "Discovered " + device.getAddress());
                            if (discoverySink != null) {
                                discoverySink.success(discoveryResult);
                            }
                            break;

                        case BluetoothAdapter.ACTION_DISCOVERY_FINISHED:
                            Log.d(TAG, "Discovery finished");
                            try {
                                context.unregisterReceiver(discoveryReceiver);
                            }
                            catch (IllegalArgumentException ex) {
                                // Ignore `Receiver not registered` exception
                            }

                            bluetoothAdapter.cancelDiscovery();

                            if (discoverySink != null) {
                                discoverySink.endOfStream();
                                discoverySink = null;
                            }
                            break;

                        default:
                            // Ignore.
                            break;
                    }
                }
            };

            discoveryChannel = new EventChannel(registrar.messenger(), PLUGIN_NAMESPACE + "/discovery");

            discoveryStreamHandler = new StreamHandler() {
                @Override
                public void onListen(Object o, EventSink eventSink) {
                    discoverySink = eventSink;
                }
                @Override
                public void onCancel(Object o) {
                    Log.d(TAG, "Canceling discovery (stream closed)");
                    try {
                        registrar.activeContext().unregisterReceiver(discoveryReceiver);
                    }
                    catch (IllegalArgumentException ex) {
                        // Ignore `Receiver not registered` exception
                    }
                    
                    bluetoothAdapter.cancelDiscovery();

                    if (discoverySink != null) {
                        discoverySink.endOfStream();
                        discoverySink = null;
                    }
                }
            };
            discoveryChannel.setStreamHandler(discoveryStreamHandler);
        }
    }

    /// Provides access to the plugin methods
    @Override
    public void onMethodCall(MethodCall call, Result result) {
        if (bluetoothAdapter == null) {
            if ("isAvailable".equals(call.method)) {
                result.success(false);
                return;
            }
            else {
                result.error("bluetooth_unavailable", "bluetooth is not available", null);
                return;
            }
        }

        switch (call.method) {

            case "isAvailable":
                result.success(true);
                break;

            case "isOn":
            case "isEnabled":
                result.success(bluetoothAdapter.isEnabled());
                break;

            case "openSettings":
                ContextCompat.startActivity(registrar.activity(), new Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS), null);
                result.success(null);
                break;

            case "requestEnable":
                if (!bluetoothAdapter.isEnabled()) {
                    Intent intent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                    ActivityCompat.startActivityForResult(registrar.activity(), intent, REQUEST_ENABLE_BLUETOOTH, null);
                }
                else {
                    result.success(true);
                }
                break;

            case "requestDisable":
                if (bluetoothAdapter.isEnabled()) {
                    bluetoothAdapter.disable();
                    result.success(true);
                }
                else {
                    result.success(false);
                }
                break;

            case "ensurePermissions":
                ensurePermissions(new EnsurePermissionsCallback() {
                    @Override
                    public void onResult(boolean granted) {
                        result.success(granted);
                    }
                });
                break;

            case "getState":
                result.success(bluetoothAdapter.getState());
                break;

            case "getBondedDevices":
                ensurePermissions(new EnsurePermissionsCallback() {
                    @Override
                    public void onResult(boolean granted) {
                        if (!granted) {
                            result.error("no_permissions", "discovering other devices requires location access permission", null);
                            return;
                        }

                        List<Map<String, Object>> list = new ArrayList<>();
                        for (BluetoothDevice device : bluetoothAdapter.getBondedDevices()) {
                            Map<String, Object> entry = new HashMap<>();
                            entry.put("address", device.getAddress());
                            entry.put("name", device.getName());
                            entry.put("type", device.getType());
                            // @TODO ? maybe "connected" - look for each of connection instances etc
                            entry.put("bonded", true);
                            list.add(entry);
                        }

                        result.success(list);
                    }
                });
                break;

            case "isDiscovering":
                result.success(bluetoothAdapter.isDiscovering());
                break;

            case "startDiscovery":
                ensurePermissions(new EnsurePermissionsCallback() {
                    @Override
                    public void onResult(boolean granted) {
                        if (!granted) {
                            result.error("no_permissions", "discovering other devices requires location access permission", null);
                            return;
                        }

                        Log.d(TAG, "Starting discovery");
                        IntentFilter intent = new IntentFilter();
                        intent.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
                        intent.addAction(BluetoothDevice.ACTION_FOUND);
                        registrar.activeContext().registerReceiver(discoveryReceiver, intent);
                        
                        bluetoothAdapter.startDiscovery();
                        
                        result.success(null);
                    }
                });
                break;

            case "cancelDiscovery": 
                Log.d(TAG, "Canceling discovery");
                try {
                    registrar.activeContext().unregisterReceiver(discoveryReceiver);
                }
                catch (IllegalArgumentException ex) {
                    // Ignore `Receiver not registered` exception
                }

                bluetoothAdapter.cancelDiscovery();
                
                if (discoverySink != null) {
                    discoverySink.endOfStream();
                    discoverySink = null;
                }
                
                result.success(null);
                break;

            /* Connection */
            case "connect": {
                if (!call.hasArgument("address")) {
                    result.error("invalid_argument", "argument 'address' not found", null);
                    break;
                }

                String address;
                try {
                    address = call.argument("address");
                    if (!BluetoothAdapter.checkBluetoothAddress(address)) {
                        throw new ClassCastException();
                    }
                }
                catch (ClassCastException ex) {
                    result.error("invalid_argument", "'address' argument is required to be string containing remote MAC address", null);
                    break;
                }

                int id = ++lastConnectionId;
                BluetoothConnectionWrapper connection = new BluetoothConnectionWrapper(id, bluetoothAdapter);
                connections.put(id, connection);

                Log.d(TAG, "Connecting to " + address + " (id: " + id + ")");

                AsyncTask.execute(() -> {
                    try {
                        connection.connect(address);
                        registrar.activity().runOnUiThread(new Runnable() {
                            @Override 
                            public void run() {
                                result.success(id);
                            }
                        });
                    }
                    catch (Exception ex) {
                        registrar.activity().runOnUiThread(new Runnable() {
                            @Override 
                            public void run() {
                                result.error("connect_error", ex.getMessage(), exceptionToString(ex));
                            }
                        });
                    }
                });
                break;
            }

            case "write": {
                if (!call.hasArgument("id")) {
                    result.error("invalid_argument", "argument 'id' not found", null);
                    break;
                }

                int id;
                try {
                    id = call.argument("id");
                }
                catch (ClassCastException ex) {
                    result.error("invalid_argument", "'id' argument is required to be integer id of connection", null);
                    break;
                }

                BluetoothConnection connection = connections.get(id);
                if (connection == null) {
                    result.error("invalid_argument", "there is no connection with provided id", null);
                    break;
                }
                
                if (call.hasArgument("string")) {
                    String string = call.argument("string");
                    AsyncTask.execute(() -> {
                        try {
                            connection.write(string.getBytes());
                            registrar.activity().runOnUiThread(new Runnable() {
                                @Override 
                                public void run() {
                                    result.success(null);
                                }
                            });
                        }
                        catch (Exception ex) {
                            registrar.activity().runOnUiThread(new Runnable() {
                                @Override 
                                public void run() {
                                    result.error("write_error", ex.getMessage(), exceptionToString(ex));
                                }
                            });
                        }
                    });
                }
                else if (call.hasArgument("bytes")) {
                    byte[] bytes = call.argument("bytes");
                    AsyncTask.execute(() -> {
                        try {
                            connection.write(bytes);
                            registrar.activity().runOnUiThread(new Runnable() {
                                @Override 
                                public void run() {
                                    result.success(null);
                                }
                            });
                        }
                        catch (Exception ex) {
                            registrar.activity().runOnUiThread(new Runnable() {
                                @Override 
                                public void run() {
                                    result.error("write_error", ex.getMessage(), exceptionToString(ex));
                                }
                            });
                        }
                    });
                }
                else {
                    result.error("invalid_argument", "there must be 'string' or 'bytes' argument", null);
                }
                break;
            }

            default:
                result.notImplemented();
                break;
        }
    }



    private interface EnsurePermissionsCallback {
        public void onResult(boolean granted);
    }

    EnsurePermissionsCallback pendingPermissionsEnsureCallbacks = null;

    private void ensurePermissions(EnsurePermissionsCallback callbacks) {
        if (
            ContextCompat.checkSelfPermission(registrar.activity(),
                Manifest.permission.ACCESS_COARSE_LOCATION) 
                    != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(registrar.activity(),
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQUEST_COARSE_LOCATION_PERMISSIONS);

            pendingPermissionsEnsureCallbacks = callbacks;
        }
        else {
            callbacks.onResult(true);
        }
    }

    @Override
    public boolean onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        switch (requestCode) {
            case REQUEST_COARSE_LOCATION_PERMISSIONS:
                pendingPermissionsEnsureCallbacks.onResult(grantResults[0] == PackageManager.PERMISSION_GRANTED);
                pendingPermissionsEnsureCallbacks = null;
                return true;
        }
        return false;
    }

    // @TODO ? Registrar addActivityResultListener(ActivityResultListener listener);
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case REQUEST_ENABLE_BLUETOOTH:
                if (resultCode == 0) { // @TODO - use underlying value of `Activity.RESULT_CANCELED` since we tend to use `androidx` in where I could find the value.
                    pendingResultForActivityResult.success(false);
                }
                else {
                    pendingResultForActivityResult.success(true);
                }
                break;

            default:
                // Ignore.
                break;
        }
    }



    /// Helper function to get string out of exception
    private String exceptionToString(Exception ex) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        ex.printStackTrace(pw);
        return sw.toString();
    }



    /// Helper wrapper class for `BluetoothConnection`
    private class BluetoothConnectionWrapper extends BluetoothConnection {
        private final int id;
        
        protected EventSink readSink;

        protected EventChannel readChannel;

        private final BluetoothConnectionWrapper self = this;
        private final StreamHandler readStreamHandler = new StreamHandler() {
            @Override
            public void onListen(Object o, EventSink eventSink) {
                readSink = eventSink;
            }
            @Override
            public void onCancel(Object o) {
                // If canceled by local, disconnects - in other case, by remote, does nothing
                self.disconnect();
                
                // True dispose 
                AsyncTask.execute(() -> {
                    readChannel.setStreamHandler(null);
                    connections.remove(id);

                    Log.d(TAG, "Disconnected (id: " + id + ")");
                });
            }
        };

        public BluetoothConnectionWrapper(int id, BluetoothAdapter adapter)
        {
            super(adapter);
            this.id = id;

            readChannel = new EventChannel(registrar.messenger(), PLUGIN_NAMESPACE + "/read/" + id);
            readChannel.setStreamHandler(readStreamHandler);
        }

        @Override
        protected void onRead(byte[] buffer) {
            registrar.activity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (readSink != null) {
                        readSink.success(buffer);
                    }
                }
            });
        }

        @Override
        protected void onDisconnected(boolean byRemote) {
            registrar.activity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (byRemote) {
                        Log.d(TAG, "Connection onDisconnected by remote");
                        if (readSink != null) {
                            readSink.endOfStream();
                            readSink = null;
                        }
                    }
                    else {
                        Log.d(TAG, "Connection onDisconnected by local");
                    }
                }
            });
        }
    }
}
