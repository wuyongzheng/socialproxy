package com.socialproxy.tunnel;

import java.io.OutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.Closeable;
import com.socialproxy.util.CircularByteBuffer;

public class TChannel implements Closeable
{
	//public static final int 
	private final Carrier carrier;
	private final int carrierSlot;
	boolean closed;
	final CircularByteBuffer muxbuf = new CircularByteBuffer(1500);
	final CircularByteBuffer dembuf = new CircularByteBuffer(1500);
	final IStream input = new IStream();
	final OStream output = new OStream();

	public TChannel (Carrier carrier, int carrierSlot)
	{
		this.carrier = carrier;
		this.carrierSlot = carrierSlot;
	}

	public void close () throws IOException
	{
		closed = true;
		synchronized (muxbuf) {muxbuf.notify();}
		synchronized (dembuf) {dembuf.notify();}
	}

	public InputStream getInputStream () {return input;}
	public OutputStream getOutputStream () {return output;}

	class IStream extends InputStream {
		@Override
		public int read () throws IOException
		{
			if (closed)
				return -1;
			synchronized (dembuf) {
				while (dembuf.isEmpty() && !closed)
					try {dembuf.wait();} catch (InterruptedException x) {};
				if (closed)
					return -1;
				int retval = dembuf.get();
				dembuf.notify();
				return retval;
			}
		}

		@Override
		public int read (byte[] b, int off, int len) throws IOException
		{
			if (len <= 0)
				return 0;
			if (closed)
				return -1;
			synchronized (dembuf) {
				while (dembuf.isEmpty() && !closed)
					try {dembuf.wait();} catch (InterruptedException x) {};
				if (closed)
					return -1;
				int retval = dembuf.get(b, off, len);
				dembuf.notify();
				return retval;
			}
		}

		//@Override public long skip (long n) throws IOException {}

		@Override
		public int available () throws IOException
		{
			return dembuf.getUsed();
		}

		@Override
		public void close () throws IOException
		{
			TChannel.this.close();
		}
	}

	class OStream extends OutputStream {
		@Override
		public void write (int b) throws IOException
		{
			if (closed)
				throw new IOException("write to closed OStream");
			synchronized (muxbuf) {
				while (muxbuf.isFull() && !closed)
					try {muxbuf.wait();} catch (InterruptedException x) {};
				if (closed)
					throw new IOException("write to closed OStream");
				muxbuf.put((byte)b);
				muxbuf.notify();
			}
		}

		@Override
		public void write (byte[] b, int off, int len) throws IOException
		{
			if (closed)
				throw new IOException("write to closed OStream");
			synchronized (muxbuf) {
				while (len > 0) {
					while (muxbuf.isFull() && !closed)
						try {muxbuf.wait();} catch (InterruptedException x) {};
					if (closed)
						throw new IOException("write to closed OStream");
					int putlen = muxbuf.put(b, off, len);
					off += putlen;
					len -= putlen;
					muxbuf.notify();
				}
			}
		}

		@Override
		public void close () throws IOException
		{
			TChannel.this.close();
		}
	}
}
