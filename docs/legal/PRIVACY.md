# Privacy Boundaries

DeskForge performs graphics rendering locally. The Venus/VirGL service has no network permission, Android
application permissions, document access, clipboard access, audio access, or signing access. It
receives only a pre-bound private Unix socket descriptor and returns bounded renderer status text.

Renderer names and fallback reasons may appear in local diagnostics. Graphics commands, framebuffer
contents, user documents, clipboard contents, audio samples, and
credentials are not copied into Kotlin state, analytics, or logs. DeskForge does not add telemetry
or remote graphics transport in this milestone.
