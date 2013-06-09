package umich.framjack.core;

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

	
	public final static int PKT_TYPE_OFFSET = 0;
	public final static int PKT_TYPE_MASK = 0xF;
	public final static int PKT_RETRIES_OFFSET = 4;
	public final static int PKT_RETRIES_MASK = 0x3 << 4;
	public final static int PKT_ACKREQ_OFFSET = 6;
	public final static int PKT_ACKREQ_MASK = 0x1 << 6;
	public final static int PKT_POWERDOWN_OFFSET = 7;
	public final static int PKT_POWERDOWN_MASK = 0x1 << 7;
}
