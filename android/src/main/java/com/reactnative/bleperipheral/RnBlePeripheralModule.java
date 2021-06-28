package com.reactnative.peripheral;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.os.ParcelUuid;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.Callback;

import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Created by Nikhil Savaliya on 17/06/21.
 */
public class RnBlePeripheralModule extends ReactContextBaseJavaModule {

    private ReactApplicationContext reactContext;
    private boolean hasListeners;
    public static final String TAG= RnBlePeripheralModule.class.getSimpleName();
    private HashMap<String,GattRequest> mRequestMap=new HashMap<>();
    private HashMap<String,BluetoothGattService> mServicesMap=new HashMap<>();
    private AdvertiseSettings mAdvSettings;
    private AdvertiseData mAdvData;
    private AdvertiseData mAdvScanResponse;
    private BluetoothManager mBluetoothManager;
    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothGattServer mGattServer;
    private BluetoothLeAdvertiser mAdvertiser;
       private static final UUID CHARACTERISTIC_USER_DESCRIPTION_UUID = UUID
            .fromString("00002901-0000-1000-8000-00805f9b34fb");
    private static final UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID
            .fromString("00002902-0000-1000-8000-00805f9b34fb");
    private final AdvertiseCallback mAdvCallback = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);
            if (mAdvPromise!=null){
                mAdvPromise.reject("onStartFailure");
            }
            mAdvPromise=null;
            Log.e(TAG, "Not broadcasting: " + errorCode);
            int statusText;
            switch (errorCode) {
                case ADVERTISE_FAILED_ALREADY_STARTED:
                    Log.w(TAG, "App was already advertising");
                    break;
                case ADVERTISE_FAILED_DATA_TOO_LARGE:
                    break;
                case ADVERTISE_FAILED_FEATURE_UNSUPPORTED:
                    break;
                case ADVERTISE_FAILED_INTERNAL_ERROR:
                    break;
                case ADVERTISE_FAILED_TOO_MANY_ADVERTISERS:
                    break;
                default:
                    Log.wtf(TAG, "Unhandled error: " + errorCode);
            }
        }

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            if (mAdvPromise!=null){
                mAdvPromise.resolve("success");
            }
            mAdvPromise=null;
        }
    };
    private final BluetoothGattServerCallback mGattServerCallback = new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, final int status, int newState) {
            super.onConnectionStateChange(device, status, newState);
            if (status == BluetoothGatt.GATT_SUCCESS) {
                if (newState == BluetoothGatt.STATE_CONNECTED) {
                    Log.v(TAG, "Connected to device: " + device.getAddress());
                } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                    Log.v(TAG, "Disconnected from device");
                }
            } else {
                // There are too many gatt errors (some of them not even in the documentation) so we just
                // show the error to the user.
                Log.e(TAG, "Error when connecting: " + status);
            }
        }

        @Override
        public void onCharacteristicReadRequest(BluetoothDevice device, int requestId, int offset,
                                                BluetoothGattCharacteristic characteristic) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic);
            Log.d(TAG, "Device tried to read characteristic: " + characteristic.getUuid());
            Log.d(TAG, "Value: " + Arrays.toString(characteristic.getValue()));
            if (!hasListeners){
                return;
            }
            /*if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                         value (optional)  characteristic.getValue());
                return;
            }*/
            mRequestMap.put(String.valueOf(requestId),new GattRequest(requestId,offset,device,characteristic));
            WritableMap params= Arguments.createMap();
            params.putString("requestId", String.valueOf(requestId));
            params.putInt("offset", offset);
            params.putString("characteristicUuid",characteristic.getUuid().toString());
            params.putString("serviceUuid",characteristic.getService().getUuid().toString());
            sendEvent("READ_REQUEST",params);
