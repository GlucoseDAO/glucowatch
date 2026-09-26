#!/usr/bin/env python3
"""Sign in to Medtronic CareLink once in a browser and save the tokens GlucoWatch refreshes itself.

CareLink's sign-in page has a reCAPTCHA, so no program can log in with a username and password
alone. This opens the CareLink CarePartner sign-in (Auth0, the same flow as the CarePartner app
and xDrip+) in Chrome, fills in CARELINK_USERNAME and CARELINK_PASSWORD from `.env` when it can,
and waits while you solve the captcha. It catches the app redirect, trades the code for tokens
and writes them to ~/.config/glucowatch/carelink-token.json (mode 600, outside the repository).

    uv run --with playwright scripts/carelink_login.py [--country DE] [--out FILE]

Then `./gradlew -q --console=plain :core:run --args="--source carelink"` reads and refreshes that
file, and `scripts/carelink_to_watch.py` moves it to a debug build on the emulator or watch.
The refresh token rotates: one copy per device, or the second one to refresh logs both out.
Use a CareLink care partner (follower) account with two-factor sign-in turned off.
"""
import argparse
import base64
import hashlib
import json
import os
import secrets
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

DISCOVERY = 'https://clcloud.minimed.eu/connect/carepartner/v13/discover/android/3.8'
DEFAULT_OUT = Path.home() / '.config' / 'glucowatch' / 'carelink-token.json'


def dot_env():
    """`.env` from the repository root, same rules as core's DotEnv; environment variables win."""
    values = {}
    path = Path(__file__).resolve().parent.parent / '.env'
    if path.is_file():
        for raw in path.read_text().splitlines():
            line = raw.strip().removeprefix('export ').strip()
            if not line or line.startswith('#') or '=' not in line:
                continue
            key, value = line.split('=', 1)
            value = value.strip()
            if len(value) >= 2 and value[0] == value[-1] and value[0] in '"\'':
                value = value[1:-1]
            else:
                value = value.split(' #')[0].strip()
            values[key.strip()] = value
    return {**values, **{k: v for k, v in os.environ.items() if k.startswith('CARELINK_')}}


def get_json(url):
    with urllib.request.urlopen(url, timeout=20) as r:
        return json.load(r)


def sso_config(country):
    disco = get_json(DISCOVERY)
    region = next((c[country]['region'] for c in disco['supportedCountries'] if country in c), None)
    if region is None:
        sys.exit(f'CareLink does not list country {country}')
    cp = next(r for r in disco['CP'] if r['region'] == region)
    key = cp.get('UseSSOConfiguration', 'SSOConfiguration')
    sso = get_json(cp.get(key) or cp['SSOConfiguration'])
    server = sso['server']
    prefix = server.get('prefix', '').strip('/')
    base = f"https://{server['hostname']}:{server['port']}" + (f'/{prefix}' if prefix else '')
    return region, base, sso


def b64url(data):
    return base64.urlsafe_b64encode(data).rstrip(b'=').decode()


def main():
    env = dot_env()
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--country', default=env.get('CARELINK_COUNTRY') or 'DE', help='two-letter country of the CareLink account')
    parser.add_argument('--out', type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()
    country = args.country.upper()

    region, base, sso = sso_config(country)
    client = sso['client']
    endpoints = sso['system_endpoints']
    verifier = b64url(secrets.token_bytes(32))
    state = b64url(secrets.token_bytes(16))
    auth_url = base + endpoints['authorization_endpoint_path'] + '?' + urllib.parse.urlencode({
        'client_id': client['client_id'],
        'response_type': 'code',
        'scope': client['scope'],
        'redirect_uri': client['redirect_uri'],
        'audience': client['audience'],
        'state': state,
        'code_challenge': b64url(hashlib.sha256(verifier.encode()).digest()),
        'code_challenge_method': 'S256',
    })

    from playwright.sync_api import sync_playwright

    redirect = client['redirect_uri']
    found = {}

    def check(url):
        if url and url.startswith(redirect) and 'code' not in found:
            query = urllib.parse.parse_qs(urllib.parse.urlsplit(url).query)
            if query.get('state', [None])[0] == state and 'code' in query:
                found['code'] = query['code'][0]

    with sync_playwright() as p:
        # X11 (or XWayland) when there is one: Chrome's Wayland backend fails in sandboxed shells.
        flags = ['--ozone-platform=x11'] if os.environ.get('DISPLAY') else []
        try:
            browser = p.chromium.launch(channel='chrome', headless=False, args=flags)
        except Exception:
            browser = p.chromium.launch(headless=False, args=flags)
        page = browser.new_page()
        page.on('response', lambda r: check(r.headers.get('location')))
        page.on('request', lambda r: check(r.url))
        page.on('framenavigated', lambda f: check(f.url))
        page.goto(auth_url)
        print(f'CareLink sign-in ({region}) is open in Chrome. Solve the captcha and sign in; this waits up to 10 minutes.')
        user, password = env.get('CARELINK_USERNAME', ''), env.get('CARELINK_PASSWORD', '')
        deadline = time.time() + 600
        filled = set()
        while 'code' not in found and time.time() < deadline:
            # Auth0 asks for the username and the password on one page or on two; fill what is shown.
            for selector, value in (('input#username, input[name=username]', user), ('input#password, input[name=password]', password)):
                if value and selector not in filled:
                    try:
                        field = page.locator(selector).first
                        if field.is_visible(timeout=200) and not field.input_value():
                            field.fill(value)
                            filled.add(selector)
                    except Exception:
                        pass
            try:
                page.wait_for_timeout(500)
            except Exception:
                break
        browser.close()

    if 'code' not in found:
        sys.exit('No sign-in code arrived. Run again and finish the sign-in in the browser window.')

    body = urllib.parse.urlencode({
        'grant_type': 'authorization_code',
        'client_id': client['client_id'],
        'code': found['code'],
        'redirect_uri': redirect,
        'code_verifier': verifier,
    }).encode()
    request = urllib.request.Request(base + endpoints['token_endpoint_path'], body, {'Content-Type': 'application/x-www-form-urlencoded'})
    try:
        with urllib.request.urlopen(request, timeout=20) as r:
            tokens = json.load(r)
    except urllib.error.HTTPError as e:
        sys.exit(f'CareLink refused the code: HTTP {e.code} {e.read().decode(errors="replace")[:300]}')
    if 'refresh_token' not in tokens:
        sys.exit('CareLink returned no refresh token, so the watch could not keep the login.')

    # Same fields as core's CareLinkToken.
    saved = {
        'country': country,
        'client_id': client['client_id'],
        'access_token': tokens['access_token'],
        'refresh_token': tokens['refresh_token'],
        'expires_at': int((time.time() + int(tokens.get('expires_in', 3600))) * 1000),
    }
    args.out.parent.mkdir(parents=True, exist_ok=True)
    fd = os.open(args.out, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, 'w') as f:
        json.dump(saved, f)
    print(f'Signed in. Tokens saved to {args.out} (access token valid for {int(tokens.get("expires_in", 0)) // 60} min, refreshed automatically).')


if __name__ == '__main__':
    main()
