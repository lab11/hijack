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

#include <inttypes.h>
#include <stdio.h>

#include "pal.h"
#include "codingStateMachine.h"
#include "framingEngine.h"
#include "packet.h"
#include "utility.h"

// TO FIX
 #include "interrupt.h"
void int_pgood();
void int_pbad();

volatile uint8_t pTransitionState = 0;

// pSend 0 - Send pGood
// pSend 1 - Send pBad
// pSend 2 - Send pStd
volatile uint8_t pSend = 0;
volatile uint8_t pLastSent = 0;

volatile uint8_t pendingStop = 0;
volatile uint8_t pendingShutdown = 0;
volatile uint8_t pendingTimerStop = 0;

volatile uint8_t pendingStart = 0;


// STOP FIX


packet_t booted_packet = {10, 0, 1, 0, 3, {0}};



uint8_t outMessage[] = {
	0xAB, 0xDC, 0xEF, 0x12, 0x34, 0x56, 0, 0, 0
};

uint8_t pgoodMessage[] = {
	0x01
};

uint8_t pbadMessage[] = {
	0x02
};

uint16_t temp_buf[100] = {0};
uint8_t buf_idx= 0;
/*
void periodicTimerFn (void) {
	static uint8_t ats = 0;
	//ats = csm_advanceTransmitState();
	pal_setDigitalGpio(pal_gpio_mic, ats);
	ats = (ats) ? 0 : 1;
	//csm_finishAdvanceTransmitState();
}
*/
void captureTimerFn(uint16_t elapsedTime, uint8_t isHigh) {
	struct csm_timer_struct timingData;
	timingData.elapsedTime = elapsedTime;
	timingData.signal = !isHigh;
	csm_receiveTiming(&timingData);
}

void packetReceivedCallback(packet_t* pkt) {

}

void packetSentCallback(void) {
/*	if (pendingShutdown) {
		pal_pause();
		pendingTimerStop = 1;
	}

	if (pendingStop) {
		fe_writeTxBuffer(pbadMessage, 1);
		pendingShutdown = 1;
	}
	else if (pendingStart) {
		fe_writeTxBuffer(pgoodMessage, 1);
		pendingStart = 0;
	}
	else {
		fe_writeTxBuffer(outMessage, 6);
	}*/
	util_delayMs(1000);
	booted_packet.data[0]++;
	fe_sendPacket(&booted_packet);
}

void updateAnalogOutputBuffer(void) {
	//pal_sampleAnalogGpios();
	uint16_t temp_ext = 0;
	uint16_t vcc = 0;
	uint16_t sample1 = 0;
	uint16_t sample2 = 0;

	//vcc = pal_readAnalogGpio(pal_gpio_vref);
	//temp_ext = pal_readAnalogGpio(pal_gpio_temp);
	//sample1 = pal_readAnalogGpio(pal_gpio_ain1);
	//sample2 = pal_readAnalogGpio(pal_gpio_ain2);

	outMessage[1] = vcc & 0xFF;
	outMessage[2] = (vcc >> 8) & 0xFF;
	outMessage[3] = temp_ext & 0xFF;
	outMessage[4] = (temp_ext >> 8) & 0xFF;
	outMessage[5] = sample1 & 0xFF;
	outMessage[6] = (sample1 >> 8) & 0xFF;
	outMessage[7] = sample2 & 0xFF;
	outMessage[8] = (sample2 >> 8) & 0xFF;
}

void updateDigitalOutputBuffer(void) {
	outMessage[0] = (outMessage[0] & ~(1 << 0)) |
		((pal_readDigitalGpio(pal_gpio_din1) & 0x1) << 0);
	outMessage[0] = (outMessage[0] & ~(1 << 1)) |
		((pal_readDigitalGpio(pal_gpio_din2) & 0x1) << 1);
}

void initializeSystem(void) {
	pal_init();

	// Initialize the transition-edge-length
	// manchester decoder.
	csm_init();
	csm_registerReceiveByte(fe_handleByteReceived);
	csm_registerTransmitByte(fe_handleByteSent);

	// Initialize the framing engine to process
	// the raw byte stream.
	fe_init();
	fe_registerPacketReceivedCb(packetReceivedCallback);
	fe_registerPacketSentCb(packetSentCallback);
	fe_registerByteSender(csm_sendBuffer);

	pal_registerPeriodicTimerCb(csm_txTimerInterrupt);
	pal_registerCaptureTimerCb(captureTimerFn);

	// Start the interrupt-driven timers.
	pal_startTimers();

	// Start the transmit callback-driven
	// loop
	fe_sendPacket(&booted_packet);
}

// This is the main loop. It's not very
// power efficient right now, it should
// do some microcontroller sleep commands
// and use wakeups.
int main () {
	initializeSystem();
	// TODO: Add sleep commands!

		/*while(1)
	{
		pal_setDigitalGpio(pal_gpio_led, 1);
		pal_setDigitalGpio(pal_gpio_led, 0);
	}*/

	// TO FIX
	interrupt_init();

//	if((P2IN >> 0) & 0x01){
//		interrupt_create(2, 0, HIGH_TO_LOW, int_pbad);
//	}
//	else{
//		interrupt_create(2, 0, LOW_TO_HIGH, int_pgood);
//	}

	// STOP FIX

	while(1) {
		//pal_setDigitalGpio(pal_gpio_led, 0);
		//updateDigitalOutputBuffer();
		//updateAnalogOutputBuffer();
		//pal_setDigitalGpio(pal_gpio_led, 1);
		//pal_loopDelay();
		//if (pTransitionState == 1) {
		//	fe_writeTxBuffer(pgoodMessage, 1);
		//}
//		_BIS_SR(LPM0_bits);
		//if (!((P2IN >> 0) & 0x01)) {
		//	pal_pauseTimers();
		//	_BIS_SR(LPM3_bits);
		//}
	}

    return 0;
}

void int_pgood(){
	//pal_setDigitalGpio(pal_gpio_dout1, 1);
	pwr_on = 1;

	pal_resume();
	//fe_writeTxBuffer(pgoodMessage, 1);
	//pTransitionState = 0;

	interrupt_remove(2, 0);
	interrupt_create(2, 0, HIGH_TO_LOW, int_pbad);
}

void int_pbad(){
	//pal_setDigitalGpio(pal_gpio_dout1, 0);
	pwr_on = 0;
	pendingStop = 1;

	//pal_pause();
	interrupt_remove(2, 0);
	interrupt_create(2, 0, LOW_TO_HIGH, int_pgood);
}
