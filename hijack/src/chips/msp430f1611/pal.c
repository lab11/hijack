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

#include "pal.h"

#include <msp430.h>
#include "gpio.h"
#include "utility.h"
#include "hardware.h"
#include "analog.h"

#include "f1611_timer.h"

pal_periodicTimerCb * pal_periodicTimerCbPtr;
pal_captureTimerCb * pal_captureTimerCbPtr;

void pal_registerPeriodicTimerCb(pal_periodicTimerCb* f) {
	pal_periodicTimerCbPtr = f;
}

void pal_registerCaptureTimerCb(pal_captureTimerCb* f) {
	pal_captureTimerCbPtr = f;
}

void pal_init(void) {
	util_disableWatchdog();
	util_boardInit();
	//analog_init();

	/*gpio_init(OUT1_PORT, OUT1_PIN, GPIO_OUT);
	gpio_init(OUT2_PORT, OUT2_PIN, GPIO_OUT);
	gpio_init(OUT3_PORT, OUT3_PIN, GPIO_OUT);
	gpio_init(OUT4_PORT, OUT4_PIN, GPIO_OUT);*/

	//gpio_init(OUT1_PORT, OUT1_PIN, GPIO_IN);
	//gpio_init(OUT2_PORT, OUT2_PIN, GPIO_IN);
	//gpio_init(OUT3_PORT, OUT3_PIN, GPIO_OUT);
	//gpio_init(OUT4_PORT, OUT4_PIN, GPIO_IN);

	//gpio_init(DIN1_PORT, DIN1_PIN, GPIO_IN);
	//gpio_init(DIN2_PORT, DIN2_PIN, GPIO_IN);
	gpio_init(MIC_PORT, MIC_PIN, GPIO_OUT);
	gpio_init(LED_PORT, LED_PIN, GPIO_OUT);

	//gpio_init(LED_PORT, LED_PIN, GPIO_OUT);
	//gpio_init(LED_PORT, LED_PIN, GPIO_OUT);

	//gpio_init(LED_PORT, LED_PIN, GPIO_IN);
	//gpio_init(LED_PORT, LED_PIN, GPIO_IN);

	//gpio_init(MIC_PORT, MIC_PIN, GPIO_IN);

	//gpio_init(LEFT_PORT, LEFT_PIN, GPIO_IN);
	//gpio_clear(LED_PORT, LED_PIN);

	util_enableInterrupt();
}

void pal_sampleAnalogGpios(void) {
	analog_sampleAll();
}

uint8_t pal_readDigitalGpio(enum pal_gpioEnum pin) {
	switch (pin) {
		case pal_gpio_din1:
			return gpio_read(DIN1_PORT, DIN1_PIN);
		case pal_gpio_din2:
			return gpio_read(DIN2_PORT, DIN2_PIN);
		default:
			return -1;
	}
}

int8_t pal_setDigitalGpio(enum pal_gpioEnum pin, uint8_t val) {
	switch (pin) {
		case pal_gpio_dout1:
			gpio_set_clear(OUT1_PORT, OUT1_PIN, val);
			return 0;
		case pal_gpio_dout2:
			gpio_set_clear(OUT2_PORT, OUT2_PIN, val);
			return 0;
		case pal_gpio_dout3:
			gpio_set_clear(OUT3_PORT, OUT3_PIN, val);
			return 0;
		case pal_gpio_dout4:
			gpio_set_clear(OUT4_PORT, OUT4_PIN, val);
			return 0;
		case pal_gpio_mic:
			gpio_set_clear(MIC_PORT, MIC_PIN, val);
			return 0;
		case pal_gpio_led:
			gpio_set_clear(LED_PORT, LED_PIN, val);
			return 0;
		default:
			return -1;
	}
}

uint16_t pal_readAnalogGpio(enum pal_gpioEnum pin) {
	switch(pin) {
		case pal_gpio_ain1:
			return analog_readInput(analog_input_in1);
		case pal_gpio_ain2:
			return analog_readInput(analog_input_in2);
		case pal_gpio_vref:
			return analog_readInput(analog_input_vcc);
		case pal_gpio_temp:
			return analog_readInput(analog_input_extTemp);
		default:
			return 0;
	}
}

void pal_startTimers(void) {
	timer_init();
	timer_setCaptureCallback(pal_captureTimerFn);
	timer_setPeriodicCallback(pal_periodicTimerFn);

	timer_start();

	//util_delayMs(100);
}

void pal_pause(void) {
	timer_stop();
	//P2DIR |= (1<<3);
}

void pal_resume(void) {
	//P2DIR &= ~(1<<3);
	timer_start();
}

void pal_periodicTimerFn(void) {
	if (pal_periodicTimerCbPtr != 0) {
		pal_periodicTimerCbPtr();
	}
}

void pal_captureTimerFn(uint16_t elapsedTime) {
	uint8_t pinValue = !!timer_readCaptureLine();
	if (pal_captureTimerCbPtr != 0) {
		pal_captureTimerCbPtr(elapsedTime, pinValue);
	}
}

void pal_loopDelay(void) {
	__delay_cycles(400);
}
