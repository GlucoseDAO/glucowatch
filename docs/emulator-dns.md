# Fix DNS failures in the Android emulator

Use this guide when a portal shows `net::ERR_NAME_NOT_RESOLVED`, WebView error `-2`,
or `ping: unknown host` inside the emulator, while the same name resolves on the PC.
This failure occurs before portal sign-in or CSV download. Android identifies `-2` as
[ERROR_HOST_LOOKUP](https://developer.android.com/reference/android/webkit/WebViewClient#ERROR_HOST_LOOKUP).

## What happened on the Windows workstation

On 2026-10-14, a Windows 10 workstation running Android Emulator 37.1.11 with an API 36
phone image could resolve the US and International Clarity domains on the PC, but the
emulator could not resolve either domain or `example.com`. The emulator could reach
`1.1.1.1` by IP, and the PC could query that alternate DNS server successfully.

Adding `glucowatch.emulator.dns=1.1.1.1,8.8.8.8` to `local.properties`, restarting with
the software-rendering launcher, and waiting for the network to settle restored resolution.
Both Clarity domains resolved and the live Clarity welcome page loaded in an Android WebView.
The exact reason the inherited DNS route failed was not established.

The emulator discovers the host's DNS servers at startup and exposes them through guest
aliases such as `10.0.2.3`. An explicit `-dns-server` list overrides that discovery. See
[Android's emulator DNS documentation](https://developer.android.com/studio/run/emulator-networking-dns).

## Confirm the failure layer

From PowerShell in the repository root, use the SDK's adb and the serial of the affected
emulator. These examples use the default SDK location and the phone emulator on port 5554:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb devices
Resolve-DnsName clarity.dexcom.com -DnsOnly
& $adb -s emulator-5554 shell ping -c 1 -W 2 clarity.dexcom.com
& $adb -s emulator-5554 shell ping -c 1 -W 2 example.com
& $adb -s emulator-5554 shell ping -c 1 -W 2 1.1.1.1
Resolve-DnsName clarity.dexcom.com -Server 1.1.1.1 -DnsOnly -QuickTimeout
```

Use the Clarity host for your account's region (`clarity.dexcom.com`, `.eu`, or `.jp`).
Keep that account region when fixing DNS. A successful lookup prints an IP address.
If `ping` prints the resolved address but receives no replies, name resolution succeeded;
ICMP may be blocked. An unsuccessful IP ping alone does not prove that all internet traffic
is broken.

If lookup also fails on the PC, investigate its connection/resolver first. If the PC resolves
names, the emulator reports unknown hosts, and the alternate resolver responds, apply the
emulator override below. A page that loads and returns HTTP 401/403 needs a separate
sign-in/export investigation.

## Apply the local fix

1. Add this line to the repository-root `local.properties`, preserving its existing `sdk.dir`:

   ```properties
   glucowatch.emulator.dns=1.1.1.1,8.8.8.8
   ```

   The file is gitignored. Choose resolver IPs that work on your own network; these are the
   public resolver addresses used in the verified workaround. The setting applies to the
   repository's emulator launchers and capture scripts. Other launchers need their own flags.

2. Close the affected emulator window. Alternatively, stop the selected emulator with:

   ```powershell
   & $adb -s emulator-5554 emu kill
   ```

3. Start it again with the repository launcher:

   ```powershell
   uv run scripts/run_emulator.py phone
   ```

   Add `--avd YOUR_PHONE_AVD` for a differently named existing AVD. Use `watch` for a watch
   emulator. The printed launch command should include `-dns-server 1.1.1.1,8.8.8.8`.
   The Windows launcher also keeps software graphics, two virtual CPUs and no snapshots.
   Changing DNS does not require switching back to host GPU rendering.

   Startup DNS flags take effect on a new emulator process. The launcher reuses an already
   running device, so reloading a page, reinstalling an APK, or rerunning the launcher without
   first closing that device does not apply a new DNS list. Android's
   [command-line reference](https://developer.android.com/studio/run/emulator-commandline)
   documents startup-only options and preservation of the user-data disk across normal restarts.

4. Wait for Android **and its network** to finish starting, then repeat the hostname checks
   and reload the portal. During the verified restart, early lookups still failed before the
   network settled. Check again after connectivity becomes available. The launcher waits for
   Android boot completion; it does not guarantee that a portal is already reachable.

A normal restart keeps installed apps, settings and Downloads. It closes the current export
wizard; GlucoPhone's accepted export dates remain saved. Use a normal restart rather than
**Wipe Data** when applying this fix.

## Temporary override and rollback

An environment variable takes precedence over the local property. For one PowerShell session:

```powershell
$env:GLUCOWATCH_EMULATOR_DNS = '1.1.1.1,8.8.8.8'
uv run scripts/run_emulator.py phone --dry-run
```

This prints the command without starting or stopping a device. The launcher accepts one to
four comma-separated IP addresses and rejects invalid values before launch.

To restore the SDK's automatic DNS, remove the `glucowatch.emulator.dns` line and clear any
session override, then close and restart the emulator:

```powershell
Remove-Item Env:GLUCOWATCH_EMULATOR_DNS -ErrorAction SilentlyContinue
```

The setting controls emulator DNS routing only. Windows adapter settings, physical-phone DNS,
and the app's optional Resolve over HTTPS setting are configured separately. If the problem
continues, retain the private startup log under `data/output/emulator/` and record which host
and emulator lookups succeeded. See [phone testing](phone-testing.md) for portal and CSV checks.
