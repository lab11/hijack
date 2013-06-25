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

public class SerialDecoder {
	private final AudioReceiver _audioReceiver;

	private enum TransmitState { IDLE, PENDING, DATA };
	private enum receiveState { IDLE, DATA, DATANEXT };

	// Receiver State
	private int _lastEdgeLength = 0;

	private int _currentEdgeLength = 0;
	private boolean _currentEdgeHigh = false;

	//TODO: REFACTOR THIS OUT
	private int _ioBaseFrequency = 613;

	private int _threshold = 7;

	// FOR THE MSP430FR5969:
	//private int _threshold = 7;

	// FOR THE MSP430F1611:
	//private int _threshold = 12;
	
	private enum edgeSpace {SINGLE, DOUBLE};
	private enum edgeResult {BIT, NULL};
	
	// Keep track of the times between edges in the preamble of the message.
	// This lets us determine the baud rate on the fly.
	private LimitedArray timesBetweenEdges = new LimitedArray(4);
	// The average of timesBetweenEdges
	private int avgEdgePeriod;
	// Whether the last edge let us set a bit or not
	private edgeResult lastEdgeResult = edgeResult.BIT;

	private int _deltaT = 0;

	private receiveState rxState = receiveState.IDLE;

	private int _rxByte = 0;
	private int _rxBits = 0;

	// Transmit State
	private TransmitState _txState = TransmitState.IDLE;
	private int _txByte = 0;
	private int _txBytePosition = 0;
	private int _txBitPosition;

	private final int _idleCycles = 20;
	private int _idleCycleCount = 0;

	// Byte Buffers
	private final List<Integer> _incoming = new ArrayList<Integer>();
	private final List<Integer> _outgoing = new ArrayList<Integer>();

	// Listeners for byte sent + byte received events
	private OnBytesAvailableListener _bytesAvailableListener = null;
	private OnByteSentListener _byteSentListener = null;


	//////////////////////////////
	// Transmit State Machine
	/////////////////////////////
	private boolean transmitIdle() {
		return _txBitPosition == 1;
	}

	private boolean transmitPending() {
		if (_txBitPosition == 0) {
			_txState = TransmitState.DATA;
			return transmitData();
		} else {
			return true;
		}
	}

	private boolean transmitData() {
		boolean ret = ((_txByte >> _txBytePosition) & 0x1) == 1;

		if (_txBitPosition == 1) {
			_txBytePosition++;

			if (_txBytePosition == 10) {

				synchronized(this) {
					_idleCycleCount = 0;
					_txState = TransmitState.IDLE;
					_txBytePosition = 0;
					notifyByteSent();
				}
			}
		}
		else {
			ret = !ret;
		}

		return ret;
	}

	/////////////////////////////
	// Receive State Machine
	/////////////////////////////
	private void receiveIdle (int timeSinceLastEdge, EdgeType edge) {
		avgEdgePeriod = timesBetweenEdges.average();
		//System.out.println("AVERAGE TIME: " + avgEdgePeriod);
		
		// Check if we:
		//  - just saw a rising edge
		//  - and that that edge was twice as far from the previous edge as the
		//    preamble edges were from each other
		//  - we have seen at least four previous edges (from the preamble)
		//  - the previous edges were all roughly the same time from each other
		//    (as they would be in the preamble)
		if (edge == EdgeType.RISING &&
			isClose(avgEdgePeriod*2, timeSinceLastEdge) &&
			timesBetweenEdges.length() == 4 &&
			timesBetweenEdges.variance() < 4) {
			 // This is a start bit!
			rxState = receiveState.DATA;
			System.out.println("got start bit");
		
		} else {
			// Still waiting for the start bit, just record this edge so we
			// can continue to track the average time between edges (aka the
			// baud rate).
			timesBetweenEdges.insert(timeSinceLastEdge);
		}
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
		if (isClose(avgEdgePeriod, timeSinceLastEdge)) {
			thisEdgeSpacing = edgeSpace.SINGLE;
		} else if (isClose(avgEdgePeriod*2, timeSinceLastEdge)) {
			thisEdgeSpacing = edgeSpace.DOUBLE;
		} else if (timeSinceLastEdge > avgEdgePeriod*3) {
			// End of the packet
			rxState = receiveState.IDLE;
			System.out.println("EOP");
			return;
		} else {
			// This is a spurious edge
			return;
		}
		
		if (thisEdgeSpacing == edgeSpace.DOUBLE) {
			if (edge == EdgeType.FALLING) {
				// add a 1 to the packet
				System.out.println("1");
			} else { // rising edge
				// add a 0 to the packet
				System.out.println("0");
			}
			lastEdgeResult = edgeResult.BIT;
		} else if (thisEdgeSpacing == edgeSpace.SINGLE) {
			if (lastEdgeResult == edgeResult.BIT) {
				// This edge is useless
				lastEdgeResult = edgeResult.NULL;
			} else if (lastEdgeResult == edgeResult.NULL) {
				if (edge == EdgeType.RISING) {
					// This edge is a 0
					System.out.println("0");
				} else { // falling edge
					// This edge is a 1
					System.out.println("1");
				}
				lastEdgeResult = edgeResult.BIT;
			}
		}
	}



