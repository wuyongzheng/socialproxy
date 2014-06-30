package com.socialproxy.bittorrent;

public class TrackerResponse {
	public String failure; // null if no failure
	public int interval;
	public int leechers; // -1 for UDP tracker
	public int seeders;  // -1 for UDP tracker
	public Peer [] peers; // never null. can be empty.

	public static class Peer {
		public final byte [] id; // null for UDP tracker
		public final String ip;
		public final int port;

		public Peer (byte [] id, String ip, int port)
		{
			this.id = id;
			this.ip = ip;
			this.port = port;
		}
	}

	public TrackerResponse () {}

	public TrackerResponse (String failure, int interval,
			int leechers, int seeders, Peer [] peers)
	{
		this.failure = failure;
		this.interval = interval;
		this.leechers = leechers;
		this.seeders = seeders;
		this.peers = peers;
	}
}
