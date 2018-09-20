# Decorator
*Populate world chunks fully by teleporting (fake) players around.*

## Links
- [Source code](https://github.com/StarTux/Decorator) on Github

## Rationale
Chunks only fully populate when a player is within range.  Generating large worlds by other means will only create the terrain, not features such as trees and ores.  Hence, on a production server, when players travel or teleport around a world which was generated like that, they will generate excessive server lag, because the chunk populator is still at work.

This plugin aims to solve this issue by populating the chunks full.  Logged in players, or optionally fake players, are teleported to empty chunks until the entire world is generated within its world border.

## Usage
Run the plugin on a server dedicated to world generation.  This server must be in **offline mode** for fake players to be able to be spawned in.  Refer to the `server.properties` file:
```yaml
online-mode: false
```
Furthermore, auto saving should be disabled in the `bukkit.yml` file.
Then, issue the command `/dec init world`, assuming your primary world is titled "world".  Then wait for it to finish.  Frequent restarts are recommended to clear up memory.  Progression state is preserved across restarts.  The plugin will save the world to disk regularly and pause when memory gets low.  Refer to the configuration for all the available options.

All players on the server will be put in creative mode and teleported around.  Playing on the server in any meaningful manner will become impossible for the duration.

## Commands
There is a set of commands to control the chunk population effort, as well as some debugging helpers.
- `/dec init <world> (all)` - Initialize the decorator and start generating.  The all option generates all chunks, not just missing ones.
- `/dec reload` - Reload the configuration file.
- `/dec pause` - Pause generation.
- `/dec save` - Save the world and progression state.
- `/dec cancel` - Cancel the currently ongoing decoration attempt.
- `/dec info` - Dump some progress info.
- `/dec fake` - Spawn in a fake player.  This is usually not required.
- `/dec players` - List players along with some related information.

## Permissions
There is only one permission node for this plugin.
- `decorator.decorator` - Use /dec


## Credits
This plugin utilizes Steveice10's excellent library [MCProtocolLib](https://github.com/Steveice10/MCProtocolLib) to spawn in fake players.  The class files are shaded into the plugin jar.