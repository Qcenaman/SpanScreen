## SpanScreen 0.1.0 — first release

Spread one picture, video or game across any number of Android devices placed next to each other.

- **Any devices, any count:** host on one device, join from the others, and tick which ones to use each time. Order them with ▲/▼, side by side or stacked.
- **Real-size alignment:** each device reports its real screen size and refresh rate, so the picture lines up across phones, foldables and tablets of different sizes. Set the bezel gap with the slider.
- **Pictures:** every screen decodes only its own piece at full native resolution.
- **Videos:** sent to every device and played in sync. Each screen keeps itself on the same frame and runs at its highest refresh rate.
- **Games (needs Shizuku on the host):** the game runs on a virtual display the size of the whole wall. The host's GPU cuts out each screen's piece, and the piece is hardware-encoded (HEVC/H.264) and streamed with low-latency settings and frame dropping instead of a growing delay. Touch any screen to play, multi-touch across screens included.
