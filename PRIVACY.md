# Privacy

DeskForge is designed to run its Linux workspace locally. This development version contains no
advertising SDK, analytics SDK, account system, or DeskForge-operated telemetry endpoint.

The application requests network access to obtain Play Asset Delivery content. This milestone does
not request microphone access or expose an audio-capture bridge. A future audio milestone must add
Android runtime consent and explicit per-session authorization before any guest capture is possible.

Linux applications installed or run by a user may make their own network connections or process
documents the user makes available to them. PRoot is a compatibility layer, not a security sandbox;
guest processes share the DeskForge application UID. The production privacy notice and Google Play
Data safety declaration require final review after document sharing, clipboard, audio, and accelerated
display bridges are implemented.
