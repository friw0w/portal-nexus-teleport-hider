package com.portalnexusteleporthider;

import com.google.inject.Provides;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.KeyCode;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.StructComposition;
import net.runelite.api.events.MenuOpened;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@Slf4j
@PluginDescriptor(
	name = "Portal Nexus Teleport Hider",
	description = "Hides chosen teleport destinations from the Portal Nexus menu in a player-owned house",
	tags = {"teleport", "house", "poh", "nexus", "interface"}
)
public class PortalNexusTeleportHiderPlugin extends Plugin
{
	/**
	 * [proc,script2675], which the teleport list proc calls once per row. Named
	 * TELENEXUS_CREATE_TELELINE by Better Teleport Menu. There is no gameval ScriptID, so
	 * script IDs are declared locally, as hub plugins do.
	 */
	private static final int SCRIPT_TELENEXUS_CREATE_TELELINE = 2675;

	/** param_660 on the destination struct: the display name. */
	private static final int PARAM_DESTINATION_NAME = 660;

	/**
	 * The proc's twelve arguments, which sit at the top of the int stack at pre-fire. All
	 * twelve are int-typed in cs2 -- components and structs included -- so nothing is read
	 * off the object stack.
	 */
	private static final int ARG_COUNT = 12;
	private static final int ARG_KEY_LAYER = 0;
	private static final int ARG_TEXT_LAYER = 1;
	private static final int ARG_ICON_LAYER = 2;
	private static final int ARG_CHILD_INDEX = 4;
	private static final int ARG_LIST_HEIGHT = 6;
	private static final int ARG_STRUCT = 7;
	private static final int ARG_SLOT = 8;
	private static final int ARG_STRIPE = 10;
	private static final int ARG_ROW_LAYER = 11;

	/**
	 * The proc returns (childIndex + 1, slot + 1, listHeight, 1 - stripe), pushed in that
	 * order, so the last one returned sits at the top of the stack. Writing an incoming
	 * value back over its outgoing counterpart makes the game's own layout code behave as
	 * though the row had never been laid out.
	 */
	private static final int RET_STRIPE_FROM_TOP = 1;
	private static final int RET_LIST_HEIGHT_FROM_TOP = 2;
	private static final int RET_SLOT_FROM_TOP = 3;

	/** Grey, for a hidden row shown in edit mode. */
	private static final int HIDDEN_TEXT_COLOR = 0x777777;

	/** The game's colour tags on a row's text, which override a widget's own colour. */
	private static final Pattern COLOUR_TAG = Pattern.compile("</?col[^>]*>");

	@Inject
	private Client client;

	@Inject
	private PortalNexusTeleportHiderConfig config;

	@Inject
	private ConfigManager configManager;

	/** Struct IDs of hidden destinations. Struct ID is the identity key, never the name. */
	private final Set<Integer> hidden = new LinkedHashSet<>();

	/** Rows built by the most recent pass, keyed by row layer and dynamic child index. */
	private final Map<Long, Row> builtRows = new HashMap<>();

	private Row pending;
	private boolean loggedUnexpectedLayout;

	@Override
	protected void startUp()
	{
		loadHidden();
		log.debug("Portal Nexus Teleport Hider started with {} hidden destination(s)", hidden.size());
	}

	@Override
	protected void shutDown()
	{
		hidden.clear();
		builtRows.clear();
		pending = null;
		loggedUnexpectedLayout = false;
	}

