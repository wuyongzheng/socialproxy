package com.socialproxy.util;

import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.io.IOException;

/* CircularByteBuffer is not thread safe. */
public final class CircularByteBuffer
{
	private final byte [] buffer;
	private int ptr;
	private int used;
	private ByteBuffer bytebuf = null;

	public CircularByteBuffer (int capacity)
	{
		buffer = new byte [capacity];
		ptr = used = 0;
	}

	public int getSize () {return buffer.length;}
	public int getUsed () {return used;}
	public int getFree () {return buffer.length - used;}
	public boolean isEmpty () {return used == 0;}
	public boolean isFull () {return buffer.length == used;}

	public int get ()
	{
		if (used == 0)
			throw new ArrayIndexOutOfBoundsException();
		int retval = buffer[ptr] & 0xff;
		ptr = (ptr + 1) % buffer.length;
		used --;
		return retval;
	}

	public int get (byte[] out, int offset, int size)
	{
		if (size <= 0 || used == 0)
			return 0;

		if (size > used)
			size = used;
		int tocopy = buffer.length - ptr < size ? buffer.length - ptr : size;
		System.arraycopy(buffer, ptr, out, offset, tocopy);
		ptr = (ptr + tocopy) % buffer.length;
		used -= tocopy;
		offset += tocopy;
		size -= tocopy;

		if (size == 0)
			return tocopy;

		assert used >= size;
		assert ptr == 0;
		System.arraycopy(buffer, 0, out, offset, size);
		ptr += size;
		used -= size;
		return tocopy + size;
	}

	public int get (WritableByteChannel channel, int limit) throws IOException
	{
		if (bytebuf == null)
			bytebuf = ByteBuffer.wrap(buffer);

		if (limit <= 0 || used == 0)
			return 0;
		if (limit > used)
			limit = used;
		int tosend = buffer.length - ptr < limit ? buffer.length - ptr : limit;
		bytebuf.limit(ptr + tosend).position(ptr);
		int sent = channel.write(bytebuf);
		assert sent >= 0;
		assert sent <= tosend;
		ptr = (ptr + sent) % buffer.length;
		used -= sent;
		limit -= sent;
		if (sent < tosend || limit == 0)
			return sent;

		assert used >= limit;
		assert ptr == 0;
		bytebuf.limit(limit).position(0);
		sent = channel.write(bytebuf);
		ptr += sent;
		used -= sent;
		return tosend + sent;
	}

	public void put (byte b)
	{
		if (buffer.length == used)
			throw new ArrayIndexOutOfBoundsException();
		buffer[(ptr + used) % buffer.length] = b;
		used ++;
	}

	public int put (byte[] in, int offset, int size)
	{
		if (size <= 0 || buffer.length == used)
			return 0;

		//System.out.printf("offset=%d, size=%d, cap=%d, ptr=%d, used=%d\n",
		//		offset, size, buffer.length, ptr, used);
		if (size > buffer.length - used)
			size = buffer.length - used;
		int tocopy = buffer.length - (ptr + used) % buffer.length;
		tocopy = tocopy < size ? tocopy : size;
		System.arraycopy(in, offset, buffer, (ptr + used) % buffer.length, tocopy);
		used += tocopy;
		offset += tocopy;
		size -= tocopy;

		if (size == 0)
			return tocopy;

		assert buffer.length - used >= size;
		assert ptr + used == buffer.length;
		System.arraycopy(in, offset, buffer, 0, size);
		used += size;
		return tocopy + size;
	}

	public int put (ReadableByteChannel channel, int limit) throws IOException
	{
		throw new UnsupportedOperationException("put(channel) not implemented");
	}
}
