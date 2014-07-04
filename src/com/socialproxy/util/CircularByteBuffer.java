package com.socialproxy.util;

/* CircularByteBuffer is not thread safe. */
public final class CircularByteBuffer
{
	private final byte [] buffer;
	private int ptr;
	private int used;

	public CircularByteBuffer (int capacity)
	{
		buffer = new byte [capacity];
		ptr = used = 0;
	}

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
}
