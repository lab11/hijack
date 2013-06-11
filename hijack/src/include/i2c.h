#ifndef __I2C_H__
#define __I2C_H__

#include "msp430.h"
#include <inttypes.h>
#include <stddef.h>
#include "utility.h"

#define I2C_NAK_INTERRUPT                UCNACKIE
#define I2C_ARBITRATIONLOST_INTERRUPT    UCALIE
#define I2C_STOP_INTERRUPT               UCSTPIE
#define I2C_START_INTERRUPT              UCSTTIE
#define I2C_TRANSMIT_INTERRUPT           UCTXIE
#define I2C_RECEIVE_INTERRUPT            UCRXIE

void i2c_init();

void i2c_enable_interrupt();

void i2c_disable_interrupt();

void i2c_send_byte(uint8_t txdat);

uint8_t i2c_receive_byte(uint8_t readReg);

#endif
