package com.socialproxy.util;

public class HexEncoding
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

	public static String bytesToString (byte[] bytes)
	{
		return bytesToString(bytes, 0, bytes.length);
	}

	public static byte[] stringToBytes (String hex)
	{
		byte [] bytes = new byte [hex.length()/2];
		for (int i = 0; i < bytes.length; i++) {
			char c1 = hex.charAt(i*2);
			char c2 = hex.charAt(i*2+1);
			int value = 0;
			if (c1 >= '0' && c1 <= '9')
				value = c1 - '0';
			else if (c1 >= 'a' && c1 <= 'f')
				value = c1 - 'a' + 10;
			else if (c1 >= 'A' && c1 <= 'F')
				value = c1 - 'A' + 10;
			value <<= 4;
			if (c2 >= '0' && c2 <= '9')
				value += c2 - '0';
			else if (c2 >= 'a' && c2 <= 'f')
				value += c2 - 'a' + 10;
			else if (c2 >= 'A' && c2 <= 'F')
				value += c2 - 'A' + 10;
			bytes[i] = (byte)value;
		}
		return bytes;
	}

	public static int bytesToInt (byte [] bytes, int offset)
	{
		return (bytes[offset] << 24) |
			(bytes[offset+1] << 16) |
			(bytes[offset+2] << 8) |
			bytes[offset+3];
	}

	public static long bytesToLong (byte [] bytes, int offset)
	{
		return ((long)bytesToInt(bytes, offset) << 32) |
			(bytesToInt(bytes, offset+4) & 0xffffffffl);
	}

	public static void intToBytes (int value, byte [] bytes, int offset)
	{
		bytes[offset+0] = (byte)(value >> 24);
		bytes[offset+1] = (byte)(value >> 16);
		bytes[offset+2] = (byte)(value >> 8);
		bytes[offset+3] = (byte)(value >> 0);
	}

	public static void longToBytes (long value, byte [] bytes, int offset)
	{
		intToBytes((int)(value >> 32), bytes, offset);
		intToBytes((int)value, bytes, offset+4);
	}
}
