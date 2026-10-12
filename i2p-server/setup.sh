#!/bin/sh
# FairEmail I2P: a router of one's own (VPS, Raspberry Pi) for Postman mail (mail.i2p),
# so the phone runs none. For Debian, Ubuntu and Raspberry Pi OS.
#
#   sudo ./setup.sh [host name or IP address the phone connects to]
#
# i2pd keeps the Postman tunnels on loopback only (SMTP 7659, POP3 7660);
# stunnel puts TLS in front of them for the phone: SMTP 7669, POP3 7670.
# Running it again is safe: the certificate, and so its fingerprint, is kept.
set -eu

NAME="${1:-$(hostname -f)}"
SMTP_DEST="3nrunsrgeo6grhx6y6vsx7vibm5vabtockdbys3sqdmj6vha7k5q.b32.i2p"
POP3_DEST="i7vd76psp3oyocljiqkoyz7fpr4fy2xq2asclf7qih6k57aj5xrq.b32.i2p"
TUNNELS=/etc/i2pd/tunnels.conf
CERT=/etc/stunnel/fairemail-i2p.pem
CONF=/etc/stunnel/fairemail-i2p.conf
UNIT=/etc/systemd/system/fairemail-i2p-tls.service

if [ "$(id -u)" != "0" ]; then
	echo "Run as root: sudo $0 $*" >&2
	exit 1
fi

echo "== Installing i2pd and stunnel"
apt-get update
DEBIAN_FRONTEND=noninteractive apt-get install -y i2pd stunnel4 openssl

echo "== I2P tunnels to Postman"
touch "$TUNNELS"
# Replace an earlier block of ours
sed -i '/^# fairemail-i2p begin/,/^# fairemail-i2p end/d' "$TUNNELS"
cat >> "$TUNNELS" <<CONF
# fairemail-i2p begin
[fairemail-smtp]
type = client
address = 127.0.0.1
port = 7659
destination = $SMTP_DEST
destinationport = 25
keys = fairemail-mail.dat

[fairemail-pop3]
type = client
address = 127.0.0.1
port = 7660
destination = $POP3_DEST
destinationport = 110
keys = fairemail-mail.dat
# fairemail-i2p end
CONF
systemctl enable i2pd
systemctl restart i2pd

echo "== TLS certificate for $NAME"
if [ ! -s "$CERT" ]; then
	case "$NAME" in
		*[!0-9.]*) SAN="DNS:$NAME" ;;
		*) SAN="IP:$NAME" ;;
	esac
	case "$NAME" in
		*:*) SAN="IP:$NAME" ;;
	esac
	openssl req -x509 -nodes -days 3650 \
		-newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
		-subj "/CN=$NAME" -addext "subjectAltName=$SAN" \
		-keyout "$CERT" -out "$CERT"
	chmod 600 "$CERT"
fi

echo "== stunnel: SMTP 7669, POP3 7670"
cat > "$CONF" <<CONF
foreground = yes
setuid = nobody
setgid = nogroup
cert = $CERT
sslVersionMin = TLSv1.2

[fairemail-smtp]
accept = 7669
connect = 127.0.0.1:7659

[fairemail-pop3]
accept = 7670
connect = 127.0.0.1:7660
CONF

STUNNEL="$(command -v stunnel4 || command -v stunnel)"
cat > "$UNIT" <<CONF
[Unit]
Description=TLS for FairEmail I2P mail (Postman tunnels of i2pd)
After=network-online.target i2pd.service
Wants=network-online.target

[Service]
ExecStart=$STUNNEL $CONF
Restart=on-failure

[Install]
WantedBy=multi-user.target
CONF
systemctl daemon-reload
systemctl enable fairemail-i2p-tls
systemctl restart fairemail-i2p-tls

if command -v ufw >/dev/null 2>&1 && ufw status | grep -q "Status: active"; then
	echo "== Firewall (ufw): opening 7669 and 7670"
	ufw allow 7669/tcp
	ufw allow 7670/tcp
fi

FP="$(openssl x509 -in "$CERT" -noout -fingerprint -sha1 | cut -d= -f2 | tr -d : | tr 'A-F' 'a-f')"
cat <<INFO

Done. In FairEmail: Setup > Wizard menu > I2P mail (mail.i2p) >
"I2P router on my own server", server: $NAME

The app shows the certificate fingerprint of the server. It must be:

    $FP

i2pd needs a few minutes after its first start to find Postman. Test with:
    openssl s_client -connect $NAME:7670 -quiet
which should answer "+OK" (QUIT to leave).

Open the TCP ports 7669 and 7670 in the firewall of your VPS provider,
or forward them on your home router to this Raspberry Pi.
INFO
