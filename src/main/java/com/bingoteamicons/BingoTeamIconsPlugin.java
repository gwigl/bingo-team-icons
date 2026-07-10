package com.bingoteamicons;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ChatIconManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.ui.components.colorpicker.ColorPickerManager;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Bingo Team Icons",
	description = "Shows a team badge next to player names in chat based on their bingo team",
	tags = {"bingo", "team", "clan", "icon", "chat", "event"}
)
public class BingoTeamIconsPlugin extends Plugin
{
	static final int MAX_TEAMS = 10;

	private static final Set<ChatMessageType> PLAYER_CHAT_TYPES = EnumSet.of(
		ChatMessageType.PUBLICCHAT,
		ChatMessageType.MODCHAT,
		ChatMessageType.AUTOTYPER,
		ChatMessageType.MODAUTOTYPER,
		ChatMessageType.FRIENDSCHAT,
		ChatMessageType.CLAN_CHAT,
		ChatMessageType.CLAN_GUEST_CHAT,
		ChatMessageType.CLAN_GIM_CHAT,
		ChatMessageType.PRIVATECHAT,
		ChatMessageType.PRIVATECHATOUT,
		ChatMessageType.MODPRIVATECHAT
	);

	private static final Set<ChatMessageType> BROADCAST_TYPES = EnumSet.of(
		ChatMessageType.CLAN_MESSAGE,
		ChatMessageType.CLAN_GUEST_MESSAGE,
		ChatMessageType.CLAN_GIM_MESSAGE
	);

	// "Player received a drop: ..." / "Player received a new collection log item: ..."
	private static final Pattern BROADCAST_PATTERN = Pattern.compile(
		"^(.+?) received (?:a drop|a new collection log item):");

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ChatIconManager chatIconManager;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ColorPickerManager colorPickerManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private BingoTeamIconsOverlay overlay;

	@Inject
	private BingoTeamIconsConfig config;

	// iconIds[team - 1] = id returned by ChatIconManager; icons are registered once
	// and kept for the lifetime of the client, since they cannot be unregistered.
	// Color changes swap the icon image in place via updateChatIcon.
	private static int[] iconIds;

	private final Map<String, Integer> playerTeams = new HashMap<>();
	private final BufferedImage[] badgeImages = new BufferedImage[MAX_TEAMS];
	private NavigationButton navButton;

	// team of the friends list row currently being laid out, between the
	// friendsChatSetText and friendsChatSetPosition script callbacks
	private Integer currentlyLayoutingTeam;

