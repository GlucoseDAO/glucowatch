# When the network will not reach Dexcom

Readings stop on mobile data and come back on Wi-Fi. The watch says nothing useful, because every
failure on the way to Dexcom used to arrive as the same "network error".

This describes what actually differs between those failures, what the app does about each, and what
no amount of app code can fix.

## The watch's route

A watch without its own SIM (the Galaxy Watch6 Classic 43 mm, SM-R950, is one) never talks to a
carrier itself. With Wi-Fi off it reaches the internet through the Bluetooth link to the phone, so
the phone's carrier decides whether Dexcom is reachable. "The watch is on data" therefore means
"the phone is on data", and a watch-side workaround that switches networks has only one other
network to switch to: Wi-Fi.

`WatchNetwork.alternate()` counts Bluetooth as a route for exactly this reason. An earlier version
looked only for Wi-Fi and cellular and so found nothing on a watch with no SIM.

## What can go wrong, and how to tell

| Fingerprint | What it is | What helps |
|---|---|---|
| Name does not resolve (`UnknownHostException`) | The network's resolver gives no answer for Dexcom — filtering, or a resolver having a bad day | DoH fallback, or Private DNS on the watch |
| Resolves, connection never opens (`ENETUNREACH`, connect timeout) | An IPv6-only link with no working NAT64 to an IPv4-only server, or the address is blocked | Another network, or a proxy. A different resolver changes nothing |
| Connects, handshake is reset | Filtering by TLS server name | Another network, or a proxy |
| Handshake fails on the certificate | Something is answering in Dexcom's place | Nothing — do not enter the login on that network |
| Connects and answers slowly or with 5xx | Dexcom's own trouble | Waiting; the client already retries once |

`FailureKind` in `core/` is this table. `NetworkFailure.classify` maps a thrown exception onto it and
walks the cause chain, since the interesting exception is usually wrapped.

An IPv6-only mobile bearer with DNS64/NAT64 is worth suspecting first. It produces exactly the
"fine on Wi-Fi, intermittent on data" pattern and it is not blocking at all, so every
DNS-level countermeasure is useless against it — and an external DoH resolver makes it *worse*, by
handing the app IPv4 addresses the link cannot route. That is why the DoH step runs only after a
name lookup actually failed, never speculatively.

## The connection check

Settings → **Connection check** walks the layers in order and stops at the first one that fails:

    resolve → connect → handshake → request

Above them it prints what the network looks like: which route is active, whether the link has IPv4
at all, the resolvers in use, whether Private DNS is on, and the NAT64 prefix if the network has
one. Below them it prints the failures the last fetches ran into, each with its layer and the route
the watch was on at the time (`NetworkFailure` keeps the last 20).

The check asks the DoH resolver only when the name did not resolve, and says so on the screen
before it runs: that request tells Cloudflare or Google that this watch looks up Dexcom, whether or
not the DoH fallback itself is switched on.

Nothing in the check relaxes certificate verification. A server presenting someone else's
certificate is reported as a failed handshake, not quietly accepted.

## What the app does on its own

`GlucoseRepository.fetchShareWithWatchFallback` runs after seven minutes without a fresh reading
(`ShareFallbackPolicy`), and tries, in this order:

1. **Another connected route** — Wi-Fi when Bluetooth is the default, or the reverse.
2. **DoH**, only on a `DNS` failure and only when the user turned it on. `DohResolver` asks
   `https://1.1.1.1/dns-query` (or Google's), whose endpoint is an IP literal so it needs no working
   DNS to bootstrap. `DirectAddress` then connects to the address it returns while the TLS server
   name, the certificate check and the `Host` header all stay on `shareous1.dexcom.com`.
3. **A proxy**, if the user configured one. Last, because it moves the whole request through a
   third party.

A rejected login or a locked account is never retried on another route: no route fixes it, and
repeated attempts lock the Dexcom account.

## What to try on the watch, without the app

Private DNS settles the DNS question in a day and fixes it at the same time if that is what it was.
Wear OS has no screen for it, but it is an ordinary global setting:

```bash
adb shell settings put global private_dns_specifier one.one.one.one
adb shell settings put global private_dns_mode hostname
```

The connection check reports whether it took (`Private DNS: one.one.one.one (encrypted)`).

From the phone on the same carrier, these four commands separate the cases in the table:

```bash
dig +short shareous1.dexcom.com                # the carrier's answer
dig +short @1.1.1.1 shareous1.dexcom.com       # a reference answer
curl -v https://shareous1.dexcom.com/ShareWebServices/Services/
curl -v --resolve shareous1.dexcom.com:443:<address> https://shareous1.dexcom.com/ShareWebServices/Services/
```

If the answers differ, it is DNS. If `--resolve` works and the plain form does not, it is DNS. If
both die at the handshake, it is the server name. If `dig` returns something under `64:ff9b::`, the
link is IPv6-only and nothing is being blocked at all.

## What none of this fixes

A network that blocks Dexcom's addresses blocks them however the app learned them. The reliable
answer to that is not a network trick but a second source: Nightscout, on a host the user runs, is
already supported and read-only, and a carrier has no reason to classify it the way it classifies
Dexcom.

## Privacy

DoH is off by default and names its resolver in the settings screen, because turning it on tells
Cloudflare or Google that this device resolves Dexcom. The proxy field carries the same warning. No
part of this sends the Dexcom login anywhere but Dexcom: the proxy test never sends credentials, and
`DirectAddress` keeps full certificate verification, so a network answering in Dexcom's place is
rejected rather than handed a password.
