package com.socialproxy.tunnel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.Random;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;
import com.socialproxy.util.Hex;

/* A carrier can carry mutiple tunneled channels.
 * It acts as a multiplexer bewteen multiple channels (frontend)
 * and a single transport backend, typically a TCP socket or UDT socket.
 *
 * This class handles the protocol AFTER handshake and authentication,
 * so the constructor expects the encryption & decryption keys.
 */
public class Carrier implements Runnable {
	private static final boolean LOGTRAFFIC = false;
	public static final int STATE_EMPTY      = 0;
	public static final int STATE_CONNECTING = 1;
	public static final int STATE_CONNECTED  = 2;
	public static final int STATE_TEARING    = 3;
	public static final byte CTRL_CON1 = 1;
	public static final byte CTRL_CON2 = 2;
	public static final byte CTRL_TRDN = 3;
	public static final byte CTRL_PING = 4;
	public static final byte CTRL_PONG = 5;
	public static final byte CTRL_PADD = 6;
	public static final int ACK_UNIT = 4096;
	public static final int MAX_ACK = 255;
	public static final int MAX_MESSAGESIZE = 8192;
	public static final int MAX_DATASIZE = MAX_MESSAGESIZE - 4;
	private static final int CHANNEL_RECVBUF_SIZE = 100 * ACK_UNIT;
	private static final int CHANNEL_SENDBUF_SIZE = MAX_DATASIZE;
	private static final int SELECT_TIMEOUT_MS = 500; // timeout is needed for speed limit
	private final static Logger LOG = Logger.getLogger(Carrier.class.getName());
	private static final Pattern PATTERN_IPV4 = Pattern.compile(
			"([0-9]+)\\.([0-9]+)\\.([0-9]+)\\.([0-9]+)");
	private static final Pattern PATTERN_IPV6 = Pattern.compile(
			"([0-9a-f]{1,4}:){7}([0-9a-f]){1,4}");
	private static final Pattern PATTERN_DNSNAME = Pattern.compile(
			"^[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*(\\.[A-Za-z]{2,})$");

	private final SocketChannel backsock;
	private final boolean isMajor; // minor creates [1, 63]; major creates [64, 126]
	private final Slot[] slots;
	private final Cipher encCipher;
	private final Cipher decCipher;
	private final Selector selector;
	private final ByteBuffer recvbuf = ByteBuffer.allocate(MAX_MESSAGESIZE * 2);
	private final ByteBuffer sendbuf = ByteBuffer.allocate(MAX_MESSAGESIZE * 2);
	private final ByteBuffer controlSendbuf = ByteBuffer.allocate(1000); // control messages are small
	private boolean hasAnyData; // hasAnyData=true iff any channel's hasData=true
	private final Random rand = new Random();
	private boolean carrierClosed = false;

	public Carrier (SocketChannel backend, boolean isMajor, byte[] enckey, byte[] deckey)
	{
		assert enckey.length == 128/8;
		assert deckey.length == 128/8;

		backsock = backend;
		this.isMajor = isMajor;
		slots = new Slot[127];
		for (int i = 1; i <= 126; i ++)
			slots[i] = new Slot(i, STATE_EMPTY);

		try {
			encCipher = Cipher.getInstance("AES/CTR/NoPadding");
			encCipher.init(Cipher.ENCRYPT_MODE,
					new SecretKeySpec(enckey, "AES"),
					new IvParameterSpec(new byte[128/8]));
			decCipher = Cipher.getInstance("AES/CTR/NoPadding");
			decCipher.init(Cipher.DECRYPT_MODE,
					new SecretKeySpec(deckey, "AES"),
					new IvParameterSpec(new byte[128/8]));
		} catch (Exception x) {
			throw new RuntimeException(x);
		}

		try {
			selector = Selector.open();
		} catch (IOException x) {
			throw new RuntimeException(x);
		}
	}

