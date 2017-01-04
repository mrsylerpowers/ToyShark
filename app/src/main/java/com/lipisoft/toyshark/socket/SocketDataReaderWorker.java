package com.lipisoft.toyshark.socket;

import android.support.annotation.NonNull;
import android.util.Log;

import com.lipisoft.toyshark.IClientPacketWriter;
import com.lipisoft.toyshark.Session;
import com.lipisoft.toyshark.SessionManager;
import com.lipisoft.toyshark.ip.IPPacketFactory;
import com.lipisoft.toyshark.ip.IPv4Header;
import com.lipisoft.toyshark.tcp.PacketHeaderException;
import com.lipisoft.toyshark.tcp.TCPHeader;
import com.lipisoft.toyshark.tcp.TCPPacketFactory;
import com.lipisoft.toyshark.udp.UDPHeader;
import com.lipisoft.toyshark.udp.UDPPacketFactory;
import com.lipisoft.toyshark.util.PacketUtil;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SocketChannel;
import java.util.Date;

/**
 * background task for reading data from remote server and write data to vpn client
 * @author Borey Sao
 * Date: July 30, 2014
 */
class SocketDataReaderWorker implements Runnable {
	private static final String TAG = "SocketDataReaderWorker";
	private IClientPacketWriter writer;
	private SessionManager sessionManager;
	private String sessionKey;
	private SocketData pData;

	SocketDataReaderWorker(IClientPacketWriter writer, String sessionKey) {
		sessionManager = SessionManager.getInstance();
		pData = SocketData.getInstance();
		this.writer = writer;
		this.sessionKey = sessionKey;
	}

	@Override
	public void run() {
		Session session = sessionManager.getSessionByKey(sessionKey);
		if(session == null) {
			Log.e(TAG, "Session NOT FOUND");
			return;
		}
		if(session.getSocketChannel() != null) {
			try{
				readTCP(session);
			} catch(Exception ex){
				Log.e(TAG, "error processRead: "+ ex.getMessage());
			}
		} else if(session.getUdpChannel() != null){
			readUDP(session);
		}
			
		if(session.isAbortingConnection()) {
			Log.d(TAG,"removing aborted connection -> "+ sessionKey);
			session.getSelectionkey().cancel();
			if(session.getSocketChannel() != null && session.getSocketChannel().isConnected()){
				try {
					session.getSocketChannel().close();
				} catch (IOException e) {
					Log.e(TAG, e.toString());
				}
			}else if(session.getUdpChannel() != null && session.getUdpChannel().isConnected()){
				try {
					session.getUdpChannel().close();
				} catch (IOException e) {
					e.printStackTrace();
				}
			}
			sessionManager.closeSession(session);
		} else {
			session.setBusyread(false);
		}

	}
	
	private void readTCP(@NonNull Session session) {
		if(session.isAbortingConnection()){
			return;
		}

		SocketChannel channel = session.getSocketChannel();
		ByteBuffer buffer = ByteBuffer.allocate(DataConst.MAX_RECEIVE_BUFFER_SIZE);
		int len;

		try {
			do {
				if(!session.isClientWindowFull()) {
					len = channel.read(buffer);
					if(len > 0) { //-1 mean it reach the end of stream
						//Log.d(TAG,"SocketDataService received "+len+" from remote server: "+name);
						sendToRequester(buffer, channel, len, session);
						buffer.clear();
					} else if(len == -1) {
						Log.d(TAG,"End of data from remote server, will send FIN to client");
						Log.d(TAG,"send FIN to: " + sessionKey);
						sendFin(session);
						session.setAbortingConnection(true);
					}
				} else {
					Log.e(TAG,"*** client window is full, now pause for " + sessionKey);
					break;
				}
			} while(len > 0);
		}catch(NotYetConnectedException e){
			Log.e(TAG,"socket not connected");
		}catch(ClosedByInterruptException e){
			Log.e(TAG,"ClosedByInterruptException reading SocketChannel: "+ e.getMessage());
			//session.setAbortingConnection(true);
		}catch(ClosedChannelException e){
			Log.e(TAG,"ClosedChannelException reading SocketChannel: "+ e.getMessage());
			//session.setAbortingConnection(true);
		} catch (IOException e) {
			Log.e(TAG,"Error reading data from SocketChannel: "+ e.getMessage());
			session.setAbortingConnection(true);
		}
	}
	
