package com.socialproxy.bittorrent;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;
import java.util.List;
import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;

public class HTTPTrackerClient
{
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
		if (req.no_peer_id != 0)
			sb.append("&no_peer_id=").append(req.no_peer_id - 1);
		if (req.compact != 0)
			sb.append("&compact=").append(req.compact - 1);

		System.err.println("URL: " + sb);
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
				System.out.println("failure reason: " + rsp.failure);
				return rsp;
			}
			if (!map.containsKey("peers")) {
				System.err.println("Warning: no peers in response");
				return rsp;
			}
			if (req.compact == 2 && map.get("peers").getValue() instanceof byte[]) {
				byte[] data = map.get("peers").getBytes();
				System.out.println("peers array length = " + data.length);
			} else if (map.get("peers").getValue() instanceof List) {
				for (BEValue peer : map.get("peers").getList()) {
					Map<String, BEValue> peermap = peer.getMap();
					if (peermap.containsKey("peer id") &&
							peermap.containsKey("ip") &&
							peermap.containsKey("port")) {
						System.out.println(HexEncoding.bytesToHex(peermap.get("peer id").getBytes()) +
								" " + peermap.get("ip").getString() +
								" " + peermap.get("port").getInt());
					} else {
						System.out.println("unknown peer");
					}
				}
			} else {
				System.out.println("unknown peers: " + map.get("peers").getValue());
			}
		} catch (InvalidBEncodingException x) {
			x.printStackTrace();
			return null;
		}
		return null;
	}

	public static void main (String [] args) throws IOException
	{
		if (args.length < 2) {
			System.out.println("Usage: java com.socialproxy.bittorrent." +
					"HTTPTrackerClient url info_hash [name=value ...]");
			System.out.println("Required Parameters: peer_id port uploaded downloaded left");
			System.out.println("Optional Parameters: ip event numwant no_peer_id compact");
			return;
		}

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
				case "no_peer_id": req.no_peer_id = Integer.parseInt(arr[1]) + 1; break;
				case "compact": req.compact = Integer.parseInt(arr[1]) + 1; break;
				default: throw new RuntimeException("Unknown parameter " + args[i]);
			}
		}

		TrackerResponse rsp = announce(req, args[0]);
	}
}