	@Override
	public void run ()
	{
		SelectionKey selkey;
		try {
			backsock.configureBlocking(false);
			selkey = backsock.register(selector, 0);
		} catch (IOException x) {
			throw new RuntimeException(x);
		}
		ByteBuffer tmpbuf = ByteBuffer.allocate(MAX_MESSAGESIZE * 2);
		while (true) {
			int selops = sendbuf.position() > 0 ?
				(SelectionKey.OP_READ | SelectionKey.OP_WRITE) :
				SelectionKey.OP_READ;
			selkey.interestOps(selops);
			LOG.finer("selecting: " +
					((selops & SelectionKey.OP_READ) == 0 ? "" : "r") +
					((selops & SelectionKey.OP_WRITE) == 0 ? "" : "w"));
			try {
				selector.select(SELECT_TIMEOUT_MS);
			} catch (IOException x) {
				throw new RuntimeException(x);
			}
			LOG.finer("selected " +
					(selector.selectedKeys().size() == 1 && selkey.isReadable() ? "r" : "") +
					(selector.selectedKeys().size() == 1 && selkey.isWritable() ? "w" : ""));

			if (carrierClosed) {
				LOG.info("closing carrier");
				closeAllChannels();
				return;
			}

			if (selector.selectedKeys().size() == 1 && selkey.isReadable()) {
				/* recvbuf layout:
				 * | partial message (pos) freespace (limit,cap) | */
				assert recvbuf.limit() == recvbuf.capacity();
				assert recvbuf.remaining() > MAX_MESSAGESIZE;
				tmpbuf.clear();
				tmpbuf.limit(recvbuf.remaining());
				int size;
				try {
					size = backsock.read(tmpbuf);
				} catch (IOException x) {
					throw new RuntimeException(x);
				}
				if (size == -1) {
					LOG.info("backsock.read()=-1 closing all channels");
					closeAllChannels();
					return;
				} else if (size > 0) {
					tmpbuf.flip();
					try {
						int decsize = decCipher.update(tmpbuf, recvbuf);
						assert decsize == size;
					} catch (javax.crypto.ShortBufferException x) {
						throw new RuntimeException(x);
					}
					recvbuf.flip();
					try {
						processRecvbuf();
					} catch (CarrierProtocolException x) {
						LOG.log(Level.WARNING, "error processing Recvbuf", x);
						closeAllChannels();
						return;
					}
					recvbuf.compact();
					assert recvbuf.limit() == recvbuf.capacity();
					assert recvbuf.remaining() > MAX_MESSAGESIZE;
				}
			}

			/* sendbuf layout:
			 * | ciphertext (pos) freespace (limit,cap) | */
			assert sendbuf.limit() == sendbuf.capacity();
			if (sendbuf.hasRemaining()) {
				int mark = sendbuf.position();
				fillSendbuf();
				/* | ciphertext (mark) plaintext (pos) freespace (limit,cap) | */
				assert sendbuf.position() >= mark;
				if (sendbuf.position() > mark) {
					try {
						encCipher.update(sendbuf.array(), mark,
								sendbuf.position() - mark, tmpbuf.array());
					} catch (javax.crypto.ShortBufferException x) {
						throw new RuntimeException(x);
					}
					System.arraycopy(tmpbuf.array(), 0,
							sendbuf.array(), mark,
							sendbuf.position() - mark);
				}
			}

			if (selector.selectedKeys().size() == 1 &&
					selkey.isWritable() &&
					sendbuf.position() > 0) {
				sendbuf.flip();
				try {
					int size = backsock.write(sendbuf);
					assert size > 0;
				} catch (IOException x) {
					throw new RuntimeException(x);
				}
				sendbuf.compact();
			}
		}
	}

