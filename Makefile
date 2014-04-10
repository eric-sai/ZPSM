COMPONENT=ZPSMAppC
BUILD_EXTRA_DEPS += ZPSM.class
CLEAN_EXTRA = *.class ZPSMMsg.java

CFLAGS += -I$(TOSDIR)/lib/T2Hack
CFLAGS += -I$(TOSDIR)/lib/printf

ZPSM.class: $(wildcard *.java) ZPSMMsg.java
	javac -target 1.5 -source 1.5 *.java

ZPSMMsg.java:
	mig java -target=null $(CFLAGS) -java-classname=ZPSMMsg ZPSM.h SerialMsg -o $@

include $(MAKERULES)

