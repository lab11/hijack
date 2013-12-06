#ifndef __PACKET_H__
#define __PACKET_H__

#define PKT_TYPE_OFFSET      0
#define PKT_TYPE_MASK        0xF
#define PKT_RETRIES_OFFSET   4
#define PKT_RETRIES_MASK     0x3 << 4
#define PKT_ACKREQ_OFFSET    6
#define PKT_ACKREQ_MASK      0x1 << 6
#define PKT_POWERDOWN_OFFSET 7
#define PKT_POWERDOWN_MASK   0x1 << 7

typedef struct {
	uint8_t length;
	uint8_t power_down;
	uint8_t ack_requested;
	uint8_t retries;
	uint8_t type;
	uint8_t data[128];
} packet_t;

#endif
