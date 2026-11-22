## SpanScreen 0.1.1

**Fixed:** Games now show on every device, not just the host. The host was sending each device's game stream to the wrong network port, so the other phones never received it.

**Also:** Builds now use one fixed signing key, so desktop and GitHub builds install over each other without uninstalling first. Added logging around game streaming to make problems easier to track down.

If you have 0.1.0 installed, uninstall it once before installing this version.
