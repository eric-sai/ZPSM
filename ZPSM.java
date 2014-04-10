import java.io.*;
import java.lang.*;
import java.net.*;
import java.util.*;
import net.tinyos.message.*;
import net.tinyos.packet.*;
import net.tinyos.util.*;

public class ZPSM
{	
//System parameters
	//NOTE: IP is "192.168.2.(NID+1)"
	static int 			protocol = 2;				//0: SPSM, 1: APSM, 2: ZPSM
	final double		available_bw = 0.5;
	final int			arrival_distribution = 0;
	final boolean		manual = true;			//manual setting or random setting
	final int			CAM_rate = 0;			//background CAM traffic rate with possion arrival
	//Manual setting
	final double[][]	nodes = {	//Node settings:	rate, delay, distribution(0: possion, 1: uniform)
								{0.5, 1, 1.1},  	//Client 1
								{0.5, 1, 0.1},  	//Client 2
								{1, 0.5, 0.1}, 		 //Client 3
								{1, 1, 0.1},  		//Client 4
								{0.5, 0.5, 1.1}, 	 //Client 5
								{0.5, 0.5, 0.1}, 	 //Client 6
								{1, 0.5, 1.1}, 		 //Client 7
								{1, 1, 1.1}   		//Client 8
								};
	/*
	final double[][]	nodes = {	//Node settings:	rate, delay, distribution(0: possion, 1: uniform)
								{0.5, 1, 0.1},  	//Client 1
								{0.5, 1, 0.1},  	//Client 2
								{0.5, 1, 0.1}, 		 //Client 3
								{0.5, 1, 0.1},  		//Client 4
								{0.5, 1, 0.1}, 	 //Client 5
								{0.5, 1, 0.1}, 		 //Client 6
								{0.5, 1, 0.1}, 		 //Client 7
								{0.5, 1, 0.1}   		//Client 8
								};
	 */

	final int[]			hops = {
								1,			//Client 1
								2,			//Client 2
								1,			//Client 3
								2,			//Client 4
								1,			//Client 5
								2,			//Client 6
								1,			//Client 7
								2			//Client 8
	};
	//Random setting
	final int		packet_rate = 1;
	final double	delay_mean = 0.5;				//in (s)
	final double 	upload_rate = 0.5;			// Upload/download
	
//Settings
	static int				NID;					//0 - AP; Otherwise - client
	static boolean			Debug;

	final String			tracefile = "./trace/client";
	final int				tracesize = 4000;			//Max number of packets
	final int				tracewindow = 100;			//Number of packets to record results
	final int				result_interval = (int)(1.0/upload_rate)*1000;	// Result updating interval in slot
	
	static final double 	SLOT__LEN = 0.001;
	static final int 		BEACON__INTERVAL = 100;
	final double			BEACON__LEN = SLOT__LEN * BEACON__INTERVAL;
	final int	 			INTER__PKT = ((int)(0.009216/SLOT__LEN));
	final int	 			BEACON__SLOTS = 2;
	final int				MAX__HIS = 50;
	final double			MAX__LISTEN__INTERVAL = 65535;
	static final int		QUEUE_LIMIT = 100;
	static final int		POSITIVE__INFINITY = 999999999;
	static final int		MAX_NODES = 7;
	static final int		MAX_SET = 60;
	static final String[]	PKT_TYPE = {"DATA", "BEACON", "PS_POLL", "JOIN", "LEAVE"};

//Packet size
	static final int		PACKET_SIZE = 2346; 		// 1450bytes for non-fragemented
	static final int		BEACON_SIZE = 200;			//bytes
	static final int		ACK_SIZE = 304;				//bits
	static final int		PS_POLL_SIZE = 352;			//bits
	static final double		SIFS = 0.000016;			//second
	static final double		DIFS = 0.000034;			//second
	
//Energy
	final double 			WF_Idle_Power = 0.462;			//Idle power in (Watt)
	final double 			WF_Rx_Power = 0.561;			//Energy to receive (Watt)
	final double 			WF_Tx_Power = 1.1517;			//Energy to transmit (Watt)
	final double 			WF_Wakeup_Energy = 0.0015;		//(J)

	final double			PSPOLL__ENERGY = (PS_POLL_SIZE*WF_Tx_Power)/54000000 + WF_Idle_Power*SIFS;
	final double			BEACON__ENERGY = (BEACON_SIZE*8.0*WF_Rx_Power)/54000000 + WF_Wakeup_Energy + WF_Idle_Power*DIFS;
	final double			IDLE__ENERGY = SLOT__LEN * WF_Idle_Power;	
	final double			PACKET__ENERGY = (PACKET_SIZE*8.0*WF_Rx_Power)/54000000 + WF_Idle_Power*DIFS;
	final double			ACK__ENERGY = (ACK_SIZE*WF_Tx_Power)/1000000 + WF_Idle_Power*SIFS;
	
//Status
	int				nNode = 0;
	int 			bw_quota = (int)((250000/8.0)*(available_bw*nNode/100.0)/(3*(1.0/BEACON__LEN)));
	static int		slot = 0;
	Client[]		client = new Client[MAX_NODES];
	static int		seq = 0;
	boolean			joined = false;
	
//Random number
	Rvgs 			rand = new Rvgs();
	final long 		SEED = 123456789;

//Other variables
	MoteIF moteIF;
	static int portNum = 6666;
	int[] quota_his = new int[MAX__HIS];
	int quota_index = 0;
	int max_packet = BEACON__INTERVAL;
	boolean wakeup = false;
	long wake_slot = -1;

//Collect results
	static int miss = 0;			//Miss packets at client
	static int meet = 0;			//Meet packets at client
	static int send_pkt = 0;		//Packets sent by AP
	static double control_energy = 0.0;			//Receive beacon and send PS_POLL
	static double idle_energy = 0.0;				//Number of idle slots
	static double packet_energy = 0.0;			//Receive data packets
	
