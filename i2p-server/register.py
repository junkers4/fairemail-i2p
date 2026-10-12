#!/usr/bin/env python3
# FairEmail I2P: creates a mailbox at Postman (hq.postman.i2p) through the HTTP proxy
# of the server's own i2pd, which listens on loopback only. Run it on that server:
#
#   ./register.py NAME          asks for the password, then creates NAME@mail.i2p
#   ./register.py --check       only reads Postman's form: is the proxy reaching it?
#
# Registration is two POSTs: the form, then a confirmation form with createstep=2.
# The page's WordPress search box (s) must never be sent along.
# Postman's cron job makes the maildir: login works up to 5 minutes after creation.
# Then, in FairEmail: Setup > Wizard menu > I2P mail (mail.i2p).

import getpass
import html
import re
import sys
import urllib.parse
import urllib.request

REGISTER_URL = "http://hq.postman.i2p/?page_id=16"
PROXY = "http://127.0.0.1:4444"
TIMEOUT = 120  # seconds
ENTERED = ("mail", "nick", "pw1", "pw2")
NAME = re.compile(r"^[a-z0-9][a-z0-9._-]{2,31}$")

opener = urllib.request.build_opener(urllib.request.ProxyHandler({"http": PROXY}))
opener.addheaders = [("User-Agent", "MYOB/6.66 (AN/ON)")]


def fetch(url, data=None):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    with opener.open(url, body, timeout=TIMEOUT) as r:
        return r.read().decode("utf-8", "replace")


def attributes(tag):
    result = {}
    for m in re.finditer(r"""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'>/]+))""", tag):
        value = next(v for v in m.groups()[1:] if v is not None)
        result[m.group(1).lower()] = html.unescape(value)
    return result


# The first form that posts: [(type, name, value)]
def post_form(page):
    for m in re.finditer(r"(?is)<form\b([^>]*)>(.*?)</form>", page):
        if attributes(m.group(1)).get("method", "").lower() != "post":
            continue
        inputs = []
        for i in re.finditer(r"(?is)<input\b([^>]*)>", m.group(2)):
            a = attributes(i.group(1))
            if "name" in a:
                inputs.append((a.get("type", "text").lower(), a["name"], a.get("value", "")))
        return inputs
    return None


def text_of(page):
    t = re.sub(r"<[^>]+>", " ", page).replace("&nbsp;", " ")
    return re.sub(r"\s+", " ", t).strip()


def sentence(text, needle):
    i = text.lower().find(needle.lower())
    return text[:240] if i < 0 else text[max(0, i - 80):i + 160].strip()


def main():
    if len(sys.argv) != 2:
        sys.exit("usage: register.py NAME | --check")

    try:
        form = post_form(fetch(REGISTER_URL))
    except OSError as ex:
        sys.exit("Postman not reachable through i2pd (%s): i2pd may still be looking for it, try again in a few minutes" % ex)
    if form is None:
        sys.exit("Postman's page has no registration form")

    if sys.argv[1] == "--check":
        print("Postman's form is reachable, fields: " + ", ".join(n for _, n, _ in form))
        return

    name = sys.argv[1].strip().lower()
    if not NAME.match(name):
        sys.exit("Use 3-32 letters, digits, dot, dash or underscore, starting with a letter or digit")
    password = getpass.getpass("Password for %s@mail.i2p: " % name)
    if password != getpass.getpass("Again: "):
        sys.exit("The passwords are not the same")
    if len(password) < 6:
        sys.exit("The password needs at least 6 characters")
    if password.strip().lower() == name:
        sys.exit("Name and password must not be the same")

    data = {}
    for type, n, value in form:
        if n == "s" or type in ("submit", "checkbox"):
            continue  # the search box, buttons, and the unchecked "public" box
        data[n] = value
    data.update(mail=name, nick=name, pw1=password, pw2=password)

    page = fetch(REGISTER_URL, data)
    confirm = post_form(page)
    if confirm is not None and dict((n, v) for _, n, v in confirm).get("createstep") == "2":
        # Only the confirmation's own hidden values; name and password as entered, not as escaped HTML
        data2 = {n: (data[n] if n in ENTERED else v) for type, n, v in confirm if type == "hidden"}
        page = fetch(REGISTER_URL, data2)

    text = text_of(page)
    if "has been created" in text:
        print(sentence(text, "has been created"))
        print("Postman activates it within 5 minutes. Then add it in FairEmail: Setup > Wizard menu > I2P mail (mail.i2p)")
    elif "already taken" in text or "already in the database" in text:
        sys.exit(sentence(text, "already"))
    else:
        sys.exit(sentence(text, "should") if "should" in text else text[:240])


if __name__ == "__main__":
    main()
