/*******************************************************************************
 * Copyright (c) 2013 Nordic Semiconductor. All Rights Reserved.
 * 
 * The information contained herein is property of Nordic Semiconductor ASA. Terms and conditions of usage are described in detail in NORDIC SEMICONDUCTOR STANDARD SOFTWARE LICENSE AGREEMENT.
 * Licensees are granted free, non-transferable use of the information. NO WARRANTY of ANY KIND is provided. This heading must NOT be removed from the file.
 ******************************************************************************/
package com.x7chen.dev.l58tool.dfu;

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.List;
import java.util.UUID;

/**
 * DFUManager class performs BluetoothGatt operations for connection, service discovery, enabling notification and writing to characteristics All operations required to connect to device on DFU mode
 * and uploading file are performed here DfuActivity implements DFUManagerCallbacks in order to receive callbacks regarding BluetoothGatt operations
 */
public class DfuManager {
	private final static String TAG = "DFUManager";

	private DfuManagerCallbacks mCallbacks;
	private BluetoothGatt mBluetoothGatt;
	private BluetoothDevice mDevice;

	private boolean isDFUServiceFound = false;
	private boolean isNotificationEnable = false;
	private boolean isFileSizeWritten = false;
	private boolean isEnablePacketNotificationWritten = false;
	private boolean isReceiveFirmwareImageWritten = false;
	private boolean mStopSendingPacket = false;

	private boolean isLastPacket = false;
	private long mFileSize = 0;
	private long mTotalPackets = 0;
	private long mPacketNumber = 0;
	HexInputStream mFileStream;
	private final int BYTES_IN_ONE_PACKET = 20;

	public final static UUID DFU_SERVICE_UUID = UUID.fromString("00001530-1212-efde-1523-785feabcd123");
	public final static UUID DFU_CONTROLPOINT_CHARACTERISTIC_UUID = UUID.fromString("00001531-1212-efde-1523-785feabcd123");
	public final static UUID DFU_PACKET_CHARACTERISTIC_UUID = UUID.fromString("00001532-1212-efde-1523-785feabcd123");
	public final static UUID DFU_STATUS_REPORT_CHARACTERISTIC_UUID = UUID.fromString("00001533-1212-efde-1523-785feabcd123");
	public static final UUID CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

	public final static String ERROR_DISCOVERY_SERVICE = "Error on discovering services";
	public final static String ERROR_WRITE_CHARACTERISTIC = "Error on writing characteristic";
	public final static String ERROR_FILE_LENGTH = "Invalid File Length";
	public final static String ERROR_FILE_TRANSFER = "File transfer failed";
	public final static String ERROR_FILE_VALIDATION = "File validation failed";
	public final static String ERROR_WRITE_DESCRIPTOR = "Error on writing descriptor";
	public final static String ERROR_FILE_OPEN = "Error on openning file";
	public final static String ERROR_FILE_CLOSE = "Error on closing file";
	public final static String ERROR_FILE_READ = "Error on reading file";

	private BluetoothGattCharacteristic mDFUPacketCharacteristic, mDFUControlPointCharacteristic;

	private final int START_DFU = 1;
	private final int INITIALIZE_DFU = 2;
	private final int RECEIVE_FIRMWARE_IMAGE = 3;
	private final int VALIDATE_FIRMWARE_IMAGE = 4;
	private final int ACTIVATE_FIRMWARE_AND_RESET = 5;
	private final int SYSTEM_RESET = 6;
	private final int REPORT_RECEIVED_IMAGE_SIZE = 7;
	private final int RESPONSE = 16;
	private final int PACKET_RECEIVED_NOTIFICATION_REQUEST = 8;
	private final int NUMBER_OF_PACKETS = 1;
	private final int PACKET_RECEIVED_NOTIFICATION = 17;
	private final int RECEIVED_OPCODE = 16;

	private static DfuManager managerInstance = null;

	/**
	 * singleton implementation of DFUManager class
	 */
	public static synchronized DfuManager getDFUManager() {
		if (managerInstance == null) {
			managerInstance = new DfuManager();
		}
		return managerInstance;
	}

