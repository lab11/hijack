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
#include "gpio.h"

#include <msp430.h>
#include <stdio.h>
#include <string.h>

int txTempBit = 0;

/////////////////////////////////
// Region: Receive state machine
/////////////////////////////////

// Called on each received edge that occurs between packets.
void csm_receiveIdle (csm_rx_input_t rx) {

	// Start checking if we have found a start bit
	if (rxPreambleReceivedEdges >= RX_PREAMBLE_LEN && // seen the minimum preamble
	    rx.signal == 1 && // rising edge
	   ) {
	   	uint32_t sum, average;
		uint16_t max, min;

		// Calculate some simple statistics about the edges
		sum = 0;
		max = 0;
		min = UINT16_MAX;
		int i;
		for (i=0; i<RX_PREAMBLE_LEN; i++) {
			uint16_t val = csm.rxPreambleBuffer[i];
			sum += val;
			if (val > max) max = val;
			if (val < min) min = val;
		}

		// Check that the previous four edges were about the same time between
		// each other.
		average = sum / RX_PREAMBLE_LEN;
		if (max - min < average / 10) {

			// Check that we just saw a double width signal (which at this point
			// is the start bit)
			if (csm_isWithinThreshold(rx.elapsedTime / 2, average)) {

				// Found start bit!

				// Move to the receiving data state
				csm.rxState = CSM_RXSTATE_DATA;
				csm.rxDeltaT  = average;

				// TODO CHANGE THIS
				TBCCR0 = average;
				return;
			}
		}
	}

	// If we get here, we have not found a start bit

	// Save the timing between edges here
	rxPreambleReceivedEdges++;
	rxPreambleBuffer[rxPreambleIdx++] = rx.elapsedTime;
	rxPreambleIdx %= 4;
}

// Called when an edge comes in after we have made it to the data portion
// of the signal
void csm_receiveData (csm_rx_input_t rx) {
	// Possible ways to receive data:
	// Two short pulses => Same bit as last bit
	// One long pulse   => different bit
	// Anything else    => nope nope nope.
	if (csm_isWithinThreshold(rx.elapsedTime, csm.rxDeltaT)) {
		// The next bit is the same as the previous bit,
		// but we must wait for the next short pulse.
		csm.rxState = CSM_RXSTATE_DATA_EXTRA;

	} else if (csm_isWithinThreshold(rx.elapsedTime / 2, csm.rxDeltaT)) {
		// The next bit is different than the previous bit.
		// Set the bit and advance the bit position counter.
		csm_receiveAddBit(CSM_RX_BIT_DIFFERENT);

	} else {
		// Either an error occurred or we just got to the end of the packet.
		if (csm.rxByteIdx >= 1 && csm.rxBitIdx == 0) {
			csm.rxCallback(csm.rxBufRaw, csm.rxByteIdx - 1);
		}
		csm_receiveClear();
		csm.rxState = CSM_RXSTATE_IDLE;
	}
}

// Called when we have to capture a second edge because we have two identical
// bits in a row
void csm_receiveDataExtra (csm_rx_input_t rx) {
	// We're waiting for the second short pulse. The only time
	// we'll see two short pulses in a row is when the bit is
	// equal to the last bit.
	if (csm_isWithinThreshold(rx.elapsedTime, csm.rxDeltaT)) {
		// If the previous bit was a one, make this bit a one also.
		csm_receiveAddBit(CSM_RX_BIT_SAME);
		csm.rxState = CSM_RXSTATE_DATA;
	} else {
		// Either an error occurred or we just got to the end of the packet.
		if (csm.rxByteIdx >= 1 && csm.rxBitIdx == 0) {
			csm.rxCallback(csm.rxBufRaw, csm.rxByteIdx - 1);
		}
		csm_receiveClear();
		csm.rxState = CSM_RXSTATE_IDLE;
	}
}

// Call to reset the receive state to what it should look like when waiting
// for a packet.
void csm_receiveClear () {
	memclr(csm.rxPreambleBuffer, RX_PREAMBLE_LEN);
	csm.rxPreambleReceivedEdges = 0;
	csm.rxPreambleIdx           = 0;

	memclr(csm.rxBufRaw, MAX_BUF_SIZE);
	csm.rxBitIdx      = 0;
	csm.rxByteIdx     = 0;
	csm.rxPreviousBit = 0;
}

