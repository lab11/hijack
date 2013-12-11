package umich.hijack.core;

public enum PacketType {
	INVALID,
	GLOBAL,
	ACK,    // Ack for the last packet sent
	BOOTED, // Message signaling the device powered on from reset
	RESUMED, // Message signaling the device woke up from sleep
	POWERDOWN,
	HIJACKIO
}


/*

Packet Type IDs:

0: Used internally for all packets that have an invalid packet type id
1: all packets are sent to these listeners
2: ACK packets
3: BOOTED packets
4: RESUMED packets
5: reserved.
6: reserved
7: reserved
8-15: application specific.


*/