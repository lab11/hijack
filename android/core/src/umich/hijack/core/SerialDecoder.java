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
	private final AudioReceiver _audioReceiver;

	private enum TransmitState { IDLE, PREAMBLE, DATA, POSTAMBLE };
	private enum receiveState { IDLE, DATA };

	private static final int NUM_PREAMBLE_BITS = 20;

	// Receiver State

	//TODO: REFACTOR THIS OUT
	//private int _ioBaseFrequency = 613;

	//private int _threshold = 7;

	// FOR THE MSP430FR5969:
	//private int _threshold = 7;

	// FOR THE MSP430F1611:
	//private int _threshold = 12;

	private enum edgeSpace {SINGLE, DOUBLE};
	private enum edgeResult {BIT, NULL};

	// Keep track of the times between edges in the preamble of the message.
	// This lets us determine the baud rate on the fly.
	private final LimitedArray _timesBetweenEdges = new LimitedArray(4);
	// The average of timesBetweenEdges
	private double _avgEdgePeriod;
	// Whether the last edge let us set a bit or not
	private edgeResult _lastEdgeResult = edgeResult.BIT;

	private receiveState _rxState = receiveState.IDLE;

	// The packet currently being received. Only one packet can be received
	// at a time, so we do not keep an array.
	private Packet _inPacket;

	// Transmit State

	private final static int START_BIT = 0;
	private final static int PREAMBLE_BIT = 1;

	// Array of packets to be transmitted. Many packets can be queued up.
	private final List<Packet> _outgoing = new ArrayList<Packet>();

	// The packet currently being transmitted.
	private Packet _outPacket;

	// Number of bits to send in the preamble
	private int _txPreambleBitLen = NUM_PREAMBLE_BITS;
	private int _txPostambleBitLen = 4;

	// Whether to send the first or the second half of the manchester bit
	private int _txBitHalf;

	private SignalLevel _txLastManBit;

	private TransmitState _txState = TransmitState.IDLE;


	// Listeners for packet receive and sent events
	private PktRecvCb _PacketReceivedCallback = null;
	private PktSentCb _PacketSentCallback = null;



	//////////////////////////////
	// Transmit State Machine
	/////////////////////////////

	// Transmit just zeroes when in the idle state.
	private SignalLevel transmitIdle () {
		return SignalLevel.FLOATING;
	}

	private SignalLevel transmitPreamble () {
		if (_txBitHalf == 1) {
			if (_txPreambleBitLen == 0) {
				_txState = TransmitState.DATA;
			}
			return (_txLastManBit == SignalLevel.HIGH) ? SignalLevel.LOW : SignalLevel.HIGH;
		}

		_txPreambleBitLen--;

		if (_txPreambleBitLen == 0) {
			return _int2man(START_BIT);
		} else {
			// Send preamble bits
			return _int2man(PREAMBLE_BIT);
		}
	}

	private SignalLevel transmitData () {
		if (_txBitHalf == 1) {
			return (_txLastManBit == SignalLevel.HIGH) ? SignalLevel.LOW : SignalLevel.HIGH;
		}

		try {
			SignalLevel manbit = _int2man(_outPacket.getBit());
			return manbit;
		} catch (IndexOutOfBoundsException excp) {
			_txPostambleBitLen = 4;
			_txState = TransmitState.POSTAMBLE;
			return SignalLevel.LOW;
		}
	}

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
	private void receiveIdle (int timeSinceLastEdge, EdgeType edge) {
		_avgEdgePeriod = _timesBetweenEdges.average();
		//System.out.println("AVERAGE TIME: " + avgEdgePeriod);


		// Check if we:
		//  - just saw a rising edge
		//  - and that that edge was twice as far from the previous edge as the
		//    preamble edges were from each other
		//  - we have seen at least four previous edges (from the preamble)
		//  - the previous edges were all roughly the same time from each other
		//    (as they would be in the preamble)
		if (edge == EdgeType.RISING &&
			isClose(_avgEdgePeriod*2, timeSinceLastEdge) &&
			_timesBetweenEdges.length() == 4) {


			if (_timesBetweenEdges.variance() < 5.0) {
			 // This is a start bit!
			_rxState = receiveState.DATA;
			//_audioReceiver.packetReceiveStart();
			System.out.println("got start bit " + _timesBetweenEdges.variance() + " " + _avgEdgePeriod + " " + timeSinceLastEdge);
			// Generate a new packet object to receive this packet into
			_inPacket = new Packet();

			return;

			} else {
				// nearly
				System.out.println("near start bit: " + _timesBetweenEdges.variance() + " " + _avgEdgePeriod);
			}

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
/*
		// Determine if the edge we got is a single or double baud
		if (isClose(_avgEdgePeriod, timeSinceLastEdge)) {
			thisEdgeSpacing = edgeSpace.SINGLE;
		} else if (isClose(_avgEdgePeriod*2, timeSinceLastEdge)) {
			thisEdgeSpacing = edgeSpace.DOUBLE;
		} else if (timeSinceLastEdge > _avgEdgePeriod*3) {
			// End of the packet
			_rxState = receiveState.IDLE;
			System.out.println("EOP");
			boolean valid = _inPacket.processReceivedPacket();
			if (valid) {
				_notifyReceivedPacket(_inPacket);
			}
			return;
		} else {
			// This is a spurious edge
			return;
		}
*/

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
			//_audioReceiver.packetReceiveStop();
			System.out.println("EOP");
			boolean valid = _inPacket.processReceivedPacket();
			if (valid) {
				_notifyReceivedPacket(_inPacket);
			}
			return;
		} else {
			// This is a spurious edge
			_rxState = receiveState.IDLE;
			//_audioReceiver.packetReceiveStop();
			System.out.println("EOP - BOOO");
			return;
		}

		if (thisEdgeSpacing == edgeSpace.DOUBLE) {
			if (edge == EdgeType.FALLING) {
				// add a 1 to the packet
				System.out.println("1");
				_inPacket.addBit(1);
			} else { // rising edge
				// add a 0 to the packet
				System.out.println("0");
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
					System.out.println("0");
					_inPacket.addBit(0);
				} else { // falling edge
					// This edge is a 1
					System.out.println("1");
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
	//	_ioBaseFrequency = freq;

		//TODO: STOP HARDCODING THIS FREQ
	//	_threshold = (int) (22050 / _ioBaseFrequency / 2 * 0.45);

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

	private boolean isClose(int value, int desired) {
		//return value < desired + _threshold && value > desired - _threshold;
		return value < desired + 5 && value > desired - 5;
	}

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
System.out.println("Tran: " + transistionPeriod + " HL: " + edge);
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
System.out.println("got packet to send");
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
