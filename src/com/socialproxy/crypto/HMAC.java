package com.socialproxy.crypto;

import javax.crypto.spec.SecretKeySpec;
import javax.crypto.Mac;

public class HMAC
{
	/* the returned byte array is 20 bytes */
	public static byte [] get (byte [] key, int keyOffset, int keyLength,
			byte [] message, int messageOffset, int messageLength)
	{
		try {
			SecretKeySpec keySpec = new SecretKeySpec(key, keyOffset, keyLength, "HmacSHA1");
			Mac mac = Mac.getInstance("HmacSHA1");
			mac.init(keySpec);
			mac.update(message, messageOffset, messageLength);
			byte [] out = mac.doFinal();
			assert out.length == 20;
			return out;
		} catch (Exception x) {
			throw new RuntimeException(x);
		}
	}

	public static byte [] get (byte [] key, String message)
	{
		try {
			byte [] msg = message.getBytes("UTF-8");
			return get(key, 0, key.length, msg, 0, msg.length);
		} catch (Exception x) {
			throw new RuntimeException(x);
		}
	}
}