	final int join_interval = 300*1000;		//100seconds
	int next_join = -1;
	double[][] join_perf = new double[MAX_NODES][2];
	
//Initialization
	public ZPSM(int id)
	{
	   rand.rngs.plantSeeds(SEED);
	   NID = id;
	   for(int i=1; i<MAX_NODES; i++)
		   join_perf[i] = new double[2];
	}

//Control message from ZigBee
	public void printResult()
	{
		System.out.println();
		System.out.println("--------------- Protocol = " + protocol + " at time " + (int)(slot*SLOT__LEN) +"(s)---------------");
		
		/* Print out current setting*/
		System.out.println("BW=" + available_bw + "%, manual=" + manual + ", rate=" + packet_rate + ", upload=" + upload_rate + ", delay=" + delay_mean +", CAM_rate="+CAM_rate);
		System.out.println("----------------------------------------------------------");
		/* Print out results */
		int recv_pkt = 0;
		double energy = 0.0;
		int miss = 0;
		int meet = 0;
		double c_energy = 0.0;
		double i_energy = 0.0;
		double p_energy = 0.0;
		for(int i = 1; i<MAX_NODES; i++)
		{
			//Print client setting
			String tmp = client[i].pkt_trace == null ? "random" : "trace";
			String tmp0 =  client[i].valid ? "On" : "Off";
			System.out.println("Client " + i + "[" + tmp0 + "|" + client[i].hop + "h|"+ tmp + "]: delay=" + client[i].delay_mean +", rate=" 
					+ (1.0/client[i].packet_interval) + ", dis=" + client[i].arrival_dis + ", m=" + client[i].listen_interval);
			
			if(!client[i].valid || client[i].hop < 0) continue;
			int pkt = client[i].meet + client[i].miss;
			if(pkt == 0) continue;
			double e = client[i].control_energy + client[i].idle_energy + client[i].packet_energy;
			System.out.println("  --> miss/meet=" + client[i].miss +"/"+client[i].meet+
					", ratio="+ (double)client[i].meet/pkt + ", total_RX=" + pkt);
			System.out.println("  --> c_en=" + client[i].control_energy/pkt +
					"(mJ/pkt), i_en=" + client[i].idle_energy/pkt + "(mJ/pkt), p_en=" + client[i].packet_energy/pkt +
					"(mJ/pkt), energy=" + e/pkt + "(mJ/pkt)");
			System.out.println("  --> Join: ratio=" + join_perf[i][0] + ", energy=" + join_perf[i][1] + ", trace=" + client[i].trace_index);
			energy += e;
			miss += client[i].miss;
			meet += client[i].meet;
			c_energy += client[i].control_energy;
			i_energy += client[i].idle_energy;
			p_energy += client[i].packet_energy;
		}
		recv_pkt = miss + meet;
		if(recv_pkt == 0) return;
		System.out.println("***************Overall***************");
		System.out.println("Miss/meet=" + miss +"/"+meet+", ratio="+ (double)meet/recv_pkt
				+ ", PDR=" + (double)recv_pkt/send_pkt + ", total_RX=" + recv_pkt);
		System.out.println("Control=" + c_energy/recv_pkt + "(mJ/pkt), idle="
				+ i_energy/recv_pkt + "(mJ/pkt), packet=" + p_energy/recv_pkt + "(mJ/pkt), all="
				+ (p_energy+c_energy+i_energy)/recv_pkt + "(mJ/pkt)");
		System.out.println();
		if(next_join > 0 && slot >= next_join)
		{
			next_join = -1;
			join_perf[nNode][0] = (double)meet/recv_pkt;
			join_perf[nNode][1] = (p_energy+c_energy+i_energy)/recv_pkt;
		}
	}
	
//Start the program from HERE!
	public void start()
	{
		//Configuration
		if(isAP())	//AP
		{
			System.out.println("AP starts!");
			if(CAM_rate > 0)		//We have CAM traffic
			{
				client[0] = new Client(MAX_NODES,1.0/CAM_rate,0.0,0,0);
				client[0].valid = true;
				client[0].hop = -1;
				client[0].next_packet = nextArrival(client[0].packet_interval, 0);
			}
			else
				client[0] = new Client();
			
			for(int i=1; i<MAX_NODES; i++)
			{
				int h = hops[i-1];
				double d,x;
				int t;
				if(manual)
				{
					x = 1.0/nodes[i-1][0];
					d = nodes[i-1][1];
					t = (int)nodes[i-1][2];
				}
				else
				{
					rand.rngs.selectStream(0);
					d = delay_mean * rand.uniform(0.5, 1.5);
					rand.rngs.selectStream(1);
					x = 1.0/packet_rate;
					t = arrival_distribution;
				}
				
				System.out.print("Client " + i + ": delay= " + (int)(d*1000) + ", rate =" + (int)(1.0/x) + ", dis=" + t + ", hop=" + h);
				client[i] = new Client(i,x,d,h,t);
				
				/* TODO: Protocol Selection */
				if(protocol == 0)
					client[i].listen_interval = 1;
				else
				{
					client[i].listen_interval = getInterval(d);
					if(protocol == 2 && client[i].hop == 0)
							client[i].listen_interval = 1;
				}
				loadTrace(client[i]);
			}
		}
		else		//Client
		{
			System.out.println("Client " + NID + " starts with protocol " + protocol);
			rand.rngs.selectStream(0);
			double d = delay_mean * rand.uniform(0.5, 1.5);
			rand.rngs.selectStream(1);
			double x = 1.0/upload_rate;
			client[0] = new Client(0,x,d,0,arrival_distribution);
			rand.rngs.selectStream(2);
			client[0].next_packet = nextArrival(client[0].packet_interval, 0);
		}
		//WiFi start listenning
		new Thread(new WiFiRecvListen(this)).start();
		//Main logic start
		while(true)
		{
			try
			{
				Thread.sleep(1);
				slotHandle();
				traffic();
				if(isAP() && slot % result_interval == 0)
					printResult();
				slot++;
			}
			catch (InterruptedException exception)
			{
				System.err.println("Slot progressing error!");
			}
		}
	}
	