	/* can be called in any thread
	 * return true if succeed
	 * if return false, caller should close the socket. */
	public boolean createChannel (SocketChannel socket, String peerAddr, int peerPort)
	{
		int addrtype;
		byte [] addrbytes;
		if (PATTERN_IPV4.matcher(peerAddr).matches()) {
			addrtype = 0;
			Matcher matcher = PATTERN_IPV4.matcher(peerAddr);
			boolean found = matcher.find();
			assert found;
			addrbytes = new byte[4];
			addrbytes[0] = (byte)Integer.parseInt(matcher.group(1));
			addrbytes[1] = (byte)Integer.parseInt(matcher.group(2));
			addrbytes[2] = (byte)Integer.parseInt(matcher.group(3));
			addrbytes[3] = (byte)Integer.parseInt(matcher.group(4));
		} else if (PATTERN_IPV6.matcher(peerAddr).matches()) {
			addrtype = 1;
			throw new RuntimeException("ipv6 not implemented yet");
		} else if (PATTERN_DNSNAME.matcher(peerAddr).matches()) {
			addrtype = 2;
			addrbytes = new byte[peerAddr.length() + 1];
			try {
				System.arraycopy(peerAddr.getBytes("UTF-8"),
						0, addrbytes, 1, peerAddr.length());
			} catch (java.io.UnsupportedEncodingException x) {
				throw new RuntimeException(x);
			}
		} else {
			LOG.warning("unknown address format " + peerAddr);
			return false;
		}

		if (carrierClosed) {
			LOG.warning("calling createChannel() while carrierClosed=true");
			return false;
		}

		// TODO race
		int cid;
		for (cid = isMajor ? 64 : 1;
				cid <= (isMajor ? 126 : 63);
				cid ++)
			if (slots[cid].state == STATE_EMPTY)
				break;
		if (cid > (isMajor ? 126 : 63)) {
			LOG.warning("createChannel() channel full");
			return false;
		}
		assert slots[cid].state == STATE_EMPTY;
		slots[cid].state = STATE_CONNECTING;
		slots[cid].channel = null;
		slots[cid].hasAck = slots[cid].hasData = false;
		slots[cid].socket = socket;

		synchronized (controlSendbuf) {
			controlSendbuf.put((byte)0);
			controlSendbuf.put(CTRL_CON1);
			controlSendbuf.put((byte)cid);
			controlSendbuf.putShort((short)(CHANNEL_RECVBUF_SIZE / ACK_UNIT));
			controlSendbuf.put((byte)addrtype);
			controlSendbuf.putShort((short)peerPort);
			controlSendbuf.put(addrbytes);
		}
		selector.wakeup();
		return true;
	}

	/* can be called in any thread */
	public void close ()
	{
		if (!carrierClosed) {
			carrierClosed = true;
			selector.wakeup();
		}
	}

	/* must only be called in Carrier thread */
	private void closeAllChannels ()
	{
		for (int cid = 1; cid <= 126; cid ++) {
			if (slots[cid].state == STATE_CONNECTED) {
				slots[cid].state = STATE_EMPTY;
				slots[cid].channel.onTRDN();
				slots[cid].channel = null;
			}
		}
		try {
			selector.close();
			backsock.close();
		} catch (Exception x) {}
	}

	/* precondition:
	 *   recvbuf's position is 0
	 *   recvbuf's limit is total size of all messages in recvbuf.
	 * post condition:
	 *   recvbuf's position = limit or start of last partial message.
	 *     i.e. should process as many message as possible.
	 *   recvbuf's limit is not changed
	 */
	private void processRecvbuf () throws CarrierProtocolException
	{
		assert recvbuf.position() == 0;
		while (recvbuf.remaining() >= 2) { // minimal message is 2 bytes
			recvbuf.mark();
			boolean finished;
			int cid = recvbuf.get() & 0xff;
			if (cid == 0) {
				cid = recvbuf.get() & 0xff;
				switch (cid) {
					case CTRL_CON1: finished = processCON1(); break;
					case CTRL_CON2: finished = processCON2(); break;
					case CTRL_TRDN: finished = processTRDN(); break;
					case CTRL_PING: finished = processPING(); break;
					case CTRL_PONG: finished = processPONG(); break;
					case CTRL_PADD: finished = processPADD(); break;
					default: throw new CarrierProtocolException("unknow control message " + cid);
				}
			} else {
				finished = processDATA(cid);
			}
			if (!finished) {
				recvbuf.reset();
				break;
			}
		}
		if (LOGTRAFFIC && recvbuf.position() > 0)
			LOG.finest("RECV: " + Hex.bytesToString(recvbuf.array(),
						0, recvbuf.position()));
	}

	private boolean processDATA (int cid) throws CarrierProtocolException
	{
		boolean hasData = cid >= 128;
		cid = cid % 128;
		int ack = recvbuf.get() & 0xff;
		if (!hasData) {
			// FIXME race condition
			if (slots[cid].state == STATE_CONNECTED) {
				assert slots[cid].channel != null;
				slots[cid].channel.onAck(ack * ACK_UNIT);
			}
			return true;
		}
		if (recvbuf.remaining() < 3)
			return false;
		int size = recvbuf.getShort();
		if (size < 1 || size > MAX_DATASIZE)
			throw new CarrierProtocolException("bad DATA size " + size);
		if (recvbuf.remaining() < size)
			return false;
		// FIXME race condition
		if (slots[cid].state == STATE_CONNECTED) {
			assert slots[cid].channel != null;
			slots[cid].channel.onDATA(recvbuf.array(), recvbuf.position(), size, ack * ACK_UNIT);
		}
		recvbuf.position(recvbuf.position() + size);
		return true;
	}

