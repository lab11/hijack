package umich.hijack.core;

import java.util.Arrays;

// A class object for a Jackit packet.

public class Packet {

	public int length;           // Length of the data payload
	public boolean ackRequested; // Set to true if the sender of this packet
	                             // requests an ack be sent.
	public boolean ackReceived;  // Set to true if this packet was acked.
	public boolean powerDown;    // Set to true if the sender is going to enter
	                             // a low power state after this packet is
	                             // transmitted.
	public int sentCount;        // The number of times this packet has been
	                             // sent.
	public int typeId;           // The ID of the functional use of the packet
	public int[] data;           // The packet payload

	private final int[] inBuf;   // Storage for a packet being received
	private int inBitOffset;     // Which bit is currently being received


	private final static int MAX_PACKET_LEN = 256;

	public final static int PKT_TYPE_OFFSET = 0;
	public final static int PKT_TYPE_MASK = 0xF;
	public final static int PKT_RETRIES_OFFSET = 4;
	public final static int PKT_RETRIES_MASK = 0x3 << 4;
	public final static int PKT_ACKREQ_OFFSET = 6;
	public final static int PKT_ACKREQ_MASK = 0x1 << 6;
	public final static int PKT_POWERDOWN_OFFSET = 7;
	public final static int PKT_POWERDOWN_MASK = 0x1 << 7;

	public Packet () {
		data = new int[MAX_PACKET_LEN];
		inBuf = new int[MAX_PACKET_LEN+2];
		reset();
	}


	///////////
	// Functions for receiving a packet
	///////////

	public void reset () {
		Arrays.fill(inBuf, 0);
		inBitOffset = 0;
	}

	public void addBit (int val) {
		if (val == 0) {
			// Just need to increment the offset for a 0 bit
			inBitOffset++;
			return;
		}

		// Add a 1 to the correct position
		int byteIdx = inBitOffset / 8;
		int bitIdx = inBitOffset - (byteIdx*8);

		inBuf[byteIdx] |= (1 << bitIdx);
		inBitOffset++;
	}

	public void processReceivedPacket () {
		int numBytes = inBitOffset / 8; // how many bytes we received in the last packet

		if (numBytes < 2) {
			// This is an invalid packet.
			// Need to get at least the header and checksum bytes
			return;
		}

		// Disect header
		powerDown = ((inBuf[0] & PKT_POWERDOWN_MASK) >> PKT_POWERDOWN_OFFSET) == 1;
		ackRequested = ((inBuf[0] & PKT_ACKREQ_MASK) >> PKT_ACKREQ_OFFSET) == 1;
		sentCount = ((inBuf[0] & PKT_RETRIES_MASK) >> PKT_RETRIES_OFFSET) + 1;
		typeId = (inBuf[0] & PKT_TYPE_MASK);

		// Set length
		length = numBytes - 2;

		// Calculate checksum and copy data
		int sum = 0;
		for (int i=0; i<length+1; i++) {
			sum += inBuf[i];
			if (i > 0) {
				data[i-1] = inBuf[i];
			}
		}

		// Check checksum
		if ((sum & 0xFF) == inBuf[numBytes-1]) {
			System.out.println("pkt checksum passed");
		} else {
			System.out.println("pkt FAILED checksum");
		}
	}


}
