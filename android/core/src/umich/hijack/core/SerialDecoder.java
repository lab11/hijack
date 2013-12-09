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
import java.util.List;

public class SerialDecoder implements PktTransmitter {

	// Object that connects to the audio hardware of the phone. Also handles
	// edge detection.
	private final AudioReceiver _audioReceiver;

	//////////////////
	// Constants
	//////////////////

	// How many preamble bits to transmit before sending the start bit
	private static final int NUM_PREAMBLE_BITS = 20;
	// How many bits to send after the last byte of the packet
	private static final int NUM_POSTAMBLE_BITS = 4;

	// The values of the different critical bits in packet construction
	private final static int START_BIT = 0;
	private final static int PREAMBLE_BIT = 1;

	/////////////////
	// ENUMS
	/////////////////

	// States for the tx and rx state machines
	private enum TransmitState { IDLE, PREAMBLE, DATA, POSTAMBLE };
	private enum receiveState { IDLE, DATA };

	// Used to note whether we just saw a single baud or double baud
	private enum edgeSpace {SINGLE, DOUBLE};

	// An edge that is marked as a bit corresponds to one where we decoded
	// the next a bit and a null edge is just a transition in the manchester
	// encoding that did not determine a bit.
	private enum edgeResult {BIT, NULL};

	///////////////////
	// Receive State
	///////////////////

	// Keep track of the times between edges in the preamble of the message.
	// This lets us determine the baud rate on the fly.
	private final LimitedArray _timesBetweenEdges = new LimitedArray(4);
	// The average of timesBetweenEdges
	private double _avgEdgePeriod;
	// Whether the last edge let us set a bit or not
	private edgeResult _lastEdgeResult;

	// What the RX state machine is doing
	private receiveState _rxState = receiveState.IDLE;

	// The packet currently being received. Only one packet can be received
	// at a time, so we do not keep an array.
	private Packet _inPacket;

	//////////////////////
	// Transmit State
	//////////////////////

	// Array of packets to be transmitted. Many packets can be queued up.
	private final List<Packet> _outgoing = new ArrayList<Packet>();

	// The packet currently being transmitted.
	private Packet _outPacket;

	// Keep track of how many more bits we need to send for the preamble
	// and postamble.
	private int _txPreambleBitLen;
	private int _txPostambleBitLen;

	// Whether to send the first or the second half of the manchester bit
	private int _txBitHalf;
	// What the value of the last manchester bit is (so we can send the
	// opposite for the next bit)
	private SignalLevel _txLastManBit;

	// TX state
	private TransmitState _txState = TransmitState.IDLE;

	//////////////////////
	// Callbacks
	//////////////////////

	// Listeners for packet receive and sent events
	private PktRecvCb _PacketReceivedCallback = null;
	private PktSentCb _PacketSentCallback = null;


	//////////////////////////////
	// Transmit State Machine
	/////////////////////////////

	// Transmit just a "floating" value when not transmitting a packet.
	private SignalLevel transmitIdle () {
		return SignalLevel.FLOATING;
	}

	// Transmit a few bits before the start bit so that the microcontroller
	// can get an average baud rate.
	private SignalLevel transmitPreamble () {
		if (_txBitHalf == 1) {
			if (_txPreambleBitLen == 0) {
				_txState = TransmitState.DATA;
			}
			return (_txLastManBit == SignalLevel.HIGH)
					? SignalLevel.LOW : SignalLevel.HIGH;
		}

		_txPreambleBitLen--;

		if (_txPreambleBitLen == 0) {
			return _int2man(START_BIT);
		} else {
			// Send preamble bits
			return _int2man(PREAMBLE_BIT);
		}
	}

	// After the preamble transmit all of the data bits
	private SignalLevel transmitData () {
		if (_txBitHalf == 1) {
			return (_txLastManBit == SignalLevel.HIGH)
					? SignalLevel.LOW : SignalLevel.HIGH;
		}

		try {
			SignalLevel manbit = _int2man(_outPacket.getBit());
			return manbit;
		} catch (IndexOutOfBoundsException excp) {
			_txPostambleBitLen = NUM_POSTAMBLE_BITS;
			_txState = TransmitState.POSTAMBLE;
			return SignalLevel.LOW;
		}
	}

