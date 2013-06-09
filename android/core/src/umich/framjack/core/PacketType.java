package umich.framjack.core;

public enum PacketType {
	DATA,   // General data type packet
	ACK,    // Ack for the last packet sent
	BOOTED, // Message signaling the device powered on from reset
	RESUMED // Message signaling the device woke up from sleep
}
