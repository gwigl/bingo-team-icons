package com.bingoteamicons;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

/**
 * Settings are edited from the sidebar panel; these items exist only for
 * persistence (team name lists are stored as plain config keys team1Names..team10Names).
 */
@ConfigGroup(BingoTeamIconsConfig.GROUP)
public interface BingoTeamIconsConfig extends Config
{
	String GROUP = "bingoteamicons";
	String TEAM_COUNT_KEY = "teamCount";

	@ConfigItem(
		keyName = TEAM_COUNT_KEY,
		name = "Number of teams",
		description = "How many bingo teams there are. Edit from the sidebar panel.",
		hidden = true
	)
	default int teamCount()
	{
		return 2;
	}

	@ConfigItem(
		keyName = "broadcastIcons",
		name = "Broadcast icons",
		description = "Also show team icons on collection log and drop broadcasts in clan chat"
	)
	default boolean broadcastIcons()
	{
		return true;
	}
}