	/////////////////////////////
	// Public Functions
	/////////////////////////////

	public SerialDecoder() {
		_audioReceiver = new AudioReceiver();
		_audioReceiver.registerIncomingSink(_incomingSink);
		_audioReceiver.registerOutgoingSource(_outgoingSink);
	}

	public void start() {
		_audioReceiver.startAudioIO();
	}

	public void stop() {
		_audioReceiver.stopAudioIO();
	}

	public void sendByte(int val) {
		synchronized(this) {
			_outgoing.add(val);
		}
	}

	public int bytesAvailable() {
		synchronized(this) {
			return _incoming.size();
		}
	}

	public int readByte() {
		synchronized(this) {
			if (_incoming.size() == 0) {
				return -1;
			}
			int ret = _incoming.get(0);
			_incoming.remove(0);
			return ret;
		}
	}

	public void setPowerFreq(int freq) {
		_audioReceiver.setPowerFrequency(freq);
	}

	public void setIoFrq(int freq) {
		_ioBaseFrequency = freq;

		//TODO: STOP HARDCODING THIS FREQ
		_threshold = (int) (22050 / _ioBaseFrequency / 2 * 0.45);

		_audioReceiver.setTransmitFrequency(freq);
	}

	/////////////////////////////
	// Listener Functions
	/////////////////////////////
	// WARNING: These are not thread-safe. We are using a
	// guarantee by AudioInterface that events will occur
	// sequentially and originate from a single thread.

	public void registerBytesAvailableListener(OnBytesAvailableListener _listener) {
		_bytesAvailableListener = _listener;
	}

	public void registerByteSentListener(OnByteSentListener _listener) {
		_byteSentListener = _listener;
	}

	private void notifyBytesAvailable(int numberBytes) {
		if (_bytesAvailableListener != null) {
			_bytesAvailableListener.onBytesAvailable(numberBytes);
		}
	}

	private void notifyByteSent() {
		if (_byteSentListener != null) {
			_byteSentListener.onByteSent();
		}
	}

	/////////////////////////////
	// Helper Functions
	/////////////////////////////

	private boolean isClose(int value, int desired) {
		//return value < desired + _threshold && value > desired - _threshold;
		return value < desired + 4 && value > desired - 4;
	}

	private byte calcParity(int in) {
		byte parity = 0;
		for (int i = 0; i < 8; i++) {
			parity ^= ((in >> i) & 1);
		}
		return parity;
	}

	private void advanceReceiveDataState() {
		if (_rxBits == 10) {
			if (calcParity(_rxByte >> 1) ==  (_rxByte >> 9)) {
			//	System.out.println("checksum PASSED.");
				synchronized(this) {
					_incoming.add((_rxByte >> 1) & 0xFF);
					notifyBytesAvailable(_incoming.size());
				}
			}
			else
			{
				System.out.println("checksum failed.");
			}
			rxState = receiveState.IDLE;
		}
		else {
			rxState = receiveState.DATA;
		}
	}

	/////////////////////////////
	// AudioInterface Listeners
	////////////////////////////

	private final IncomingSink _incomingSink = new IncomingSink() {
		@Override
		public void handleNextBit(int transistionPeriod, EdgeType edge) {
			//isHighToLow = !isHighToLow;
			System.out.println("Tran: " + transistionPeriod + " HL: " + edge);
		//	_currentEdgeLength = transistionPeriod;
		//	_currentEdgeHigh = isHighToLow;
			switch (rxState) {
				case IDLE:
					receiveIdle(transistionPeriod, edge);
					break;
				case DATA:
					receiveData(transistionPeriod, edge);
					break;
				default:
					break;
			}
		//	_lastEdgeLength = _currentEdgeLength;*/
		}
	};

	private final OutgoingSource _outgoingSink = new OutgoingSource() {
		@Override
		public boolean getNextBit() {
			boolean ret = false;
			switch (_txState) {
			case DATA:
				ret = transmitData();
				break;
			case IDLE:
				if(_idleCycleCount < _idleCycles) {
					_idleCycleCount++;
				}

				synchronized(SerialDecoder.this) {
					if (_outgoing.size() > 0 && _idleCycleCount == _idleCycles) {
						int byteToSend = _outgoing.get(0);
						_outgoing.remove(0);

						int parity = calcParity(byteToSend);
						_txByte = (byteToSend << 1) | (parity << 9);
						_txState = TransmitState.PENDING;
					}
				}

				ret = transmitIdle();
				break;
			case PENDING:
				ret = transmitPending();
				break;
			default:
				break;
			}
			_txBitPosition = _txBitPosition == 0 ? 1 : 0;

			return ret;
		}
	};
}