	// After the data, wait a couple bit (transmit just low values) and then
	// transmit a 1. This gap and then bit signifies the packet has ended.
	private SignalLevel transmitPostamble () {
		if (_txBitHalf == 1) {
			if (_txPostambleBitLen > 0) {
				// If we are in the midst of sending postamble zeros, just
				// send zeroes.
				return SignalLevel.LOW;
			} else if (_txPostambleBitLen == 0) {
				// The last bit is an actual manchester bit, so we should
				// treat it as such.
				_txState = TransmitState.IDLE;
				_notifySentPacket();
				return (_txLastManBit == SignalLevel.HIGH) ? SignalLevel.LOW : SignalLevel.HIGH;
			}
		}

		_txPostambleBitLen--;

		if (_txPostambleBitLen == 0) {
			// Send a 1 bit so the receiver knows the packet is over.
			return SignalLevel.HIGH;
		} else {
			// Otherwise just send all zeroes
			return SignalLevel.LOW;
		}
	}

	/////////////////////////////
	// Receive State Machine
	/////////////////////////////

	// Watch incoming edges and wait for a start bit
	private void receiveIdle (int timeSinceLastEdge, EdgeType edge) {
		_avgEdgePeriod = _timesBetweenEdges.average();

		// Check if we:
		//  - just saw a rising edge
		//  - and that that edge was twice as far from the previous edge as the
		//    preamble edges were from each other
		//  - we have seen at least four previous edges (from the preamble)
		//  - the previous edges were all roughly the same time from each other
		//    (as they would be in the preamble)
		if (edge == EdgeType.RISING &&
			isClose(_avgEdgePeriod*2, timeSinceLastEdge) &&
			_timesBetweenEdges.length() == 4 &&
			_timesBetweenEdges.variance() < 5.0) {
			 // This is a start bit!

			_rxState = receiveState.DATA;
			_lastEdgeResult = edgeResult.BIT;
			// Generate a new packet object to receive this packet into
			_inPacket = new Packet();

			return;
		}

		// Still waiting for the start bit, just record this edge so we
		// can continue to track the average time between edges (aka the
		// baud rate).
		_timesBetweenEdges.insert(timeSinceLastEdge);
	}

	// The primary function of this function is to determine if this edge
	// represents a 0, 1 or neither.
	// The logic looks like this:
	//   | time since last edge | edge type | previous edge result || result |
	//   ---------------------------------------------------------------------
	//   | 2 periods            | falling   | 0, 1, or -           || 1      |
	//   | 2 periods            | rising    | 0, 1, or -           || 0      |
	//   | 1 period             | rising    | 0 or 1               || -      |
	//   | 1 period             | rising    | -                    || 0      |
	//   | 1 period             | falling   | 0 or 1               || -      |
	//   | 1 period             | falling   | -                    || 1      |
	private void receiveData (int timeSinceLastEdge, EdgeType edge) {
		edgeSpace thisEdgeSpacing; // Number of bauds from the previous edge

		// Determine if the edge we got is a single or double baud
		if (timeSinceLastEdge < ((_avgEdgePeriod*3)/2)) {
			thisEdgeSpacing = edgeSpace.SINGLE;
		} else if ((timeSinceLastEdge > ((_avgEdgePeriod*3)/2)) &&
				   (timeSinceLastEdge < ((_avgEdgePeriod*5)/2))
				) {
			thisEdgeSpacing = edgeSpace.DOUBLE;
		} else if (timeSinceLastEdge > _avgEdgePeriod*3) {
			// End of the packet
			_rxState = receiveState.IDLE;
			boolean valid = _inPacket.processReceivedPacket();
			if (valid) {
				_notifyReceivedPacket(_inPacket);
			}
			return;
		} else {
			// This is a spurious edge
			_rxState = receiveState.IDLE;
			return;
		}

		if (thisEdgeSpacing == edgeSpace.DOUBLE) {
			if (edge == EdgeType.FALLING) {
				// add a 1 to the packet
				_inPacket.addBit(1);
			} else { // rising edge
				// add a 0 to the packet
				_inPacket.addBit(0);
			}
			_lastEdgeResult = edgeResult.BIT;
		} else if (thisEdgeSpacing == edgeSpace.SINGLE) {
			if (_lastEdgeResult == edgeResult.BIT) {
				// This edge is useless
				_lastEdgeResult = edgeResult.NULL;
			} else if (_lastEdgeResult == edgeResult.NULL) {
				if (edge == EdgeType.RISING) {
					// This edge is a 0
					_inPacket.addBit(0);
				} else { // falling edge
					// This edge is a 1
					_inPacket.addBit(1);
				}
				_lastEdgeResult = edgeResult.BIT;
			}
		}
	}

