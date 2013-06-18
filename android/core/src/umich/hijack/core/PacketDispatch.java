/*
 *  This file is part of hijack-infinity.
 *
 *  hijack-infinity is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.
 *
 *  hijack-infinity is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with hijack-infinity.  If not, see <http://www.gnu.org/licenses/>.
 */

package umich.hijack.core;

import java.util.ArrayList;

public class PacketDispatch {
	private final ArrayList<ArrayList<IncomingPacketListener>> incomingListeners;
	private OutgoingByteListener _outgoingListener;

	private final static int _receiveMax = 18;
	private final static int START_BYTE = 0xCC;
	private final static int ESCAPE_BYTE = 0xDD;

	private final static int MAX_PACKET_TYPES = 16;

	private enum ReceiveState {START, HEADER1, HEADER2, DATA, DATA_ESCAPE, CHECKSUM};
	private ReceiveState _receiveState = ReceiveState.START;
	private final ArrayList<Integer> _receiveBuffer;
	private int dataLength;    // Length of the data payload to higher layers
	private int headerMeta;    // power down, ack, retries, type
	private int checksum;

	private final ArrayList<Integer> _transmitBuffer;

	public PacketDispatch() {
		_receiveBuffer = new ArrayList<Integer>();
		_transmitBuffer = new ArrayList<Integer>();

		// Create the data structure for callbacks
		incomingListeners = new ArrayList<ArrayList<IncomingPacketListener>>(MAX_PACKET_TYPES);
		for (int i=0; i<MAX_PACKET_TYPES; i++) {
			incomingListeners.add(new ArrayList<IncomingPacketListener>());
		}
	}

	public void receiveByte(int val) {
		//System.out.print(val + " ");
		if (val == START_BYTE && _receiveState != ReceiveState.DATA_ESCAPE) {
			// We got a start of packet byte. Clear the buffer and prepare to
			// receiver the header.
		//	System.out.println("RECEIVED START BYTE " + Integer.toHexString(val));
			_receiveState = ReceiveState.HEADER1;
			_receiveBuffer.clear();
			return;
		}

		switch (_receiveState) {
			case DATA:
				if (val == ESCAPE_BYTE) {
					// The next byte is escaped and this byte won't be in the
					// actual packet data.
			//		System.out.println("ESCAPED BYTE " + Integer.toHexString(val));
					_receiveState = ReceiveState.DATA_ESCAPE;
					dataLength--;
					break;
				}
				_receiveBuffer.add(val);
				if (_receiveBuffer.size() == dataLength) {
					// Got all the bytes for the payload
					_receiveState = ReceiveState.CHECKSUM;
				}
				break;
			case DATA_ESCAPE:
				_receiveState = ReceiveState.DATA;
				_receiveBuffer.add(val);
				if (_receiveBuffer.size() == dataLength) {
					_receiveState = ReceiveState.CHECKSUM;
				}
				break;
			case HEADER1:
				if (val > _receiveMax) {
					// Trying to receive a packet that is too large
					System.out.println("Trying to receive a LARGE packet: " + val + " bytes.");
					_receiveState = ReceiveState.START;
					break;
				}
				//System.out.println("RECEIVED LENGTH 0x" + Integer.toHexString(val));
				dataLength = val;
				_receiveState = ReceiveState.HEADER2;
				break;
			case HEADER2:
				headerMeta = val;
				//System.out.println("RECEIVED HEADER2 0x" + Integer.toHexString(val));
				if (dataLength == 0) {
					_receiveState = ReceiveState.CHECKSUM;
				} else {
					_receiveState = ReceiveState.DATA;
				}
				break;
			case CHECKSUM:
				checksum = val;
				//System.out.println("RECEIVED CHECKSUM 0x" + Integer.toHexString(val));
				_receiveState = ReceiveState.START;
				processPacket();
				break;
			default:
				break;
		}
	}

