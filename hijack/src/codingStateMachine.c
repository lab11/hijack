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

#include "codingStateMachine.h"
#include "pal.h"
#include "hardware.h"

#include <msp430.h>
#include <stdio.h>
#include <string.h>

int txTempBit = 0;

/////////////////////////////////
// Region: Receive state machine
/////////////////////////////////

void csm_receiveIdle(void) {
	// If we see a 2T high period, it's a start bit!
	csm.threshold = csm.lastRx.elapsedTime * 4 / 10;

	if (csm.curRx.signal == 0 &&
		csm_isWithinThreshold(csm.curRx.elapsedTime / 2,
								csm.lastRx.elapsedTime)) {

		// we'll use the first time as a reference for
		// framing the rest of this byte.
		csm.deltaT = csm.lastRx.elapsedTime;
		//TODO: FIX THIS, THIS IS AWFUL
		TBCCR0 = csm.lastRx.elapsedTime;

		csm.rxState = csm_receiveState_data;

		// We include the start bit in the rx bits
		// to make the algorithm a little simpler.
		csm.rxByte  = 0;
		csm.rxBits = 1;
	}
}

void csm_receiveData(void) {
	// Possible ways to receive data:
	// Two short pulses => Same bit as last bit
	// One long pulse => different bit
	// Anything else => nope nope nope.
	if (csm_isWithinThreshold(csm.curRx.elapsedTime,
								csm.deltaT)) {
		// The next bit is the same as the previous bit,
		// but we must wait for the next short pulse.
		csm.rxState = csm_receiveState_dataNext;
	}
	else if (csm_isWithinThreshold(csm.curRx.elapsedTime / 2,
								csm.deltaT)) {
		// The next bit is different than the previous bit.
		// Set the bit and advance the bit position counter.
		if(((csm.rxByte >> (csm.rxBits - 1)) & 1) == 0) {
			csm.rxByte |= (1 << csm.rxBits);
		}

		csm.rxBits++;
		csm_advanceReceiveDataState();
	}
	else {
		// An error has occured, flip back to waiting
		// for a start bit.
		csm.rxState = csm_receiveState_idle;
	}
}

void csm_receiveDataNext(void) {
	// We're waiting for the second short pulse. The only time
	// we'll see two short pulses in a row is when the bit is
	// equal to the last bit.
	if (csm_isWithinThreshold(csm.curRx.elapsedTime,
								csm.deltaT)) {
		// If the previous bit was a one, make this bit
		// a one also.
		if (((csm.rxByte >> (csm.rxBits - 1)) & 1) == 1) {
			csm.rxByte |= (1 << csm.rxBits);
		}

		csm.rxBits++;
		csm_advanceReceiveDataState();
	}
	else {
		// An error has occured, flip back to waiting
		// for a start bit.
		csm.rxState = csm_receiveState_idle;
	}
}

/////////////////////////////////
// Region: Transmit state machine
/////////////////////////////////
uint8_t csm_transmitIdle(void) {
	if (csm.txBitPosition && csm.txIdleCycles < IDLE_CYCLES) {
		csm.txIdleCycles++;
	}
	return csm.txBitPosition;
}

uint8_t csm_transmitPending(void) {
	if (csm.txBitPosition == 0) {
		csm.txState = CSM_TXSTATE_DATA;
		return csm_transmitData();
	} else {
		return 1;
	}
}

uint8_t csm_transmitData(void) {
	uint8_t ret = ((csm.txByte >> csm.txBytePosition) & 0x1);


	if (csm.txBitPosition == 1) {
		csm.txBytePosition++;

		if (csm.txBytePosition == 10) {
			csm.txState = CSM_TXSTATE_IDLE;
			csm.txBytePosition = 0;
			csm.txIdleCycles = 0;
		}
	} else {
		 ret = !ret;
	}

	return ret;
}

/////////////////////////////
// Region: Public functions
/////////////////////////////

void csm_receiveTiming(struct csm_timer_struct * in) {
	csm.curRx = (*in);
	csm.rxDispatch[csm.rxState]();
	csm.lastRx = (*in);
}

void csm_registerReceiveBuffer(csm_byteReceiver* f) {
	csm.rxCallback = f;
}

void csm_registerTransmitBuffer (csm_bufferSent* f) {
	csm.txCallback = f;
}

// Call this to transmit a buffer of data to the phone.
// Returns 0 on success, >0 otherwise
uint8_t csm_sendBuffer (uint8_t* buf, uint8_t len) {
	if (csm.transmittingPacket) return 1;
	if (len > MAX_BUF_SIZE) return 2;

	csm.transmittingPacket = 1;

	// Copy the buffer to be transmitted and initialize buffer settings
	memcpy(csm.rawTxBuf, buf, len);
	csm.txLen = len;
	csm.txByteIdx = 0;
	csm.txBitIdx = 0;
	csm.txBitHalf = 0;

	// Setup the pin value to be the start bit
	csm.txPinVal = csm_int2man(PREAMBLE_BIT);

	// Mark the state as data to start things sending
	csm.txState = CSM_TXSTATE_PREAMBLE;
	csm.preambleBitLen = 4;

	return 0;
}

