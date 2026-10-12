#!/bin/sh
# FairEmail I2P: a router of one's own (VPS, Raspberry Pi) for Postman mail (mail.i2p),
# so the phone runs none. For Debian, Ubuntu and Raspberry Pi OS.
#
#   sudo ./setup.sh [host name or IP address the phone connects to] [phone name]
#
# i2pd keeps the Postman tunnels on loopback only (SMTP 7659, POP3 7660);
# stunnel puts TLS in front of them for the phone: SMTP 7669, POP3 7670.
# stunnel lets in only phones holding a client certificate made here, so the
# open ports are no free gateway into I2P for anyone else.
# Running it again is safe: the certificates, and so their fingerprints, are kept.
# Running it with another phone name adds a certificate for that phone.
set -eu

NAME="${1:-$(hostname -f)}"
PHONE="${2:-phone}"
SMTP_DEST="3nrunsrgeo6grhx6y6vsx7vibm5vabtockdbys3sqdmj6vha7k5q.b32.i2p"
POP3_DEST="i7vd76psp3oyocljiqkoyz7fpr4fy2xq2asclf7qih6k57aj5xrq.b32.i2p"
TUNNELS=/etc/i2pd/tunnels.conf
CERT=/etc/stunnel/fairemail-i2p.pem
CONF=/etc/stunnel/fairemail-i2p.conf
CLIENTS=/etc/stunnel/fairemail-i2p-clients.pem
CLIENT_DIR=/root/fairemail-i2p
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
BEFORE="$(md5sum < "$TUNNELS")"
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
# A restart makes i2pd look for Postman again, which takes minutes
if [ "$BEFORE" != "$(md5sum < "$TUNNELS")" ] || ! systemctl is-active -q i2pd; then
	systemctl restart i2pd
fi

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

case "$PHONE" in
	*[!A-Za-z0-9_-]*|"") echo "Phone name: letters, digits, - and _ only" >&2; exit 1 ;;
esac
P12="$CLIENT_DIR/$PHONE.p12"
if [ ! -s "$P12" ]; then
	echo "== Client certificate for $PHONE"
	mkdir -p -m 700 "$CLIENT_DIR"
	openssl req -x509 -nodes -days 3650 \
		-newkey ec -pkeyopt ec_paramgen_curve:prime256v1 \
		-subj "/CN=fairemail-i2p $PHONE" \
		-keyout "$CLIENT_DIR/$PHONE.key" -out "$CLIENT_DIR/$PHONE.crt"
	openssl rand -hex 8 > "$CLIENT_DIR/$PHONE.password"
	openssl pkcs12 -export -name "fairemail-i2p $PHONE" \
		-inkey "$CLIENT_DIR/$PHONE.key" -in "$CLIENT_DIR/$PHONE.crt" \
		-passout "file:$CLIENT_DIR/$PHONE.password" -out "$P12"
	chmod 600 "$CLIENT_DIR"/*
fi
# The phones let in: every client certificate made here
cat "$CLIENT_DIR"/*.crt > "$CLIENTS"
chmod 644 "$CLIENTS"

echo "== stunnel: SMTP 7669, POP3 7670"
cat > "$CONF" <<CONF
foreground = yes
setuid = nobody
setgid = nogroup
cert = $CERT
sslVersionMin = TLSv1.2
CAfile = $CLIENTS
verifyPeer = yes

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

Client certificate for $PHONE: copy $P12 to the phone,
install it (Settings > Security > Encryption & credentials > Install a certificate >
VPN & app user certificate), its password is: $(cat "$CLIENT_DIR/$PHONE.password")
then select it in FairEmail with "Client certificate".

The app shows the certificate fingerprint of the server. It must be:

    $FP

i2pd needs a few minutes after its first start to find Postman. Test with:
    openssl s_client -connect $NAME:7670 -quiet \
        -cert $CLIENT_DIR/$PHONE.crt -key $CLIENT_DIR/$PHONE.key
which should answer "+OK" (QUIT to leave).

Open the TCP ports 7669 and 7670 in the firewall of your VPS provider,
or forward them on your home router to this Raspberry Pi.
INFO
