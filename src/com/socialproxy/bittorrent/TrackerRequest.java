package com.socialproxy.bittorrent;

public class TrackerRequest {
	public byte [] info_hash;
	public byte [] peer_id;
	public String ip; // optional
	public int port;
	public long uploaded;
	public long downloaded;
	public long left;
	public String event; // optional
	public int numwant; // optional. 0: not present

	public TrackerRequest () {}

	public TrackerRequest (byte [] info_hash, byte [] peer_id,
			String ip, int port, long uploaded, long downloaded, long left)
	{
		this.info_hash = info_hash;
		this.peer_id = peer_id;
		this.ip = ip;
		this.port = port;
		this.uploaded = uploaded;
		this.downloaded = downloaded;
		this.left = left;
		this.event = null;
		this.numwant = 0;
	}
}