// Called by the periodic timer to signal that half of a symbol period has
// elapsed. This is used to output the manchester encoded signal.
void csm_txTimerInterrupt (void) {
	pal_setDigitalGpio(pal_gpio_mic, csm.txPinVal);

	// In all cases if we need to send the other half of the Manchester bit
	// just do it.
	if (csm.txBitHalf == 0) {
		csm.txBitHalf = 1;
		csm.txPinVal = (csm.txPinVal) ? 0 : 1;
		return;
	}

	// Update txPinVal for the next time this interrupt fires
	switch (csm.txState) {
		case CSM_TXSTATE_IDLE:
			csm.txBitHalf = 1;
			csm.txPinVal = 0;
			break;

		case CSM_TXSTATE_PREAMBLE:
			csm.txBitHalf = 0;
			csm.preambleBitLen--;

			if (csm.preambleBitLen == 0) {
				// send the start bit after the preamble
				csm.txPinVal = csm_int2man(START_BIT);
				csm.txState = CSM_TXSTATE_START;
			} else {
				csm.txPinVal = csm_int2man(PREAMBLE_BIT);
			}
			break;

		case CSM_TXSTATE_START:
			csm.txBitHalf = 0;
			csm.txPinVal = csm_int2man(csm.rawTxBuf[0] & 0x1);
			csm.txState = CSM_TXSTATE_DATA;
			break;

		case CSM_TXSTATE_DATA:
			// Just sent the second half, need to move to the next bit
			csm.txBitHalf = 0;
			csm.txBitIdx++;

			//gpio_toggle(LED_PORT, LED_PIN);

			if (csm.txBitIdx < 8) {
				// Send the correct data bit
				csm.txPinVal = csm_int2man(
					(csm.rawTxBuf[csm.txByteIdx] >> csm.txBitIdx) & 0x1);
			} else {
				// Either done or need to move onto the next byte
				csm.txByteIdx++;

				if (csm.txByteIdx < csm.txLen) {
					// Starting a new byte, send the first bit
					csm.txBitIdx = 0;
					csm.txPinVal = csm_int2man(csm.rawTxBuf[csm.txByteIdx] & 0x1);
				} else {
					// Finished sending the packet, move to the idle state
					csm.txState = CSM_TXSTATE_POSTAMBLE;
					csm.postambleBitLen = 8;
					csm.txPinVal = 0;
					csm.txBitHalf = 1;
//					csm.transmittingPacket = 0;
//					csm.txCallback();
				}

			}
			break;

		// Make sure there is a period of no transitions after the packet is
		// sent. This allows the phone to know that the packet is done being
		// transmitted.
		case CSM_TXSTATE_POSTAMBLE:
			csm.postambleBitLen--;

			if (csm.postambleBitLen == 0) {
				csm.txState = CSM_TXSTATE_IDLE;
				csm.txPinVal = csm_int2man(IDLE_BIT);
				csm.transmittingPacket = 0;
				csm.txCallback();
			} else {
				csm.txBitHalf = 1;
				csm.txPinVal = 0;
			}
			break;

		default:
			break;
	}
}

void csm_init(void) {
	memset(&csm, 0, sizeof(struct csm_state_struct));
	csm.txState = CSM_TXSTATE_IDLE;

	// See config.h for declaration of these
	// platform specific parameters.
	csm.threshold = THRESHOLD;
	csm.deltaT = DELTAT;
}

////////////////////////////
// Region: Helper functions
////////////////////////////

uint8_t csm_isWithinThreshold(uint16_t value, uint16_t desired) {
	return value < desired + csm.threshold &&
		value > desired - csm.threshold ? 1 : 0;
}

// Converts bit 0 of val to what the first half of the manchester bit should be
// set at.
inline uint8_t csm_int2man (uint8_t val) {
	return val & 0x1;
}

uint8_t csm_calcByteParity(uint8_t byte) {
	// calculate parity function
	uint8_t parity = 0;
	uint8_t i = 0;
	while (i < 8) {
		parity ^= ((byte >> i) & 1);
		i++;
	}
	return parity;
}

void csm_advanceReceiveDataState(void) {
	if (csm.rxBits == 10) {
		// we have 1 start bit + 8 data bits + 1 parity bit

		if (csm_calcByteParity(csm.rxByte >> 1) == (csm.rxByte >> 9)) {
			csm.rxCallback((csm.rxByte >> 1) & 0xFF);
		}

		csm.rxState = csm_receiveState_idle;
	} else {
		csm.rxState = csm_receiveState_data;
	}
}
