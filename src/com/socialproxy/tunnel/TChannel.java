package com.socialproxy.tunnel;

//import java.io.OutputStream;
//import java.io.InputStream;
import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.socialproxy.util.CircularByteBuffer;

/* TChannel is responsible for relaying traffic between a connected local
 * socket and the carrier */
public class TChannel implements Runnable
{
	private final static Logger LOG = Logger.getLogger(TChannel.class.getName());
	private final Carrier carrier;
	private final int channelID;
	private final ByteBuffer         sendbuf; // from local socket to carrier
	private final CircularByteBuffer recvbuf; // from carrier to local socket
	private final SocketChannel socket;
	private boolean closed = false;
	private final Selector selector;

	/* myUnsendAck: number of bytes that has not been acked to the peer.
	 * initially = 0
	 * increase when local socket consumes data
	 * decrease when sending ack
	 * always 0 <= myUnsendAck <= my recvbuf size */
	private final AtomicInteger myUnsendAck;

	/* peerFreeRecvbuf: number of bytes that is free in peer's receiving buffer.
	 * i.e. size of data that we can send.
	 * initially = peer recvbuf size
	 * increase when we receive ack
	 * decrease when we send data (put data to sendbuf)
	 * always: 0 <= peerFreeRecvbuf <= peer recvbuf size */
	private final AtomicInteger peerFreeRecvbuf;

	public TChannel (Carrier carrier, int channelID,
			int sendbufSize, int recvbufSize, int peerRecvbufSize,
			SocketChannel socket)
	{
		this.carrier = carrier;
		this.channelID = channelID;
		sendbuf = ByteBuffer.allocate(sendbufSize);
		recvbuf = new CircularByteBuffer(recvbufSize);
		this.socket = socket;
		try {
			selector = Selector.open();
		} catch (IOException x) {
			throw new RuntimeException(x);
		}

		myUnsendAck = new AtomicInteger(0);
		peerFreeRecvbuf = new AtomicInteger(peerRecvbufSize);
		LOG.fine("TC" + channelID + " TChannel()");
	}

	/* called by carrier when it receives TRDN */
	public void onTRDN ()
	{
		LOG.fine("TC" + channelID + " onTRDN()");
		closed = true;
		selector.wakeup();
	}

	/* called by carrier when it receives DATA
	 * ack is in bytes
	 * throw exception if protocol error. e.g. overflow */
	public void onDATA (byte [] data, int offset, int size, int ack) throws CarrierProtocolException
	{
		assert size > 0;
		assert ack >= 0;
		synchronized (recvbuf) {
			// the ack mechanism guarantees recvbuf has enough free space
			if (recvbuf.getFree() < size) {
				LOG.warning("TC" + channelID + " recvbuf.getFree()=" + recvbuf.getFree() + " < datasize=" + size);
				closed = true;
				selector.wakeup();
				throw new CarrierProtocolException("receive buffer overflow");
			} else {
				int putsize = recvbuf.put(data, offset, size);
				assert putsize == size;
				LOG.finer("TC" + channelID + " onDATA(size=" + size + ") " +
						"new recvbuf:" + recvbuf.getUsed() +
						"u/" + recvbuf.getFree() + "f");
			}
		}
		if (ack > 0) {
			int newvalue = peerFreeRecvbuf.addAndGet(ack);
			LOG.finer("TC" + channelID + " onDATA() ack=" + ack + ", new peerFreeRecvbuf=" + newvalue);
		}
		selector.wakeup();
	}

	/* called by carrier when it receives ack. in bytes */
	public void onAck (int ack)
	{
		assert ack > 0;
		int newvalue = peerFreeRecvbuf.addAndGet(ack);
		LOG.finer("TC" + channelID + " onAck() ack=" + ack + ", new peerFreeRecvbuf=" + newvalue);
		selector.wakeup();
	}

	/* called by carrier.
	 * return bytes transfered to dst.
	 * dst should have enough space. */
	public int sendToCarrier (ByteBuffer dst)
	{
		int size;
		synchronized (sendbuf) {
			if (sendbuf.position() == 0)
				return 0;
			sendbuf.flip();
			dst.put(sendbuf);
			size = sendbuf.position();
			sendbuf.compact();
		}
		LOG.finer("TC" + channelID + " sendToCarrier() return " + size);
		selector.wakeup();
		return size;
	}

