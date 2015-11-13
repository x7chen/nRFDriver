/*******************************************************************************
 * Copyright (c) 2013 Nordic Semiconductor. All Rights Reserved.
 * 
 * The information contained herein is property of Nordic Semiconductor ASA.
 * Terms and conditions of usage are described in detail in NORDIC SEMICONDUCTOR STANDARD SOFTWARE LICENSE AGREEMENT.
 * Licensees are granted free, non-transferable use of the information. NO WARRANTY of ANY KIND is provided. 
 * This heading must NOT be removed from the file.
 ******************************************************************************/
package com.x7chen.dev.l58tool.dfu;

public interface DfuManagerCallbacks {

	public void onDeviceConnected();

	public void onDFUServiceFound();

	public void onDeviceDisconnected();

	public void onFileTransferStarted();

	public void onFileTranfering(long sizeTransfered);

	public void onFileTransferCompleted();

	public void onFileTransferValidation();

	public void onError(String message, int errorCode);

	public void onDeviceNotSupported();
}