// Add a bit to the received array.
//  bit: whether or not to add the same bit as last time
void csm_receiveAddBit (csm_receiveBitType_e bit) {
	uint8_t last_bit;

	if (csm.rxPreviousBit == 0 && bit == CSM_RX_BIT_SAME ||
		csm.rxPreviousBit == 1 && bit == CSM_RX_BIT_DIFFERENT) {
		// Simple case of just needing to add a zero
		// Just need to increment the counters
		csm.rxPreviousBit = 0;
	} else {
		// Need to add a 1
		csm.rxBufRaw[csm.rxByte] |= (1 << csm.rxBitIdx);
		csm.rxPreviousBit = 1;
	}

	// Increment the bit position counters
	csm.rxBitIdx++;
	if (csm.rxBitIdx > 7) {
		csm.rxBitIdx = 0;
		csm.rxByteIdx++;
	}
}


/////////////////////////////
// Region: Public functions
/////////////////////////////

// Function that is called when an interrupt is detected
void csm_receiveTiming (csm_rx_input_t* in) {
	switch (csm.rxState) {
		case CSM_RXSTATE_IDLE: csm_receiveIdle(in); break;
		case CSM_RXSTATE_IDLE: csm_receiveData(in); break;
		case CSM_RXSTATE_IDLE: csm_receiveDataExtra(in); break;
	}
}

// Call to register a function to get called when a packet is received
void csm_registerReceiveCallback (csm_bufferReceived* f) {
	csm.rxCallback = f;
}

// Register a function to be called after a packet is transmitted
void csm_registerTransmitCallback (csm_bufferSent* f) {
	csm.txCallback = f;
}

// Call this to transmit a buffer of data to the phone.
// Returns 0 on success, >0 otherwise
uint8_t csm_sendBuffer (uint8_t* buf, uint8_t len) {
	if (csm.transmittingPacket) return 1;
	if (len > MAX_BUF_SIZE) return 2;

	csm.transmittingPacket = 1;

	// Copy the buffer to be transmitted and initialize buffer settings
	memcpy(csm.txBufRaw, buf, len);
	csm.txLen     = len;
	csm.txByteIdx = 0;
	csm.txBitIdx  = 0;
	csm.txBitHalf = 0;

	// Setup the pin value to be the start bit
	gpio_init(MIC_PORT, MIC_PIN, GPIO_OUT);
	csm.txPinOutput = 1;
	csm.txPinVal    = csm_int2man(PREAMBLE_BIT);

	// Mark the state as data to start things sending
	csm.txState        = CSM_TXSTATE_PREAMBLE;
	csm.preambleBitLen = 4;

	return 0;
}


/////////////////////////////////
// Region: Transmit state machine
/////////////////////////////////


// Called by the periodic timer to signal that half of a symbol period has
// elapsed. This is used to output the manchester encoded signal.
void csm_txTimerInterrupt (void) {

	// The first operation is to set the correct value of the pin that was
	// calculated before. This ensures little to no delay in the timing of
	// the signal.
	if (csm.txPinOutput) {
		pal_setDigitalGpio(pal_gpio_mic, csm.txPinVal);
	}

	// Manchester encoding requires two bauds per bit. If we have already
	// set the first one, then in all cases if we need to send the other half
	// of the Manchester bit, which is just the opposite of the previous level.
	if (csm.txBitHalf == 0) {
		csm.txBitHalf = 1;
		csm.txPinVal = (csm.txPinVal) ? 0 : 1;
		return;
	}

	// Update txPinVal for the next time this interrupt fires
	switch (csm.txState) {
		case CSM_TXSTATE_IDLE:
			gpio_init(MIC_PORT, MIC_PIN, GPIO_IN);
			csm.txPinOutput = 0;
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
			csm.txPinVal = csm_int2man(csm.txBufRaw[0] & 0x1);
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
					(csm.txBufRaw[csm.txByteIdx] >> csm.txBitIdx) & 0x1);
			} else {
				// Either done or need to move onto the next byte
				csm.txByteIdx++;

				if (csm.txByteIdx < csm.txLen) {
					// Starting a new byte, send the first bit
					csm.txBitIdx = 0;
					csm.txPinVal = csm_int2man(csm.txBufRaw[csm.txByteIdx] & 0x1);
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

			if (csm.postambleBitLen == 1) {
				// Need a spike at the end to signal the end of the packet
				csm.txBitHalf = 0;
				csm.txPinVal = 1;
			} else if (csm.postambleBitLen == 0) {
				csm.txState = CSM_TXSTATE_IDLE;
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


// Init function for this module
void csm_init(void) {
	memset(&csm, 0, sizeof(struct csm_state_struct));
	csm.txState = CSM_TXSTATE_IDLE;

	csm.rxState = CSM_RXSTATE_IDLE;
	csm_receiveClear();

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