	/* Trace loading: each line records one instance of packet arrival interval */
	public void loadTrace(Client c)
	{
		try
		{
			File file = new File(tracefile + c.nodeid);
			if(!file.exists())			//Does not exist, using random
			{
				System.out.println(", arrival= random");
				return;
			}
			else
			{
				c.pkt_trace = new int[tracesize+1];
				c.freq_trace = new double[tracesize/tracewindow + 1];
				c.wakeup_z = new int[tracesize/tracewindow + 1];
				c.wakeup_w = new int[tracesize/tracewindow + 1];
				c.freq_z = new double[tracesize/tracewindow + 1];
				c.freq_w = new double[tracesize/tracewindow + 1];
			}
			
			String line = null;
			int index = 0;
			BufferedReader read = new BufferedReader(new FileReader(file));
			while((line = read.readLine()) != null && index < tracesize)
			{
				if(line.equals("") || line.startsWith("#")) continue;
				c.pkt_trace[index] = Integer.parseInt(line);
				index++;
			}
			read.close();
			c.pkt_trace[index] = -1;			//-1: indicate the end
			System.out.println(", arrival= trace");
		}
	    catch (IOException ex)
	    {
	        ex.printStackTrace();
	    }

	}

//Record the result of a trace window
	public void logTrace(Client c, int index)
	{
		c.freq_trace[index] = c.num_wakeup/((slot - c.last_window)*SLOT__LEN);
		c.freq_z[index] = c.num_wakeup_z/((slot - c.last_window)*SLOT__LEN);
		c.freq_w[index] = c.num_wakeup_w/((slot - c.last_window)*SLOT__LEN);
		c.wakeup_z[index] = c.num_wakeup_z;
		c.wakeup_w[index] = c.num_wakeup_w;
		c.num_wakeup_w = 0;
		c.num_wakeup_z = 0;
		c.num_wakeup = 0;
		c.last_window = slot;
	}
	
//Write trace final results to file	
	public void writeTraceResult(Client c)
	{
		try
		{
			// Create file 
			FileWriter fstream = new FileWriter("log"+c.nodeid);
			BufferedWriter out = new BufferedWriter(fstream);
			
			// Print out setting
			out.write("#Log of Client "+ c.nodeid);
			out.newLine();
			out.write("#Packet index   |   Wakeup frequency    |    Z_frequency   |   W_frequency   |   Z_wakeup   |   W_wakeup");
			out.newLine();
			for(int i=1; i<tracesize/tracewindow; i++)
			{
				if(c.freq_trace[i] > 0.0)
				{
					out.write((i*tracewindow) + "   " + c.freq_trace[i]+ "   " + c.freq_z[i]+ "   " + c.freq_w[i]+ "   " + c.wakeup_z[i]+ "   " + c.wakeup_w[i]);
					out.newLine();
				}
				else
					break;
			}
			//Close the output stream
			out.close();
		}
		catch (Exception e){//Catch exception if any
			System.err.println("Error: " + e.getMessage());
		}
	}
	
//Handle packet transmission
/*	public void slotHandle()
	{
		/*AP Handler*/
	/*	int i = slot % BEACON__INTERVAL;
		if(isAP())
		{
			//Broadcast beacon message with TIM
			if(i == 0)
			{
				int index = slot/BEACON__INTERVAL;
				for(int j = 1; j < MAX_NODES; j++)
				{
					if(index % client[j].listen_interval == 0 && client[j].valid)
					{
						client[j].last_regular_wake = slot;
						client[j].up = true;
						client[j].num_wakeup_w ++;
					}
				}
				/* TODO: Protocol Selection */
	/*		if(protocol == 2)
				{
					runProtocol(index);
				}

				//Send beacon message with TIM
				sendBeacon();
			}
		}
		/* Client Handler*/
	/*	else
		{
			if(!joined && i == 0)   //Client try to join the AP
				sendJoin();
		}
	}
	
//Generate packets
 * */
	public void traffic()
	{
		if(isAP())
		{
			for(int i=0; i<MAX_NODES; i++)
			{
				if(!client[i].valid) continue;
				if(slot == client[i].next_packet)
				{
					if(client[i].hop >= 0)	//PSM nodes
					{
						client[i].push();
						rand.rngs.selectStream(2);

						client[i].next_packet = nextArrival(client[i].packet_interval, i);
					}
					else		//CAM nodes
					{
						client[i].push();
						sendWiFi(client[i].pop(NID));		//CAM client sends packet immediately
						client[i].next_packet = nextArrival(client[i].packet_interval, i);
					}
				}
			}
		}
		else
		{
			if(slot == client[0].next_packet)      //Client upload packet immediately
			{
				client[0].push();
				sendWiFi(client[0].pop(NID));
				rand.rngs.selectStream(2);
				client[0].next_packet = nextArrival(client[0].packet_interval, 0);
			}
		}
	}
	
