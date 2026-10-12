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
sudo ./setup.sh vps.example.org     # meno z DNS alebo IP adresa, na ktorú sa pripája telefón
```

Skript:

- nainštaluje `i2pd` a `stunnel4`,
- pridá do `/etc/i2pd/tunnels.conf` tunely k Postmanovi, len na `127.0.0.1`,
- vyrobí certifikát (platný 10 rokov, pri ďalšom spustení sa ponechá),
- spustí službu `fairemail-i2p-tls` (stunnel) na portoch 7669 a 7670,
- na konci vypíše **odtlačok certifikátu** — ten si poznačte.

Porty 7669 a 7670/TCP treba otvoriť vo firewalle VPS u poskytovateľa,
alebo pri Raspberry Pi presmerovať na domácom routeri (a mať DynDNS alebo
vlastnú doménu, ktorá ukazuje na domácu IP).

Prvé spustenie i2pd trvá pár minút, kým nájde Postmana. Test:

```sh
openssl s_client -connect vps.example.org:7670 -quiet    # má odpovedať "+OK"
```

## Telefón

Nastavenie > sprievodca > menu > **I2P mail (mail.i2p)** >
**I2P router na mojom serveri**, zadať meno servera.

Aplikácia ukáže odtlačok certifikátu servera. Ak sa zhoduje s tým, čo vypísal
`setup.sh`, ťuknite znova na OK — certifikát sa uloží (pripne) do účtu aj
identity a iný certifikát už aplikácia neprijme.

- **Už mám schránku**: prihlásenie sa overí cez server, telefón nespustí nič.
- **Nová schránka**: registrácia u Postmana ide raz cez router v telefóne
  (HTTP proxy servera nemá byť otvorená do internetu). Po registrácii sa router
  v telefóne zastaví a pošta ide cez server.

Router v telefóne sa odteraz spúšťa len vtedy, keď ho používa nejaký zapnutý
účet alebo identita.

## Bezpečnosť

- Heslo k Postmanovi ide z telefónu na server len cez TLS s pripnutým certifikátom.
- Na porty 7669/7670 sa môže pripojiť ktokoľvek, ale ďalej sa dostane len
  k Postmanovi a potrebuje vlastné prihlasovacie údaje. Kto chce viac, nechá
  porty zatvorené a pripája sa cez WireGuard/Tailscale (v aplikácii potom zadá
  IP adresu servera v tuneli a v `setup.sh` tú istú adresu).
- Od Postmana po server ide spojenie cez I2P; od servera k telefónu cez internet.
  Server teda vidí, že sa z telefónu niekto pripája — anonymitu voči
  poskytovateľovi VPS nemáte, voči Postmanovi a sieti áno.
