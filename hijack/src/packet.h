#ifndef __PACKET_H__
#define __PACKET_H__

typedef struct {
	uint8_t length;
	uint8_t power_down;
	uint8_t ack_requested;
	uint8_t retries;
	uint8_t type;
	uint8_t data[128];
} packet_t;

#endif
