package com.socialproxy.bittorrent;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;

public class HTTPTrackerClient {
	public static Response announce (Request req, String url) throws IOException
	{
		assert url.startsWith("http://");

		StringBuilder sb = new StringBuilder(url);
		sb.append(url.contains("?") ? "&" : "?");
		sb.append("info_hash=").append(URLByteEncoder.encode(req.info_hash));
		sb.append("&peer_id=").append(URLByteEncoder.encode(req.peer_id));
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

		HttpURLConnection conn = (HttpURLConnection)(new URL(sb.toString()).openConnection());
		InputStream in = conn.getInputStream();
		BEValue bvalue = BDecoder.bdecode(in);
		System.out.println("resp=" + bvalue.getMap());
		return null;
	}

	public static class Request {
		public byte [] info_hash;
		public byte [] peer_id;
		public String ip; // optional
		public int port;
		public long uploaded;
		public long downloaded;
		public long left;
		public String event; // optional
		public int numwant; // optional. 0: not present
		public int no_peer_id; // 0: not present; 1: false; 2: true
		public int compact;    // 0: not present; 1: false, 2: true
	}

	public static class Response {
		public String failure; // null if no failure
		public int interval;
		public Peer [] peers;
	}

	public static class Peer {
		public byte [] id;
		public String ip;
		public int port;
	}
}
