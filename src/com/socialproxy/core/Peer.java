package com.socialproxy.core;

import java.security.PublicKey;

public class Peer {
	public final PublicKey publicKey;
	public final byte [] rootKey;

	public Peer (PublicKey p, byte [] r)
	{
		publicKey = p;
		rootKey = r;
	}
}
