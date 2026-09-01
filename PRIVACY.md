# Privacy

DeskForge is designed to run its Linux workspace locally. This development version contains no
advertising SDK, analytics SDK, account system, or DeskForge-operated telemetry endpoint.

The application requests network access to obtain Play Asset Delivery content. This milestone does
not request microphone access or expose an audio-capture bridge. A future audio milestone must add
Android runtime consent and explicit per-session authorization before any guest capture is possible.

Clipboard synchronization is disabled. DeskForge reads one direct plain-text item from the Android
clipboard only when the user selects **Paste to Linux**. Guest clipboard contents remain behind an
availability notification until the user selects **Copy from Linux**; copied guest text is marked as
sensitive for Android's clipboard preview. DeskForge does not transfer clipboard files, content URIs,
HTML, intents, or multiple items, and it does not log clipboard contents.

Linux applications installed or run by a user may make their own network connections or process
documents the user makes available to them. PRoot is a compatibility layer, not a security sandbox;
guest processes share the DeskForge application UID. The production privacy notice and Google Play
Data safety declaration require final review after document sharing, audio, and accelerated
display bridges are implemented.
