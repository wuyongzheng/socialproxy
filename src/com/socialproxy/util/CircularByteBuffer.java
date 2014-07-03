package com.socialproxy.util;

public final class CircularByteBuffer
{
	private final byte [] buffer;
	private int ptr;
	private int used;

	public CircularByteBuffer (int capacity)
	{
		buffer = new byte [capacity];
	}

	public int getUsed () {return used;}
	public int getFree () {return buffer.length - used;}
	public boolean isEmpty () {return used == 0;}
	public boolean isFull () {return buffer.length == used;}

	public synchronized int get ()
	{
		if (used == 0)
			throw new ArrayIndexOutOfBoundsException();
		int retval = buffer[ptr] & 0xff;
		ptr = (ptr + 1) % buffer.length;
		used --;
		return retval;
	}

	public synchronized int get (byte[] out, int offset, int size)
	{
		if (size <= 0 || used == 0)
			return 0;
		//TODO: use array copy
		int origsize = size;
		while (used > 0 && size > 0) {
			out[offset ++] = (byte)get();
			size --;
		}
		return origsize - size;
	}

	public synchronized void put (byte b)
	{
		if (buffer.length == used)
			throw new ArrayIndexOutOfBoundsException();
		buffer[(ptr + used) % buffer.length] = b;
		used ++;
	}

	public synchronized int put (byte[] in, int offset, int size)
	{
		if (size <= 0 || buffer.length == used)
			return 0;
		//TODO: use array copy
		int origsize = size;
		while (size > 0 && used < buffer.length) {
			put(in[offset ++]);
			size --;
		}
		return origsize - size;
	}
}