	public int nextArrival(double interval, int id)
	{
		rand.rngs.selectStream(2);
		switch(client[id].arrival_dis)
		{
			case 0:			//Poisson arrival
				if(client[id].pkt_trace == null)
					return slot + (int)(rand.exponential(interval)/SLOT__LEN + 1.0);
				else
				{
					if(tracesize == client[id].trace_index || client[id].pkt_trace[client[id].trace_index] < 0)
					{
						writeTraceResult(client[id]);
						client[id].pkt_trace = null;
						return slot + (int)(rand.exponential(interval)/SLOT__LEN + 1.0);
					}
					else
					{
						if(client[id].trace_index % tracewindow == 0)
							logTrace(client[id],client[id].trace_index / tracewindow);
						return slot + client[id].pkt_trace[client[id].trace_index++];
					}
				}
			case 1:			//Uniform arrival
				if(client[id].pkt_trace == null)
					return slot + (int)(rand.uniform(0.5, 1.5)*interval/SLOT__LEN + 1.0);
				else
				{
					if(tracesize == client[id].trace_index || client[id].pkt_trace[client[id].trace_index] < 0)
					{
						writeTraceResult(client[id]);
						client[id].pkt_trace = null;
						return slot + (int)(rand.uniform(0.5, 1.5)*interval + 1.0);
					}
					else
					{
						if(client[id].trace_index % tracewindow == 0)
							logTrace(client[id],client[id].trace_index / tracewindow);
						return slot + client[id].pkt_trace[client[id].trace_index++];
					}
				}
			case 2:			//Normal arrival
				if(client[id].pkt_trace == null)
					return slot + (int)(rand.PNormal(interval, interval/3) + 1.0);
				else
				{
					if(tracesize == client[id].trace_index || client[id].pkt_trace[client[id].trace_index] < 0)
					{
						writeTraceResult(client[id]);
						client[id].pkt_trace = null;
						return slot + (int)(rand.PNormal(interval, interval/3)/SLOT__LEN + 1.0);
					}
					else
					{
						if(client[id].trace_index % tracewindow == 0)
							logTrace(client[id],client[id].trace_index / tracewindow);
						return slot + client[id].pkt_trace[client[id].trace_index++];
					}
				}
			default:
				System.out.println("Wrong distribution!");
				return -1;
		}
	}

/*	public void sendWakeUp(SchedSet[] wSet)
	{
		//Wake sleeping node
		int j = 0;
		short[][] wlist = new short[MAX_NODES][2];
		for(int i=0; i<MAX_NODES; i++)
		{
			int id = wSet[1].nid[i];
			if(id > 0)
			{
				if(client[id].hop == 1) //one-hop wake up
				{
					wlist[j][0] = (short)id;
					wlist[j][1] = (short)0;
					client[id].up = true;
					client[id].num_wakeup_z ++;
				}
				else if(client[id].hop == 2) //two-hop wake up
				{
					wlist[j][0] = (short)client[id].nexthop;			//the relayer's id
					wlist[j][1] = (short)id;
					client[id].up = true;
					client[id].num_wakeup_z ++;
				}
				else
					System.err.println("Wrong hop!");
				j++;
			}
			else
				break;
		}
    }
*/
/*	public void sendWiFi(DatagramPacket packet)
	{
		try	{
			if(Debug)
			{
				byte[] buf = packet.getData();
				String[] data = (new String(buf)).split(":");
				if(isAP())
					System.out.println("Slot " +slot + "- AP WF-TX "+ PKT_TYPE[Integer.parseInt(data[0])] + " from Node " + data[1] + " to "+ data[2] + " : Len=" + buf.length + ",Data=" + new String(buf));
				else
					System.out.println("Slot " +slot + "- Client " + NID + " WF-TX "+ PKT_TYPE[Integer.parseInt(data[0])] + " from Node " + data[1] + " to "+ data[2] + " : Len=" + buf.length + ",Data=" + new String(buf));
			}
			DatagramSocket udpClient = new DatagramSocket();
			udpClient.send(packet);
		}
		catch(Exception e) {
			System.err.println("UDP Socket Send:"+ e);
	    }
	}

	//Send by AP to inform the client about the possible traffic
	public void sendBeacon()
	{
		String message = "1:" + NID + ":255:";
		for(int i = 1; i < MAX_NODES; i++)
		{
			if(!client[i].valid) continue;
			if(client[i].up)
			{
				client[i].num_wakeup ++;
				message += i + ":" + client[i].queue_len + ":";
			}
			client[i].up = false;
		}
		if(message.getBytes().length < BEACON_SIZE)
			message += new String(new byte[BEACON_SIZE - message.getBytes().length]);
		
		byte[] data = message.getBytes();
		DatagramPacket packet = null;
		try{
			InetAddress address = InetAddress.getByName("192.168.2.0");		//Broadcast
			packet = new DatagramPacket(data, data.length, address, ZPSM.portNum);
		}
		catch(Exception e)
		{
			System.err.println("Beacon generation:"+ e);
		}

		sendWiFi(packet);
	}

	//Send PS-Poll to retrieve packet after a short random backoff
	public void sendPS_Poll()
	{
		String message = "2:" + NID + ":0:";
		if(message.getBytes().length < PS_POLL_SIZE/8)
			message += new String(new byte[PS_POLL_SIZE/8 - message.getBytes().length]);
		byte[] data = message.getBytes();
		DatagramPacket packet = null;
		try{
			InetAddress address = InetAddress.getByName("192.168.2.1");		//To AP
			packet = new DatagramPacket(data, data.length, address, ZPSM.portNum);
		}
		catch(Exception e)
		{
			System.err.println("Beacon generation:"+ e);
		}

		sendWiFi(packet);
	}
	
	//Send Join packet
	public void sendJoin()
	{
		String message = "3:" + NID + ":0:";
		byte[] data = message.getBytes();
		DatagramPacket packet = null;
		try{
			InetAddress address = InetAddress.getByName("192.168.2.1");		//To AP
			packet = new DatagramPacket(data, data.length, address, ZPSM.portNum);
		}
		catch(Exception e)
		{
			System.err.println("Join generation:"+ e);
		}

		sendWiFi(packet);
	}

	public void recvWiFi(String[] str)
	{
		switch(Integer.parseInt(str[0]))
		{
			case 0:
				recvData(str);
				break;
			case 1:
				recvBeacon(str);
				break;
			case 2:
				recvPS_Poll(str);
				break;
			case 3:
				recvJoin(str);
				break;
			default:
				System.err.println("Wrong packet type on WiFi!");
		}
		
	}
	*/
//Receive Data
/*	public void recvData(String[] msg)
	{
		if(isAP())
		{
			//Uploading data packet for collect results from clients
			int id = Integer.parseInt(msg[1]);
			client[id].miss = Integer.parseInt(msg[5]);
			client[id].meet = Integer.parseInt(msg[6]);
			client[id].control_energy = Integer.parseInt(msg[7])/10.0;
			client[id].idle_energy = Integer.parseInt(msg[8])/10.0;
			client[id].packet_energy = Integer.parseInt(msg[9])/10.0;
		}
		else
		{
			if(wake_slot > 0)
			{
				idle_energy += (System.nanoTime() - wake_slot) * WF_Idle_Power / 1000000000.0;
				wake_slot = -1;
			}
			packet_energy += ACK__ENERGY + PACKET__ENERGY;
						
			if(Integer.parseInt(msg[3]) < Integer.parseInt(msg[4]))
				miss ++;
			else
				meet ++;
		}
	}

//Get information from the beacon message
	public void recvBeacon(String[] msg)
	{
		//indicate no packet has been successfully retrieved in the last beacon
		for(int i = 3; i+1 < msg.length; i += 2)
			if(Integer.parseInt(msg[i]) == NID)
			{
				joined = true;			//Joined ACK
				//Have packets to retrieve
				if(Integer.parseInt(msg[i+1]) > 0)
				{
					sendPS_Poll();			//Send PS Poll ASAP
					control_energy += PSPOLL__ENERGY;
					wake_slot = System.nanoTime();
				}
				control_energy += BEACON__ENERGY;
				return;
			}
	}

/* Put the packets to the transmission queue */
/*	public void recvPS_Poll(String[] msg)
	{
		int id = Integer.parseInt(msg[1]);
		if(!isAP() || id <= 0)	System.err.println("Wrong PS_POLL receiver!");

		int size = client[id].queue_len;
		for(int i = 0; i<size; i++)
			sendWiFi(client[id].pop(0));
	}

//Receive join packet
	public void recvJoin(String[] msg)
	{
		int id = Integer.parseInt(msg[1]);
		if(!client[id].valid)
		{
			nNode ++;
			bw_quota = (int)((250000/8.0)*(available_bw*nNode/100.0)/(3*(1.0/BEACON__LEN)));
			System.out.println("Client " + id + " join at time " + (int)(slot*SLOT__LEN));
			client[id].join();
			//Start traffic
			client[id].next_packet = nextArrival(client[id].packet_interval, id);			
			next_join = slot + join_interval;
		}
	}
	
/*---------------------------------------------------------------
--------------------------Protocol--------------------------
---------------------------------------------------------------*/
/*	public void runProtocol(int index)
	{
		//Initialize the data structure to be used
		SchedSet[] bSet = new SchedSet[MAX_SET];		//S_k: the array index is k
		SchedSet[] wSet = new SchedSet[MAX_SET];		//Theta: the array index is the allocated beacon
		int quota = 0;
		for(int i=0; i<MAX_SET; i++)
		{
			wSet[i] = new SchedSet();
			bSet[i] = new SchedSet();
			wSet[i].valid = bSet[i].valid = false;
			for(int j=0; j<MAX_NODES; j++)
				wSet[i].nid[j] = bSet[i].nid[j] = 0;
		}

		//Get S_k set
		getSchedSet(bSet, index);

		Integer count = new Integer(0);

		//Schedule S_1
		quota = schedBeaconOne(bSet, wSet, quota, count);

		/* Simple Protocol */
		//Schedule S_2 ...
	/*	if(quota < bw_quota)
			quota = schedBeaconOthersSimple(bSet, wSet, quota, index, count);

		//Send out ZigBee packets to wake up corresponding clients
		if(wSet[1].valid)
			sendWakeUp(wSet);

		//Update quota estimation
		quota_his[quota_index % MAX__HIS] = quota;
		quota_index++;

		//Attempt to increase regular listen interval
		if(index % 10 == 0)
			incrRegListenInterval();
	}
	
	/* Get S_k set */
/*	public void getSchedSet(SchedSet[] bSet, int index)
	{
		//Get beacon set
		for(int i = 1; i < MAX_NODES; i++)
		{
			if(!client[i].valid) continue;
			if(client[i].hop <= 0) { client[i].safe = false; continue; }				//This is SPSM node
			client[i].safe = true;
			int interval = client[i].listen_interval;
			//Not empty queue and not at regular wake-up now
			if(index % interval != 0 && client[i].queue_len > 0)
			{
				int nextInterval = interval - (index % interval) + 1;			//number of intervals before next regular wake-up
				int deadline = client[i].queue[client[i].head];
				//Miss deadline
				if(deadline < slot + nextInterval*BEACON__INTERVAL)
				{
					int k = (deadline - slot)/BEACON__INTERVAL;		//compute the number of chance to wake up
					if(k < 4) k = 0;
					if(k > MAX_SET - 1) k = MAX_SET - 1; 
					bSet[k].valid = true;
					for(int j=0; j<MAX_NODES; j++)
						if(bSet[k].nid[j] == 0)
						{
							client[i].safe = false;
							bSet[k].nid[j] = i;
							bSet[k].numPkt[j] = client[i].queue_len;
							bSet[k].interval[j] = deadline - client[i].last_regular_wake - 1;					//The new regular listen interval if missed
							break;
						}
				}
			}
		}
	}

	/* Schedule the nodes that have one beacon to wake up */
	/*public int schedBeaconOne(SchedSet[] bSet, SchedSet[] wSet, int quota, Integer count)
	{
		// Consider k = 0 and k = 1
		int x = 0;
		for(int i=1; i>=0; i--)
		{
			if(bSet[i].valid)
			{
				wSet[1].valid = true;
				while(true)
				{
					//Find the most efficient use of bandwidth
					double max_pkt = 0.0;
					int max_index = -1;
					for(int j=0; j<MAX_NODES; j++)
					{
						int nid = bSet[i].nid[j];
						if(nid > 0)
						{
							double metric = ((double)(bSet[i].numPkt[j]))/client[nid].hop;
							if(max_pkt < metric)
							{
								max_pkt = metric;
								max_index = j;
							}
							count = count + 1;
						}
					}
					if(max_index < 0)	break;

					//Record the best allocation
					int nid = bSet[i].nid[max_index];
					if(quota + client[nid].hop <= bw_quota)
					{
						wSet[1].nid[x] = bSet[i].nid[max_index];
						wSet[1].numPkt[x] = bSet[i].numPkt[max_index];
						quota = quota + client[nid].hop;
						bSet[i].nid[max_index] = 0;				// 0: scheduled and removed
						x++;
					}
					else
						bSet[i].nid[max_index] = -bSet[i].nid[max_index];					// -: not scheduled
				}
			}
		}

		//Decrease regular listen interval when necessary
		for(int i=1; i>=0; i--)
		{
			if(bSet[i].valid)
			{
				for(int j=0; j<MAX_NODES; j++)
				{
					int nid = bSet[i].nid[j];
					if(nid < 0)
						client[(-nid)].listen_interval = bSet[i].interval[j] < 1 ? 1 : bSet[i].interval[j];					//NOTE!
				}
			}
		}
		return quota;
	}

	/* Schedule the nodes that have more than one beacon to wake up */
/*	public int schedBeaconOthersSimple(SchedSet[] bSet, SchedSet[] wSet, int quota, int index, Integer count)
	{
		for(int k=2; k<MAX_SET; k++)
		{
			if(bSet[k].valid)
			{
				while(true)				//Allocate one node with one iteration
				{
					double min_e = POSITIVE__INFINITY;
					int min_i = -1;
					for(int i=0; i<MAX_NODES; i++)			//Find the best node to allocate
					{
						int nid = bSet[k].nid[i];
						if(nid > 0)
						{
							Integer next_packet = new Integer(0);
							double d = computeEnergy(wSet, nid, bSet[k].numPkt[i], k, 1, index, next_packet);
							if(min_e > d && max_packet - next_packet < bSet[k].numPkt[i])
							{
								min_e = d;
								min_i = i;
							}
							count = count + 1;
						}
					}
					if(min_i < 0) break;

					//Record the allocated node
					for(int i=0; i<MAX_NODES; i++)
						if(wSet[1].nid[i] == 0)
						{
							wSet[1].valid = true;
							wSet[1].nid[i] = bSet[k].nid[min_i];
							wSet[1].numPkt[i] = bSet[k].numPkt[min_i];
							break;
						}

					//Bandwidth update
					int nid = bSet[k].nid[min_i];
					if(quota + client[nid].hop <= bw_quota)
						quota = quota + client[nid].hop;

					//All bandwidth has been allocated
					if(quota == bw_quota) return quota;

					//Remove the allocated one from S_k
					bSet[k].nid[min_i] = 0;
				}
			}
		}
		return quota;
	}

	/* Compute the energy gain/loss by add a node nid with numPkt packet at beacon j */
/*	public double computeEnergy(SchedSet[] wSet, int nid, int numPkt, int k_max, int j_chosen, int index, Integer next_packet)
	{
		double oldE = computeEnergy(wSet, k_max, index, next_packet);
		boolean valid = wSet[j_chosen].valid;
		int j = 0;
		for(int i=0; i<MAX_NODES; i++)
			if(wSet[j_chosen].nid[i] == 0)
			{
				wSet[j_chosen].valid = true;
				wSet[j_chosen].nid[i] = nid;
				wSet[j_chosen].numPkt[i] = numPkt;
				j = i;
				break;
			}

		double newE = computeEnergy(wSet, k_max, index, null);
		wSet[j_chosen].valid = valid;
		wSet[j_chosen].nid[j] = 0;

//		printf("Delta = %f \t %f\n",newE, oldE);
		return newE - oldE;
	}

	public double computeEnergy(SchedSet[] wSet, int k_max, int index, Integer next_packet)
	{
		double energy = 0.0;
		double packet = 0.0;
		for(int j=1; j<=k_max; j++)
		{
			/*Compute n_j and L_j*/
	/*		int s = index + (j - 1);
			double n_j = 0.0;
			double L_j = 0.0;

			//SPSM nodes
			for(int i=1; i<MAX_NODES; i++)
			{
				if(!client[i].valid) continue;
				if(client[i].hop == 0 && s % client[i].listen_interval == 0)			//SPSM node wake up at beacon j
				{
					n_j += 1.0;
					L_j += client[i].listen_interval * BEACON__LEN / client[i].packet_interval;
				}
			}

			if(wSet[j].valid)
			{
				//Scheduled ZPSM nodes
				for(int i=0; i<MAX_NODES; i++)
				{
					int id = wSet[j].nid[i];
					if(id > 0)
					{
						n_j += 1.0;
						L_j += wSet[j].numPkt[i] + (j - 1) * BEACON__LEN / client[id].packet_interval;
//						printf("A: n_j = %f, L_j = %f\n",n_j, L_j);
					}
				}

				//Unscheduled ZPSM nodes
				for(int k = 1; k<j; k++)
				{
					if(!wSet[k].valid) continue;
					for(int i=0; i<MAX_NODES; i++)
					{
						int id = wSet[k].nid[i];
						if(id > 0)
						{
							double x = estimateX(client[id].listen_interval, client[id].delay_mean*SLOT__LEN, 1.0/client[id].packet_interval);
							//Regular wake up
							if(s % client[id].listen_interval == 0)
								n_j += 1.0;
							//On-demand wake up
							else
								n_j += x/client[id].listen_interval;
							L_j += client[id].listen_interval * BEACON__LEN / (client[id].packet_interval * (x + 1.0));
//							printf("B: n_j = %f, L_j = %f\n",n_j, L_j);
						}
					}
				}
			}
			//Safe ZPSM nodes
			for(int i=1; i<MAX_NODES; i++)
			{
				if(!client[i].valid) continue;
				if(client[i].safe)
				{
					double x = estimateX(client[i].listen_interval, client[i].delay_mean*SLOT__LEN, 1.0/client[i].packet_interval);
					//Regular wake up
					if(s % client[i].listen_interval == 0)
						n_j += 1.0;
					//On-demand wake up
					else
						n_j += x/client[i].listen_interval;
					L_j += client[i].listen_interval * BEACON__LEN / client[i].packet_interval;
//					printf("C: n_j = %f, L_j = %f\n",n_j, L_j);
				}
			}

			/* Compute result */
/*			if(L_j > max_packet)
			{
				energy += n_j * BEACON__ENERGY + (n_j - 1) * max_packet * IDLE__ENERGY / 2 + max_packet * (L_j - max_packet) * n_j * IDLE__ENERGY / (4 * L_j);
				packet += max_packet;
//				printf("A: e = %f, p = %f, L_j = %f, max = %d\n",energy, packet, L_j, max_packet);
			}
			else
			{
				energy += n_j * BEACON__ENERGY + (n_j - 1) * L_j * IDLE__ENERGY / 2;
				packet += L_j;
//				printf("B: e = %f, p = %f, L_j = %f\n",energy, packet, L_j);
			}
			if(j == 1 && next_packet != null) next_packet = (int)(L_j + 1.0);
		}

		if(packet > 0.0)
			return energy/packet;
		else
			return 0.0;
	}

	/* Attempt to increase regular listen interval in order to save energy */
	/* Use feedback to increase regluar listen interval  */
	/*public void incrRegListenInterval()
	{
		double quota = 0.0;

		for(int i=0; i<MAX__HIS; i++)
			if(quota < quota_his[i])
				quota = quota_his[i];

		int delta = 2;
		int count = 0;
		while(true)
		{
			double energy = 0.0;
			int index = 0;
			for(int i = 1; i<MAX_NODES; i++)
			{
				if(client[i].hop > 0 && client[i].valid && client[i].listen_interval < MAX__LISTEN__INTERVAL)
				{
					int b = client[i].hop;
					double e = computeEnergyIncr(i, delta)/(b*client[i].listen_interval);
					if(energy < e && bw_quota >= quota + b)
					{
						energy = e;
						index = i;
					}
				}
			}
			if(index > 0)
			{
				client[index].listen_interval += delta;
				if(client[index].listen_interval >= MAX__LISTEN__INTERVAL) client[index].listen_interval = (int)MAX__LISTEN__INTERVAL;
				quota += client[index].hop;
			}
			else
				break;
//			delta = 2*delta;
		}
	}

	/* The energy saving by increasing regular listen interval by one unit */
	/*public double computeEnergyIncr(int nid, int delta)
	{
		double N_active = 0.0;
		double N_active_ = 0.0;
		double lambda = 0.0;
		int n = 0;
		for(int i = 1; i<MAX_NODES; i++)
		{
			if(!client[i].valid) continue;
			if(client[i].hop == 0)
			{
				n++;
				lambda += 1.0/client[i].packet_interval;
				N_active += 1.0/client[i].listen_interval;
				N_active_ += 1.0/client[i].listen_interval;
			}
			if(client[i].hop > 0)
			{
				n++;
				lambda += 1.0/client[i].packet_interval;
				double x = (1.0+estimateX(client[i].listen_interval, client[i].delay_mean*SLOT__LEN, 1.0/client[i].packet_interval))/client[i].listen_interval;
				N_active += x;
				if(i == nid)
					N_active_ += (1.0+estimateX(client[i].listen_interval+delta, client[i].delay_mean*SLOT__LEN, 1.0/client[i].packet_interval))/(client[i].listen_interval+delta);
				else
					N_active_ += x;
			}
		}

		if(n == 0) return 0.0;
		else
		{
			double e = (N_active * N_active * BEACON__ENERGY)/(lambda*BEACON__LEN*n) + (N_active - 1) * IDLE__ENERGY/2.0;
			double e_ = (N_active_ * N_active_ * BEACON__ENERGY)/(lambda*BEACON__LEN*n) + (N_active_ - 1) * IDLE__ENERGY/2.0;
//			ASSERT(e - e_ >= 0.0);
			return e - e_;
		}
	}
	
	public double estimateX(double y, double d, double l)
	{
		if((y+1)*BEACON__LEN <= d)
			return 0.0;
		else
			return (y - (int)((d-BEACON__LEN)/BEACON__LEN))*(1.0 - Math.pow(2.7182818284,-l*BEACON__LEN));
	}
/*---------------------------------------------------------------
--------------------------Main function--------------------------
---------------------------------------------------------------*/
	public static void main(String[] args) throws Exception
	{
		//Parse arguments
		String source = null;
		int nid = 0;
		if (args.length == 3)
		{
			nid = Integer.parseInt(args[1]);
			protocol = Integer.parseInt(args[0]);
			if(args[2].equals("on"))
				ZPSM.Debug = true;
			else if(args[2].equals("off"))
				ZPSM.Debug = false;
			else
			{
				System.err.println("usage: ZPSM [<protocol> <node id> <on/off>]");
				System.exit(1);
			}
			
		}
		else if (args.length != 0)
		{
			System.err.println("usage: ZPSM [<protocol> <node id> <on/off>]");
			System.exit(1);
		}
		
		ZPSM psm = new ZPSM(nid);
		psm.start();
	}
	
/*	public int getInterval(double t) {
		int i = (int)(t/(BEACON__INTERVAL*SLOT__LEN)); if(i>1) return i; else return 1;
	}
	
	public boolean isAP() { if(NID > 0) return false; else return true; }

}

/*-------- Client Block --------*/
class Client
{
	public Client(int i, double x, double d, int h, int t)		//packet rate and delay in slot
	{
		nodeid = i;
		packet_interval = x;
		delay_mean = (int)(d/ZPSM.SLOT__LEN);
		hop = h;
		arrival_dis = t;
	}
	