	@Provides
	PortalNexusTeleportHiderConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PortalNexusTeleportHiderConfig.class);
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (PortalNexusTeleportHiderConfig.GROUP.equals(event.getGroup())
			&& PortalNexusTeleportHiderConfig.HIDDEN_DESTINATIONS.equals(event.getKey()))
		{
			loadHidden();
		}
	}

	/**
	 * Runs above Better Teleport Menu's 1.f so a hidden row's text is already blank by the
	 * time BTM parses a shortcut key out of it.
	 */
	@Subscribe(priority = 2.f)
	public void onScriptPreFired(ScriptPreFired event)
	{
		if (event.getScriptId() != SCRIPT_TELENEXUS_CREATE_TELELINE)
		{
			return;
		}

		pending = null;

		if (isStandingDown())
		{
			return;
		}

		final int base = client.getIntStackSize() - ARG_COUNT;
		if (base < 0)
		{
			return;
		}

		final int[] stack = client.getIntStack();
		final int keyLayer = stack[base + ARG_KEY_LAYER];
		final int textLayer = stack[base + ARG_TEXT_LAYER];
		final int rowLayer = stack[base + ARG_ROW_LAYER];

		// The layout guard. Three of the arguments are component IDs we already know, so
		// matching them proves at runtime that the arguments are in the order this plugin
		// assumes. If the game's script ever changes shape, we do nothing rather than
		// write nonsense back onto the stack.
		final boolean primary = keyLayer == InterfaceID.TelenexusTeleport.KEY_LISTENERS
			&& textLayer == InterfaceID.TelenexusTeleport.TEXT1
			&& rowLayer == InterfaceID.TelenexusTeleport.ROWS1;
		final boolean alternate = keyLayer == InterfaceID.TelenexusTeleport.EXTRA_KEY_LISTENERS
			&& textLayer == InterfaceID.TelenexusTeleport.EXTRAS
			&& rowLayer == InterfaceID.TelenexusTeleport.ROWS2;

		if (!primary && !alternate)
		{
			if (!loggedUnexpectedLayout)
			{
				loggedUnexpectedLayout = true;
				log.debug("Unexpected argument layout for script {}; standing down. "
						+ "key={} text={} row={}",
					SCRIPT_TELENEXUS_CREATE_TELELINE, keyLayer, textLayer, rowLayer);
			}
			return;
		}

		pending = new Row(
			stack[base + ARG_STRUCT],
			keyLayer,
			textLayer,
			stack[base + ARG_ICON_LAYER],
			rowLayer,
			stack[base + ARG_CHILD_INDEX],
			stack[base + ARG_LIST_HEIGHT],
			stack[base + ARG_SLOT],
			stack[base + ARG_STRIPE]);
	}

	@Subscribe(priority = 2.f)
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != SCRIPT_TELENEXUS_CREATE_TELELINE)
		{
			return;
		}

		final Row row = pending;
		pending = null;

		if (row == null)
		{
			return;
		}

		builtRows.put(row.key(), row);

		if (config.diagnosticLogging())
		{
			log.debug("Captured row: struct={} name='{}' rowLayer={} index={} slot={}",
				row.structId, destinationName(row.structId), row.rowLayer, row.childIndex, row.slot);
		}

		if (!hidden.contains(row.structId))
		{
			return;
		}

		if (config.showHidden())
		{
			markHiddenRow(row);
			return;
		}

		collapseRow(row);
	}

	@Subscribe
	public void onMenuOpened(MenuOpened event)
	{
		final boolean shift = client.isKeyPressed(KeyCode.KC_SHIFT);

		if (config.diagnosticLogging())
		{
			logNexusMenu(event, shift);
		}

		if (!shift)
		{
			return;
		}

		for (MenuEntry entry : event.getMenuEntries())
		{
			final int rowLayer = entry.getParam1();
			if (rowLayer != InterfaceID.TelenexusTeleport.ROWS1
				&& rowLayer != InterfaceID.TelenexusTeleport.ROWS2)
			{
				continue;
			}

			final Row row = builtRows.get(Row.key(rowLayer, entry.getParam0()));
			if (row == null)
			{
				continue;
			}

			addHideEntries(row.structId);
			return;
		}
	}

	/** Diagnostic only: dumps a right-click menu raised anywhere on the nexus interface. */
	private void logNexusMenu(MenuOpened event, boolean shift)
	{
		boolean onNexus = false;
		for (MenuEntry entry : event.getMenuEntries())
		{
			if (WidgetUtil.componentToInterface(entry.getParam1()) == InterfaceID.TELENEXUS_TELEPORT)
			{
				onNexus = true;
				break;
			}
		}

		if (!onNexus)
		{
			return;
		}

		log.debug("Nexus menu opened: shift={} rowsCaptured={} (ROWS1={} ROWS2={})",
			shift, builtRows.size(),
			InterfaceID.TelenexusTeleport.ROWS1, InterfaceID.TelenexusTeleport.ROWS2);

		for (MenuEntry entry : event.getMenuEntries())
		{
			log.debug("  option='{}' type={} param0={} param1={}",
				entry.getOption(), entry.getType(), entry.getParam0(), entry.getParam1());
		}
	}

	private void addHideEntries(int structId)
	{
		final String name = destinationName(structId);

		if (hidden.contains(structId))
		{
			client.getMenu().createMenuEntry(-1)
				.setOption("Unhide")
				.setTarget(name)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> setDestinationHidden(structId, false));
		}
		else
		{
			client.getMenu().createMenuEntry(-1)
				.setOption("Hide")
				.setTarget(name)
				.setType(MenuAction.RUNELITE)
				.onClick(e -> setDestinationHidden(structId, true));
		}

		if (!hidden.isEmpty())
		{
			client.getMenu().createMenuEntry(-1)
				.setOption("Unhide all")
				.setTarget("Portal Nexus")
				.setType(MenuAction.RUNELITE)
				.onClick(e -> unhideAll());
		}
	}

	private void setDestinationHidden(int structId, boolean hide)
	{
		if (hide == hidden.contains(structId))
		{
			return;
		}

		if (hide)
		{
			hidden.add(structId);
		}
		else
		{
			hidden.remove(structId);
		}

		persistHidden();

		if (config.diagnosticLogging())
		{
			log.debug("{} destination {} ({})", hide ? "Hid" : "Unhid", structId, destinationName(structId));
		}

		// Immediate feedback. The gap only closes and the shortcuts only renumber the next
		// time the interface is built, because that reflow is the game's own.
		if (hide && !config.showHidden())
		{
			final Row row = findRow(structId);
			if (row != null)
			{
				hideRowWidgets(row);
			}
		}
	}

	private void unhideAll()
	{
		if (hidden.isEmpty())
		{
			return;
		}

		log.debug("Unhiding all {} destination(s)", hidden.size());
		hidden.clear();
		persistHidden();
	}

	/**
	 * Hides the row's four widgets, disarms its shortcut, and rewinds the layout
	 * accumulators the proc returned so the next row takes this one's place. The game calls
	 * if_setscrollsize and ~scrollbar_resize afterwards from that same accumulator, so the
	 * scrollbar resizes itself and must not be touched here.
	 */
	private void collapseRow(Row row)
	{
		hideRowWidgets(row);

		final int[] stack = client.getIntStack();
		final int size = client.getIntStackSize();

		stack[size - RET_STRIPE_FROM_TOP] = row.stripe;
		stack[size - RET_LIST_HEIGHT_FROM_TOP] = row.listHeight;

		if (config.recompactShortcuts())
		{
			stack[size - RET_SLOT_FROM_TOP] = row.slot;
		}

		// The first return value, the dynamic child index, is deliberately left alone: the
		// child was created either way and the index must advance.

		if (config.diagnosticLogging())
		{
			log.debug("Collapsed row: struct={} index={} slot={} height={}",
				row.structId, row.childIndex, row.slot, row.listHeight);
		}
	}

	private void hideRowWidgets(Row row)
	{
		final Widget key = child(row.keyLayer, row.childIndex);
		if (key != null)
		{
			key.setHidden(true);
			// 2674 withholds the listener from locked placeholder rows rather than relying
			// on hidden-ness, so clear it explicitly instead of trusting setHidden.
			key.setOnKeyListener((Object[]) null);
		}

		final Widget text = child(row.textLayer, row.childIndex);
		if (text != null)
		{
			text.setHidden(true);
			// Also starves Better Teleport Menu's key-prefix regex, which would otherwise
			// register this row's shortcut and fire it from a hidden entry.
			text.setText("");
		}

		final Widget icon = child(row.iconLayer, row.childIndex);
		if (icon != null)
		{
			icon.setHidden(true);
		}

		final Widget background = child(row.rowLayer, row.childIndex);
		if (background != null)
		{
			background.setHidden(true);
		}
	}

	/**
	 * Edit mode: the row stays visible and fully working, just marked. Leaving it armed
	 * keeps its own shortcut alive and gives Better Teleport Menu nothing unusual to parse.
	 */
	private void markHiddenRow(Row row)
	{
		final Widget text = child(row.textLayer, row.childIndex);
		if (text == null)
		{
			return;
		}

		final String current = text.getText();
		if (current == null || current.isEmpty() || current.contains("<str>"))
		{
			return;
		}

		// The row text carries the game's own colour tags, which win over setTextColor, so
		// strip them before greying the row out.
		text.setText("<str>" + COLOUR_TAG.matcher(current).replaceAll("") + "</str>");
		text.setTextColor(HIDDEN_TEXT_COLOR);
	}

	/**
	 * Nexus Map replaces the list with its own map and cannot be told about hidden rows, so
	 * stand down whenever the list container has been hidden by someone else.
	 */
	private boolean isStandingDown()
	{
		final Widget list = client.getWidget(InterfaceID.TelenexusTeleport.SCROLLING1);

		if (list == null)
		{
			if (config.diagnosticLogging())
			{
				log.debug("Standing down: the teleport list container could not be resolved");
			}
			return true;
		}

		if (list.isSelfHidden())
		{
			if (config.diagnosticLogging())
			{
				log.debug("Standing down: the teleport list container is hidden by another plugin");
			}
			return true;
		}

		return false;
	}

	private Widget child(int layerId, int childIndex)
	{
		final Widget layer = client.getWidget(layerId);
		return layer == null ? null : layer.getChild(childIndex);
	}

	private Row findRow(int structId)
	{
		for (Row row : builtRows.values())
		{
			if (row.structId == structId)
			{
				return row;
			}
		}
		return null;
	}

	private String destinationName(int structId)
	{
		final StructComposition struct = client.getStructComposition(structId);
		if (struct == null)
		{
			return "Destination";
		}

		final String name = struct.getStringValue(PARAM_DESTINATION_NAME);
		return name == null || name.isEmpty() ? "Destination" : name;
	}

	private void loadHidden()
	{
		hidden.clear();

		final String raw = config.hiddenDestinations();
		if (raw == null || raw.isEmpty())
		{
			return;
		}

		for (String part : raw.split(","))
		{
			final String trimmed = part.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}

			try
			{
				hidden.add(Integer.parseInt(trimmed));
			}
			catch (NumberFormatException e)
			{
				log.debug("Ignoring unparseable hidden destination '{}'", trimmed);
			}
		}
	}

	private void persistHidden()
	{
		configManager.setConfiguration(
			PortalNexusTeleportHiderConfig.GROUP,
			PortalNexusTeleportHiderConfig.HIDDEN_DESTINATIONS,
			hidden.stream().map(String::valueOf).collect(Collectors.joining(",")));
	}

	/** One row of the teleport list, as the proc was called with it. */
	private static final class Row
	{
		private final int structId;
		private final int keyLayer;
		private final int textLayer;
		private final int iconLayer;
		private final int rowLayer;
		private final int childIndex;
		private final int listHeight;
		private final int slot;
		private final int stripe;

		private Row(int structId, int keyLayer, int textLayer, int iconLayer, int rowLayer,
			int childIndex, int listHeight, int slot, int stripe)
		{
			this.structId = structId;
			this.keyLayer = keyLayer;
			this.textLayer = textLayer;
			this.iconLayer = iconLayer;
			this.rowLayer = rowLayer;
			this.childIndex = childIndex;
			this.listHeight = listHeight;
			this.slot = slot;
			this.stripe = stripe;
		}

		private long key()
		{
			return key(rowLayer, childIndex);
		}

		private static long key(int rowLayer, int childIndex)
		{
			return ((long) rowLayer << 32) | (childIndex & 0xffffffffL);
		}
	}
}
