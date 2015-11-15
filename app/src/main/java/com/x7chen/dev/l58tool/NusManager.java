/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.x7chen.dev.l58tool;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.util.List;
import java.util.UUID;

/**
 * Service for managing connection and data communication with a GATT server hosted on a
 * given Bluetooth LE device.
 */
public class NusManager {
    public static final String TAG = "NUS";
    private BluetoothGatt mBluetoothGatt;
    private BluetoothDevice mDevice;
    private int mConnectionState = STATE_DISCONNECTED;

    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    public static final UUID NUS_SERVICE_UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_RX_CHAR_UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e");
    public static final UUID NUS_TX_CHAR_UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e");
    public static final String CLIENT_CHARACTERISTIC_CONFIG = "00002902-0000-1000-8000-00805f9b34fb";

    public final static String ERROR_DISCOVERY_SERVICE = "Error on discovering services";
    public final static String ERROR_WRITE_CHARACTERISTIC = "Error on writing characteristic";
    public final static String ERROR_WRITE_DESCRIPTOR = "Error on writing descriptor";


    private NusManagerCallBacks mCallbacks;
    private boolean isNUSServiceFound = false;

    private BluetoothGattCharacteristic mNusRxCharacteristic;
    private BluetoothGattCharacteristic mNusTxCharacteristic;

    // Implements callback methods for GATT events that the app cares about.  For example,
    // connection change and services discovered.
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                mCallbacks.onDeviceConnected();
                Log.i(TAG, "Connected to GATT server.");
                // Attempts to discover services after successful connection.
                Log.i(TAG, "Attempting to start service discovery:" +
                        mBluetoothGatt.discoverServices());

            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                mCallbacks.onDeviceDisconnected();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            isNUSServiceFound = false;
            if (status == BluetoothGatt.GATT_SUCCESS) {
                List<BluetoothGattService> services = gatt.getServices();
                for (BluetoothGattService service : services) {
                    Log.d(TAG, "Found Service: " + service.getUuid());
                    if (service.getUuid().equals(NUS_SERVICE_UUID)) {
                        Log.d(TAG, "NUS Service found!");
                        isNUSServiceFound = true;
                        mNusRxCharacteristic = service.getCharacteristic(NUS_RX_CHAR_UUID);
                        mNusTxCharacteristic = service.getCharacteristic(NUS_TX_CHAR_UUID);
                        enableReceiveNotification();
                        mCallbacks.onInitialized();
                    }
                }
                if (isNUSServiceFound) {
                    mCallbacks.onServiceFound();
                } else {
                    mCallbacks.onDeviceNotSupported();
                    gatt.disconnect();
                }
            } else {
                mCallbacks.onError(ERROR_DISCOVERY_SERVICE, status);
            }
        }

        @Override
        public void onCharacteristicRead(BluetoothGatt gatt,
                                         BluetoothGattCharacteristic characteristic,
                                         int status) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                mCallbacks.onReceived(characteristic.getValue());
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
            mCallbacks.onSending();
            super.onCharacteristicWrite(gatt, characteristic, status);
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                                            BluetoothGattCharacteristic characteristic) {
            mCallbacks.onReceived(characteristic.getValue());
        }
    };

    public void registerCallbacks(NusManagerCallBacks callBacks) {
        mCallbacks = callBacks;
    }

    public interface NusManagerCallBacks {
        void onDeviceConnected();

        void onDeviceDisconnected();

        void onServiceFound();

        void onReceived(byte[] data);

        void onInitialized();

        void onSending();

        void onError(String message, int errorCode);

        void onDeviceNotSupported();
    }

    public BluetoothDevice getDevice() {
        return mDevice;
    }

    public void connect(Context context, BluetoothDevice device) {
        if (device == null) {
            return;
        }
        mDevice = device;
        mBluetoothGatt = mDevice.connectGatt(context, false, mGattCallback);
    }

    public void disconnect() {
        Log.d(TAG, "Disconnecting device");
        if (mBluetoothGatt != null) {
            mBluetoothGatt.disconnect();
        }
    }

    /**
     * After using a given BLE device, the app must call this method to ensure resources are
     * released properly.
     */
    public void close() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.close();
        mBluetoothGatt = null;
    }

    public void send(byte[] data) {
        if (mNusRxCharacteristic == null) {
            mCallbacks.onError(ERROR_WRITE_CHARACTERISTIC, 0);
            return;
        }
        mNusRxCharacteristic.setValue(data);
        mBluetoothGatt.writeCharacteristic(mNusRxCharacteristic);
    }

    public void enableReceiveNotification() {
        if (mBluetoothGatt == null) {
            return;
        }
        mBluetoothGatt.setCharacteristicNotification(mNusTxCharacteristic, true);

        List<BluetoothGattDescriptor> descriptors = mNusTxCharacteristic.getDescriptors();
        for (BluetoothGattDescriptor dp : descriptors) {
            dp.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
            mBluetoothGatt.writeDescriptor(dp);
        }
    }
}
