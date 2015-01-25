package com.socialproxy.bittorrent;

import java.io.*;
import java.net.*;
import java.nio.*;
import java.util.*;
import java.util.logging.*;
import java.nio.channels.*;

public class UDPTrackerClient implements Runnable
{
	private final static Logger LOG = Logger.getLogger(UDPTrackerClient.class.getName());
	private final static Random random = new Random();
	private static UDPTrackerClient instance;
	private final Selector selector;
	private final DatagramChannel socket;
	private final Thread thread;
	private final HashMap<Integer, Session> sessions = new HashMap<Integer, Session>();

	/* no public constructor */
	private UDPTrackerClient ()
	{
		try {
			socket = DatagramChannel.open();
			socket.bind(null);
			int port = socket.socket().getLocalPort();
			socket.configureBlocking(true);
			selector = Selector.open();
			socket.register(selector, SelectionKey.OP_READ);
			thread = new Thread(this, "udptc" + port);
			thread.start();
		} catch (IOException x) {
			throw new RuntimeException(x);
		}
	}

	/* create or get a UDPTrackerClient instance */
	public static synchronized UDPTrackerClient getInstance ()
	{
		if (instance != null)
			return instance;
		return instance = new UDPTrackerClient();
	}

	public void announce (TrackerRequest request,
			InetSocketAddress tracker,
			TrackerResponseListener listener)
	{
		if (tracker.isUnresolved())
			throw new IllegalArgumentException("tracker address is unresolved");
		Session session = new Session(request, tracker, listener);
		sessions.put(session.transID, session);
		selector.wakeup();
	}

	public void run ()
	{
		ByteBuffer buffer = ByteBuffer.allocate(1500);
		buffer.order(ByteOrder.BIG_ENDIAN);
		while (true) {
			try {
				selector.select(sessions.isEmpty() ? 0 : random.nextInt(2000) + 4000);
			} catch (IOException x) {
			}

			/* Step 1: process response */
			SocketAddress addr;
			try {
				addr = socket.receive(buffer);
			} catch (IOException x) {
				throw new RuntimeException(x);
			}
			if (addr != null) {
				buffer.flip();
				doReceive(addr, buffer);
				buffer.clear();
			}

			/* Step 2: generate request */
			ArrayList<Session> toDelete = null;
			long time = System.currentTimeMillis();
			for (Session session : sessions.values()) {
				if (session.expect == Session.EXPECT_NONE) { // first timer or connectid expire
					doSend(session);
				} else if (session.lastSendTime + 15000 * (1 << session.retryCount) > time) { // continue wait
				} else if (session.retryCount > 8) { // give up
					session.listener.onResponse(null);
					if (toDelete == null)
						toDelete = new ArrayList<Session>();
					toDelete.add(session);
				} else { // timeout
					session.retryCount ++;
					doSend(session);
				}
			}
			if (toDelete != null)
				for (Session session : toDelete)
					sessions.remove(session.transID);
		}
	}

	public static byte [] ipDotStringToBytes (String dotNotation)
	{
		try {
			return InetAddress.getByName(dotNotation).getAddress();
		} catch (UnknownHostException x) {
			throw new RuntimeException(x);
		}
	}

	private static final HashMap<String, Integer> ANNEVENTS;
	static {
		ANNEVENTS = new HashMap<String, Integer>(4);
		ANNEVENTS.put("none", 0);
		ANNEVENTS.put("completed", 1);
		ANNEVENTS.put("started", 2);
		ANNEVENTS.put("stopped", 3);
	}
	private void doSend (Session session)
	{
		ByteBuffer buffer = ByteBuffer.allocate(1500);
		buffer.order(ByteOrder.BIG_ENDIAN);
		CID cid = getCID(session.tracker.toString());

		if (cid == null) { // send connect
			buffer.putLong(0x41727101980l);
			buffer.putInt(0);
			buffer.putInt(session.transID + 0);
		} else { // send announce
			buffer.putLong(cid.cid);
			buffer.putInt(1);
			buffer.putInt(session.transID + 1);
			buffer.put(session.request.info_hash);
			buffer.put(session.request.peer_id);
			buffer.putLong(session.request.downloaded);
			buffer.putLong(session.request.left);
			buffer.putLong(session.request.uploaded);
			buffer.putInt(session.request.event == null ? 0 : ANNEVENTS.get(session.request.event));
			if (session.request.ip == null)
				buffer.putInt(0);
			else
				buffer.put(ipDotStringToBytes(session.request.ip));
			buffer.putInt(0); // we don't support key
			buffer.putInt(session.request.numwant == 0 ? -1 : session.request.numwant);
			buffer.putShort((short)session.request.port);
			assert buffer.position() == 98;
		}

		buffer.flip();
		try {
			int sent = socket.send(buffer, session.tracker);
			if (sent == 0) {
				LOG.info("socket.send() = 0");
			} else {
				session.expect = cid == null ? Session.EXPECT_CONNECT : Session.EXPECT_ANNOUNCE;
				session.lastSendTime = System.currentTimeMillis();
			}
		} catch (IOException x) {
			throw new RuntimeException(x);
		}
	}

