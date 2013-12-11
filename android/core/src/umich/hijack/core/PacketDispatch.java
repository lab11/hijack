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
import java.util.Queue;

public class PacketDispatch implements PktTransmitter, PktRecvCb, PktSentCb {

	/////////////////////
	// Constants
	/////////////////////

	private final static int MAX_PACKET_TYPES = 16;

	/////////////////////
	// Callbacks
	/////////////////////

	// List of packet callback functions that are waiting for packets of
	// various types to come in.
	private final ArrayList<ArrayList<PktRecvCb>> _recvListeners;


	/////////////////////
	// Other State
	/////////////////////

	// Object that can actually transmit packets
	private PktTransmitter _pktTx;

	// Keep track of the global sequence number so that all new packets have
	// a sequence number
	private int _sequenceNumber = 1;

	// Keep track of incoming packets. They are kept in a queue so that they
	// go out in order. The head of the queue is transmitted first. If that
	// packet needs an ack, it stays in the queue (and blocks subsequent
	// packets) until an ack is received. Packets that do not need acks
	// are transmitted and removed immediately. All packets (ack requested
	// or not) queue so that they go out in order.
	private Queue<Packet> packets;


	// Init
	public PacketDispatch() {
		// Create the data structure for callbacks
		_recvListeners = new ArrayList<ArrayList<PktRecvCb>>(MAX_PACKET_TYPES);
		for (int i=0; i<MAX_PACKET_TYPES; i++) {
			_recvListeners.add(new ArrayList<PktRecvCb>());
		}
	}

	///////////////////////////////
	// Register Callback Functions
	///////////////////////////////

	// Register with the dispatcher what should actually transmit packets.
	public void registerPacketTransmitter (PktTransmitter ptx) {
		_pktTx = ptx;
	}

	// Register a callback handler for a particular packet type. Each packet
	// type can have multiple handlers in case multiple services want to know
	// about a given packet type.
	public void registerIncomingPacketListener(PktRecvCb listener,
	                                           PacketType packetTypeID) {
		_recvListeners.get(packetTypeID.ordinal()).add(listener);
	}

	/////////////////////
	// Public functions
	/////////////////////

	// Transmit a packet
	@Override
	public void sendPacket (Packet p) {
		p.setSequenceNumber(_sequenceNumber++);
		packets.add(p);
		_transmit();
	}

	// Take the top of the queue and transmit
	private void _transmit () {
		while (true) {
			Packet p = packets.peek();

			if (p == null) {
				// No packet to send
				break;
			}

			if (p.ackRequested == false) {
				_pktTx.sendPacket(p);
				packets.remove();
			} else {
				_pktTx.sendPacket(p);
				// TODO: set timer to retransmit this packet
				break;
			}
		}
	}


	// The insertion point for packets into the dispatch layer. After being
	// decoded and detected as valid packets, received packets enter the
	// dispatch layer here.
	@Override
	public void recvPacket (Packet p) {

		// Check if the packet needs an ack, and if so send it to the lower
		// layer.
		if (p.ackRequested) {
			Packet ack = new Packet();
			ack.length = 0;
			ack.ackRequested = false;
			ack.powerDown = false;
			ack.sentCount = 0;
			_pktTx.sendPacket(ack);
		}

		// TODO duplicate detection

		// Check if we got an ack, and if so remove whichever packet we
		// were waiting on an ack for and then transmit the next packet
		if (p.typeId == PacketType.ACK) {
			packets.remove();
			_transmit();
		}

		// Pass packet to all waiting listeners
		for (PktRecvCb l : _recvListeners.get(p.typeId.ordinal())) {
			l.recvPacket(p);
		}
	}

	@Override
	public void sentPacket () {
		//packet was sent
	}

}
