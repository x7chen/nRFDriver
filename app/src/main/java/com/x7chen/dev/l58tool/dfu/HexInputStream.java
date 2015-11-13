/*******************************************************************************
 * Copyright (c) 2013 Nordic Semiconductor. All Rights Reserved.
 * 
 * The information contained herein is property of Nordic Semiconductor ASA.
 * Terms and conditions of usage are described in detail in NORDIC SEMICONDUCTOR STANDARD SOFTWARE LICENSE AGREEMENT.
 * Licensees are granted free, non-transferable use of the information. NO WARRANTY of ANY KIND is provided. 
 * This heading must NOT be removed from the file.
 ******************************************************************************/
package com.x7chen.dev.l58tool.dfu;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * HexInputStream class accepts hex file as InputStream and converts it into bin file format
 */
public class HexInputStream extends FilterInputStream {
	private final int LINE_LENGTH = 16;
	private final byte[] localBuf;
	private int localPos;
	private int size;
	private int pos;

	protected HexInputStream(final InputStream in) {
		super(in);
		localBuf = new byte[LINE_LENGTH];
		localPos = LINE_LENGTH; // we are at the end of the local buffer, new one must be obtained
		size = localBuf.length;
	}

	/**
	 * Fills the buffer with next bytes from the stream.
	 * 
	 * @return the size of the buffer
	 * @throws IOException
	 */
	public int readPacket(byte[] buffer) throws IOException {
		int i = 0;
		while (i < buffer.length) {
			if (localPos < size) {
				buffer[i++] = localBuf[localPos++];
				continue;
			}

			size = readBuffer();
			if (size == 0)
				break; // end of file reached
		}
		return i;
	}

	@Override
	public int read() throws IOException {
		throw new UnsupportedOperationException("Please, use readPacket() method instead");
	}

	@Override
	public int read(byte[] buffer) throws IOException {
		return readPacket(buffer);
	}

	@Override
	public int read(byte[] buffer, int offset, int count) throws IOException {
		throw new UnsupportedOperationException("Please, use readPacket() method instead");
	}

	@Override
	public int available() throws IOException {
		final int newLineBytes = 2; // CR LF
		final int firstLineSize = 15 + newLineBytes;
		final int lastTwoLinesSize = 30 + 2 * newLineBytes;
		final int dataSize = in.available() - firstLineSize - lastTwoLinesSize;

		// each 2 HEX characters for 1 byte
		final int fullLineDataBytes = 2 * 16;
		// 1  - comma
		// 8  - 2 bytes - data length
		//    - 4 bytes - address
		//    - 2 bytes - line type
		// 2  - checksum
		// new line bytes
		final int fullLineOtherBytes = 1 + 8 + 2 + newLineBytes;
		final int fullLineBytes = fullLineDataBytes + fullLineOtherBytes;
		final int lines = dataSize / fullLineBytes;
		final int lastDataLineSize = dataSize % fullLineBytes;
		return (lines * fullLineDataBytes + ((lastDataLineSize > 0) ? (lastDataLineSize - fullLineOtherBytes) : 0)) / 2; // one byte is 2 HEX characters
	}

	private int readBuffer() throws IOException {
		// end of file reached
		if (pos == -1)
			return 0;

		// skip first line, except end of line
		if (pos == 0)
			pos += in.skip(15);

		// temporary value
		int b = 0;

		// skip end of line
		while (true) {
			b = in.read();
			pos++;

			if (b != '\n' && b != '\r') {
				break;
			}
		}

		/*
		 * Each line starts with comma (':')
		 * Data is written in HEX, so each 2 ASCII letters give one byte.
		 * After the comma there is one byte (2 HEX signs) with line length (normally 10 -> 0x10 -> 16 bytes -> 32 HEX characters)
		 * After that there is a 4 byte of an address. This part may be skipped.
		 * There is a packet type after the address (1 byte = 2 HEX characters). 00 is the valid data. Other values can be skipped when converting to BIN file.
		 * Then goes n bytes of data followed by 1 byte (2 HEX chars) of checksum, which is also skipped in BIN file.
		 */
		checkComma(b); // checking the comma at the beginning
		final int lineSize = readByte(); // reading the length of the data in this line
		pos += 2;
		pos += in.skip(4); // skipping address part
		final int type = readByte(); // reading the line type
		pos += 2;

		// if the line type is no longer data type (0x00), we've reached the end of the file
		if (type != 0) {
			pos = -1;
			return 0;
		}
		// otherwise read lineSize bytes or fill the whole buffer
		for (int i = 0; i < localBuf.length && i < lineSize; ++i) {
			b = readByte();
			pos += 2;
			localBuf[i] = (byte) b;
		}
		pos += in.skip(2); // skip the checksum
		localPos = 0;

		return lineSize;
	}

	@Override
	public synchronized void reset() throws IOException {
		super.reset();

		pos = 0;
		localPos = 0;
	}

	private void checkComma(final int comma) throws IOException {
		if (comma != ':')
			throw new IOException("Not a HEX file");
	}

	private int readByte() throws IOException {
		final int first = asciiToInt(in.read());
		final int second = asciiToInt(in.read());

		return first << 4 | second;
	}

	private int asciiToInt(final int ascii) {
		if (ascii >= 'A')
			return ascii - 0x37;

		if (ascii >= '0')
			return ascii - '0';
		return -1;
	}
}
