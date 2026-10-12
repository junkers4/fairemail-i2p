# Vlastný I2P router pre FairEmail I2P

I2P router (i2pd) v telefóne beží stále a berie batériu. Namiesto neho môže
bežať na vlastnom serveri — na VPS alebo na Raspberry Pi doma — a telefón sa
naň len pripája. Telefón potom nespúšťa žiadny router.

```
telefón (FairEmail) --TLS--> server :7669 SMTP / :7670 POP3
                              stunnel --> i2pd (127.0.0.1:7659 / 7660) --I2P--> Postman (mail.i2p)
```

## Server (Debian, Ubuntu, Raspberry Pi OS)

```sh
git clone https://github.com/junkers4/fairemail-i2p.git
cd fairemail-i2p/i2p-server
sudo ./setup.sh vps.example.org telefon   # meno servera (DNS alebo IP), na ktoré sa pripája telefón; meno telefónu
```

Skript:

- nainštaluje `i2pd` a `stunnel4`,
- pridá do `/etc/i2pd/tunnels.conf` tunely k Postmanovi, len na `127.0.0.1`,
- vyrobí certifikát (platný 10 rokov, pri ďalšom spustení sa ponechá),
- vyrobí **klientsky certifikát** pre telefón: `/root/fairemail-i2p/<telefon>.p12`
  a heslo k nemu v `<telefon>.password`; stunnel pustí len telefóny s takýmto
  certifikátom (ďalší telefón = spustiť skript znova s iným menom),
- spustí službu `fairemail-i2p-tls` (stunnel) na portoch 7669 a 7670,
- na konci vypíše **odtlačok certifikátu** — ten si poznačte.

Porty 7669 a 7670/TCP treba otvoriť vo firewalle VPS u poskytovateľa,
alebo pri Raspberry Pi presmerovať na domácom routeri (a mať DynDNS alebo
vlastnú doménu, ktorá ukazuje na domácu IP).

Prvé spustenie i2pd trvá pár minút, kým nájde Postmana. Test:

```sh
cat /root/fairemail-i2p/telefon.crt /root/fairemail-i2p/telefon.key > /tmp/c.pem
openssl s_client -connect vps.example.org:7670 -quiet -cert /tmp/c.pem   # má odpovedať "+OK"
rm /tmp/c.pem
```

Bez certifikátu musí server odmietnuť: `tlsv13 alert certificate required`.

Raspberry Pi za NAT-om operátora (CGNAT) sa dá sprístupniť cez VPS rovnako ako
pošta: VPS posiela TCP 7669/7670 cez WireGuard na Pi (u nás `homelab`
`mail/vps/nftables.conf`, `tcp_door`).

## Telefón

Najprv súbor `.p12` skopírovať do telefónu a nainštalovať: Nastavenia >
Zabezpečenie > Šifrovanie a poverenia > Inštalovať certifikát >
**Certifikát používateľa VPN a aplikácií**, s heslom zo súboru `.password`.

Potom Nastavenie > sprievodca > menu > **I2P mail (mail.i2p)** >
**I2P router na mojom serveri**, zadať meno servera a tlačidlom
**Klientsky certifikát** vybrať nainštalovaný certifikát.

Aplikácia ukáže odtlačok certifikátu servera. Ak sa zhoduje s tým, čo vypísal
`setup.sh`, ťuknite znova na OK — certifikát sa uloží (pripne) do účtu aj
identity a iný certifikát už aplikácia neprijme.

- **Už mám schránku**: prihlásenie sa overí cez server, telefón nespustí nič.
  Ak už táto schránka v aplikácii je (cez router v telefóne), prepne sa na
  server a správy v nej zostanú — nevznikne druhý účet.
- **Nová schránka**: registrácia u Postmana ide raz cez router v telefóne
  (HTTP proxy servera nemá byť otvorená do internetu). Po registrácii sa router
  v telefóne zastaví a pošta ide cez server.

Router v telefóne sa odteraz spúšťa len vtedy, keď ho používa nejaký zapnutý
účet alebo identita.

## Bezpečnosť

- Heslo k Postmanovi ide z telefónu na server len cez TLS s pripnutým certifikátom.
- Porty 7669/7670 sú otvorené, ale TLS spojenie dokončí len telefón
  s klientskym certifikátom zo `setup.sh`; nikto iný nemôže server použiť ako
  bránu do I2P. Kto chce ešte viac, nechá porty zatvorené a pripája sa cez
  WireGuard/Tailscale (v aplikácii potom zadá IP adresu servera v tuneli
  a v `setup.sh` tú istú adresu).
- Stratený telefón: zmazať jeho `.crt` z `/root/fairemail-i2p/` a spustiť
  `setup.sh` znova (zoznam povolených certifikátov sa poskladá nanovo).
- Od Postmana po server ide spojenie cez I2P; od servera k telefónu cez internet.
  Server teda vidí, že sa z telefónu niekto pripája — anonymitu voči
  poskytovateľovi VPS nemáte, voči Postmanovi a sieti áno.
