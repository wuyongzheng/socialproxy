package com.socialproxy.bittorrent;

import java.io.IOException;
import java.io.InputStream;
import java.util.logging.ConsoleHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;

public class HTTPTrackerClient
{
	private final static Logger LOG = Logger.getLogger(HTTPTrackerClient.class.getName());

	final private static char[] hexArray = "0123456789ABCDEF".toCharArray();
	public static String encodeURLBytes (byte [] data)
	{
		StringBuilder sb = new StringBuilder();
		for (byte b : data) {
			char c = (char)(b & 0xff);
			if ((c >= '0' && c <= '9') ||
					(c >= 'a' && c <= 'z') ||
					(c >= 'A' && c <= 'Z'))
				sb.append(c);
			else
				sb.append('%').append(hexArray[c >>> 4]).append(hexArray[c & 0xf]);
		}
		return sb.toString();
	}

	public static TrackerResponse announce (TrackerRequest req, String url) throws IOException
	{
		assert url.startsWith("http://");

		StringBuilder sb = new StringBuilder(url);
		sb.append(url.contains("?") ? "&" : "?");
		sb.append("info_hash=").append(encodeURLBytes(req.info_hash));
		sb.append("&peer_id=").append(encodeURLBytes(req.peer_id));
		sb.append("&port=").append(req.port);
		sb.append("&uploaded=").append(req.uploaded);
		sb.append("&downloaded=").append(req.downloaded);
		sb.append("&left=").append(req.left);
		if (req.ip != null)
			sb.append("&ip=").append(req.ip);
		if (req.event != null)
			sb.append("&event=").append(req.event);
		if (req.numwant != 0)
			sb.append("&numwant=").append(req.numwant);

		LOG.config("URL: " + sb);
		HttpURLConnection conn = (HttpURLConnection)(new URL(sb.toString()).openConnection());
		conn.setDoInput(true);
		InputStream in = conn.getInputStream();
		BEValue bvalue = BDecoder.bdecode(in);
		TrackerResponse rsp = new TrackerResponse();
		try {
			Map<String, BEValue> map = bvalue.getMap();
			if (map.containsKey("interval"))
				rsp.interval = map.get("interval").getInt();
			// what about "min interval"?
			if (map.containsKey("failure reason")) {
				rsp.failure = map.get("failure reason").getString();
				return rsp;
			}
			if (!map.containsKey("peers")) {
				LOG.warning("no failure or peers in response");
				rsp.failure = "ERR_BADRSP";
				return rsp;
			}
			if (map.get("peers").getValue() instanceof byte[]) {
				// we didn't ask for compact peer list, but
				// the server returns compact list.
				byte[] bytes = (byte[])map.get("peers").getValue();
				rsp.peers = new TrackerResponse.Peer[bytes.length/6];
				for (int i = 0; i < rsp.peers.length; i ++) {
					String ip = (bytes[i*6+0] & 0xff) + "." +
						(bytes[i*6+1] & 0xff) + "." +
						(bytes[i*6+2] & 0xff) + "." +
						(bytes[i*6+3] & 0xff);
					int port = ((bytes[i*6+4] & 0xff) << 8) | (bytes[i*6+5] & 0xff);
					rsp.peers[i] = new TrackerResponse.Peer(null, ip, port);
				}
				return rsp;
			}
			if (!(map.get("peers").getValue() instanceof List)) {
				LOG.warning("peers is not a list: " + map.get("peers").getValue().getClass().toString());
				rsp.failure = "ERR_BADRSP";
				return rsp;
			}
			ArrayList<TrackerResponse.Peer> peers = new ArrayList<TrackerResponse.Peer>();
			for (BEValue peer : map.get("peers").getList()) {
				Map<String, BEValue> peermap = peer.getMap();
				// some trackers use peer_id instead of peer id
				if (peermap.containsKey("peer_id") && !peermap.containsKey("peer id"))
					peermap.put("peer id", peermap.get("peer_id"));
				if (peermap.containsKey("peer id") &&
						peermap.containsKey("ip") &&
						peermap.containsKey("port")) {
					peers.add(new TrackerResponse.Peer(peermap.get("peer id").getBytes(),
								peermap.get("ip").getString(),
								peermap.get("port").getInt()));
				} else {
					LOG.warning("bad peer item: " + peermap.keySet());
				}
			}
			rsp.peers = new TrackerResponse.Peer[peers.size()];
			rsp.peers = peers.toArray(rsp.peers);
			return rsp;
		} catch (InvalidBEncodingException x) {
			LOG.log(Level.WARNING, "bad response", x);
			rsp.failure = "ERR_BADRSP";
			return rsp;
		}
	}

	public static void main (String [] args) throws IOException
	{
		if (args.length < 2) {
			System.out.println("Usage: java com.socialproxy.bittorrent." +
					"HTTPTrackerClient url info_hash [name=value ...]");
			System.out.println("Required Parameters: peer_id port uploaded downloaded left");
			System.out.println("Optional Parameters: ip event numwant");
			return;
		}

		ConsoleHandler handler = new ConsoleHandler();
		handler.setLevel(Level.ALL);
		Logger.getLogger("").addHandler(handler);
		Logger.getLogger("").setLevel(Level.ALL);

		TrackerRequest req = new TrackerRequest();
		req.info_hash = HexEncoding.hexToBytes(args[1]);
		req.peer_id = HexEncoding.hexToBytes("e5fa44f2b31c1fb553b6021e7360d07d5d91ff5e");
		req.port = 6009;
		for (int i = 2; i < args.length; i ++) {
			assert args[i].indexOf('=') >= 0;
			String [] arr = args[i].split("=");
			switch(arr[0]) {
				case "peer_id": req.peer_id = HexEncoding.hexToBytes(arr[1]); break;
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

		TrackerResponse rsp = announce(req, args[0]);
		if (rsp.failure != null) {
			System.out.println("Announce Error: " + rsp.failure);
			return;
		}

		System.out.println("interval: " + rsp.interval);
		System.out.println("leechers: " + rsp.leechers);
		System.out.println("seeders: " + rsp.seeders);
		System.out.println("peers:");
		for (TrackerResponse.Peer peer : rsp.peers)
			System.out.println((peer.id == null ? "null" : HexEncoding.bytesToHex(peer.id)) +
					" " + peer.ip + " " + peer.port);
	}
}
