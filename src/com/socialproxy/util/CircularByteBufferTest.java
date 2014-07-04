package com.socialproxy.util;

import java.util.Random;
import java.util.Arrays;

public class CircularByteBufferTest
{
	public static void test (int datalen, int bufcap, int seed)
	{
		Random rand = new Random(bufcap + datalen + seed);
		CircularByteBuffer cbuf = new CircularByteBuffer(bufcap);
		byte [] indata = new byte [datalen];
		byte [] outdata = new byte [datalen];
		int inptr = 0;
		int outptr = 0;
		rand.nextBytes(indata);

		while (outptr < datalen) {
			if (rand.nextBoolean() && inptr < datalen) { // put
				if (rand.nextInt(10) < 1 && !cbuf.isFull()) { // %10 percent single-byte put
					cbuf.put(indata[inptr ++]);
				} else {
					int size = rand.nextInt(bufcap + bufcap/10);
					size = size < datalen - inptr ? size : datalen - inptr;
					int expcpsize = size < cbuf.getFree() ? size : cbuf.getFree();
					int cpsize = cbuf.put(indata, inptr, size);
					if (expcpsize != cpsize) throw new RuntimeException("put() expcpsize=" + expcpsize + ", cpsize=" + cpsize);
					inptr += cpsize;
				}
			} else { // get
				if (rand.nextInt(10) < 1 && !cbuf.isEmpty()) { // %10 percent single-byte get
					outdata[outptr ++] = (byte)cbuf.get();
				} else {
					int size = rand.nextInt(bufcap + bufcap/10);
					size = size < datalen - outptr ? size : datalen - outptr;
					int expcpsize = size < cbuf.getUsed() ? size : cbuf.getUsed();
					int cpsize = cbuf.get(outdata, outptr, size);
					if (expcpsize != cpsize) throw new RuntimeException("get() expcpsize=" + expcpsize + ", cpsize=" + cpsize);
					outptr += cpsize;
				}
			}
		}

		if (!cbuf.isEmpty()) throw new RuntimeException("more data");
		if (!Arrays.equals(indata, outdata)) throw new RuntimeException("indata != outdata");
	}

	public static void main (String [] args)
	{
		test(10000, 5, 1);
		test(2015, 11, 1);
		test(100000, 99, 2);
		test(100000, 1600, 3);
	}
}
