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
	memset((void*) &fe, 0, sizeof(struct fe_state_struct));
}

void fe_handleBufferReceived (uint8_t* buf, uint8_t len) {

	// Check that the checksum is correct
	uint8_t sum = 0;
	int i;

	for (i=0; i<len-1; i++) {
		sum += buf[i];
	}

	if (sum != buf[len-1]) {
		// checksum failed
		return;
	}


	// Parse the buffer and create the packet
	fe.rxPacket.length        = len - 2; // Subtract 2 for the header bit and the checksum
	fe.rxPacket.power_down    = (buf[0] & PKT_POWERDOWN_MASK) >> PKT_POWERDOWN_OFFSET;
	fe.rxPacket.ack_requested = (buf[0] & PKT_ACKREQ_MASK) >> PKT_ACKREQ_OFFSET;
	fe.rxPacket.retries       = (buf[0] & PKT_RETRIES_MASK) >> PKT_RETRIES_OFFSET;
	fe.rxPacket.type          = (buf[0] & PKT_TYPE_MASK);
	memcpy(fe.rxPacket.data, buf+1, fe.rxPacket.length);

	fe.packetReceivedCb(&fe.rxPacket);
}

// Gets called when the lower layer has transmitted the buffer we passed to it
void fe_handleBufferSent (void) {
	fe.sendingPacket = 0;
	fe.packetSentCb();
}

// Send a packet. First it builds the outgoing packet in the outBuf and then
// starts the process of sending it.
// Returns an error if there is already a packet transmission in progress
fe_error_e fe_sendPacket (packet_t* pkt) {
	uint8_t i;
	uint8_t sum;
	uint8_t error;

	if (fe.sendingPacket) {
		return FE_BUSY;
	}

	fe.sendingPacket = 1;

	// Fill the outgoing buffer from the given packet
	fe.outBufIdx = 0;
	fe.outBuf[fe.outBufIdx] = (pkt->power_down << PKT_POWERDOWN_OFFSET) & PKT_POWERDOWN_MASK |
	                          (pkt->ack_requested << PKT_ACKREQ_OFFSET) & PKT_ACKREQ_OFFSET |
	                          (pkt->retries << PKT_RETRIES_OFFSET) & PKT_RETRIES_MASK |
	                          (pkt->type & PKT_TYPE_MASK);
	sum = fe.outBuf[fe.outBufIdx++];

	// Copy the data portion of the packet to the buffer, inserting escapes
	// where necessary.
	for (i=0; i<pkt->length; i++) {
		fe.outBuf[fe.outBufIdx++] = pkt->data[i];
		sum += pkt->data[i];
	}
	fe.outBuf[fe.outBufIdx] = sum; // checksum
	fe.outBufLen = pkt->length + 2; // header, chksum

	// Start sending the packet
	error = fe.bufferSender(fe.outBuf, fe.outBufLen);
	if (error > 0) {
		return FE_FAIL;
	}

	return FE_SUCCESS;
}

//////////////////////////////////
// Region: Callback Subscriptions
//////////////////////////////////

void fe_registerPacketReceivedCb (fe_packetReceived* cb) {
	fe.packetReceivedCb = cb;
}

void fe_registerPacketSentCb (fe_packetSent* cb) {
	fe.packetSentCb = cb;
}

void fe_registerBufferSender (fe_bufferSender* sender) {
	fe.bufferSender = sender;
}