	private void doReceive (SocketAddress addr, ByteBuffer buffer)
	{
		if (buffer.remaining() < 8) {
			LOG.info("response size too small " + buffer.limit() + ". ignored.");
			return;
		}

		int action = buffer.getInt();
		int tranID = buffer.getInt();

		Session session = sessions.get(tranID - tranID % 4);
		if (session == null) {
			LOG.info("unknown response transID " + tranID + ". ignored.");
			return;
		}
		if (!addr.equals(session.tracker)) {
			LOG.info("tracker address mismatch: " + addr + ", " + session.tracker);
			return;
		}

		switch (action) {
			case 0: // connect
				if (tranID % 4 != 0) {
					LOG.info("bad transID");
					break;
				}
				if (buffer.remaining() < 8) {
					LOG.info("short connect response");
					break;
				}
				putCID(session.tracker.toString(), buffer.getLong());
				if (session.expect != Session.EXPECT_CONNECT) {
					LOG.info("don't expect connect response");
					break;
				}
				session.retryCount = 0;
				doSend(session);
				break;
			case 1: // announce
				if (tranID % 4 != 1) {
					LOG.info("bad transID");
					break;
				}
				if (buffer.remaining() < 12) {
					LOG.info("short announce response");
					break;
				}
				doReceiveAnnounce(session, buffer);
				break;
			case 3: // error
				String err = "";
				if (buffer.remaining() > 0) {
					byte [] arr = new byte [buffer.remaining()];
					buffer.get(arr);
					try {
						err = new String(arr, "UTF-8");
					} catch (UnsupportedEncodingException x) {
						throw new RuntimeException(x);
					}
				}
				LOG.fine("error response " + tranID + " " + err);
				TrackerResponse response = new TrackerResponse();
				response.failure = err;
				session.listener.onResponse(response);
				sessions.remove(session.transID);
				break;
			default:
				LOG.info("unknown responce action " + action + ". ignored.");
		}
	}

	private void doReceiveAnnounce (Session session, ByteBuffer buffer)
	{
	}

	private static class Session {
		public final int transID = random.nextInt(1<<28) * 4;
		public final TrackerRequest request;
		public final InetSocketAddress tracker;
		public final TrackerResponseListener listener;
		public static final int EXPECT_NONE = 0;
		public static final int EXPECT_CONNECT = 0;
		public static final int EXPECT_ANNOUNCE = 0;
		public int expect; // what responce are we waiting?
		public long lastSendTime; // System.currentTimeMillis()
		public int retryCount;

		public Session (TrackerRequest request,
				InetSocketAddress tracker,
				TrackerResponseListener listener)
		{
			this.request = request;
			this.tracker = tracker;
			this.listener = listener;
			expect = EXPECT_NONE;
		}
	}

	private static final HashMap<String, CID> CIDCache = new HashMap<String, CID>();
	private static CID getCID (String addr)
	{
		CID cide = CIDCache.get(addr);
		return cide == null ||
			cide.lastUpdate + 60000 < System.currentTimeMillis() ?
			null : cide;
	}
	private static void putCID (String addr, long cid)
	{
		CID cide = CIDCache.get(addr);
		if (cide == null) {
			cide = new CID(System.currentTimeMillis(), cid);
			CIDCache.put(addr, cide);
		} else {
			cide.cid = cid;
			cide.lastUpdate = System.currentTimeMillis();
		}
	}
	private static class CID {
		public long lastUpdate;
		public long cid;
		public CID (long l, long c) {lastUpdate = l; cid = c;}
	}
}