	/**
	 * Callbacks for activity {DfuActivity} that implements DFUManagerCallbacks interface activity use this method to register itself for receiving callbacks
	 */
	public void setGattCallbacks(DfuManagerCallbacks callbacks) {
		mCallbacks = callbacks;
	}

	public BluetoothDevice getDevice() {
		return mDevice;
	}

	public void connect(Context context, BluetoothDevice device) {
		Log.d(TAG, "Connecting device");
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
	 * BluetoothGatt callbacks for connection/disconnection, service discovery, receiving notification, etc
	 */
	private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {

		@Override
		public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {
			if (newState == BluetoothProfile.STATE_CONNECTED) {
				Log.i(TAG, "Device connected");
				mBluetoothGatt.discoverServices();
				//This will send callback to DfuActivity when device get connected
				mCallbacks.onDeviceConnected();
			} else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
				Log.i(TAG, "Device disconnected");
				// try to refresh device cache on the device. The refresh method is hidden and may be removed in the future
				try {
					final Method refresh = gatt.getClass().getMethod("refresh");
					if (refresh != null) {
						final boolean success = (Boolean) refresh.invoke(gatt);
						Log.d(TAG, "Device cache refresh result: " + success);
					}
				} catch (final Exception e) {
					// do nothing
				}

				//This will send callback to DfuActivity when device get disconnected
				mCallbacks.onDeviceDisconnected();
			}
		}

		@Override
		public void onServicesDiscovered(BluetoothGatt gatt, int status) {
			isDFUServiceFound = false;
			if (status == BluetoothGatt.GATT_SUCCESS) {
				List<BluetoothGattService> services = gatt.getServices();
				for (BluetoothGattService service : services) {
					Log.d(TAG, "Found Service: " + service.getUuid());
					if (service.getUuid().equals(DFU_SERVICE_UUID)) {
						Log.d(TAG, "DFU Service found!");
						isDFUServiceFound = true;
						mDFUControlPointCharacteristic = service.getCharacteristic(DFU_CONTROLPOINT_CHARACTERISTIC_UUID);
						mDFUPacketCharacteristic = service.getCharacteristic(DFU_PACKET_CHARACTERISTIC_UUID);
					}
				}
				if (isDFUServiceFound) {
					//This will send callback to DfuActicity when DFU Service is found in device
					mCallbacks.onDFUServiceFound();
				} else {
					mCallbacks.onDeviceNotSupported();
					gatt.disconnect();
				}
			} else {
				mCallbacks.onError(ERROR_DISCOVERY_SERVICE, status);
			}
		}

		@Override
		public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {

			if (status == BluetoothGatt.GATT_SUCCESS) {
				//After enabling notification on Control Point 
				//StartDFU {1} will be written on Control Point Characteristic
				//Here we check if StartDFU is written successfully and then 
				//file size will be written to DFU Packet Characteristic and then
				//notification will be received on onCharacteristicChanged() BluetoothGatt Callback
				if (characteristic.getUuid().equals(DFU_CONTROLPOINT_CHARACTERISTIC_UUID) && !isFileSizeWritten) {
					Log.d(TAG, "successfully written startDFU and now writing file size");
					writeFileSize();
					isFileSizeWritten = true;
				} else if (characteristic.getUuid().equals(DFU_CONTROLPOINT_CHARACTERISTIC_UUID) && !isEnablePacketNotificationWritten) {
					Log.d(TAG, "successfully written Packet received notification and now writing receive firmware image");
					receiveFirmwareImage();
					isEnablePacketNotificationWritten = true;
				} else if (characteristic.getUuid().equals(DFU_CONTROLPOINT_CHARACTERISTIC_UUID) && !isReceiveFirmwareImageWritten) {
					Log.d(TAG, "successfully written ReceiveFirmwareImage and now writing file");
					startUploadingFile();
					isReceiveFirmwareImageWritten = true;
				}
			} else {
				Log.e(TAG, ERROR_WRITE_CHARACTERISTIC + " [" + characteristic.getUuid() + "] Error code: " + status);
				mCallbacks.onError(ERROR_WRITE_CHARACTERISTIC, status);
			}
		}

		@Override
		public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
			final int OPERATION_SUCCESS = 1;
			long receivedBytes = 0;
			int errorStatus;

			//First byte {characteristic.getValue()[0]}  of received characteristic values is Received Opcode = 16
			//Second byte {characteristic.getValue()[1]} of received characteristic values is Requested Opcode
			//Third byte {characteristic.getValue()[2]} of received characteristic values is status byte where
			//status byte = 1 means operation is performed successfully
			int request, opCode;
			opCode = characteristic.getValue()[0];
			request = characteristic.getValue()[1];

			//Here we check if File Size is written successfully and then 
			//notification for received packet will be enabled 
			if (opCode == RECEIVED_OPCODE && request == START_DFU) {
				Log.d(TAG, "Received notification for StartDFU");
				if (characteristic.getValue()[2] == OPERATION_SUCCESS) {
					Log.d(TAG, "File length is valid: " + characteristic.getValue()[2]);
					enablePacketNotification();
				} else {
					errorStatus = characteristic.getValue()[2];
					Log.e(TAG, ERROR_FILE_LENGTH + " (" + errorStatus + ")");
					mCallbacks.onError(ERROR_FILE_LENGTH, errorStatus);
				}
			}
			//This is packet received notification
			//This returns total number of bytes successfully transfered so far 
			//here we transfer next packet of 20 bytes
			else if (opCode == PACKET_RECEIVED_NOTIFICATION) {
				Log.d(TAG, "Received Notification for sent Packet");
				int firstByte = (characteristic.getValue()[1]) & 0x00FF;
				int secondByte = (characteristic.getValue()[2]) & 0x00FF;
				int thirdByte = (characteristic.getValue()[3]) & 0x00FF;
				int forthByte = (characteristic.getValue()[4]) & 0x00FF;

				receivedBytes = (long) (firstByte | (secondByte << 8) | (thirdByte << 16) | (forthByte << 24)) & 0xFFFFFFFF;
				Log.d(TAG, "Bytes received in Packet: " + receivedBytes);
				mCallbacks.onFileTranfering(receivedBytes);
				if (!isLastPacket && !mStopSendingPacket) {
					sendPacket();
				} else {
					Log.d(TAG, "last packet notification received");
				}
			}
			//After all the bytes of file has been transfered this notification will be received
			// if file has been transfered successfully the we will validate transfered file 
			else if (opCode == RECEIVED_OPCODE && request == RECEIVE_FIRMWARE_IMAGE) {
				Log.d(TAG, "File has been transfered");
				if (characteristic.getValue()[2] == OPERATION_SUCCESS) {
					Log.d(TAG, "Successful File transfer!");
					mCallbacks.onFileTransferCompleted();
					validateFirmware();
				} else {
					errorStatus = characteristic.getValue()[2];
					Log.d(TAG, ERROR_FILE_TRANSFER + errorStatus);
					mCallbacks.onError(ERROR_FILE_TRANSFER, errorStatus);
				}
			}
			//After sending file validation this notification will be received
			//if file has been validated successfully then we will activate and reset device 
			else if (opCode == RECEIVED_OPCODE && request == VALIDATE_FIRMWARE_IMAGE) {
				Log.d(TAG, "Transfered file has been validated");
				if (characteristic.getValue()[2] == OPERATION_SUCCESS) {
					Log.d(TAG, "Successful File Transfer Validation!");
					mCallbacks.onFileTransferValidation();
					activateAndReset();
					isNotificationEnable = false;
				} else {
					errorStatus = characteristic.getValue()[2];
					Log.e(TAG, ERROR_FILE_VALIDATION + errorStatus);
					mCallbacks.onError(ERROR_FILE_VALIDATION, errorStatus);
				}
			}
		}