	private void sendToRequester(ByteBuffer buffer, SocketChannel channel, int dataSize, Session session){
		
		if(session == null){
			Log.e(TAG,"Session not found for destination server: " + channel.socket().getInetAddress().getHostAddress());
			return;
		}
		
		//last piece of data is usually smaller than MAX_RECEIVE_BUFFER_SIZE
		if(dataSize < DataConst.MAX_RECEIVE_BUFFER_SIZE)
			session.setHasReceivedLastSegment(true);
		else
			session.setHasReceivedLastSegment(false);

		buffer.limit(dataSize);
		buffer.flip();
		byte[] data = new byte[dataSize];
		System.arraycopy(buffer.array(), 0, data, 0, dataSize);
		session.addReceivedData(data);
		//Log.d(TAG,"DataService added "+data.length+" to session. session.getReceivedDataSize(): "+session.getReceivedDataSize());
		//pushing all data to vpn client
		while(session.hasReceivedData()){
			pushDataToClient(session);
		}
	}
	/**
	 * create packet data and send it to VPN client
	 * @param session Session
	 * @return boolean
	 */
	private boolean pushDataToClient(Session session){
		if(!session.hasReceivedData()){
			//no data to send
			Log.d(TAG,"no data for vpn client");
			return false;
		}
		
		IPv4Header ipHeader = session.getLastIpHeader();
		TCPHeader tcpheader = session.getLastTcpHeader();
		int max = session.getMaxSegmentSize() - 60;
		
		if(max < 1){
			max = 1024;
		}
		byte[] packetBody = session.getReceivedData(max);
		if(packetBody != null && packetBody.length > 0) {
			long unAck = session.getSendNext();
			long nextUnAck = session.getSendNext() + packetBody.length;
			//Log.d(TAG,"sending vpn client body len: "+packetBody.length+", current seq: "+unAck+", next seq: "+nextUnAck);
			session.setSendNext(nextUnAck);
			//we need this data later on for retransmission
			session.setUnackData(packetBody);
			session.setResendPacketCounter(0);
			
			byte[] data = TCPPacketFactory.createResponsePacketData(ipHeader,
					tcpheader, packetBody, session.hasReceivedLastSegment(),
					session.getRecSequence(), unAck,
					session.getTimestampSender(), session.getTimestampReplyto());
			try {
				writer.write(data);
				pData.addData(data);
				//Log.d(TAG,"finished sending "+data.length+" to vpn client: "+PacketUtil.intToIPAddress(session.getDestAddress())+":"+session.getDestPort()+"-"+
				//		PacketUtil.intToIPAddress(session.getSourceIp())+":"+session.getSourcePort());
				
				/* for debugging purpose 
				Log.d(TAG,"========> BG: packet data to vpn client++++++++");
				IPv4Header vpnIp = null;
				try {
					vpnIp = factory.createIPv4Header(data, 0);
				} catch (PacketHeaderException e) {
					e.printStackTrace();
				}
				TCPHeader vpnTcp = null;
				try {
					vpnTcp = factory.createTCPHeader(data, vpnIp.getIPHeaderLength());
				} catch (PacketHeaderException e) {
					e.printStackTrace();
				}
				if(vpnIp != null && vpnTcp != null){
					String out = PacketUtil.getOutput(vpnIp, vpnTcp, data);
					Log.d(TAG, out);
				}
				
				Log.d(TAG,"=======> BG: finished sending packet to vpn client ========");
				if(vpnTcp != null){
					int offset = vpnTcp.getTCPHeaderLength() + vpnIp.getIPHeaderLength();
					int bodySize = data.length - offset;
					byte[] clientData = new byte[bodySize];
	        		System.arraycopy(data, offset, clientData, 0, bodySize);
	        		Log.d(TAG,"444444 Packet Data sent to Client 444444");
	        		String vpn = new String(clientData);
	        		Log.d(TAG,vpn);
	        		Log.d(TAG,"444444 End Data to Client 4444444");
				}
				*/
				
			} catch (IOException e) {
				Log.e(TAG,"Failed to send ACK + Data packet: " + e.getMessage());
				return false;
			}
			return true;
		}
		return false;
	}
	private void sendFin(Session session){
		final IPv4Header ipHeader = session.getLastIpHeader();
		final TCPHeader tcpheader = session.getLastTcpHeader();
		final byte[] data = TCPPacketFactory.createFinData(ipHeader, tcpheader,
				session.getSendNext(), session.getRecSequence(),
				session.getTimestampSender(), session.getTimestampReplyto());
		try {
			writer.write(data);
			pData.addData(data);
			/* for debugging purpose 
			Log.d(TAG,"========> BG: FIN packet data to vpn client++++++++");
			IPv4Header vpnIp = null;
			try {
				vpnIp = factory.createIPv4Header(data, 0);
			} catch (PacketHeaderException e) {
				e.printStackTrace();
			}
			TCPHeader vpnTcp = null;
			try {
				vpnTcp = factory.createTCPHeader(data, vpnIp.getIPHeaderLength());
			} catch (PacketHeaderException e) {
				e.printStackTrace();
			}
			if(vpnIp != null && vpnTcp != null){
				String out = PacketUtil.getOutput(vpnIp, vpnTcp, data);
				Log.d(TAG,out);
			}
			
			Log.d(TAG,"=======> BG: finished sending FIN packet to vpn client ========");
			*/
			
		} catch (IOException e) {
			Log.e(TAG,"Failed to send FIN packet: " + e.getMessage());
		}
	}
	private void readUDP(Session session){
		DatagramChannel channel = session.getUdpChannel();
		ByteBuffer buffer = ByteBuffer.allocate(DataConst.MAX_RECEIVE_BUFFER_SIZE);
		int len;

		try {
			do{
				if(session.isAbortingConnection()){
					break;
				}
				len = channel.read(buffer);
				if(len > 0){
					Date date = new Date();
					long responseTime = date.getTime() - session.connectionStartTime;
					
					buffer.limit(len);
					buffer.flip();
					//create UDP packet
					byte[] data = new byte[len];
					System.arraycopy(buffer.array(),0, data, 0, len);
					byte[] packetData = UDPPacketFactory.createResponsePacket(
							session.getLastIpHeader(), session.getLastUdpHeader(), data);
					//write to client
					writer.write(packetData);
					//publish to packet subscriber
					pData.addData(packetData);
					Log.d(TAG,"SDR: sent " + len + " bytes to UDP client, packetData.length: "
							+ packetData.length);
					buffer.clear();
					
					try {
						IPv4Header ip = IPPacketFactory.createIPv4Header(packetData, 0);
						UDPHeader udp = UDPPacketFactory.createUDPHeader(
								packetData, ip.getIPHeaderLength());
						String str = PacketUtil.getUDPoutput(ip, udp);
						Log.d(TAG,"++++++ SD: packet sending to client ++++++++");
						Log.i(TAG,"got response time: " + responseTime);
						Log.d(TAG,str);
						Log.d(TAG,"++++++ SD: end sending packet to client ++++");
					} catch (PacketHeaderException e) {
						e.printStackTrace();
					}
				}
			} while(len > 0);
		}catch(NotYetConnectedException ex){
			Log.e(TAG,"failed to read from unconnected UDP socket");
		} catch (IOException e) {
			e.printStackTrace();
			Log.e(TAG,"Failed to read from UDP socket, aborting connection");
			session.setAbortingConnection(true);
		}
	}
}
