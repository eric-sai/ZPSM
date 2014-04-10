#include "ZPSM.h"

configuration ZPSMAppC {}
implementation
{
	components ZPSMC as App;
	
	components MainC;
	components LedsC;
	components new TimerMilliC();
	
	components SerialActiveMessageC;
	components new SerialAMSenderC(AM_SERIALMSG);
	components new SerialAMReceiverC(AM_SERIALMSG);
	
	components ActiveMessageC;
	components new AMSenderC(AM_RADIOMSG);
	components new AMReceiverC(AM_RADIOMSG);
	
	App.Boot -> MainC;
	App.Leds -> LedsC;
	App.Timer -> TimerMilliC;

	App.SerialControl -> SerialActiveMessageC;
	App.SerialPacket -> SerialAMSenderC;
	App.SerialSend -> SerialAMSenderC;
	App.SerialReceive -> SerialAMReceiverC;
	
	App.RadioControl -> ActiveMessageC;
	App.RadioPacket -> AMSenderC;
	App.RadioSend -> AMSenderC;
	App.RadioReceive -> AMReceiverC;
}