	@Provides
	BingoTeamIconsConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(BingoTeamIconsConfig.class);
	}

	@Override
	protected void startUp()
	{
		if (iconIds == null)
		{
			iconIds = new int[MAX_TEAMS];
			for (int i = 0; i < MAX_TEAMS; i++)
			{
				BufferedImage badge = TeamIconFactory.createBadge(teamColor(i + 1));
				badgeImages[i] = badge;
				iconIds[i] = chatIconManager.registerChatIcon(badge);
			}
		}
		else
		{
			// colors may have changed while the plugin was off
			clientThread.invokeLater(this::updateIconImages);
		}

		rebuildPlayerTeams();
		overlayManager.add(overlay);
		rebuildFriendsList();

		BingoTeamIconsPanel panel = new BingoTeamIconsPanel(this, configManager, colorPickerManager);
		navButton = NavigationButton.builder()
			.tooltip("Bingo Team Icons")
			.icon(TeamIconFactory.createPanelIcon())
			.priority(7)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		clientThread.invokeLater(this::retagChatHistory);
	}

	@Override
	protected void shutDown()
	{
		overlayManager.remove(overlay);
		clientToolbar.removeNavigation(navButton);
		navButton = null;
		playerTeams.clear();
		clientThread.invokeLater(this::retagChatHistory);
		rebuildFriendsList();
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!BingoTeamIconsConfig.GROUP.equals(event.getGroup()))
		{
			return;
		}

		if (event.getKey().endsWith("Color"))
		{
			clientThread.invokeLater(this::updateIconImages);
			return;
		}

		rebuildPlayerTeams();
		clientThread.invokeLater(this::retagChatHistory);
		rebuildFriendsList();
	}

	/**
	 * Appends the team badge to names while the client lays out friends list
	 * rows, using the same script callbacks as the core Friend Notes plugin.
	 */
	@Subscribe
	public void onScriptCallbackEvent(ScriptCallbackEvent event)
	{
		if (!config.friendsListIcons())
		{
			return;
		}

		switch (event.getEventName())
		{
			case "friendsChatSetText":
			{
				Object[] objectStack = client.getObjectStack();
				int objectStackSize = client.getObjectStackSize();
				String rsn = (String) objectStack[objectStackSize - 1];
				Integer team = playerTeams.get(Text.standardize(rsn));
				currentlyLayoutingTeam = null;
				if (team != null)
				{
					int iconIndex = chatIconManager.chatIconIndex(iconIds[team - 1]);
					if (iconIndex != -1)
					{
						currentlyLayoutingTeam = team;
						objectStack[objectStackSize - 1] = rsn + " <img=" + iconIndex + ">";
					}
				}
				break;
			}
			case "friendsChatSetPosition":
			{
				if (currentlyLayoutingTeam == null)
				{
					return;
				}

				int[] intStack = client.getIntStack();
				int intStackSize = client.getIntStackSize();
				intStack[intStackSize - 4] += TeamIconFactory.BADGE_SIZE + 1;
				break;
			}
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (PLAYER_CHAT_TYPES.contains(event.getType()))
		{
			tagMessageNode(event.getMessageNode());
		}
		else if (BROADCAST_TYPES.contains(event.getType()))
		{
			tagBroadcastNode(event.getMessageNode());
		}
	}

	/**
	 * The configured color for a team, falling back to the default palette.
	 */
	Color teamColor(int team)
	{
		String stored = configManager.getConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsPanel.teamColorKey(team));
		if (stored != null && !stored.isEmpty())
		{
			try
			{
				return Color.decode(stored);
			}
			catch (NumberFormatException ex)
			{
				log.debug("invalid stored color for team {}: {}", team, stored);
			}
		}
		return TeamIconFactory.defaultTeamColor(team);
	}

	private void updateIconImages()
	{
		for (int team = 1; team <= MAX_TEAMS; team++)
		{
			BufferedImage badge = TeamIconFactory.createBadge(teamColor(team));
			badgeImages[team - 1] = badge;
			chatIconManager.updateChatIcon(iconIds[team - 1], badge);
		}
	}

	/**
	 * Re-runs the friends list build script so name tags reflect the current
	 * roster (mirrors the core Friend Notes plugin).
	 */
	private void rebuildFriendsList()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}

			client.runScript(
				ScriptID.FRIENDS_UPDATE,
				InterfaceID.Friends.LIST_CONTAINER,
				InterfaceID.Friends.SORT_NAME,
				InterfaceID.Friends.SORT_RECENT,
				InterfaceID.Friends.SORT_WORLD,
				InterfaceID.Friends.SORT_LEGACY,
				InterfaceID.Friends.LIST,
				InterfaceID.Friends.SCROLLBAR,
				InterfaceID.Friends.LOADING,
				InterfaceID.Friends.TOOLTIP
			);
		});
	}

	boolean hasRoster()
	{
		return !playerTeams.isEmpty();
	}

	/**
	 * The team for an already-standardized player name, or null.
	 */
	Integer teamFor(String standardizedName)
	{
		return playerTeams.get(standardizedName);
	}

	BufferedImage badgeImage(int team)
	{
		return team >= 1 && team <= MAX_TEAMS ? badgeImages[team - 1] : null;
	}

	/**
	 * Adds, updates, or removes our badge on a message node's sender name to
	 * match the current roster. Safe to call repeatedly on the same node.
	 */
	private void tagMessageNode(MessageNode node)
	{
		String name = node.getName();
		if (name == null || name.isEmpty())
		{
			return;
		}

		String strippedName = stripOwnTags(name);
		Integer team = playerTeams.get(Text.standardize(strippedName));
		String tag = "";
		if (team != null)
		{
			int iconIndex = chatIconManager.chatIconIndex(iconIds[team - 1]);
			if (iconIndex != -1)
			{
				tag = "<img=" + iconIndex + ">";
			}
		}

		String newName = tag + strippedName;
		if (!newName.equals(name))
		{
			node.setName(newName);
		}
	}

	/**
	 * Adds, updates, or removes our badge before the player name inside a clan
	 * broadcast message (collection log / drop announcements), where the name
	 * is part of the message text rather than the sender field.
	 */
	private void tagBroadcastNode(MessageNode node)
	{
		String value = node.getValue();
		if (value == null || value.isEmpty())
		{
			return;
		}

		String stripped = stripOwnTags(value);
		String newValue = stripped;

		if (config.broadcastIcons())
		{
			Matcher matcher = BROADCAST_PATTERN.matcher(Text.removeTags(stripped));
			if (matcher.find())
			{
				String playerName = matcher.group(1);
				Integer team = playerTeams.get(Text.standardize(playerName));
				if (team != null)
				{
					int iconIndex = chatIconManager.chatIconIndex(iconIds[team - 1]);
					if (iconIndex != -1)
					{
						String tag = "<img=" + iconIndex + ">";
						int at = stripped.indexOf(playerName);
						newValue = at >= 0
							? stripped.substring(0, at) + tag + stripped.substring(at)
							: tag + stripped;
					}
				}
			}
		}

		if (!newValue.equals(value))
		{
			node.setValue(newValue);
		}
	}

	/**
	 * Removes any of this plugin's img tags from a name, leaving other tags
	 * (e.g. friends chat rank icons) intact.
	 */
	private String stripOwnTags(String name)
	{
		for (int id : iconIds)
		{
			int iconIndex = chatIconManager.chatIconIndex(id);
			if (iconIndex != -1)
			{
				name = name.replace("<img=" + iconIndex + ">", "");
			}
		}
		return name;
	}

	/**
	 * Re-applies tagging to all buffered chat lines so roster changes update
	 * messages already on screen. Must run on the client thread.
	 */
	private void retagChatHistory()
	{
		Map<Integer, ChatLineBuffer> chatLineMap = client.getChatLineMap();
		if (chatLineMap == null)
		{
			return;
		}

		boolean removeAll = navButton == null; // shutting down
		for (ChatLineBuffer buffer : chatLineMap.values())
		{
			if (buffer == null)
			{
				continue;
			}

			for (MessageNode node : buffer.getLines())
			{
				if (node == null)
				{
					continue;
				}

				if (PLAYER_CHAT_TYPES.contains(node.getType()))
				{
					if (removeAll)
					{
						String name = node.getName();
						if (name != null && !name.isEmpty())
						{
							String stripped = stripOwnTags(name);
							if (!stripped.equals(name))
							{
								node.setName(stripped);
							}
						}
					}
					else
					{
						tagMessageNode(node);
					}
				}
				else if (BROADCAST_TYPES.contains(node.getType()))
				{
					if (removeAll)
					{
						String value = node.getValue();
						if (value != null && !value.isEmpty())
						{
							String stripped = stripOwnTags(value);
							if (!stripped.equals(value))
							{
								node.setValue(stripped);
							}
						}
					}
					else
					{
						tagBroadcastNode(node);
					}
				}
			}
		}

		client.refreshChat();
	}

	private void rebuildPlayerTeams()
	{
		playerTeams.clear();
		int teamCount = Math.min(Math.max(config.teamCount(), 1), MAX_TEAMS);
		for (int team = 1; team <= teamCount; team++)
		{
			String names = configManager.getConfiguration(BingoTeamIconsConfig.GROUP, BingoTeamIconsPanel.teamNamesKey(team));
			if (names == null || names.isEmpty())
			{
				continue;
			}

			for (String name : names.split("[,\n]"))
			{
				String standardized = Text.standardize(name);
				if (!standardized.isEmpty())
				{
					playerTeams.putIfAbsent(standardized, team);
				}
			}
		}
	}
}
