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
	public PacketType type;      // The functional use of the packet
	public int[] data;           // The packet payload	

}
