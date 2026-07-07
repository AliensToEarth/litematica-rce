# Litematica RCE PoC

**This project is for educational and research purposes only.**
Do not use this against systems you do not own or have explicit permission to test.

Information about this vulnerability has already spread widely across the
Minecraft modding community. Litematica has released a patch. I'm publishing
this PoC so the community can study the mechanism and understand how it works.

This Proof of concept is exploiting a directory traversal vulnerability in Litematica's
schematic file transmission protocol.

## The Vulnerability

Litematica (versions prior to 0.26.11) has a schematic sharing feature that
uses a custom network protocol via the `servux:litematics` channel. The protocol
allows a server to send schematic files to clients. The schematic is written to
`schematics/transmit/<filename>` on the client filesystem.

The vulnerability is that **the filename is not sanitized**. By supplying a
filename like `../../mods/evil.litematic.jar`, the path resolves outside the
`schematics/transmit/` directory and into the `mods/` folder. Since the file is
written as-is (not validated as a real schematic), any arbitrary JAR file can be
deployed and loaded as a Fabric mod on the next client restart.

Litematica has already posted patches in version 0.26.11 for Minecraft 1.21.x.
Clients running patched versions are not vulnerable. I found the message about
the existence of this vulnerability in the DupersUnited Discord server and
started digging into Litematica's source code, comparing the patched version
against the unpatched one to understand the exact mechanism.

## How the Exploit Works

1. **Server-side Fabric mod** registers a payload on channel `servux:litematics`.
2. When a client connects, the server fabricates three NBT-compound packets
   matching Litematica's schematic transmission protocol (TransmitStart,
   TransmitData, TransmitEnd):
   - **TransmitStart**: declares the filename, file type, total size, and
     slice count. The filename is `../../mods/<payload>.litematic.jar`.
   - **TransmitData**: contains the raw bytes of the payload JAR.
   - **TransmitEnd**: signals completion so Litematica writes the file to disk.
3. Litematica receives these packets, resolves the filename against
   `schematics/transmit/`, and writes the payload to
   `.minecraft/mods/<payload>.litematic.jar`.
4. On the next client restart, the payload JAR is loaded as a mod.

Each message is sent as its own PacketSplitter session with 2-tick delays
between them to ensure the client processes them sequentially.

## Files

| File | Purpose |
|------|---------|
| `src/main/java/.../LitematicaRceFabric.java` | Server-side mod that sends the crafted packets |
| `src/main/java/.../PayloadJar.java` | Loads the payload JAR from bundled resource |
| `src/main/resources/payload.jar` | The actual payload JAR embedded in the mod |
| `embed_payload.sh` | Script to replace the payload with any JAR |

## Usage

1. Build the default mod (includes baked-in example payload):
   ```
   ./gradlew build
   ```
   Deploy `build/libs/litematica-rce.jar` to your server's `mods/` folder.

2. Or, embed your own payload:
   ```
   ./embed_payload.sh path/to/your-mod.jar [output-name]
   ./gradlew build
   ```
   This copies the JAR into the mod's resources and generates `PayloadJar.java`
   referencing it. The `output-name` becomes the filename written to the
   victim's `mods/` folder (defaults to `poc.litematic.jar`).

3. Start the server, have a client (running Litematica < 0.26.11) connect.
   The server waits 3 seconds after login, then sends the three packets.
   The client writes the file and logs a schematic read error (expected -
   the file is not a real schematic). The payload JAR sits in `mods/` and is
   loaded on next launch.

## Protocol Details

Wire format for each ServuxLitematicaPacket (type 11):
```
VarInt(11) + VarInt(msgSize) + VarInt(1) + writeNbt(compound)
```

Where `compound` contains:
- **TransmitStart**: `{Task: "Litematic-TransmitStart", SliceKey, FileName, FileType, TotalSlices, TotalSize}`
- **TransmitData**:  `{Task: "Litematic-TransmitData", SliceKey, Slice: 0, Size, Data: [bytes]}`
- **TransmitEnd**:   `{Task: "Litematic-TransmitEnd", SliceKey, TotalSize, TotalSlices}`

## Patch

Upgrade Litematica to version 0.26.11 or later. The patch adds sanitization
to the filename to prevent directory traversal.