	public Client()
	{
		nodeid = -1;
		valid = false;
	}
	
	boolean valid = false;						//true - when joined
	int 	nodeid;
	
	int[]		pkt_trace = null;			//record the packet trace
	double[] 	freq_trace = null;
	double[]	freq_z = null;
	double[]	freq_w = null;
	int[]		wakeup_z = null;
	int[]		wakeup_w = null;
	int num_wakeup_z = 0;
	int num_wakeup_w = 0;
	int num_wakeup = 0;
	int last_window = 0;
	
	int		trace_index = 0;	//index of next packet in the array
	
	double 	packet_interval;
	int		next_packet = ZPSM.POSITIVE__INFINITY;			//The slot at which the next packet arrives
	int 	arrival_dis = 0;		//0: Poisson; 1: Uniform; 2: Normal
	
	int		queue_len = 0;
	int[]	queue = new int[ZPSM.QUEUE_LIMIT];				//Packet queue stores the arrival time
	int		drop = 0;
	int 	head = 0;
	int		tail = 0;
	
	int 	delay_mean;
	
	int hop;				//in the ZigBee range: -1 CAM; 0 - SPSM; 1 or 2: ZPSM
	int nexthop;				// relayer

	boolean up = false;						// true - up at next slot; false - sleep at next slot
	boolean safe = false;					//true - safe ZPSM nodes which do not need to be scheduled

