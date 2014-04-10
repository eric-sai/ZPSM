/*
Install the application on two motes with id 0 and 1.
Type "java ZPSM -comm serial@/dev/ttyUSB0:telosb" to let the PC send a message to node 0.
Node 0 will send packet to node 1, which notifies this to the PC.
Type "java ZPSM -comm serial@/dev/ttyUSB1:telosb" to see the message received from the port of node 1
*/

#include "ZPSM.h"
#include "printf.h"

module ZPSMC
{
	uses
	{
		interface Boot;
		interface Leds;
		interface Timer<TMilli> as Timer;

		interface SplitControl as SerialControl;
		interface Receive as SerialReceive;
		interface AMSend as SerialSend;
		interface AMPacket as SerialPacket;
		
		interface SplitControl as RadioControl;
		interface Receive as RadioReceive;
		interface AMSend as RadioSend;
		interface AMPacket as RadioPacket;
	}
}
implementation
{
	/* Local Variable Definition */
	message_t serial_pkt;
	message_t radio_pkt;
	bool busy = FALSE;
	bool locked = FALSE;
	int seqno = 0;

	/* Booting */
	event void Boot.booted()
	{
		call SerialControl.start();
		call RadioControl.start();
	}
	
/*--------------------------------------------------------------------------
-----------------------------Initialization--------------------------------
--------------------------------------------------------------------------*/
	/* Start Serial Communication */
	event void SerialControl.startDone(error_t err)
	{
		if (err != SUCCESS)
			call SerialControl.start();
	}
	event void SerialControl.stopDone(error_t err) {}
 	
 	/* Start Radio Communication */
  	event void RadioControl.startDone(error_t err)
	{
		if (err == SUCCESS)
		{
			if(TOS_NODE_ID == 100)
				call Timer.startPeriodic(TIMER_PERIOD);
		}
		else
			call RadioControl.start();
	}
	event void RadioControl.stopDone(error_t err) {}

/*--------------------------------------------------------------------------
-------------------------------TIMER------------------------------------
--------------------------------------------------------------------------*/
	/* Timer Handler */
	event void Timer.fired()
	{
		//Send syn packet
		if (!locked)
		{
			RadioMsg* btrpkt = (RadioMsg*)(call RadioSend.getPayload(&radio_pkt, sizeof(RadioMsg)));
			if (btrpkt != NULL)
			{
				//Pass the message from PC to Mote
				btrpkt->source = TOS_NODE_ID;
				btrpkt->seq = seqno;
				seqno++;
			
				//Broadcast the message through radio
				if (call RadioSend.send(AM_BROADCAST_ADDR, &radio_pkt, sizeof(RadioMsg)) == SUCCESS)
					locked = TRUE;
				call Leds.led0Toggle();
			}
		}
	}

/*--------------------------------------------------------------------------
----------------------------PC-to-Mote Communication----------------------
--------------------------------------------------------------------------*/
	/* Serial Receive: Node sends the packet through its raido upon receiving a message from the PC */
 	event message_t* SerialReceive.receive(message_t* bufPtr, void* payload, uint8_t len)
 	{
 		int i;
 		if (len == sizeof(SerialMsg) && TOS_NODE_ID != 100)
		{
			SerialMsg* rcm = (SerialMsg*)payload;
			if (!busy)
			{
				RadioMsg* btrpkt = (RadioMsg*)(call RadioSend.getPayload(&radio_pkt, sizeof(RadioMsg)));
				if (btrpkt != NULL)
				{
					//Pass the message from PC to Mote
					btrpkt->source = rcm->source;
					btrpkt->seq = rcm->seq;
					for(i = 0; i<MAX_NODE; i++)
					{
						btrpkt->wlist[i][0] = rcm->wlist[i][0];
						btrpkt->wlist[i][1] = rcm->wlist[i][1];
					}
				
					//Broadcast the message through radio
					if (call RadioSend.send(AM_BROADCAST_ADDR, &radio_pkt, sizeof(RadioMsg)) == SUCCESS)
						locked = TRUE;
					call Leds.led1Toggle();
				}
			}
		}
		return bufPtr;
	}

	/* Serial Send Done */
	event void SerialSend.sendDone(message_t* bufPtr, error_t error)
	{
		if (&serial_pkt == bufPtr)
    		busy = FALSE;
	}
 
 /*--------------------------------------------------------------------------
----------------------------Mote-To-PC Communication----------------------
--------------------------------------------------------------------------*/
 	/* Radio Receive: Node forwards the packet to the PC through serial port once it receives a packet from  its raido*/
 	event message_t* RadioReceive.receive(message_t* bufPtr, void* payload, uint8_t len)
	{
		int i;
		if (len == sizeof(RadioMsg) && TOS_NODE_ID != 100)
		{
			RadioMsg* btrpkt = (RadioMsg*)payload;
			/* Send message to PC */
			if (!locked)
			{
				SerialMsg* rcm = (SerialMsg*)call SerialSend.getPayload(&serial_pkt, sizeof(SerialMsg));
				if(rcm != NULL && (call SerialSend.maxPayloadLength() >= sizeof(SerialMsg)))
				{
					//Pass the message from Mote to PC
					rcm->source = btrpkt->source;
					rcm->seq = btrpkt->seq;
					for(i = 0; i<MAX_NODE; i++)
					{
						rcm->wlist[i][0] = btrpkt->wlist[i][0];
						rcm->wlist[i][1] = btrpkt->wlist[i][1];
					}
					//Send message to PC through serial port
					if (call SerialSend.send(AM_BROADCAST_ADDR, &serial_pkt, sizeof(SerialMsg)) == SUCCESS)
						busy = TRUE;
    				call Leds.led2Toggle();
				}
		    }
		}
		return bufPtr;
	}
	
	/* Radio Send Done */
  	event void RadioSend.sendDone(message_t* bufPtr, error_t error)
	{
		if (&radio_pkt == bufPtr)
			locked = FALSE;
	}
}
