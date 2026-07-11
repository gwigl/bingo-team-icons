package com.bingoteamicons;

import com.google.inject.Provides;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatLineBuffer;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.FriendsChatManager;
import net.runelite.api.FriendsChatMember;
import net.runelite.api.GameState;
import net.runelite.api.MessageNode;
import net.runelite.api.ScriptID;
import net.runelite.api.clan.ClanChannel;
import net.runelite.api.clan.ClanChannelMember;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.ClanChannelChanged;
import net.runelite.api.events.ClanMemberJoined;
import net.runelite.api.events.ClanMemberLeft;
import net.runelite.api.events.FriendsChatChanged;
import net.runelite.api.events.FriendsChatMemberJoined;
import net.runelite.api.events.FriendsChatMemberLeft;
import net.runelite.api.events.ScriptCallbackEvent;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
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
	// / "Player received special loot from a raid: ..."
	// / pets: "Player has a funny feeling like he's being followed" (and the
	// "would have been followed" dupe) / "Player feels something weird sneaking
	// into her backpack"
	private static final Pattern BROADCAST_PATTERN = Pattern.compile(
		"^(.+?) (?:received (?:a drop|a new collection log item|special loot from a raid):|has a funny feeling|feels something weird sneaking)");

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
	private BingoTeamIconsPanel panel;

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
		rebuildClanLists();

		panel = new BingoTeamIconsPanel(this, configManager, colorPickerManager);
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
		panel = null;
		playerTeams.clear();
		clientThread.invokeLater(this::retagChatHistory);
		rebuildFriendsList();
		rebuildClanLists();
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
		rebuildClanLists();
		refreshPanelOnlineCounts();
	}

	@Subscribe
	public void onClanChannelChanged(ClanChannelChanged event)
	{
		refreshPanelOnlineCounts();
	}

	@Subscribe
	public void onClanMemberJoined(ClanMemberJoined event)
	{
		refreshPanelOnlineCounts();
	}

	@Subscribe
	public void onClanMemberLeft(ClanMemberLeft event)
	{
		refreshPanelOnlineCounts();
	}

	@Subscribe
	public void onFriendsChatChanged(FriendsChatChanged event)
	{
		refreshPanelOnlineCounts();
	}

	@Subscribe
	public void onFriendsChatMemberJoined(FriendsChatMemberJoined event)
	{
		refreshPanelOnlineCounts();
	}

	@Subscribe
	public void onFriendsChatMemberLeft(FriendsChatMemberLeft event)
	{
		refreshPanelOnlineCounts();
	}

	private void refreshPanelOnlineCounts()
	{
		BingoTeamIconsPanel p = panel;
		if (p != null)
		{
			p.refreshOnlineCounts();
		}
	}

	/**
	 * Counts rostered players per team who are currently online in the clan
	 * channel, guest clan channel, or friends chat channel. Reads client state
	 * on the client thread and delivers the result on the Swing EDT.
	 */
	void computeOnlineCounts(Consumer<Map<Integer, Integer>> consumer)
	{
		clientThread.invokeLater(() ->
		{
			Set<String> onlineNames = new HashSet<>();

			ClanChannel clan = client.getClanChannel();
			if (clan != null)
			{
				for (ClanChannelMember member : clan.getMembers())
				{
					onlineNames.add(Text.standardize(member.getName()));
				}
			}

			ClanChannel guest = client.getGuestClanChannel();
			if (guest != null)
			{
				for (ClanChannelMember member : guest.getMembers())
				{
					onlineNames.add(Text.standardize(member.getName()));
				}
			}

			FriendsChatManager friendsChat = client.getFriendsChatManager();
			if (friendsChat != null)
			{
				for (FriendsChatMember member : friendsChat.getMembers())
				{
					onlineNames.add(Text.standardize(member.getName()));
				}
			}

			Map<Integer, Integer> onlineByTeam = new HashMap<>();
			for (Map.Entry<String, Integer> entry : playerTeams.entrySet())
			{
				if (onlineNames.contains(entry.getKey()))
				{
					onlineByTeam.merge(entry.getValue(), 1, Integer::sum);
				}
			}

			SwingUtilities.invokeLater(() -> consumer.accept(onlineByTeam));
		});
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

	/**
	 * Appends the team badge to names in the clan and guest clan member lists
	 * after the client rebuilds them.
	 */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.CLAN_SIDEPANEL_DRAW || !config.clanListIcons())
		{
			return;
		}

		tagMemberList(InterfaceID.ClansSidepanel.PLAYERLIST);
		tagMemberList(InterfaceID.ClansGuestSidepanel.PLAYERLIST);
	}

	private void tagMemberList(int componentId)
	{
		Widget list = client.getWidget(componentId);
		if (list == null || list.getChildren() == null)
		{
			return;
		}

		for (Widget child : list.getChildren())
		{
			if (child == null)
			{
				continue;
			}

			String text = child.getText();
			if (text == null || text.isEmpty())
			{
				continue;
			}

			// strip any tag we added on a previous pass: CLAN_SIDEPANEL_DRAW fires
			// per panel but we tag both, so the non-rebuilt panel keeps our tag
			String stripped = stripOwnListTags(text);

			// non-name children (e.g. world numbers) simply won't match the roster
			String newText = stripped;
			Integer team = playerTeams.get(Text.standardize(stripped));
			if (team != null)
			{
				int iconIndex = chatIconManager.chatIconIndex(iconIds[team - 1]);
				if (iconIndex != -1)
				{
					newText = stripped + " <img=" + iconIndex + ">";
				}
			}

			if (!newText.equals(text))
			{
				child.setText(newText);
			}
		}
	}

	/**
	 * Removes this plugin's img tags including the space we prepend to them in
	 * widget list entries.
	 */
	private String stripOwnListTags(String text)
	{
		for (int id : iconIds)
		{
			int iconIndex = chatIconManager.chatIconIndex(id);
			if (iconIndex != -1)
			{
				text = text.replace(" <img=" + iconIndex + ">", "");
			}
		}
		return text;
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

	/**
	 * Re-runs the clan sidepanel draw scripts so member list tags reflect the
	 * current roster (same redraw trick as the core Chat Channel plugin).
	 */
	private void rebuildClanLists()
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				return;
			}

			Widget w = client.getWidget(InterfaceID.ClansSidepanel.UNIVERSE);
			if (w != null)
			{
				client.runScript(w.getOnVarTransmitListener());
			}

			w = client.getWidget(InterfaceID.ClansGuestSidepanel.UNIVERSE);
			if (w != null)
			{
				client.runScript(w.getOnVarTransmitListener());
			}
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
