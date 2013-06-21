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

#include <stdio.h>
#include <string.h>

#include "framingEngine.h"

/////////////////////////////
// Region: Public functions
/////////////////////////////

void fe_init(void) {
	memset((void*) &fe_state, 0, sizeof(struct fe_state_struct));

	fe_state.rxState = fe_receiveState_start;
}

void fe_handleByteReceived(uint8_t val) {

	// If it's a start-byte abandon what we were
	// doing previously and reset state back to start.
	if (val == START_BYTE &&
		fe_state.rxState != fe_receiveState_dataEscape) {
		fe_state.rxState = fe_receiveState_size;
		fe_state.incomingBufferPos = 0;
		return;
	}

	switch (fe_state.rxState) {
		// More than most likely it's data
		// or an escape character:
		case fe_receiveState_data:
			if (val == ESCAPE_BYTE) {
				fe_state.rxState = fe_receiveState_dataEscape;
				fe_state.receiveSize--;
				break;
			}
			// INTENTIONAL FALL THROUGH
		case fe_receiveState_dataEscape:
			fe_state.incomingBuffer[fe_state.incomingBufferPos++] = val;
			if (fe_state.incomingBufferPos == fe_state.receiveSize) {
				fe_checkPacket();
				fe_state.rxState = fe_receiveState_start;
			}
			break;

		// The byte after the start byte is the size:
		case fe_receiveState_size:
			if (val > FE_RECEIVEMAX) {
				fe_state.rxState = fe_receiveState_start;
				break;
			}
			fe_state.receiveSize = val;
			fe_state.rxState = fe_receiveState_header;
			break;

		case fe_receiveState_header:
			fe_state.header = val;
			fe_state.rxState = fe_receiveState_data;
			break;

		default:
			break;
	}
}

// Gets called when the lower layer has transmitted the buffer we passed to it
void fe_handleBufferSent (void) {
	fe_state.sendingPacket = 0;
	fe_state.packetSentCb();
}

// Send a packet. First it builds the outgoing packet in the outBuf and then
// starts the process of sending it.
// Returns an error if there is already a packet transmission in progress
fe_error_e fe_sendPacket (packet_t* pkt) {
	uint8_t i;
	uint8_t sum;
	uint8_t error;

	if (fe_state.sendingPacket) {
		return FE_BUSY;
	}

	fe_state.sendingPacket = 1;

	// Fill the outgoing buffer from the given packet
	fe_state.outBufIdx = 0;
	fe_state.outBuf[fe_state.outBufIdx++] = START_BYTE;
/*	fe_state.outBuf[fe_state.outBufIdx++] = pkt->length; // length
	fe_state.outBuf[fe_state.outBufIdx] = (pkt->power_down & 0x1) << 7 |
	                                      (pkt->ack_requested & 0x1) << 6 |
	                                      (pkt->retries & 0x3) << 4 |
	                                      (pkt->type & 0xF);
	sum = fe_state.outBuf[fe_state.outBufIdx++];

	// Copy the data portion of the packet to the buffer, inserting escapes
	// where necessary.
	for (i=0; i<pkt->length; i++) {
		if (pkt->data[i] == START_BYTE || pkt->data[i] == ESCAPE_BYTE) {
			fe_state.outBuf[fe_state.outBufIdx++] = ESCAPE_BYTE;
			sum += ESCAPE_BYTE;
			fe_state.outBuf[1]++; // add 1 to length byte
		}

		fe_state.outBuf[fe_state.outBufIdx++] = pkt->data[i];
		sum += pkt->data[i];
	}
	fe_state.outBuf[fe_state.outBufIdx] = sum; // checksum
	fe_state.outBufIdx = 0;
	fe_state.outBufLen = fe_state.outBuf[1] + 4; // start byte, length, header, chksum

	// Start sending the packet
	error = fe_state.bufferSender(fe_state.outBuf, fe_state.outBufLen);
	if (error > 0) {
		return FE_FAIL;
	}
*/
	fe_state.outBuf[1] = 0xCC;
	error = fe_state.bufferSender(fe_state.outBuf, 2);

	return FE_SUCCESS;
}

//////////////////////////////////
// Region: Callback Subscriptions
//////////////////////////////////

void fe_registerPacketReceivedCb (fe_packetReceived* cb) {
	fe_state.packetReceivedCb = cb;
}

void fe_registerPacketSentCb (fe_callback* cb) {
	fe_state.packetSentCb = cb;
}

void fe_registerBufferSender (fe_bufferSender* sender) {
	fe_state.bufferSender = sender;
}

////////////////////////////
// Region: Helper functions
////////////////////////////

void fe_checkPacket() {
	uint8_t sum = fe_state.header;
	uint8_t i = 0;
	uint8_t incomingPktPos = 0;

	// Compute the simple byte-addition checksum.
	for (i = 0; i < fe_state.incomingBufferPos - 1; i++) {
		sum += fe_state.incomingBuffer[i];
	}

	// Fill incomingPktBuffer with the data.
	if (sum == fe_state.incomingBuffer[fe_state.incomingBufferPos - 1]) {

	//	if ((LED_OUT & LED_0) == 0){
	//		LED_OUT ^= LED_0;
	//	} else {
	//		LED_OUT &= ~LED_0;
	//	}

		// Ignore the checksum on the end.
		for (i = 0; i < fe_state.incomingBufferPos - 1; i++) {
			fe_state.incomingPkt.data[incomingPktPos++] =
				fe_state.incomingBuffer[i];
		}

		fe_state.incomingPkt.length = incomingPktPos;

		// Process the header fields
		if (fe_state.header & 0x40) fe_state.incomingPkt.ack_requested = 1;
		fe_state.incomingPkt.retries = (fe_state.header & 0x30) >> 4;
		fe_state.incomingPkt.type = fe_state.header & 0x0f;

		fe_state.packetReceivedCb(&fe_state.incomingPkt);
	}
}
