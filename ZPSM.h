#ifndef ZPSM_H
#define ZPSM_H

#include <Timer.h>
#define MAX_NODE 9

/* Message format between Mote and PC */
typedef nx_struct SerialMsg
{
	nx_uint8_t source;
	nx_uint16_t seq;
	nx_uint8_t wlist[MAX_NODE][2];
} SerialMsg;

/* Message format between Motes */
typedef nx_struct RadioMsg
{
	nx_uint8_t source;
	nx_uint16_t seq;		//Seq: 0 to starting program
	nx_uint8_t wlist[MAX_NODE][2];
} RadioMsg;

enum
{
	AM_SERIALMSG = 1,
	AM_RADIOMSG = 2,
	TIMER_PERIOD = 1000
};

#endif
