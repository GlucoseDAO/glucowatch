# CareLink and a separate CGM

GlucoPhone and GlucoWatch can read glucose from Dexcom Share while adding basal, bolus, carbs
and active insulin from a MiniMed pump through CareLink. In **Connect** on the phone, choose
**Dexcom Share** as the glucose source and check **CareLink (MiniMed)** under **Additional insulin
sources**. Nightscout can also add therapy data. The main source supplies the glucose trajectory;
the extra sources never splice a second sensor's glucose values into it.

**Sign in to CareLink** opens the system browser. Choose the account's two-letter country code
and sign in with a care partner account linked to the patient. The app validates OAuth state,
uses PKCE and stores the resulting rotating tokens in private app storage. It does not store a
CareLink username or password. The country is required before sign-in because it selects the
account's regional server; it is the account country, not necessarily the phone's current
location. If mobile DNS cannot resolve the official `.eu` CarePartner cloud, the client retries
the identical discovery/API path on MiniMed's official `.com` cloud. This retry happens only for
a DNS failure and keeps normal HTTPS certificate verification. The flow uses the CarePartner discovery endpoint, as described by
[xDrip's CareLink maintainers](https://github.com/NightscoutFoundation/xDrip/discussions/4318).

For the watch to show the phone's combined data, choose **Phone app** on the watch. Its encrypted
Bluetooth relay includes merged treatments and active insulin. Alternatively, the watch's
settings allow the same combination with independent network fetching. **Get CareLink sign-in
from phone** moves the sign-in to the watch: the phone forgets it. Do not use one refresh-token
session on two devices. Sign in separately if both must connect directly.

Each source has an account-specific cache. Changing or disabling a pump source immediately
removes its data from the combined view, and changes the relay's upstream identity. A late fetch
for an old configuration is discarded. A failed source is named in the dashboard without
preventing the other source from fetching. Treatment merging keeps basal separate from bolus
and removes matching duplicated boluses/carbs from overlapping uploads.

CareLink data varies by pump and upload. The reader keeps `INSULIN` boluses, `MEAL` carbs and
`AUTO_BASAL_DELIVERY` pulses where supplied. `basal.basalRate` is shown as **Reported basal** in
U/h at the pump upload's timestamp. This is a rate setting, not measured delivered insulin or a
reconstructed historical schedule. The IOB value is taken from `activeInsulin`. No dose is
calculated from a rate. The pump and uploading phone can have different clock offsets, so pump
events use `medicalDeviceTime` paired with `lastMedicalDeviceDataUpdateServerTime` when present;
the conduit clock is a fallback for older payloads.

## Local development with `.env`

1. `uv run --with playwright scripts/carelink_login.py` opens a visible browser and fills
   `CARELINK_USERNAME` and `CARELINK_PASSWORD` from `.env` where possible. Complete sign-in there.
   The session is saved outside the repository at `~/.config/glucowatch/carelink-token.json`.
2. Build/install a phone debug APK on an emulator. Dexcom defaults come from `.env`; release
   builds contain empty credentials. CareLink passwords and tokens are never embedded in an APK.
3. `uv run scripts/carelink_to_phone.py --serial emulator-5590` moves the session into the debug
   app's private storage through adb stdin and opens **Dexcom Share + CareLink insulin**. Tokens
   never appear in adb command arguments. After the import is confirmed, the desktop copy is
   deleted. `--source CARELINK --also ''` selects CareLink alone.

Screenshots and fetched payloads with real data belong under the gitignored `data/output/` and
must not be published. This integration has emulator/network checks; Bluetooth still needs the
real-hardware verification described in `phone-link.md`.
