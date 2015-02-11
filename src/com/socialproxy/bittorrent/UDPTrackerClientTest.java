package com.socialproxy.bittorrent;

import java.util.logging.*;
import com.socialproxy.util.*;

public class UDPTrackerClientTest
{
	public static void main (String [] args) throws Exception
	{
		if (args.length < 2) {
			System.out.println("Usage: java com.socialproxy.bittorrent." +
					"UDPTrackerClient addr port info_hash [name=value ...]");
			System.out.println("Required Parameters: peer_id port uploaded downloaded left");
			System.out.println("Optional Parameters: ip event numwant");
			return;
		}

		ConsoleHandler handler = new ConsoleHandler();
		handler.setLevel(Level.ALL);
		Logger.getLogger("").addHandler(handler);
		Logger.getLogger("").setLevel(Level.ALL);

		TrackerRequest req = new TrackerRequest();
		req.info_hash = HexEncoding.stringToBytes(args[2]);
		req.peer_id = HexEncoding.stringToBytes("e5fa44f2b31c1fb553b6021e7360d07d5d91ff5e");
		req.port = 6009;
		for (int i = 3; i < args.length; i ++) {
			assert args[i].indexOf('=') >= 0;
			String [] arr = args[i].split("=");
			switch(arr[0]) {
				case "peer_id": req.peer_id = HexEncoding.stringToBytes(arr[1]); break;
				case "ip": req.ip = arr[1]; break;
				case "port": req.port = Integer.parseInt(arr[1]); break;
				case "uploaded": req.uploaded = Long.parseLong(arr[1]); break;
				case "downloaded": req.downloaded = Long.parseLong(arr[1]); break;
				case "left": req.left = Long.parseLong(arr[1]); break;
				case "event": req.event = arr[1]; break;
				case "numwant": req.numwant = Integer.parseInt(arr[1]); break;
				default: throw new RuntimeException("Unknown parameter " + args[i]);
			}
		}

		UDPTrackerClient client = UDPTrackerClient.getInstance();

		client.announce(req,
				new java.net.InetSocketAddress(args[0], Integer.parseInt(args[1])),
				new TrackerResponseListener() {
				public void onResponse (TrackerResponse rsp) {
					System.out.println("interval: " + rsp.interval);
					System.out.println("leechers: " + rsp.leechers);
					System.out.println("seeders: " + rsp.seeders);
					System.out.println("peers:");
					for (TrackerResponse.Peer peer : rsp.peers)
						System.out.println((peer.id == null ? "null" : HexEncoding.bytesToString(peer.id)) +
								" " + peer.ip + " " + peer.port);
				}
			});
	}
}
