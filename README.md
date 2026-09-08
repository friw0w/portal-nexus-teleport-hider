# Portal Nexus Teleport Hider

Tired of the alternate teleport options in the Portal Nexus? Already have Grand Exchange tp in
the jewellery box? Look no further!

Hides any teleport destinations you pick from the Portal Nexus list in your house. Purely
client-side — nothing is removed from the nexus itself, so it costs nothing and undoes in a
click.

## How to use

1. Open the Portal Nexus in your house.
2. **Shift + right-click** a destination and choose **Hide**.
3. Reopen the interface — the row is gone, the list closes up, and the shortcut keys renumber.
4. To bring one back, tick **Show hidden**, then shift + right-click the greyed row and choose
   **Unhide**, or **Unhide all** to reset everything.

Plain right-click is untouched, so there is no "Hide" sitting next to "Teleport" to misclick.

## Settings

| Setting | What it does |
|---|---|
| **Show hidden** | Keeps hidden rows in the list, greyed out and struck through, so you can unhide them. They still teleport normally while this is on — it is a management view, not a preview of the hidden state. |
| **Recompact shortcuts** | Renumbers the 1-9 and A-Z shortcuts so they run without gaps. Turn it off and the rows that remain keep the letters they always had. |
| **Diagnostic logging** | Logs what the plugin does to each row. For troubleshooting only, and only visible when the client is started with `--debug`. |

## Good to know

- Hidden destinations are remembered between sessions.
- A hidden row's shortcut key is disarmed, so it cannot teleport you somewhere you hid.
- **Better Teleport Menu**: hidden rows are disarmed for its keyboard handling as well. But a
  *custom* hotkey you assigned in that plugin is stored by destination name and will still fire
  — clear it there too.
- **Nexus Map**: this plugin stands down entirely while its map is showing, so hidden
  destinations stay visible and clickable on the map.

## Licence

BSD 2-Clause. See [LICENSE](LICENSE).
