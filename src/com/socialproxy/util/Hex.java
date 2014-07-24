package com.socialproxy.util;

public class Hex
{
	final protected static char[] hexArray = "0123456789abcdef".toCharArray();
	public static String bytesToString (byte[] bytes, int offset, int length)
	{
		char[] hexChars = new char[length * 2];
		for (int j = 0; j < length; j++) {
			int v = bytes[offset + j] & 0xFF;
			hexChars[j * 2] = hexArray[v >>> 4];
			hexChars[j * 2 + 1] = hexArray[v & 0x0F];
		}
		return new String(hexChars);
	}
}