		@Override
		public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
			if (status == BluetoothGatt.GATT_SUCCESS) {
				if (isNotificationEnable) {
					startDFU();
				} else {
					Log.d(TAG, "Notification is disabled!");
				}
			} else {
				Log.e(TAG, ERROR_WRITE_DESCRIPTOR + " (" + status + ")");
				int errorStatus = status;
				mCallbacks.onError(ERROR_WRITE_DESCRIPTOR, errorStatus);
			}
		}
	};

	/**
	 * Enabling notification on Control Point Characteristic
	 */
	public void enableNotification() {
		Log.d(TAG, "Enable Notification");
		mBluetoothGatt.setCharacteristicNotification(mDFUControlPointCharacteristic, true);
		BluetoothGattDescriptor descriptor = mDFUControlPointCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
		descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
		mBluetoothGatt.writeDescriptor(descriptor);
		isNotificationEnable = true;
	}

	/**
	 * disable notification on Control Point Characteristic
	 */
	public void disableNotification() {
		if (isNotificationEnable) {
			Log.d(TAG, "Disable Notification");
			mBluetoothGatt.setCharacteristicNotification(mDFUControlPointCharacteristic, false);
			BluetoothGattDescriptor descriptor = mDFUControlPointCharacteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_DESCRIPTOR_UUID);
			descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
			mBluetoothGatt.writeDescriptor(descriptor);
			isNotificationEnable = false;
		}
	}

	/**
	 * After enabling notification on Control Point StartDFU = {1} will be written on Control Point Characteristic
	 */
	private void startDFU() {
		Log.d(TAG, "startDFU");
		if (isDFUServiceFound) {
			mDFUControlPointCharacteristic.setValue(START_DFU, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
			Log.d(TAG, "writing start DFU value");
			mBluetoothGatt.writeCharacteristic(mDFUControlPointCharacteristic);
			isFileSizeWritten = false;
		}
	}

	/**
	 * After StartDFU size of file will be written on DFU Packet Characteristic
	 */
	private void writeFileSize() {
		Log.d(TAG, "writeFileSize");
		if (isDFUServiceFound) {
			mDFUPacketCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
			mDFUPacketCharacteristic.setValue((int) mFileSize, BluetoothGattCharacteristic.FORMAT_UINT32, 0);
			Log.d(TAG, "writing File size");
			mBluetoothGatt.writeCharacteristic(mDFUPacketCharacteristic);
		}
	}

	/**
	 * After writing file size, Packet notification will be enabled A notification will be sent by receiving device after n number of received packets here n = 1 value contains three bytes first byte
	 * for opcode = {8} and rest of two bytes for number of packets with Least significant byte comes first
	 */
	private void enablePacketNotification() {
		Log.d(TAG, "Enable Packet Notification");
		byte[] value = { PACKET_RECEIVED_NOTIFICATION_REQUEST, NUMBER_OF_PACKETS, 0 };
		mDFUControlPointCharacteristic.setValue(value);
		mBluetoothGatt.writeCharacteristic(mDFUControlPointCharacteristic);
	};

	/**
	 * After enabling Packet notification Receive Firmware Image = {3} will be written on Control Point Characteristic
	 */

	private void receiveFirmwareImage() {
		Log.d(TAG, "sending Receive Firmware Image message");
		mDFUControlPointCharacteristic.setValue(RECEIVE_FIRMWARE_IMAGE, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
		mBluetoothGatt.writeCharacteristic(mDFUControlPointCharacteristic);
	}

	/**
	 * After Receive Firmware Image we will start uploading file by sending each time packet of 20 bytes and wait for packet Received notification
	 */
	private void startUploadingFile() {
		Log.d(TAG, "Preparing to send file");
		sendPacket();
		mCallbacks.onFileTransferStarted();
	}

	/**
	 * After the whole file has been transfered we will validate the uploaded file by writing {4} on Control Point Characteristic
	 */
	private void validateFirmware() {
		mDFUControlPointCharacteristic.setValue(VALIDATE_FIRMWARE_IMAGE, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
		Log.d(TAG, "writing validate Firmware value");
		mBluetoothGatt.writeCharacteristic(mDFUControlPointCharacteristic);
	}

	/**
	 * After validation of uploaded file we will activate and reset DFU device now device on DFU mode will be disconnected to Phone and start advertising the uploaded BluetoothLE Service
	 */
	private void activateAndReset() {
		mDFUControlPointCharacteristic.setValue(ACTIVATE_FIRMWARE_AND_RESET, BluetoothGattCharacteristic.FORMAT_UINT8, 0);
		Log.d(TAG, "writing activate and reset value");
		mBluetoothGatt.writeCharacteristic(mDFUControlPointCharacteristic);
	}

	private int getNumberOfPackets() {
		int numOfPackets = (int) (mFileSize / BYTES_IN_ONE_PACKET);
		if ((mFileSize % BYTES_IN_ONE_PACKET) > 0) {
			numOfPackets++;
		}
		return numOfPackets;
	}

	private int getBytesInLastPacket() {
		return (int) (mFileSize % BYTES_IN_ONE_PACKET);
	}

	/**
	 * Here selected IntelHex file will be converted into Binary format
	 */
	public void openFile(InputStream stream) {
		try {
			mPacketNumber = 0;
			//HexInputStream class convert file format from Hex to Binary
			mFileStream = new HexInputStream(stream);
			mFileSize = mFileStream.available();
			mTotalPackets = getNumberOfPackets();
			Log.d(TAG, "File Size: " + mFileSize);
		} catch (IOException e) {
			Log.e(TAG, ERROR_FILE_OPEN + " " + e);
			mCallbacks.onError(ERROR_FILE_OPEN, 0);
		}
	}

	/**
	 * close the file stream
	 */
	public void closeFile() {
		if (mFileStream != null) {
			try {
				mFileStream.close();
				mFileStream = null;
			} catch (IOException e) {
				Log.e(TAG, ERROR_FILE_CLOSE + " " + e.toString());
				mCallbacks.onError(ERROR_FILE_CLOSE, 0);
			}
		}
	}

	/**
	 * reads the next packet with max 20 bytes
	 */
	private byte[] getNextPacket() {
		try {
			byte[] buffer = new byte[20];
			mFileStream.readPacket(buffer);
			return buffer;
		} catch (IOException e) {
			Log.e(TAG, ERROR_FILE_READ);
			mCallbacks.onError(ERROR_FILE_READ, 0);
		}
		return null;
	}

	/**
	 * write packet, 20 bytes, on DFU Packet characteristic last packet can have less than 20 bytes
	 */
	private void sendPacket() {
		mPacketNumber++;
		//If last packet then send only remaining bytes 
		if (mPacketNumber == mTotalPackets) {
			Log.d(TAG, "This is last packet, packet number: " + mPacketNumber);
			isLastPacket = true;
			byte[] buffer = getNextPacket();
			byte[] data = new byte[getBytesInLastPacket()];
			for (int i = 0; i < getBytesInLastPacket(); i++) {
				data[i] = buffer[i];
			}
			mDFUPacketCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
			mDFUPacketCharacteristic.setValue(data);
			mBluetoothGatt.writeCharacteristic(mDFUPacketCharacteristic);
		}
		// otherwise send packet of 20 bytes
		else {
			mDFUPacketCharacteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE);
			mDFUPacketCharacteristic.setValue(getNextPacket());
			mBluetoothGatt.writeCharacteristic(mDFUPacketCharacteristic);
		}
	}

	public void close() {
		closeFile();
		if (mBluetoothGatt != null) {
			mBluetoothGatt.close();
			mBluetoothGatt = null;
		}
		managerInstance = null;
	}

	public void closeBluetoothGatt() {
		if (mBluetoothGatt != null) {
			mBluetoothGatt.close();
			mBluetoothGatt = null;
		}
	}

	public void resetStatus() {
		isFileSizeWritten = false;
		isEnablePacketNotificationWritten = false;
		isReceiveFirmwareImageWritten = false;
		isDFUServiceFound = false;
		isNotificationEnable = false;
		isLastPacket = false;
		mStopSendingPacket = false;
	}

	/**
	 * systemReset will reset device in DFU mode if something goes wrong during uploading so that uploading can be started from the beginning
	 */
	public void systemReset() {
		final byte[] value = { SYSTEM_RESET };
		mDFUControlPointCharacteristic.setValue(value);
		mBluetoothGatt.writeCharacteristic(mDFUControlPointCharacteristic);
	}

	public long getFileSize() {
		return mFileSize;
	}

	/**
	 * pause uploading file
	 */
	public void stopSendingPacket() {
		mStopSendingPacket = true;
	}

	/**
	 * resume paused uploading
	 */
	public void resumeSendingPacket() {
		mStopSendingPacket = false;
		sendPacket();
	}
}
