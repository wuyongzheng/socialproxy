package com.socialproxy.bittorrent;

import java.io.IOException;
import java.io.InputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Random;
import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;

public class UDPTrackerClient
{
	public static byte [] craftConnect (int transID)
	{
		ByteBuffer buffer = ByteBuffer.allocate(16);
		buffer.putLong(0x41727101980l);
		buffer.putInt(0);
		buffer.putInt(transID);
		return buffer.array();
	}

	public static TrackerResponse announce (TrackerRequest request,
			String serverAddr, int serverPort, int maxAttempt) throws IOException
	{
		long connID = 0;
		int connIDTime = 0; // timestamp when we get a fresh conn id. 0 if we don't have one
		int attempts = 0;
		Random rand = new Random();

		DatagramSocket socket = new DatagramSocket(serverPort, InetAddress.getByName(serverAddr));

		while (attempts < maxAttempt) {
			if (connIDTime == 0) { // step 1: connect
				int transID = rand.nextInt();
				byte [] reqmsg = craftConnect(transID);
				socket.send(new DatagramPacket(reqmsg, reqmsg.length));

				// TODO: should ignore bad packets until timeout
				socket.setSoTimeout(1000 * 15 * (1 << attempts));
				DatagramPacket rsppack = new DatagramPacket(new byte[512], 512);
				try {
					socket.receive(rsppack);
				} catch (SocketTimeoutException x) {
					attempts ++;
					continue;
				}
				if (rsppack.getLength() < 16 ||
						HexEncoding.bytesToInt(rsppack.getData(), 0) != 0 ||
						HexEncoding.bytesToInt(rsppack.getData(), 4) != transID) {
					System.err.println("Unexpected UDP connect response: " +
							HexEncoding.bytesToHex(rsppack.getData(), 0, rsppack.getLength()));
				}
				connID = HexEncoding.bytesToLong(rsppack.getData(), 8);
				connIDTime = (int)(System.currentTimeMillis() / 1000);
			} else { // step 2: announce
				if (System.currentTimeMillis() / 1000 - connIDTime > 60) {
					connIDTime = 0;
					continue;
				}
				//TODO
			}
		}
		return new TrackerResponse();
	}

	public static void main (String [] args) throws IOException
	{
		if (args.length < 2) {
			System.out.println("Usage: java com.socialproxy.bittorrent." +
					"UDPTrackerClient addr port [name=value ...]");
			System.out.println("Required Parameters: info_hash peer_id downloaded left");
			System.out.println("                     uploaded event ip key numwant port");
			//System.out.println("Optional Parameters:");
			return;
		}
	}
}
