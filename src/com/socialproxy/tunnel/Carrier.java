package com.socialproxy.tunnel;

import java.io.IOException;
import java.nio.channels.Selector;
import java.nio.channels.SelectionKey;
import java.nio.channels.SocketChannel;
import java.nio.ByteBuffer;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import javax.crypto.spec.IvParameterSpec;

/* A carrier can carry mutiple tunneled channels.
 * It acts as a multiplexer bewteen multiple channels (frontend)
 * and a single transport backend, typically a TCP socket or UDT socket.
 *
 * This class handles the protocol AFTER handshake and authentication,
 * so the constructor expects the encryption & decryption keys.
 */
public class Carrier implements Runnable {
	public static final int ACK_UNIT = 256;
	public static final int MAX_ACK = 255;
	public static final int MAX_MESSAGESIZE = 2000;
	public static final int MAX_DATASIZE = MAX_MESSAGESIZE - 4;
	private static final int SELECT_TIMEOUT_MS = 500; // timeout in needed for speed limit
	private final static Logger LOG = Logger.getLogger(Carrier.class.getName());

	private final SocketChannel backsock;
	private final boolean isMajor; // minor creates [1, 63]; major creates [64, 126]
	private final Slot[] slots;
	private final Cipher encCipher;
	private final Cipher decCipher;
	private final Selector selector;
	private final ByteBuffer recvbuf = ByteBuffer.allocate(MAX_MESSAGESIZE * 2);
	private final ByteBuffer sendbuf = ByteBuffer.allocate(MAX_MESSAGESIZE * 2);

	public Carrier (SocketChannel backend, boolean isMajor, byte[] enckey, byte[] deckey)
	{
		assert enckey.length == 128/8;
		assert deckey.length == 128/8;

		backsock = backend;
		this.isMajor = isMajor;
		slots = new Slot[256];
		for (int i = 1; i <= 126; i ++)
			slots[i] = new Slot(i);

		try {
			encCipher = Cipher.getInstance("AES/CTR/NoPadding");
			encCipher.init(Cipher.ENCRYPT_MODE,
					new SecretKeySpec(enckey, "AES"),
					new IvParameterSpec(new byte[128/8]));
			decCipher = Cipher.getInstance("AES/CTR/NoPadding");
			decCipher.init(Cipher.DECRYPT_MODE,
					new SecretKeySpec(deckey, "AES"),
					new IvParameterSpec(new byte[128/8]));
		} catch (java.security.InvalidAlgorithmParameterException x) {
			throw new RuntimeException(x);
		} catch (java.security.InvalidKeyException x) {
			throw new RuntimeException(x);
		} catch (java.security.NoSuchAlgorithmException x) {
			throw new RuntimeException(x);
		} catch (javax.crypto.NoSuchPaddingException x) {
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
				SelectionKey.OP_READ | SelectionKey.OP_WRITE :
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
			LOG.finer("selected: " +
					(selkey.isReadable() ? "r" : "") +
					(selkey.isWritable() ? "w" : ""));
			if (selkey.isReadable()) {
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
					// TODO handle close
				} else if (size > 0) {
					tmpbuf.flip();
					try {
						int decsize = decCipher.update(tmpbuf, recvbuf);
						assert decsize == size;
					} catch (javax.crypto.ShortBufferException x) {
						throw new RuntimeException(x);
					}
					recvbuf.flip();
					processRecvbuf();
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

			if (selkey.isWritable()) {
				assert sendbuf.position() > 0;
				sendbuf.flip();
				try {
					int size = backsock.write(tmpbuf);
					assert size > 0;
				} catch (IOException x) {
					throw new RuntimeException(x);
				}
				sendbuf.compact();
			}
		}
	}

	/* precondition:
	 *   recvbuf's position is 0
	 *   recvbuf's limit is total size of all messages in recvbuf.
	 * post condition:
	 *   recvbuf's position = limit or start of last partial message.
	 *     i.e. should process as many message as possible.
	 *   recvbuf's limit is not changed
	 */
	private void processRecvbuf ()
	{
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
	}

	/* called by channel in channel thread to indicate local socket initiated close */
	void channelClose (int channelID)
	{
	}

	/* called by channel in channel thread to indicate more data */
	void channelSend (int channelID)
	{
	}

	/* called by channel in channel thread to indicate that it want to send ack */
	void channelSendAck (int channelID)
	{
	}

	class Slot {
		final int id;
		TChannel channel;

		Slot (int id)
		{
			this.id = id;
		}
	}
}
