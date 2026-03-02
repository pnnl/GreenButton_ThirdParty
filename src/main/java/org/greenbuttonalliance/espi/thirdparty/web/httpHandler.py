#!/usr/bin/env python3
import os, sys
from scapy.all import sniff, TCP, Raw
import urllib.parse, requests

# ─── CONFIG ────────────────────────────────────────────────────────────────
CALLBACK_PATH = '/ThirdParty/espi/1_1/OAuthCallBack'
PORT          = 9090
TOKEN_URL     = 'http://localhost:8080/DataCustodian/oauth/token'
CLIENT_ID     = 'third_party'
CLIENT_SECRET = 'secret'
# ───────────────────────────────────────────────────────────────────────────

def handle(pkt):
    # only look at TCP dport=9090 with payload
    if TCP in pkt and pkt[TCP].dport == PORT and Raw in pkt:
        raw = pkt[Raw].load.decode('utf-8', errors='ignore')
        if raw.startswith('GET '):
            # extract path and query
            req_line = raw.split('\r\n',1)[0]
            path = req_line.split(' ')[1]
            u = urllib.parse.urlparse(path)
            if u.path == CALLBACK_PATH:
                params = urllib.parse.parse_qs(u.query)
                code  = params.get('code', [None])[0]
                state = params.get('state', [None])[0]
                print(f"→ Got code={code!r}, state={state!r}")
                # exchange for token
                r = requests.post(
                    TOKEN_URL,
                    data={
                        'grant_type':'authorization_code',
                        'code':code,
                        'redirect_uri':f'http://localhost:{PORT}{CALLBACK_PATH}'
                    },
                    auth=(CLIENT_ID, CLIENT_SECRET)
                )
                print(f"→ POST {TOKEN_URL} → {r.status_code}: {r.text}")
                return True
    return False

if __name__=='__main__':
    if os.geteuid()!=0:
        print("Must run as root to sniff loopback", file=sys.stderr)
        sys.exit(1)
    print(f"Sniffing lo:{PORT} for {CALLBACK_PATH}…")
    sniff(
        filter=f"tcp port {PORT}",
        stop_filter=handle,
        iface='lo',
    )
