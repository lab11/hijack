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

#ifndef __FRAMINGENGINE_H__
#define __FRAMINGENGINE_H__

#include "config.h"

#include <inttypes.h>

#include "gpio.h"
#include "hardware.h"
#include "packet.h"


////////////////////////////////////////
// Public Memebers:
////////////////////////////////////////

typedef void fe_packetSent (void);
typedef void fe_packetReceived (packet_t* pkt);
typedef uint8_t fe_bufferSender (uint8_t*, uint8_t);

typedef enum fe_error {
	FE_SUCCESS = 0,
	FE_BUSY = 1,
	FE_FAIL = 2
} fe_error_e;

// Initialize the internal structures of
// the framing engine.
void fe_init(void);

// Called after a packet buffer has been received from the phone. Converts
// the raw buffer to a packet
void fe_handleBufferReceived(uint8_t* buf, uint8_t len);

// Called by the byte-sending machinery to
// notify the framing engine a byte has been sent
// allowing it to queue another byte.
void fe_handleBufferSent (void);


// Register a callback to be invoked when a full packet has been received by the
// framing engine and verified to have a valid checksum.
void fe_registerPacketReceivedCb(fe_packetReceived * cb);

// Register a callback to be invoked when a full packet
// has been sent to allow for the application layer to
// queue another packet for transmission.
void fe_registerPacketSentCb(fe_packetSent* cb);

// Register a function that allows the framing engine
// to send a byte to the lower layers of communication.
void fe_registerBufferSender (fe_bufferSender* sender);

fe_error_e fe_sendPacket (packet_t* pkt);

////////////////////////////////////////
// Private Memebers:
////////////////////////////////////////

#define FE_OUTBUFFERSIZE 128

struct fe_state_struct {

	// Whether or not there is a packet transmission in progress
	uint8_t sendingPacket;

	// Storage space for the rendered outgoing packet that can be put on the
	// wire.
	uint8_t outBuf[FE_OUTBUFFERSIZE];
	uint8_t outBufIdx;
	uint8_t outBufLen;

	// Where to put the packet from the incoming buffer
	packet_t rxPacket;

	// Callbacks for events related to packets
	fe_packetReceived* packetReceivedCb;
	fe_packetSent* packetSentCb;

	// Function that transmits the created buffer
	fe_bufferSender* bufferSender;
} fe;

#endif