/*
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS,
                    offset, characteristic.getValue());
*/

        }

       /* @Override
        public void onNotificationSent(BluetoothDevice device, int status) {
            super.onNotificationSent(device, status);
            Log.v(TAG, "Notification sent. Status: " + status);
        }*/

        @Override
        public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId,
                                                 BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded,
                                                 int offset, byte[] value) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite,
                    responseNeeded, offset, value);
            if (!hasListeners) return;
            Log.v(TAG, "Characteristic Write request: " + Arrays.toString(value));
            mRequestMap.put(String.valueOf(requestId),new GattRequest(requestId,offset,device,characteristic));
            WritableMap params= Arguments.createMap();
            params.putString("requestId", String.valueOf(requestId));
            params.putInt("offset", offset);
            params.putString("value",new String(value));
            params.putString("characteristicUuid",characteristic.getUuid().toString());
            params.putString("serviceUuid",characteristic.getService().getUuid().toString());
            sendEvent("WRITE_REQUEST",params);
           /* int status;
            if (offset != 0) {
                status= BluetoothGatt.GATT_INVALID_OFFSET;
            }
            // Heart Rate control point is a 8bit characteristic
            if (value.length != 1) {
                status= BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
            }
            status= BluetoothGatt.GATT_SUCCESS;
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, status,
                        *//* No need to respond with an offset *//* 0,
                        *//* No need to respond with a value *//* null);
            }*/

        }

        /*@Override
        public void onDescriptorReadRequest(BluetoothDevice device, int requestId,
                                            int offset, BluetoothGattDescriptor descriptor) {
            super.onDescriptorReadRequest(device, requestId, offset, descriptor);
            Log.d(TAG, "Device tried to read descriptor: " + descriptor.getUuid());
            Log.d(TAG, "Value: " + Arrays.toString(descriptor.getValue())+"offset::"+offset);
            if (offset != 0) {
                mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset,
                        *//* value (optional) *//* null);
                return;
            }
            mGattServer.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset,
                    descriptor.getValue());
        }

        @Override
        public void onDescriptorWriteRequest(BluetoothDevice device, int requestId,
                                             BluetoothGattDescriptor descriptor, boolean preparedWrite, boolean responseNeeded,
                                             int offset,
                                             byte[] value) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded,
                    offset, value);
            Log.v(TAG, "Descriptor Write Request " + descriptor.getUuid() + " " + Arrays.toString(value));
            int status = BluetoothGatt.GATT_SUCCESS;
            if (descriptor.getUuid() == SERVICE_UUID) {
                BluetoothGattCharacteristic characteristic = descriptor.getCharacteristic();
                boolean supportsNotifications = (characteristic.getProperties() &
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0;
                boolean supportsIndications = (characteristic.getProperties() &
                        BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0;

                if (!(supportsNotifications || supportsIndications)) {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                } else if (value.length != 2) {
                    status = BluetoothGatt.GATT_INVALID_ATTRIBUTE_LENGTH;
                } else if (Arrays.equals(value, BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    descriptor.setValue(value);
                } else if (supportsNotifications &&
                        Arrays.equals(value, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    descriptor.setValue(value);
                } else if (supportsIndications &&
                        Arrays.equals(value, BluetoothGattDescriptor.ENABLE_INDICATION_VALUE)) {
                    status = BluetoothGatt.GATT_SUCCESS;
                    descriptor.setValue(value);
                } else {
                    status = BluetoothGatt.GATT_REQUEST_NOT_SUPPORTED;
                }
            } else {
                status = BluetoothGatt.GATT_SUCCESS;
                descriptor.setValue(value);
            }
            if (responseNeeded) {
                mGattServer.sendResponse(device, requestId, status,
                        *//* No need to respond with offset *//* 0,
                        *//* No need to respond with a value *//* null);
            }
        }*/
    };
    private Promise mAdvPromise;



    public static BluetoothGattDescriptor getClientCharacteristicConfigurationDescriptor() {
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                CLIENT_CHARACTERISTIC_CONFIGURATION_UUID,
                (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        descriptor.setValue(new byte[]{0, 0});

        return descriptor;
    }

    public static BluetoothGattDescriptor getCharacteristicUserDescriptionDescriptor(String defaultValue) {
        BluetoothGattDescriptor descriptor = new BluetoothGattDescriptor(
                CHARACTERISTIC_USER_DESCRIPTION_UUID,
                (BluetoothGattDescriptor.PERMISSION_READ | BluetoothGattDescriptor.PERMISSION_WRITE));
        try {
            descriptor.setValue(defaultValue.getBytes("UTF-8"));
        } finally {
            return descriptor;
        }
    }


    public RnBlePeripheralModule(ReactApplicationContext reactContext) {
        super(reactContext);
        Log.i(TAG,"init");
        this.reactContext = reactContext;
        mBluetoothManager = (BluetoothManager) getReactApplicationContext().getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();

    }

    @Override
    public Map<String, Object> getConstants() {
        Map<String, Object> map=new HashMap<>();
        map.put("READ_REQUEST","READ_REQUEST");
        map.put("STATE_CHANGED","STATE_CHANGED");
        map.put("SUBSCRIBED","SUBSCRIBED");
        map.put("UNSUBSCRIBED","UNSUBSCRIBED");
        map.put("WRITE_REQUEST","WRITE_REQUEST");
        return map;
    }

    // Will be called when this module's first listener is added.
    public void startObserving() {
        hasListeners = true;
    }

    // Will be called when this module's last listener is removed, or on dealloc.
    public void stopObserving() {
        hasListeners = false;
    }

    @ReactMethod
    public void getState(Promise promise){
        Log.i(TAG,"getstate");
        if (mBluetoothAdapter.isMultipleAdvertisementSupported()){
            promise.resolve("poweredOn");
        }else {
            promise.reject(new Exception());
        }
    }

    @ReactMethod
    public void addService(ReadableMap map, Promise promise){
        Log.i(TAG,"add service"+map);
        if (map!=null){
            UUID serviceUuid=UUID.fromString(map.getString("uuid"));
            ReadableArray characteristics = map.getArray("characteristics");

            if (serviceUuid==null && characteristics!=null) {
                BluetoothGattService mBluetoothGattService = new BluetoothGattService(serviceUuid,
                        BluetoothGattService.SERVICE_TYPE_PRIMARY);
                for (int i = 0; i < characteristics.size(); i++) {
                    ReadableMap characteristic =characteristics.getMap(0);
                    UUID characteristicUuid=UUID.fromString("uuid");
                    int property=getProperty(characteristic.getArray("properties"));
                    int permission=getPermission(characteristic.getArray("permissions"));
                    BluetoothGattCharacteristic mCharacteristic = new BluetoothGattCharacteristic(characteristicUuid,
                            property,
                            permission);
                    mCharacteristic.addDescriptor(getClientCharacteristicConfigurationDescriptor());
                    mBluetoothGattService.addCharacteristic(mCharacteristic);
                }
                mServicesMap.put(serviceUuid.toString(),mBluetoothGattService);
                promise.resolve(null);
            }else {
                promise.reject("invalid_service");
            }
        }else {
            promise.reject("invalid_service");
        }
    }

    @ReactMethod
    public void removeService(ReadableMap map,Promise promise){
        if (map!=null){
            UUID serviceUuid=UUID.fromString(map.getString("uuid"));
            if (serviceUuid!=null) {
                mServicesMap.remove(serviceUuid);
                promise.resolve(null);
            }else {
                promise.reject("invalid_service");
            }
        }else {
            promise.reject("invalid_service");

        }
    }

    @ReactMethod
    public void startAdvertising(ReadableMap map,
                                 Promise promise){
        Log.i(TAG,"start Advertising"+map);
        if (map==null){
            promise.reject("invalid_advertisement");
            return;
        }
        String deviceName=map.getString("name");
        mBluetoothAdapter.setName(deviceName);
        ReadableArray serviceUuids=map.getArray("serviceUuids");
        if (serviceUuids==null||serviceUuids.size()==0){
            promise.reject("invalid_advertisement");
            return;
        }
        mGattServer = mBluetoothManager.openGattServer(getReactApplicationContext(), mGattServerCallback);
        if (mGattServer == null) {
            //ensureBleFeaturesAvailable();
            promise.reject("invalid_advertisement");
            return;
        }
        this.mAdvPromise=promise;
        mAdvSettings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
                .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
                .setConnectable(true)
                .build();

        mAdvScanResponse = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .build();
        for (int i = 0; i < serviceUuids.size(); i++) {
            String serviceUuid=serviceUuids.getString(0);
            mAdvData = new AdvertiseData.Builder()
                    .setIncludeTxPowerLevel(true)
                    .addServiceUuid(new ParcelUuid(UUID.fromString(serviceUuid)))
                    .build();
            // If the user disabled Bluetooth when the app was in the background,
            // openGattServer() will return null.

            // Add a service for a total of three services (Generic Attribute and Generic Access
            // are present by default).
            BluetoothGattService mBluetoothGattService=mServicesMap.get(serviceUuid);
            mGattServer.addService(mBluetoothGattService);
        }

        if (mBluetoothAdapter.isMultipleAdvertisementSupported()) {
            mAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
            mAdvertiser.startAdvertising(mAdvSettings, mAdvData, mAdvScanResponse, mAdvCallback);
        } else {
            promise.reject("invalid_advertisement");
            //not supported
        }
    }

    @ReactMethod
    public void stopAdvertising(Promise promise){
        if (mAdvPromise!=null){
            mAdvPromise=null;
        }
        if (mGattServer != null) {
            mGattServer.close();
        }
        if (mBluetoothAdapter.isEnabled() && mAdvertiser != null) {
            // If stopAdvertising() gets called before close() a null
            // pointer exception is raised.
            mAdvertiser.stopAdvertising(mAdvCallback);
        }
        promise.resolve(null);
    }

    @ReactMethod
    public void respond(String requestId,String status,String value, Promise promise){
        GattRequest request=mRequestMap.get(requestId);
        if (request!=null){
            byte[] valueArr=null;
            if (valueArr!=null){
                valueArr=value.getBytes();
            }
            int statusInt=BluetoothGatt.GATT_INVALID_OFFSET;
            if ("success".equalsIgnoreCase(value)){
                statusInt=BluetoothGatt.GATT_SUCCESS;
            }
            mGattServer.sendResponse(request.device,request.requestId,statusInt,request.offset,valueArr);
            mRequestMap.remove(requestId);
            promise.resolve(null);
        }else {
            promise.reject("invalid_request");
        }

    }

    private void sendEvent(String eventName, WritableMap params){
        getReactApplicationContext().getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(eventName,params);
    }

    @Override
    public String getName() {
        return "RnBlePeripheral";
    }

    public static class GattRequest{
        int requestId;
        int offset;
        BluetoothDevice device;
        BluetoothGattCharacteristic characteristic;

        public GattRequest(int requestId, int offset,BluetoothDevice device, BluetoothGattCharacteristic characteristic) {
            this.requestId = requestId;
            this.offset = offset;
            this.device=device;
            this.characteristic = characteristic;
        }
    }

    public static int getPermission(ReadableArray permissions){
        if (permissions==null||permissions.size()==0){
            return -1;
        }else {
            int permission=-1;
            for (int i = 0; i < permissions.size(); i++) {
                if (i==0){
                    permission=getPermissionValue(permissions.getString(i));
                }else {
                    permission=permission|getPermissionValue(permissions.getString(i));
                }
            }
            return permission;
        }
    }

    public static int getProperty(ReadableArray properties){
        if (properties==null||properties.size()==0){
            return -1;
        }else {
            int property=-1;
            for (int i = 0; i < properties.size(); i++) {
                if (i==0){
                    property=getPropertyValue(properties.getString(i));
                }else {
                    property=property|getPropertyValue(properties.getString(i));
                }
            }
            return property;
        }
    }

    public static int getPermissionValue(String permission){
        switch (permission){
            case "readable":
                return BluetoothGattCharacteristic.PERMISSION_READ;
            case "writeable":
                return BluetoothGattCharacteristic.PERMISSION_WRITE;
            case "readEncryptionRequired":
                return BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED;
            case "writeEncryptionRequired":
                return BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED;
        }
        return -1;
    }

    public static int getPropertyValue(String permission){
        switch (permission){
            case "broadcast":
                return BluetoothGattCharacteristic.PROPERTY_BROADCAST;
            case "read":
                return BluetoothGattCharacteristic.PROPERTY_READ;
            case "writeWithoutResponse":
                return BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
            case "write":
                return BluetoothGattCharacteristic.PROPERTY_WRITE;
            case "notify":
                return BluetoothGattCharacteristic.PROPERTY_NOTIFY;
            case "indicate":
                return BluetoothGattCharacteristic.PROPERTY_INDICATE;
            case "authenticatedSignedWrites":
                return BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE;
            case "extendedProperties":
                return BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS;
        }
        return -1;
    }
}

