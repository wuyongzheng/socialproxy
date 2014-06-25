package com.socialproxy.bittorrent;

public class HexEncoding
{
	final protected static char[] hexArray = "0123456789abcdef".toCharArray();
	public static String bytesToHex (byte[] bytes)
	{
		char[] hexChars = new char[bytes.length * 2];
		for ( int j = 0; j < bytes.length; j++ ) {
			int v = bytes[j] & 0xff;
			hexChars[j * 2] = hexArray[v >>> 4];
			hexChars[j * 2 + 1] = hexArray[v & 0xf];
		}
		return new String(hexChars);
	}

	public static byte[] hexToBytes (String hex)
	{
		byte [] bytes = new byte [hex.length()/2];
		for (int i = 0; i < bytes.length; i++) {
			char c1 = hex.charAt(i*2);
			char c2 = hex.charAt(i*2+1);
			int value = 0;
			if (c1 >= '0' && c1 <= '9')
				value = c1 - '0';
			else if (c1 >= 'a' && c1 <= 'F')
				value = c1 - 'a';
			else if (c1 >= 'A' && c1 <= 'F')
				value = c1 - 'A';
			value <<= 4;
			if (c2 >= '0' && c2 <= '9')
				value += c2 - '0';
			else if (c2 >= 'a' && c2 <= 'F')
				value += c2 - 'a';
			else if (c2 >= 'A' && c2 <= 'F')
				value += c2 - 'A';
			bytes[i] = (byte)value;
		}
		return bytes;
	}
}
