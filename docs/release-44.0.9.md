# 44.0.9 deployment status

- Android versionName44.0.9 / versionCode53.
- Default receiver: E1D7D101. Cast Console confirms Published / Unlisted.
- HTTPS receiver: https://hsvtr365.github.io/android-audio-cast/
- Hosting branch: codex/cast-receiver. Only static index.html and .nojekyll are published.
- HTTPS200 and response body matching receiver/web/index.html verified.
- Signed AAB/APK and Release lint passed. AAB signature verification passed.
- Current physical phone remains44.0.8; the running session was preserved.
- New HTTPS receiver launch and44.0.9 installation have not been tested on the phone.
- Google Play alpha remains0.43 and internal test0.40.44.0.9 has not been uploaded or approved.
- Production access currently unavailable in the console; closed testing shows12 opted-in testers for9 days.

## Existing device evidence

On44.0.8 with the receiver served by the phone and the PC server stopped:

- Three-speaker group audible onset within9seconds confirmed by the user.
- Sender continuous PLAYING began at4.020seconds; no startup retry.
- After an8second Wi-Fi interruption, all three speakers recovered automatically according to the user.
- Following recovery,18minutes45seconds of113state samples were allPLAYING,
  with no new load, retry, HTTP failure or encoder failure in that interval.
- This is sampled receiver state rather than continuous acoustic measurement.
- A prior standalone trial had19second audible onset; a repeat had4seconds.
  Startup variation, exact steady-state latency and overnight reliability remain unproven.
- The user ended further long-duration testing.

Do not commit signing.properties, private keys, passwords, device identifiers, raw device logs,
or private Obsidian notes. They remain outside the published source.
