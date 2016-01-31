package com.socialproxy.core;

public class Core {
	public static Core instance;

	private Core () {}

	public static Core getInstance ()
	{
		assert instance != null;
		return instance;
	}

	public static Core create ()
	{
		instance = new Core();
		return instance;
	}
}
