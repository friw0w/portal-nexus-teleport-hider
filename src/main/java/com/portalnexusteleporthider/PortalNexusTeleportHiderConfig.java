package com.portalnexusteleporthider;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(PortalNexusTeleportHiderConfig.GROUP)
public interface PortalNexusTeleportHiderConfig extends Config
{
	String GROUP = "portalnexusteleporthider";
	String HIDDEN_DESTINATIONS = "hiddenDestinations";

	@ConfigItem(
		keyName = HIDDEN_DESTINATIONS,
		name = "Hidden destinations",
		description = "Struct IDs of the destinations to hide. Managed by shift + right-click in game.",
		hidden = true
	)
	default String hiddenDestinations()
	{
		return "";
	}

	@ConfigItem(
		keyName = "showHidden",
		name = "Show hidden",
		description = "Keep hidden destinations in the list, greyed out and struck through, so they can be unhidden.",
		position = 1
	)
	default boolean showHidden()
	{
		return false;
	}

	@ConfigItem(
		keyName = "recompactShortcuts",
		name = "Recompact shortcuts",
		description = "Renumber the 1-9 and A-Z shortcuts so they run without gaps. When off, the visible rows keep the shortcuts they would have had.",
		position = 2
	)
	default boolean recompactShortcuts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "diagnosticLogging",
		name = "Diagnostic logging",
		description = "Log what the plugin does to each row. Only visible when the client is started with --debug.",
		position = 3
	)
	default boolean diagnosticLogging()
	{
		return false;
	}
}