	/* called by carrier.
	 * return ack in ACK_UNIT bytes */
	public int sendAckToCarrier ()
	{
		int oldval;
		int decrement; // in ACK_UNIT bytes
		do {
			oldval = myUnsendAck.get();
			assert oldval >= 0;
			decrement = oldval / Carrier.ACK_UNIT;
			if (decrement == 0) break;
			if (decrement > Carrier.MAX_ACK) decrement = Carrier.MAX_ACK;
		} while (!myUnsendAck.compareAndSet(oldval, oldval - decrement * Carrier.ACK_UNIT));
		LOG.finer("TC" + channelID + " sendAckToCarrier() oldval=" + oldval + ", decrement=" + decrement + "*" + Carrier.ACK_UNIT);
		return decrement;
	}

	private void runInternal () throws Exception
	{
		socket.configureBlocking(false);
		SelectionKey selkey = socket.register(selector, 0);
		while (true) {
			int selops = 0;
			if (peerFreeRecvbuf.get() > 0 && sendbuf.hasRemaining())
				selops |= SelectionKey.OP_READ;
			LOG.finer("TC" + channelID + " peerFreeRecvbuf=" +
					peerFreeRecvbuf.get() + " sendbuf.remaining=" +
					sendbuf.remaining());
			if (!recvbuf.isEmpty())
				selops |= SelectionKey.OP_WRITE;
			selkey.interestOps(selops);
			LOG.finer("TC" + channelID + " selecting " +
					((selops & SelectionKey.OP_READ) == 0 ? "" : "r") +
					((selops & SelectionKey.OP_WRITE) == 0 ? "" : "w"));
			selector.select();
			LOG.finer("TC" + channelID + " selected " +
					(closed ? "c" : "") +
					(selkey.isReadable() ? "r" : "") +
					(selkey.isWritable() ? "w" : ""));
			if (closed) {
				socket.close();
				selector.close();
				break;
			}

			if (selector.selectedKeys().size() == 1 &&
					selkey.isReadable() &&
					peerFreeRecvbuf.get() > 0) {
				int size = 0;
				synchronized (sendbuf) {
					if (sendbuf.hasRemaining()) {
						// has to limit the bytes received from socket by peerFreeRecvbuf
						int remaining = peerFreeRecvbuf.get();
						if (sendbuf.remaining() > remaining)
							sendbuf.limit(sendbuf.position() + remaining);
						size = socket.read(sendbuf);
						LOG.finer("TC" + channelID + " read " + size + " bytes (limit=" + remaining + ") from local socket");
						sendbuf.limit(sendbuf.capacity());
					}
				}
				if (size == -1) {
					carrier.channelClose(channelID);
					socket.close();
					selector.close();
					break;
				} else if (size > 0) {
					int newvalue = peerFreeRecvbuf.addAndGet(-size);
					assert newvalue >= 0;
					carrier.channelSend(channelID);
				}
			}

			if (selector.selectedKeys().size() == 1 &&
					selkey.isWritable() &&
					!recvbuf.isEmpty()) {
				int size = 0;
				synchronized (recvbuf) {
					try {
						size = recvbuf.get(socket, -1);
					} catch (IOException x) {
						size = -1;
						LOG.log(Level.INFO, "Exception while writing to local socket", x);
					}
					LOG.finer("TC" + channelID + " write " + size + " bytes to local socket");
				}
				if (size == -1) {
					carrier.channelClose(channelID);
					socket.close();
					selector.close();
					break;
				} else if (size > 0) {
					int newval = myUnsendAck.addAndGet(size);
					// keep silent if recvbuf is %95 free
					if (newval >= Carrier.ACK_UNIT && newval >= recvbuf.getSize() / 20)
						carrier.channelSendAck(channelID);
				}
			}
		}
	}

	@Override
	public void run ()
	{
		try {
			runInternal();
		} catch (Exception x) {
			throw new RuntimeException(x);
		}
	}
}