	int listen_interval;
	int last_regular_wake;			//the time of the most recent regular wake up
	
	int miss = 0;
	int meet = 0;
	double control_energy = 0.0;			//Receive beacon and send PS_POLL in (mJ)
	double idle_energy = 0.0;				//Number of idle slots in (mJ)
	double packet_energy = 0.0;			//Receive data packets in (mJ)
	
	public void join()
	{
		valid = true;
		queue_len = 0;
		drop = 0;
		up = false;
		safe = false;
		miss = 0;
		meet = 0;
		control_energy = 0.0;
		idle_energy = 0.0;
		packet_energy = 0.0;
		last_window = ZPSM.slot;
		num_wakeup = 0;
		num_wakeup_w = 0;
		num_wakeup_z = 0;
	}
		
	public void push()
	{
		if(queue_len == ZPSM.QUEUE_LIMIT){
			drop ++;
		}else{
			queue[tail] = ZPSM.slot + delay_mean;
			tail = (tail + 1) % ZPSM.QUEUE_LIMIT;
			queue_len ++;
		}
	}
	
	public DatagramPacket pop(int source)
	{
		if(queue_len == 0)
			return null;
		else
		{
			String message = "0:" + source + ":" + nodeid + ":"+ (queue[head]+ZPSM.BEACON__INTERVAL) + ":" + ZPSM.slot + ":";
			
			//Piggyback results in the message
			if(nodeid == 0)
				message += ZPSM.miss + ":" + ZPSM.meet + ":" + (int)(ZPSM.control_energy*10000) + ":" + (int)(ZPSM.idle_energy*10000) + ":" + (int)(ZPSM.packet_energy*10000) + ":";
			
			if(nodeid > 0 && nodeid < ZPSM.MAX_NODES)
				ZPSM.send_pkt ++;
			
			//Fill the message to the packet size
			message += new String(new byte[ZPSM.PACKET_SIZE - message.getBytes().length]);
			byte[] data = message.getBytes();
			DatagramPacket packet = null;
			try{
				InetAddress address = InetAddress.getByName("192.168.2." + (nodeid + 1));
				packet = new DatagramPacket(data, data.length, address, ZPSM.portNum);
			}
			catch(Exception e)
			{
				System.err.println("Packet generation:"+ e);
			}
			queue_len--;
			head = (head + 1) % ZPSM.QUEUE_LIMIT;
			return packet;
		}
	}
}

class SchedSet
{
	boolean valid;												//not used if false
	int[] nid = new int[ZPSM.MAX_NODES];						//id of the node
	int[] numPkt = new int[ZPSM.MAX_NODES];				//number of buffered packets
	int[] interval = new int[ZPSM.MAX_NODES];				//the interval to decrease if missed
};


/*-------- Receiving Listener Thread --------*/
class WiFiRecvListen implements Runnable
{
	private ZPSM psm;
	public WiFiRecvListen(ZPSM psm)
	{
		this.psm = psm;
	}
	
