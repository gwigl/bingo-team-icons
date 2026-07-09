package com.bingoteamicons;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class BingoTeamIconsPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(BingoTeamIconsPlugin.class);
		RuneLite.main(args);
	}
}