	private void processPacket() {
		//System.out.println();

		System.out.print("Length: 0x" + Integer.toHexString(dataLength));
		System.out.print(" Header: 0x" + Integer.toHexString(headerMeta) + " Data: 0x");

		// Verify the checksum is correct
		int sum = headerMeta;
		for (int i = 0; i < _receiveBuffer.size(); i++) {
			System.out.print(Integer.toHexString(_receiveBuffer.get(i)));
			sum += _receiveBuffer.get(i);

			// Have to add in an escape byte to checksum if there was one in the
			// original message.
			if (_receiveBuffer.get(i) == START_BYTE || _receiveBuffer.get(i) == ESCAPE_BYTE) {
				sum += ESCAPE_BYTE;
			}
		}
		System.out.println();

		if ((sum & 0xFF) != checksum) {
			System.out.println("Received packet with failed checksum.");
			return;
		}

		// Create a new packet and fill in the header and data fields.
		Packet P = new Packet();
		P.length = _receiveBuffer.size();
		P.ackRequested = ((headerMeta & Packet.PKT_ACKREQ_MASK) >> Packet.PKT_ACKREQ_OFFSET) != 0;
		P.powerDown = ((headerMeta & Packet.PKT_POWERDOWN_MASK) >> Packet.PKT_POWERDOWN_OFFSET) != 0;
		P.sentCount = ((headerMeta & Packet.PKT_RETRIES_MASK) >> Packet.PKT_RETRIES_OFFSET) + 1;
		P.typeId = ((headerMeta & Packet.PKT_TYPE_MASK) >> Packet.PKT_TYPE_OFFSET);
		P.data = new int[P.length];
		for (int i = 0; i < P.length; i++) {
			P.data[i] = _receiveBuffer.get(i);
		}

		// Send to other layers
/*
		// Check if there is at least one listener for this incoming packet
		if (incomingListeners.get(P.typeId).size() > 0) {
			for (int i=0; i<incomingListeners.get(P.typeId).size(); i++) {
				incomingListeners.get(P.typeId).get(i).IncomingPacketReceive(P);
			}
		} else {
			// No listener registered for this packet
			for (int i=0; i<incomingListeners.get(0).size(); i++) {
				incomingListeners.get(0).get(i).IncomingPacketReceive(P);
			}
		}

		// Send all packets to all listeners on 1
		for (int i=0; i<incomingListeners.get(1).size(); i++) {
			incomingListeners.get(1).get(i).IncomingPacketReceive(P);
		}
*/
	}

	public void transmitByte(int val) {
		if (val == START_BYTE) {
			_transmitBuffer.add(ESCAPE_BYTE);
		}
		_transmitBuffer.add(val);
	}

	public void transmitEnd() {
		int[] toSend = new int[_transmitBuffer.size() + 3];
		toSend[0] = 0xDD;
		toSend[1] = _transmitBuffer.size() + 1;
		for (int i = 0; i < _transmitBuffer.size(); i++) {
			toSend[i + 2] = _transmitBuffer.get(i);
			toSend[_transmitBuffer.size() + 2] += toSend[i + 2];
		}

		toSend[_transmitBuffer.size() + 2] &= 0xFF;
		_transmitBuffer.clear();
		_outgoingListener.OutgoingByteTransmit(toSend);
	}

	// Register a callback handler for a particular packet type. Each packet
	// type can have multiple handlers in case multiple services want to know
	// about a given packet type.
	public void registerIncomingPacketListener(IncomingPacketListener listener, int packetTypeID) {
		if (packetTypeID < 0 ||  packetTypeID > MAX_PACKET_TYPES) {
			// throw an exception?
			System.out.println("Registering incoming listener: Bad packet type ID.");
			return;
		}
		incomingListeners.get(packetTypeID).add(listener);
	}

	public void registerOutgoingByteListener(OutgoingByteListener listener) {
		_outgoingListener = listener;
	}

	public interface IncomingPacketListener {
		public abstract void IncomingPacketReceive(Packet packet);
	}

	public interface OutgoingByteListener {
		public abstract void OutgoingByteTransmit(int[] outgoingRaw);
	}
}
