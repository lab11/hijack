package umich.hijack.core;

import java.util.Arrays;

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
	public PacketType typeId;    // The ID of the functional use of the packet
	public int[] data;           // The packet payload

	// The seq no keeps track of this packet so we can check for duplicates
	// and for which packet is being acked
	private int _seqNo;

	// Storage for a packet in raw buffer form. This is used when receiving
	// a packet (each bit is stored) and when transmitting a packet (the buffer
	// is filled from the fields in the packet).
	private final int[] _buf;
	// Which bit is currently being accessed in the raw buffer
	private int _bufIdx;


	private final static int MAX_PACKET_LEN = 256;
	private final static int MIN_PACKET_LEN = 2;

	public final static int PKT_TYPE_OFFSET = 0;
	public final static int PKT_TYPE_MASK = 0xF;
	public final static int PKT_RETRIES_OFFSET = 4;
	public final static int PKT_RETRIES_MASK = 0x3 << 4;
	public final static int PKT_ACKREQ_OFFSET = 6;
	public final static int PKT_ACKREQ_MASK = 0x1 << 6;
	public final static int PKT_POWERDOWN_OFFSET = 7;
	public final static int PKT_POWERDOWN_MASK = 0x1 << 7;

	public final static int HEADER_LEN = 2;
	public final static int CHECKSUM_LEN = 1;
	public final static int DISPATCH_BYTE_IDX = 0;
	public final static int SEQ_NO_IDX = 1;

	public Packet () {
		data = new int[MAX_PACKET_LEN];
		_buf = new int[MAX_PACKET_LEN+2];
		reset();
	}

	public void setSequenceNumber (int seqno) {
		_seqNo = seqno;
	}


	///////////
	// Functions for receiving a packet
	///////////

	public void reset () {
		Arrays.fill(_buf, 0);
		_bufIdx = 0;
	}

	// Add a bit to the internal receive buffer.
	public void addBit (int val) {
		if (val == 0) {
			// Just need to increment the offset for a 0 bit
			_bufIdx++;
			return;
		}

		// Add a 1 to the correct position
		int byteIdx = _bufIdx / 8;
		int bitIdx = _bufIdx - (byteIdx*8);

		if (byteIdx > MAX_PACKET_LEN) {
			// TODO: handle long packets better than this
			return;
		}

		_buf[byteIdx] |= (1 << bitIdx);
		_bufIdx++;
	}

	// Parses _buf to fill in the packet fields.
	// Returns true if the packet is valid, false if not.
	public boolean processReceivedPacket () {
		int numBytes = _bufIdx / 8; // how many bytes we received in the last packet

		if (numBytes < MIN_PACKET_LEN) {
			// This is an invalid packet.
			// Need to get at least the header and checksum bytes
			return false;
		} else if (numBytes > MAX_PACKET_LEN) {
			// Too long
			return false;
		}

		// Disect header
		powerDown    = ((_buf[DISPATCH_BYTE_IDX] & PKT_POWERDOWN_MASK) >> PKT_POWERDOWN_OFFSET) == 1;
		ackRequested = ((_buf[DISPATCH_BYTE_IDX] & PKT_ACKREQ_MASK) >> PKT_ACKREQ_OFFSET) == 1;
		sentCount    = ((_buf[DISPATCH_BYTE_IDX] & PKT_RETRIES_MASK) >> PKT_RETRIES_OFFSET) + 1;
		typeId       = PacketType.values()[(_buf[DISPATCH_BYTE_IDX] & PKT_TYPE_MASK)];

		if (typeId == PacketType.ACK) {
			// Ack packets are just a header byte and a checksum
			length = 0;
		} else {
			// Set seq no
			_seqNo = _buf[SEQ_NO_IDX];

			// Set length
			length = numBytes - HEADER_LEN - CHECKSUM_LEN;

			// Copy data
			for (int i=0; i<length; i++) {
				data[i] = _buf[i+HEADER_LEN];
			}
		}

		// Check checksum
		if (_calculateChecksum() == _buf[numBytes-CHECKSUM_LEN]) {

//System.out.println("pkt checksum passed");

// Print the received packet
System.out.println("PACKET");
System.out.println("   power down: " + powerDown);
System.out.println("   ack req:    " + ackRequested);
System.out.println("   sent count: " + sentCount);
System.out.println("   type id:    " + typeId);
System.out.print  ("   data:       ");
for (int i=0; i<length; i++) {
	System.out.print(Integer.toHexString(data[i]) + " ");
}
System.out.println();

			return true;
		} else {

System.out.println("pkt FAILED checksum");
for (int i=0; i<numBytes; i++) {
	System.out.print("0x" + Integer.toHexString(_buf[i]) + " ");
}
System.out.println();
System.out.println("calc checksum: " + Integer.toHexString(_calculateChecksum()));

			return false;
		}
	}

	// Takes the packet fields and creates a packet buffer in _buf that
	// can be transmitted to the peripheral.
	public void compressToBuffer () {

		_buf[DISPATCH_BYTE_IDX] = 0;
		_buf[DISPATCH_BYTE_IDX] |= (((powerDown)?1:0 << PKT_POWERDOWN_OFFSET) & PKT_POWERDOWN_MASK);
		_buf[DISPATCH_BYTE_IDX] |= (((ackRequested)?1:0 << PKT_ACKREQ_OFFSET) & PKT_ACKREQ_MASK);
		_buf[DISPATCH_BYTE_IDX] |= ((sentCount << PKT_RETRIES_OFFSET) & PKT_RETRIES_MASK);
		_buf[DISPATCH_BYTE_IDX] |= (typeId.ordinal() & PKT_TYPE_MASK);

		if (typeId != PacketType.ACK) {
			_buf[SEQ_NO_IDX] = _seqNo;
		}

		for (int i=0; i<length; i++) {
			_buf[i+HEADER_LEN] = data[i];
		}

		_buf[length+HEADER_LEN] = _calculateChecksum();

		_bufIdx = 0;
	}

	// Returns the next bit in the packet buffer. Throws
	// IndexOutOfBoundsException when there are no more bits in the array.
	public int getBit () throws IndexOutOfBoundsException {

		// Check if we are past the end of the buffer
		if (_bufIdx >= ((length+2)*8)) {
			throw new IndexOutOfBoundsException();
		}

		int byteIdx = _bufIdx / 8;
		int bitIdx = _bufIdx - (byteIdx*8);

		_bufIdx++;

		return (_buf[byteIdx] >> bitIdx) & 0x1;
	}

	private int _calculateChecksum () {
		// Calculate checksum and copy data
		int sum = 0;
		for (int i=0; i<length+HEADER_LEN; i++) {
			sum += _buf[i];
		}
		return sum & 0xFF;
	}


}