	/////////////////////////////
	// Public Functions
	/////////////////////////////

	public SerialDecoder() {
		_audioReceiver = new AudioReceiver();
		_audioReceiver.registerIncomingSink(_incomingSink);
		_audioReceiver.registerOutgoingSource(_outgoingSource);
	}

	public void start() {
		_audioReceiver.startAudioIO();
	}

	public void stop() {
		_audioReceiver.stopAudioIO();
	}

	// This function is called by the dispatch layer to send a packet.
	@Override
	public void sendPacket(Packet p) {
		synchronized(this) {
			_outgoing.add(p);
		}
	}

	public void setPowerFreq(int freq) {
		_audioReceiver.setPowerFrequency(freq);
	}

	public void setIoFrq(int freq) {
		_audioReceiver.setTransmitFrequency(freq);
	}

	/////////////////////////////
	// Listener Functions
	/////////////////////////////
	// WARNING: These are not thread-safe. We are using a
	// guarantee by AudioInterface that events will occur
	// sequentially and originate from a single thread.

	public void registerPacketReceivedCallback (PktRecvCb listener) {
		_PacketReceivedCallback = listener;
	}

	public void registerPacketSentCallback (PktSentCb listener) {
		_PacketSentCallback = listener;
	}

	private void _notifyReceivedPacket (Packet p) {
		if (_PacketReceivedCallback != null) {
			_PacketReceivedCallback.recvPacket(p);
		}
	}

	private void _notifySentPacket () {
		if (_PacketSentCallback != null) {
			_PacketSentCallback.sentPacket();
		}
	}

	/////////////////////////////
	// Helper Functions
	/////////////////////////////

	private boolean isClose(double value, double desired) {
		//return value < desired + _threshold && value > desired - _threshold;
		return value < desired + 5.0 && value > desired - 5.0;
	}

	private SignalLevel _int2man (int i) {
		if (i == 1) {
			return SignalLevel.HIGH;
		}
		return SignalLevel.LOW;
	}

	/////////////////////////////
	// AudioInterface Listeners
	////////////////////////////

	private final IncomingSink _incomingSink = new IncomingSink() {
		@Override
		public void handleNextBit(int transistionPeriod, EdgeType edge) {
			switch (_rxState) {
				case IDLE:
					receiveIdle(transistionPeriod, edge);
					break;
				case DATA:
					receiveData(transistionPeriod, edge);
					break;
				default:
					break;
			}
		}
	};

	private final OutgoingSource _outgoingSource = new OutgoingSource() {
		@Override
		public SignalLevel getNextManchesterBit() {
			SignalLevel ret = SignalLevel.FLOATING;

			switch (_txState) {
				case IDLE:
					// Check if there is a packet in the queue that we can
					// transmit now.
					synchronized(SerialDecoder.this) {
						if (_outgoing.size() > 0) {
							_outPacket = _outgoing.get(0);
							_outgoing.remove(0);

							_outPacket.compressToBuffer();

							_txState = TransmitState.PREAMBLE;
							_txPreambleBitLen = NUM_PREAMBLE_BITS;
						}
					}

					ret = transmitIdle();
					break;

				case PREAMBLE:
					ret = transmitPreamble();
					break;

				case DATA:
					ret = transmitData();
					break;

				case POSTAMBLE:
					ret = transmitPostamble();
					break;

				default:
					break;
			}
			_txBitHalf = (_txBitHalf == 1) ? 0 : 1;
			_txLastManBit = ret;
			return ret;
		}
	};
}