	//UDP: RECV DATA	
	public void run()
	{
		try
		{
			DatagramSocket socket = new DatagramSocket(psm.portNum);
			while(true)
			{
				byte[] buf = new byte[ZPSM.PACKET_SIZE];
				DatagramPacket packet = new DatagramPacket(buf, buf.length);
				socket.receive(packet);
				String[] data = (new String(buf)).split(":");
				if(Integer.parseInt(data[1]) != ZPSM.NID)  //Prevent loop
				{
					if(ZPSM.Debug)
					if(psm.isAP())
						System.out.println("Slot " + ZPSM.slot + "- AP WF-RX "+ ZPSM.PKT_TYPE[Integer.parseInt(data[0])] + " from Node "+ data[1] + " to "+ data[2] +" : Len=" + buf.length + ",Data=" + new String(buf));
					else
						System.out.println("Slot " + ZPSM.slot + "- Client " + ZPSM.NID + " WF-RX "+ ZPSM.PKT_TYPE[Integer.parseInt(data[0])] + " from Node "+ data[1] + " to "+ data[2] +" : Len=" + buf.length + ",Data=" + new String(buf));
					psm.recvWiFi(data);
				}
			}
		}
		catch(Exception e)
	    {
			System.err.println("UDP Socket Receive:"+ e);
			e.printStackTrace();
	    }
	}
}

/*------------------------------------------------------------------
----------------------Random Number Generator ----------------------
------------------------------------------------------------------*/
class Rvgs{
    
