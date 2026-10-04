# Phone configuration import

On **Connect**, tap **Upload .env / YAML** and choose a UTF-8 file from Android's file
picker, or enter its HTTPS URL and tap **Import from URL**. Both formats work by either
route. The file is read locally; it is not uploaded. No Dexcom, CareLink, Nightscout or
Hugging Face credentials are sent to the configuration URL. URL redirects must also use
HTTPS. Development APKs additionally allow the emulator host `http://10.0.2.2` and localhost.

For development, transfer your filled repository `.env` to the phone and select it.
Import works at runtime, without rebuilding. See [.env.example](../.env.example) and
[config.example.yaml](../config.example.yaml). Do not commit a filled configuration or
make a URL containing credentials publicly accessible. Remove the transferred file from
shared Downloads after importing; settings live in the app's private storage.

The entire file is validated before settings change. Omitted fields preserve current
settings; explicit empty strings clear a field. Changing the primary glucose account
clears its cached history and, unless explicitly specified, additional pump sources.
Pair only sources belonging to the **same person**. One person's Nightscout and another
person's Dexcom + CareLink must be separate configurations.

After saving, the app tests the selected sources. A nonempty `HF_MODEL_ADDRESS` or
`prediction.model` also resolves the repository's ONNX location, downloads the model and
adjacent metadata/scalers, validates it, and selects it for prediction. Use `owner/repo`,
a Hugging Face repo/tree/file URL, or a short name that has exactly one search result.
For multiple files, the app prefers `onnx/model.onnx`, then `model.onnx`; otherwise supply
the particular file's URL. The previous predictor remains selected if model setup fails;
source configuration stays saved, and the import status reports that failure. Supported
models use their maximum forecast horizon. The large GlucoseDao bundles require a debug
APK; see [prediction runtimes](prediction.md#development-and-release-runtimes).

## dotenv fields

| Field | Meaning |
|---|---|
| `GLUCOWATCH_SOURCE` | `dexcom` / `share`, `nightscout`, `carelink` / `medtronic`, or `demo`. Blank/omitted infers from a supplied filled Dexcom login, then Nightscout URL. |
| `DEXCOM_USERNAME`, `DEXCOM_PASSWORD`, `DEXCOM_REGION` | Share credentials; region `eu`/`ous`, `us`, `jp`. |
| `DEXCOM_NOTIFICATIONS` | `true`/`false`; notification access still requires Android's permission screen. |
| `NIGHTSCOUT_URL`, `NIGHTSCOUT_TOKEN`, `NIGHTSCOUT_API` | Site, optional readable token/API secret, `v1`/`v3`. |
| `GLUCOWATCH_ALSO_FROM` | Comma-separated `nightscout,carelink`, or empty to clear extra sources. |
| `GLUCOWATCH_UNIT`, `GLUCOWATCH_HEART_TRACK` | `mgdl`/`mmol`, heart track `true`/`false`. |
| `CARELINK_COUNTRY` | Two-letter account country, e.g. `DE`. |
| `CARELINK_CLIENT_ID`, `CARELINK_ACCESS_TOKEN`, `CARELINK_REFRESH_TOKEN`, `CARELINK_EXPIRES_AT` | Optional complete CareLink session; expiry is epoch **milliseconds**. |
| `HF_TOKEN`, `HF_MODEL_ADDRESS` | Optional Hub token, repository name or model URL. A supplied model triggers setup. |

dotenv follows the repository's literal syntax: one `KEY=value` per line, optional
`export `, single/double quotes, and comments. No shell commands, variable interpolation
or escape expansion run. Unknown desktop-only variables are ignored, including the
watch-only `GLUCOWATCH_PREDICTION` switch. A supplied model is selected regardless of
that watch switch. YAML uses the nested example schema and rejects unknown keys,
duplicates, collection aliases, object tags and multiple documents. Both formats are limited to
64 KiB; errors never echo credential values.

## CareLink sign-in

The phone keeps an existing session if the country matches. If CareLink is selected and
no session is available, importing its country opens Medtronic's browser sign-in flow.
CareLink usernames/passwords in a development `.env` are desktop sign-in inputs; the
phone cannot bypass Medtronic's browser/CAPTCHA with them.

Alternatively, move a complete session from the desktop sign-in export into the four
session fields. Its refresh token must be used by **one device only**; stop the original
client before importing it. An incomplete session or access token without an account
identifier rejects the import before any settings change. Session replacement and
account changes share the repository's fetch lock, so in-flight requests cannot put
another account's data into the new cache.
