package com.portalnexusteleporthider;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

public class PortalNexusTeleportHiderPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PortalNexusTeleportHiderPlugin.class);
		RuneLite.main(args);
	}
}
