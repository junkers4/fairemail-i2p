package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2026 by Marcel Bokhorst (M66B)
*/

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Creates a mailbox at Postman (hq.postman.i2p) through the router's HTTP proxy.
// Registration is two POSTs: the form, then a confirmation form with createstep=2.
// The page's WordPress search box (s) must never be sent along.
public class I2pPostman {
    static final String REGISTER_URL = "http://hq.postman.i2p/?page_id=16";

    // Postman's cron job makes the maildir: login works up to 5 minutes after creation
    static final long ACTIVATION_DELAY = 5 * 60 * 1000L; // milliseconds

    private static final int TIMEOUT = 120 * 1000; // milliseconds
    private static final List<String> ENTERED = Arrays.asList("mail", "nick", "pw1", "pw2");
    private static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{2,31}$");

    enum Result {CREATED, TAKEN, REJECTED}

    static class Outcome {
        final Result result;
        final String message;

        Outcome(Result result, String message) {
            this.result = result;
            this.message = message;
        }
    }

    static String normalize(String name) {
        return (name == null ? "" : name.trim().toLowerCase(Locale.ROOT));
    }

    // null = fine, otherwise what is wrong
    static String check(String name, String password) {
        String n = normalize(name);
        if (!NAME.matcher(n).matches())
            return "Use 3-32 letters, digits, dot, dash or underscore, starting with a letter or digit";
        if (n.equals(password.trim().toLowerCase(Locale.ROOT)))
            return "Name and password must not be the same";
        if (password.length() < 6)
            return "The password needs at least 6 characters";
        return null;
    }

    static Outcome register(String name, String password) throws IOException {
        String n = normalize(name);

        Form form = Form.getPost(fetch(REGISTER_URL, null));
        if (form == null)
            throw new IOException("Postman's page has no registration form");

        Map<String, String> data = new LinkedHashMap<>();
        for (Input input : form.inputs) {
            if ("s".equals(input.name) || "submit".equals(input.type) || "checkbox".equals(input.type))
                continue; // the search box, buttons, and the unchecked "public" box
            data.put(input.name, input.value);
        }
        data.put("mail", n);
        data.put("nick", n);
        data.put("pw1", password);
        data.put("pw2", password);

        String page = fetch(REGISTER_URL, data);

        Form confirm = Form.getPost(page);
        if (confirm != null && "2".equals(confirm.get("createstep"))) {
            // Only the confirmation's own hidden values; name and password as entered, not as escaped HTML
            Map<String, String> data2 = new LinkedHashMap<>();
            for (Input input : confirm.inputs)
                if ("hidden".equals(input.type))
                    data2.put(input.name, ENTERED.contains(input.name) ? data.get(input.name) : input.value);
            page = fetch(REGISTER_URL, data2);
        }

        return interpret(page);
    }

    static Outcome interpret(String page) {
        String text = page.replaceAll("<[^>]+>", " ")
                .replace("&nbsp;", " ")
                .replaceAll("\\s+", " ").trim();
        if (text.contains("has been created"))
            return new Outcome(Result.CREATED, sentence(text, "has been created"));
        if (text.contains("already taken") || text.contains("already in the database"))
            return new Outcome(Result.TAKEN, sentence(text, "already"));
        return new Outcome(Result.REJECTED,
                text.contains("should") ? sentence(text, "should") : text.substring(0, Math.min(240, text.length())));
    }

    private static String sentence(String text, String needle) {
        int i = text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
        if (i < 0)
            return text.substring(0, Math.min(240, text.length()));
        return text.substring(Math.max(0, i - 80), Math.min(text.length(), i + 160)).trim();
    }

    private static String fetch(String url, Map<String, String> data) throws IOException {
        Proxy proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(I2pRouter.HOST, I2pRouter.PROXY_PORT));
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(proxy);
        c.setConnectTimeout(TIMEOUT);
        c.setReadTimeout(TIMEOUT);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "MYOB/6.66 (AN/ON)");
        try {
            if (data != null) {
                StringBuilder sb = new StringBuilder();
                for (Map.Entry<String, String> kv : data.entrySet()) {
                    if (sb.length() > 0)
                        sb.append('&');
                    sb.append(URLEncoder.encode(kv.getKey(), "UTF-8"))
                            .append('=')
                            .append(URLEncoder.encode(kv.getValue(), "UTF-8"));
                }
                byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream os = c.getOutputStream()) {
                    os.write(body);
                }
            }

            int status = c.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK)
                throw new IOException("Postman: HTTP " + status + " " + c.getResponseMessage());
            try (InputStream is = c.getInputStream()) {
                return Helper.readStream(is, StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    private static class Input {
        String type;
        String name;
        String value;
    }

    private static class Form {
        Map<String, String> attrs;
        List<Input> inputs = new ArrayList<>();

        String get(String name) {
            for (Input input : inputs)
                if (name.equals(input.name))
                    return input.value;
            return null;
        }

        // The first form that posts
        static Form getPost(String page) {
            Matcher m = Pattern.compile("(?is)<form\\b([^>]*)>(.*?)</form>").matcher(page);
            while (m.find()) {
                Form form = new Form();
                form.attrs = attributes(m.group(1));
                if (!"post".equalsIgnoreCase(form.attrs.get("method")))
                    continue;
                Matcher i = Pattern.compile("(?is)<input\\b([^>]*)>").matcher(m.group(2));
                while (i.find()) {
                    Map<String, String> a = attributes(i.group(1));
                    if (!a.containsKey("name"))
                        continue;
                    Input input = new Input();
                    input.type = (a.containsKey("type") ? a.get("type").toLowerCase(Locale.ROOT) : "text");
                    input.name = a.get("name");
                    input.value = (a.containsKey("value") ? a.get("value") : "");
                    form.inputs.add(input);
                }
                return form;
            }
            return null;
        }

        private static Map<String, String> attributes(String tag) {
            Map<String, String> result = new HashMap<>();
            Matcher m = Pattern.compile("([\\w-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>/]+))").matcher(tag);
            while (m.find()) {
                String value = (m.group(2) != null ? m.group(2) : m.group(3) != null ? m.group(3) : m.group(4));
                result.put(m.group(1).toLowerCase(Locale.ROOT), unescape(value));
            }
            return result;
        }

        private static String unescape(String value) {
            return value.replace("&quot;", "\"")
                    .replace("&#39;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&");
        }
    }
}