	private boolean processCON1 () throws CarrierProtocolException
	{
		if (recvbuf.remaining() < 4)
			return false;
		int cid = recvbuf.get() & 0xff;
		if (isMajor) {
			if (cid < 1 || cid > 63)
				throw new CarrierProtocolException("incorrect cid=" + cid + " for remote minor");
		} else {
			if (cid < 64 || cid > 126)
				throw new CarrierProtocolException("incorrect cid=" + cid + " for remote major");
		}
		if (slots[cid].state != STATE_EMPTY)
			throw new CarrierProtocolException("received CON1 while state=" + slots[cid].state);
		int peerRecvbufSize = (recvbuf.getShort() & 0xffff) * ACK_UNIT;
		int targetType = recvbuf.get() & 0xff;
		int targetPort;
		String targetAddr;
		switch (targetType) {
		case 0:
			if (recvbuf.remaining() < 6)
				return false;
			targetPort = recvbuf.getShort() & 0xffff;
			targetAddr =
				(recvbuf.get() & 0xff) + "." +
				(recvbuf.get() & 0xff) + "." +
				(recvbuf.get() & 0xff) + "." +
				(recvbuf.get() & 0xff);
			break;
		case 1:
			if (recvbuf.remaining() < 2+16)
				return false;
			targetPort = recvbuf.getShort() & 0xffff;
			targetAddr = null; //TODO
			break;
		case 2:
			if (recvbuf.remaining() < 4)
				return false;
			targetPort = recvbuf.getShort() & 0xffff;
			int hostnameLen = recvbuf.get() & 0xff;
			if (recvbuf.remaining() < hostnameLen)
				return false;
			byte [] hostnameArr = new byte[hostnameLen];
			recvbuf.get(hostnameArr);
			try {
				targetAddr = new String(hostnameArr, "UTF-8");
			} catch (java.io.UnsupportedEncodingException x) {
				throw new RuntimeException(x);
			}
			break;
		case 3:
			targetPort = 8080; // TODO
			targetAddr = null;
			break;
		default:
			throw new CarrierProtocolException("unknown target type " + targetType);
		}

		// TODO check targetAddress
		slots[cid].state = STATE_CONNECTING;
		ChannelRunner crunner = new ChannelRunner(cid, targetAddr, targetPort, peerRecvbufSize);
		crunner.start();
		return true;
	}

	private boolean processCON2 () throws CarrierProtocolException
	{
		if (recvbuf.remaining() < 2)
			return false;
		int cid = recvbuf.get() & 0xff;
		if (!isMajor) {
			if (cid < 1 || cid > 63)
				throw new CarrierProtocolException("CON2 incorrect cid=" + cid + " for my minor");
		} else {
			if (cid < 64 || cid > 126)
				throw new CarrierProtocolException("CON2 incorrect cid=" + cid + " for my major");
		}
		if (slots[cid].state != STATE_CONNECTING)
			throw new CarrierProtocolException("received CON2 while state=" + slots[cid].state);
		assert slots[cid].socket != null;
		int reason = recvbuf.get() & 0xff;
		if (reason != 0) {
			LOG.warning("peer rejected channel " + cid + ", reason: " + reason);
			try {slots[cid].socket.close();} catch (Exception x) {}
			slots[cid].socket = null;
			slots[cid].state = STATE_EMPTY;
			return true;
		}
		if (recvbuf.remaining() < 2)
			return false;
		int peerRecvbufSize = (recvbuf.getShort() & 0xffff) * ACK_UNIT;
		slots[cid].state = STATE_CONNECTED;
		slots[cid].channel = new TChannel(
				Carrier.this, cid,
				CHANNEL_SENDBUF_SIZE, CHANNEL_RECVBUF_SIZE,
				peerRecvbufSize, slots[cid].socket);
		slots[cid].socket = null;
		new Thread(slots[cid].channel, "C" + cid).start();
		return true;
	}

