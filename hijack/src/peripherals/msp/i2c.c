#include "i2c.h"

void i2c_init(){
	// Select Port 3 Pins 1 & 3 as i2c pins
	P3SEL |= 0x0A;
	
	// Set I2C Mode 
	U0CTL |= I2C + SYNC;
	
	// Disable I2C
	U0CTL &= ~I2CEN;
	
	// Set clock source to SMCLK
	I2CTCTL = I2CSSEL1;
	
	I2CSCLL = 10;
	I2CSCLH = 0;
	
	// Set slave address
	I2CSA = 0b1000000;
	
	// Enable I2C
	U0CTL |= I2CEN;
}

void i2c_enable_interrupt(){
}

void i2c_disable_interrupt(){
}

void i2c_send_byte(uint8_t txdat){
	// Three byte transfer
	I2CNDAT = 0x01;
	
	// Master mode
	U0CTL |= MST;
	
	// Start transfer - start bit, stop bit, tx mode
	I2CTCTL |= I2CSTT + I2CSTP + I2CTRX;
	
	// Wait for transmitter to be ready, 
	// then load transfer byte and wait
	// for stop condition
	while((I2CIFG & TXRDYIFG) == 0);
	I2CDRB = txdat;
	while((I2CTCTL & I2CSTP) == 0x02);
}

uint8_t i2c_receive_byte(uint8_t readReg){
	uint8_t i = 0;
	uint8_t recByte = 0;
	
	i2c_send_byte(readReg);
	
	I2CNDAT = 0x03;
	
	// There is some condition in which it 'falls out' of master
	// mode so just to be safe I do it each time. If this is 
	// redundant it wont hurt anything
	U0CTL |= MST;
	
	// Start bit, stop bit, rx mode
	I2CTCTL = I2CSTT + I2CSTP;
	
	// What for receiver to be ready
	while((I2CIFG & RXRDYIFG) == 0);
	
	// Receive byte
	recByte = I2CDRB;
	
	// Wait for receiver to be ready
	while((I2CIFG & RXRDYIFG) == 0);
	
	// Cant tell what this does but I couldnt get it to work without it
	// I found it in some example code
	i = i + I2CDRB;
	
	// Wait for the transmission to be complete
	while((I2CTCTL & I2CSTP) == 0x02);
	
	return recByte;
}
