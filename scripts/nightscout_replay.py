#!/usr/bin/env python3
"""Replay a real Nightscout's recent data, shifted in time so its last loop report is ~90 s old.

A loop that has not uploaded for a while makes the watch hide IOB, COB and the loop forecast,
which is correct but leaves nothing to look at. This server takes the last 30 hours from a
Nightscout (API v1, read-only), moves every timestamp forward by the same amount, and serves the
result as a Nightscout v1 API. Glucose, boluses, carbs and forecasts keep their real shape.

Serves /api/v1/entries/sgv.json, /api/v1/treatments.json and /api/v1/devicestatus.json with the
`count` and `find[...][$gte]` filters GlucoWatch uses. For screenshots and manual testing only.

    python3 scripts/nightscout_replay.py https://my.nightscout.example 8537 [token]

The emulator reaches it at http://10.0.2.2:8537 (debug builds allow plain http to that host).
"""
import json
import sys
import threading
import time
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


def _iso(ms):
    return datetime.fromtimestamp(ms / 1000, timezone.utc).strftime('%Y-%m-%dT%H:%M:%S.') + f'{int(ms % 1000):03d}Z'


def _ms(text):
    return int(datetime.fromisoformat(text.replace('Z', '+00:00')).timestamp() * 1000)


def load(source, token=''):
    """Fetches the last 30 h from `source` and returns (entries, treatments, devicestatus, shift_ms)."""
    base = source.rstrip('/')
    auth = f'&token={urllib.parse.quote(token)}' if token else ''
    now = time.time() * 1000

    def get(path, **params):
        query = '&'.join(f'{urllib.parse.quote(k)}={urllib.parse.quote(str(v))}' for k, v in params.items())
        with urllib.request.urlopen(f'{base}{path}?{query}{auth}', timeout=30) as r:
            return json.load(r)

    entries = get('/api/v1/entries/sgv.json', count=2000, **{'find[date][$gte]': int(now - 30 * 3600e3)})
    treatments = get('/api/v1/treatments.json', count=3000, **{'find[created_at][$gte]': _iso(now - 30 * 3600e3)})
    status = get('/api/v1/devicestatus.json', count=40)
    loops = [_ms(d['created_at']) for d in status if ('openaps' in d or 'loop' in d) and 'created_at' in d]
    anchor = max(loops) if loops else max((e['date'] for e in entries), default=now)
    shift = int(now - anchor - 90_000)

    def move(doc, key):
        value = doc.get(key)
        if isinstance(value, (int, float)):
            doc[key] = int(value + shift)
        elif isinstance(value, str):
            try:
                doc[key] = _iso(_ms(value) + shift)
            except ValueError:
                pass

    for e in entries:
        for k in ('date', 'mills', 'dateString', 'sysTime'):
            move(e, k)
    for t in treatments:
        for k in ('created_at', 'date', 'mills', 'timestamp'):
            move(t, k)
    for d in status:
        for k in ('created_at', 'date', 'mills'):
            move(d, k)
        openaps = d.get('openaps') or {}
        for k in ('suggested', 'enacted'):
            if isinstance(openaps.get(k), dict):
                for kk in ('timestamp', 'deliverAt'):
                    move(openaps[k], kk)
        iob = openaps.get('iob')
        for item in (iob if isinstance(iob, list) else [iob]):
            if isinstance(item, dict):
                for kk in ('time', 'timestamp'):
                    move(item, kk)
        loop = d.get('loop') or {}
        for k in ('timestamp',):
            move(loop, k)
        for k in ('iob', 'cob'):
            if isinstance(loop.get(k), dict):
                move(loop[k], 'timestamp')
        if isinstance(loop.get('predicted'), dict):
            move(loop['predicted'], 'startDate')
    return entries, treatments, status, shift


def serve(source, port, token='', quiet=True):
    """Starts the replay in a background thread and returns (server, shift_ms)."""
    entries, treatments, status, shift = load(source, token)

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            url = urllib.parse.urlparse(self.path)
            params = urllib.parse.parse_qs(url.query)
            count = int(params.get('count', ['10'])[0])
            now = time.time() * 1000
            if url.path.endswith('/entries/sgv.json'):
                low = int(float(params.get('find[date][$gte]', ['0'])[0]))
                rows = sorted((e for e in entries if low <= e.get('date', 0) <= now), key=lambda e: -e['date'])
            elif url.path.endswith('/treatments.json') or url.path.endswith('/devicestatus.json'):
                docs = treatments if 'treatments' in url.path else status
                low = params.get('find[created_at][$gte]', [''])[0]
                rows = sorted((d for d in docs if 'created_at' in d and low <= d['created_at'] and _ms(d['created_at']) <= now),
                              key=lambda d: d['created_at'], reverse=True)
            else:
                self.send_response(404)
                self.end_headers()
                return
            body = json.dumps(rows[:count]).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(body)

        def log_message(self, fmt, *args):
            if not quiet:
                sys.stderr.write('%s %s\n' % (self.command, self.path[:120]))

    server = ThreadingHTTPServer(('0.0.0.0', port), Handler)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    return server, shift


if __name__ == '__main__':
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    src, port = sys.argv[1], int(sys.argv[2]) if len(sys.argv) > 2 else 8537
    _, moved = serve(src, port, sys.argv[3] if len(sys.argv) > 3 else '', quiet=False)
    print(f'Replaying {src} on http://0.0.0.0:{port}, shifted by {moved / 60000:.1f} min. Ctrl+C stops.', flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        pass