	private boolean processTRDN () throws CarrierProtocolException
	{
		if (recvbuf.remaining() < 1)
			return false;
		int cid = recvbuf.get() & 0xff;
		if (slots[cid].state == STATE_TEARING) {
			slots[cid].state = STATE_EMPTY;
		} else if (slots[cid].state == STATE_CONNECTED) {
			slots[cid].channel.onTRDN();
			slots[cid].state = STATE_EMPTY;
			synchronized (controlSendbuf) {
				controlSendbuf.put((byte)0);
				controlSendbuf.put(CTRL_TRDN);
				controlSendbuf.put((byte)cid);
			}
		} else {
			throw new CarrierProtocolException("received TRDN at state " + slots[cid].state);
		}
		return true;
	}

	private boolean processPING () throws CarrierProtocolException
	{
		if (recvbuf.remaining() < 12)
			return false;
		int nonce = recvbuf.getInt();
		long peerts = recvbuf.getLong();
		long myts = System.currentTimeMillis();
		synchronized (controlSendbuf) {
			controlSendbuf.put((byte)0);
			controlSendbuf.put(CTRL_PONG);
			controlSendbuf.putInt(nonce);
			controlSendbuf.putLong(myts);
		}
		LOG.fine("received ping nonce=" + nonce + ", peerts=" + peerts + ", myts" + myts);
		return true;
	}

	private boolean processPONG () throws CarrierProtocolException
	{
		if (recvbuf.remaining() < 12)
			return false;
		int nonce = recvbuf.getInt();
		long peerts = recvbuf.getLong();
		long myts = System.currentTimeMillis();
		LOG.fine("received pong nonce=" + nonce + ", peerts=" + peerts + ", myts" + myts);
		return true;
	}

	private boolean processPADD () throws CarrierProtocolException
	{
		if (recvbuf.remaining() < 1)
			return false;
		int size = recvbuf.get() & 0xff;
		if (recvbuf.remaining() < size)
			return false;
		byte [] bytes = new byte [size];
		recvbuf.get(bytes);
		for (byte b : bytes)
			if (b != 0)
				throw new CarrierProtocolException("PADD data is not 0");
		return true;
	}

	/* precondition:
	 *   sendbuf's position is the next free space
	 *   recvbuf's limit = capacity
	 * postcondition:
	 *   sendbuf's position is the next free space
	 *   recvbuf's limit is not changed
	 */
	private void fillSendbuf ()
	{
		if (!sendbuf.hasRemaining())
			return;

		int mark = sendbuf.position();

		synchronized (controlSendbuf) {
			if (controlSendbuf.position() > 0) {
				controlSendbuf.flip();
				if (sendbuf.remaining() >= controlSendbuf.remaining())
					sendbuf.put(controlSendbuf);
				else {
					int size = sendbuf.remaining();
					sendbuf.put(controlSendbuf.array(), controlSendbuf.position(), size);
					controlSendbuf.position(controlSendbuf.position() + size);
				}
				controlSendbuf.compact();
			}
		}

		if (sendbuf.remaining() >= MAX_MESSAGESIZE) {
			for (int cid = 1; cid <= 126; cid ++) {
				if (slots[cid].state == STATE_CONNECTED && slots[cid].hasAck) {
					slots[cid].hasAck = false;
					int ack = slots[cid].channel.sendAckToCarrier();
					if (ack > 0) {
						sendbuf.put((byte)cid);
						sendbuf.put((byte)ack);
					}
				}
			}
		}

		if (LOGTRAFFIC && sendbuf.position() > mark)
			LOG.finest("SEND: " + Hex.bytesToString(sendbuf.array(),
						mark, sendbuf.position() - mark));

		while (sendbuf.remaining() >= MAX_MESSAGESIZE && hasAnyData) {
			int cid = 0;
			if (rand.nextBoolean()) {
				for (int i = 1; i <= 126; i ++)
					if (slots[i].state == STATE_CONNECTED && slots[i].hasData) {
						cid = i;
						break;
					}
			} else {
				for (int i = 126; i >= 1; i --)
					if (slots[i].state == STATE_CONNECTED && slots[i].hasData) {
						cid = i;
						break;
					}
			}
			if (cid == 0) {
				hasAnyData = false;
				break;
			}
			slots[cid].hasData = false;
			slots[cid].hasAck = false;
			int position = sendbuf.position();
			sendbuf.position(position + 4);
			int size = slots[cid].channel.sendToCarrier(sendbuf);
			if (size == 0) { // this happens
				sendbuf.position(position);
				continue;
			}
			int ack = slots[cid].channel.sendAckToCarrier();
			int position2 = sendbuf.position();
			assert position2 > position + 4;
			sendbuf.put(position+0, (byte)(128 + cid));
			sendbuf.put(position+1, (byte)ack);
			sendbuf.putShort(position+2, (short)(position2 - position - 4));
			if (LOGTRAFFIC)
				LOG.finest("SEND DATA " +
						Hex.bytesToString(sendbuf.array(), position, 4) +
						" " + (position2 - position - 4));
		}
	}