    public Rngs rngs; 
    
    public Rvgs(){
   	rngs = new Rngs();
    }
    
    public long bernoulli(double p)
    {
	return ((rngs.random() < (1.0 - p)) ? 0 : 1);
    }
    
    public long equilikely(long a, long b)
    {
	return (a + (long) ((b - a + 1) * rngs.random()));
    }

    public double uniform(double a, double b)
    { 
	return (a + (b - a) * rngs.random());
    }

    public double exponential(double m)
    {
	return (-m * Math.log(1.0 - rngs.random()));
    }
    
    public double PNormal(double m, double s)
    {
    	double x = 0.0;
    	do
    	{
    		x = normal(m,s);
    	}while(x <= 0.0);
    	return x;
    }
    
    public double normal(double m, double s)
    /* ========================================================================
     * Returns a normal (Gaussian) distributed real number.
     * NOTE: use s > 0.0
     *
     * Uses a very accurate approximation of the normal idf due to Odeh & Evans, 
     * J. Applied Statistics, 1974, vol 23, pp 96-97.
     * ========================================================================
     */
    { 
      final double p0 = 0.322232431088;     final double q0 = 0.099348462606;
      final double p1 = 1.0;                final double q1 = 0.588581570495;
      final double p2 = 0.342242088547;     final double q2 = 0.531103462366;
      final double p3 = 0.204231210245e-1;  final double q3 = 0.103537752850;
      final double p4 = 0.453642210148e-4;  final double q4 = 0.385607006340e-2;
      double u, t, p, q, z;

      u   = rngs.random();
      if (u < 0.5)
        t = Math.sqrt(-2.0 * Math.log(u));
      else
        t = Math.sqrt(-2.0 * Math.log(1.0 - u));
      p   = p0 + t * (p1 + t * (p2 + t * (p3 + t * p4)));
      q   = q0 + t * (q1 + t * (q2 + t * (q3 + t * q4)));
      if (u < 0.5)
        z = (p / q) - t;
      else
        z = t - (p / q);
      return (m + s * z);
    }
}

class Rngs {

  long MODULUS      = 2147483647; /* DON'T CHANGE THIS VALUE                  */
  long MULTIPLIER   = 48271;      /* DON'T CHANGE THIS VALUE                  */
  static long CHECK = 399268537L; /* DON'T CHANGE THIS VALUE                  */
  long DEFAULT      = 123456789L; /* initial seed, use 0 < DEFAULT < MODULUS  */

  int STREAMS       = 256;        /* # of streams, DON'T CHANGE THIS VALUE    */
  long A256         = 22925;      /* jump multiplier, DON'T CHANGE THIS VALUE */

  static long[] seed;                     /* current state of each stream   */
  static int  stream        = 0;          /* stream index, 0 is the default */
  static int  initialized   = 0;          /* test for stream initialization */
  
  public Rngs () {
    seed = new long[STREAMS];
  }
  
  public double random() {
    long Q = MODULUS / MULTIPLIER;
    long R = MODULUS % MULTIPLIER;
    long t;

    t = MULTIPLIER * (seed[stream] % Q) - R * (seed[stream] / Q);
    if (t > 0)
      seed[stream] = t;
    else
      seed[stream] = t + MODULUS;
    return ((double) seed[stream] / MODULUS);
  }

  public void plantSeeds(long x) {
    long Q = MODULUS / A256;
    long R = MODULUS % A256;
    int  j;
    int  s;

    initialized = 1;
    s = stream;                            /* remember the current stream */
    selectStream(0);                       /* change to stream 0          */
    putSeed(x);                            /* set seed[0]                 */
    stream = s;                            /* reset the current stream    */
    for (j = 1; j < STREAMS; j++) {
      x = A256 * (seed[j - 1] % Q) - R * (seed[j - 1] / Q);
      if (x > 0)
        seed[j] = x;
      else
        seed[j] = x + MODULUS;
    }
  }
  
    public void putSeed(long x) {
    boolean ok = false;

    if (x > 0)
      x = x % MODULUS;                            /* correct if x is too large  */
    if (x < 0) {
      Date now = new Date();
      x = now.getTime();
    }
    if (x == 0)
      while (!ok) {
        try {
          System.out.print("\nEnter a positive integer seed (9 digits or less) >> ");
          String line;
          InputStreamReader r = new InputStreamReader(System.in);
          BufferedReader ReadThis = new BufferedReader(r);
	  
          line = ReadThis.readLine();
          x = Long.parseLong(line);
        } catch (IOException e) {
        } catch (NumberFormatException nfe) {
        }
        ok = (0 < x) && (x < MODULUS);
        if (!ok)
          System.out.println("\nInput out of range ... try again");
      }
      
    seed[stream] = x;
  }

   public void selectStream(int index) {
    stream = index % STREAMS;
    if ((initialized == 0) && (stream != 0))   /* protect against        */
      plantSeeds(DEFAULT);                     /* un-initialized streams */
  }
}
