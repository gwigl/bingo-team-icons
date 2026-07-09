package com.bingoteamicons;

import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;

/**
 * Test-only plugin (not part of the hub build): "::bingotest" or
 * "::bingotest Some Name" injects sample chat lines and broadcasts locally
 * (nothing is sent to the server) to preview team icons.
 */
@PluginDescriptor(
	name = "Bingo Team Icons Test",
	description = "::bingotest command that injects sample messages to preview team icons"
)
public class BingoTeamIconsTestPlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ConfigManager configManager;

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"bingotest".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}

		String name = null;
		String[] args = event.getArguments();
		if (args != null && args.length > 0)
		{
			name = String.join(" ", args);
		}
		else
		{
			String team1 = configManager.getConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsPanel.teamNamesKey(1));
			if (team1 != null && !team1.isEmpty())
			{
				name = Text.standardize(team1.split("[,\n]")[0]);
			}
		}
		if (name == null || name.isEmpty())
		{
			name = client.getLocalPlayer() != null ? client.getLocalPlayer().getName() : "Player";
		}

		client.addChatMessage(ChatMessageType.CLAN_CHAT, name, "Test chat message", "bingo-test");
		client.addChatMessage(ChatMessageType.CLAN_MESSAGE, "",
			name + " received a drop: Twisted bow (1,644,105,262 coins).", "bingo-test");
		client.addChatMessage(ChatMessageType.CLAN_MESSAGE, "",
			name + " received a new collection log item: Twisted bow (472/1,568)", "bingo-test");
	}
}
