package umich.framjack.core;

public enum PacketType {
	DATA,   // General data type packet
	ACK,    // Ack for the last packet sent
	BOOTED, // Message signaling the device powered on from reset
	RESUMED // Message signaling the device woke up from sleep
}


/*

Packet Type IDs:

0: reserved. Used internally for all packets that have an invalid packet type id
1: ACK packets
2: BOOTED packets
3: RESUMED packets
4: reserved.
5: reserved
6: reserved
7: reserved
8-15: application specific.


*/