	/* called by channel in channel thread to indicate local socket initiated close */
	void channelClose (int channelID)
	{
		assert slots[channelID].state != STATE_EMPTY && slots[channelID].state != STATE_CONNECTING;
		if (slots[channelID].state == STATE_CONNECTED) { // TODO: race condition
			slots[channelID].state = STATE_TEARING;
			synchronized (controlSendbuf) {
				controlSendbuf.put((byte)0);
				controlSendbuf.put(CTRL_TRDN);
				controlSendbuf.put((byte)channelID);
			}
			selector.wakeup();
		}
	}

	/* called by channel in channel thread to indicate more data */
	void channelSend (int channelID)
	{
		assert slots[channelID].state == STATE_CONNECTED;
		slots[channelID].hasData = true;
		hasAnyData = true;
		selector.wakeup();
	}

	/* called by channel in channel thread to indicate that it want to send ack */
	void channelSendAck (int channelID)
	{
		assert slots[channelID].state == STATE_CONNECTED;
		slots[channelID].hasAck = true;
		selector.wakeup();
	}

	class Slot {
		final int id;
		int state;
		TChannel channel;
		boolean hasAck;
		boolean hasData;
		SocketChannel socket; // only for local initiated channel

		Slot (int id, int state)
		{
			this.id = id;
			this.state = state;
		}
	}

	class ChannelRunner extends Thread
	{
		final int cid;
		final String hostname;
		final int port;
		final int peerRecvbufSize;

		ChannelRunner (int cid, String hostname, int port, int peerRecvbufSize)
		{
			super("C" + cid);
			this.cid = cid;
			this.hostname = hostname;
			this.port = port;
			this.peerRecvbufSize = peerRecvbufSize;
		}

		@Override
		public void run ()
		{
			assert slots[cid].state == STATE_CONNECTING;
			InetSocketAddress addr = new InetSocketAddress(hostname, port);
			if (carrierClosed) return;
			if (addr.isUnresolved()) {
				assert slots[cid].state == STATE_CONNECTING;
				slots[cid].state = STATE_EMPTY;
				synchronized (controlSendbuf) {
					controlSendbuf.put((byte)0);
					controlSendbuf.put(CTRL_CON2);
					controlSendbuf.put((byte)cid);
					controlSendbuf.put((byte)2);
				}
				selector.wakeup();
				return;
			}
			SocketChannel socket;
			try {
				socket = SocketChannel.open(addr);
			} catch (IOException x) {
				LOG.warning("error connecting " + hostname + ":" + port + " cid=" + cid);
				assert slots[cid].state == STATE_CONNECTING;
				slots[cid].state = STATE_EMPTY;
				synchronized (controlSendbuf) {
					controlSendbuf.put((byte)0);
					controlSendbuf.put(CTRL_CON2);
					controlSendbuf.put((byte)cid);
					controlSendbuf.put((byte)2);
				}
				selector.wakeup();
				return;
			}

			if (carrierClosed) {
				try {socket.close();} catch (Exception x) {}
				return;
			}
			assert slots[cid].state == STATE_CONNECTING;
			slots[cid].state = STATE_CONNECTED;
			synchronized (controlSendbuf) {
				controlSendbuf.put((byte)0);
				controlSendbuf.put(CTRL_CON2);
				controlSendbuf.put((byte)cid);
				controlSendbuf.put((byte)0);
				controlSendbuf.putShort((short)(CHANNEL_RECVBUF_SIZE / ACK_UNIT));
			}
			slots[cid].channel = new TChannel(
					Carrier.this, cid,
					CHANNEL_SENDBUF_SIZE, CHANNEL_RECVBUF_SIZE,
					peerRecvbufSize, socket);
			selector.wakeup();
			slots[cid].channel.run();
		}
	}
}
