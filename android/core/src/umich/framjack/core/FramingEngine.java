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

package umich.framjack.core;

import java.util.ArrayList;

public class FramingEngine {
	private IncomingPacketListener _incomingListener;
	private OutgoingByteListener _outgoingListener;
	
	private final int _receiveMax = 18;
	private final int START_BYTE = 0xCC;
	private final int ESCAPE_BYTE = 0xDC;
	
	private enum ReceiveState {START, HEADER1, HEADER2, DATA, DATA_ESCAPE};
	private ReceiveState _receiveState = ReceiveState.START;
	private ArrayList<Integer> _receiveBuffer;
	private int dataLength;    // Length of the data payload to higher layers
	private int headerMeta;    // power down, ack, retries, type
	
	private ArrayList<Integer> _transmitBuffer;
	
	public FramingEngine() {
		_receiveBuffer = new ArrayList<Integer>();
		_transmitBuffer = new ArrayList<Integer>();
	}
	
	public void receiveByte(int val) {
		//System.out.print(val + " ");
		if (val == START_BYTE && _receiveState != ReceiveState.DATA_ESCAPE) {
			// We got a start of packet byte. Clear the buffer and prepare to
			// receiver the header.
			_receiveState = ReceiveState.HEADER1;
			_receiveBuffer.clear();
			return;
		}
		
		switch (_receiveState) {
			case DATA:
				if (val == ESCAPE_BYTE) {
					// The next byte is escaped and this byte won't be in the
					// actual packet data.
					_receiveState = ReceiveState.DATA_ESCAPE;
					dataLength--;
					break;
				}
				_receiveBuffer.add(val);
				if (_receiveBuffer.size() == dataLength) {
					// Got all the bytes for the payload
					checkPacket();
					_receiveState = ReceiveState.START;
				}
				break;
			case DATA_ESCAPE:
				_receiveState = ReceiveState.DATA;
				_receiveBuffer.add(val);
				if (_receiveBuffer.size() == dataLength) {
					checkPacket();
					_receiveState = ReceiveState.START;
				}
				break;
			case HEADER1:
				if (val > _receiveMax) {
					// Trying to receive a packet that is too large
					System.out.println("Trying to receive a LARGE packet: " + val + " bytes.");
					_receiveState = ReceiveState.START;
					break;
				}
				dataLength = val;
				_receiveState = ReceiveState.HEADER2;
				break;
			case HEADER2:
				headerMeta = val;
				_receiveState = ReceiveState.DATA;
				break;
			default:
				break;
		}
	}
	
	private void checkPacket() {
		int sum = 0;
		for (int i = 0; i < _receiveBuffer.size() - 1; i++) {
			//System.out.print(Integer.toHexString(_receiveBuffer.get(i)) + " ");
			sum += _receiveBuffer.get(i);
		}
		//System.out.println(Integer.toHexString(_receiveBuffer.get(_receiveBuffer.size() - 1)));
		
		if ((sum & 0xFF) == _receiveBuffer.get(_receiveBuffer.size() - 1)) {
			int[] retArray = new int[_receiveBuffer.size() - 1];
			for (int i = 0; i < _receiveBuffer.size() - 1; i++) {
				retArray[i] = _receiveBuffer.get(i);
			}
			_incomingListener.IncomingPacketReceive(retArray);
		}		
	}
	
	public void transmitByte(int val) {
		if (val == 0xCC) {
			_transmitBuffer.add(0xDD);
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
	
	public void registerIncomingPacketListener(IncomingPacketListener listener) {
		_incomingListener = listener;
	}
	
	public void registerOutgoingByteListener(OutgoingByteListener listener) {
		_outgoingListener = listener;
	}
	
	public interface IncomingPacketListener {
		public abstract void IncomingPacketReceive(int[] packet);
	}
	
	public interface OutgoingByteListener {
		public abstract void OutgoingByteTransmit(int[] outgoingRaw); 
	}
}
