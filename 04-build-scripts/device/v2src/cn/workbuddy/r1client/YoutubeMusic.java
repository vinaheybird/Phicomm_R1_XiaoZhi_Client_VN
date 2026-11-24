package cn.workbuddy.r1client;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Direct on-device YouTube audio search & streaming relay (zero external NAS/server).
 *
 * <ul>
 *   <li>Search: YouTube Innertube WEB endpoint ({@code hl=vi, gl=VN, params=EgIQAQ==}).</li>
 *   <li>Resolve: YouTube Innertube IOS client ({@code audio/mp4} AAC 128kbps {@code itag=140},
 *       fallback {@code itag=139} / progressive {@code itag=18}).</li>
 *   <li>Playback: Loopback HTTP proxy {@code http://127.0.0.1:<port>/yt/stream?v=<id>} served
 *       by {@link WebConsole} with {@code Range} / {@code HEAD} support and a 16KB streaming
 *       buffer so R1's ExoPlayer plays stereo M4A without Android 5.1.1 SSL issues or high RAM.</li>
 * </ul>
 */
public final class YoutubeMusic {

    private static final String TAG = "YoutubeMusic";

    private static final String SEARCH_URL = "https://www.youtube.com/youtubei/v1/search?prettyPrint=false";
    private static final String PLAYER_URL = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String SW_DATA_URL = "https://www.youtube.com/sw.js_data";

    private static final String WEB_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";
    private static final String ANDROID_UA =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";
    private static final String ANDROID_VER = "20.10.38";

    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final Pattern VISITOR_PAT = Pattern.compile("\"(Cg[a-zA-Z0-9_%-]{10,})\"");

    /** Cache up to 16 resolved stream URLs for 45 min so ExoPlayer Range seeks never re-query Innertube. */
    private static final int CACHE_MAX = 16;
    private static final long CACHE_TTL_MS = 45L * 60L * 1000L;
    private static final long VISITOR_TTL_MS = 2L * 60L * 60L * 1000L;

    private static volatile String cachedVisitorData = "";
    private static volatile long cachedVisitorAt = 0L;

    private static final String[] RANDOM_QUERIES = new String[]{
            "nhạc trẻ việt nam hot nhất hiện nay",
            "vpop hits nhẹ nhàng",
            "nhạc chill việt nam hay nhất",
            "nhạc acoustic việt nam",
            "nhạc lofi chill việt nam",
            "nhạc trữ tình bolero hay nhất",
            "nhạc us uk chill hits"
    };

    private static volatile OkHttpClient httpClient;

    public static final class StreamInfo {
        public final String url;
        public final String mimeType;
        public final long contentLength;
        public final long fetchedAt;

        StreamInfo(String url, String mimeType, long contentLength, long fetchedAt) {
            this.url = url;
            this.mimeType = mimeType;
            this.contentLength = contentLength;
            this.fetchedAt = fetchedAt;
        }
    }

    private static final LinkedHashMap<String, StreamInfo> streamCache =
            new LinkedHashMap<String, StreamInfo>(CACHE_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, StreamInfo> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    private static OkHttpClient client() {
        OkHttpClient c = httpClient;
        if (c != null) {
            return c;
        }
        synchronized (YoutubeMusic.class) {
            if (httpClient != null) {
                return httpClient;
            }
            OkHttpClient.Builder b = new OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .writeTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true);
            try {
                X509TrustManager tm = new X509TrustManager() {
                    public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                    public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                    public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                };
                SSLContext ctx = SSLContext.getInstance("TLS");
                ctx.init(null, new TrustManager[]{tm}, new SecureRandom());
                b.sslSocketFactory(ctx.getSocketFactory(), tm);
                b.hostnameVerifier(new HostnameVerifier() {
                    public boolean verify(String host, SSLSession session) { return true; }
                });
            } catch (Throwable t) {
                Log.w(TAG, "ssl init: " + t);
            }
            httpClient = b.build();
            return httpClient;
        }
    }

    public static boolean isValidVideoId(String id) {
        if (id == null || id.length() < 6 || id.length() > 20) {
            return false;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    public static String localStreamUrl(String videoId) {
        try {
            return "http://127.0.0.1:" + WebConsole.getPort() + "/yt/stream?v="
                    + URLEncoder.encode(videoId, "UTF-8");
        } catch (Throwable t) {
            return "http://127.0.0.1:" + WebConsole.getPort() + "/yt/stream?v=" + videoId;
        }
    }

    public static String thumbUrl(String videoId) {
        return isValidVideoId(videoId)
                ? ("https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg") : "";
    }

    /**
     * Search YouTube videos and return standard PlayerBridge online JSON:
     * {@code {total, list:[{title, artist, source:"yt", rid, album:"YouTube", duration, pic}]}}
     */
    public static JSONObject searchJson(String query, int limit) {
        if (query == null || query.trim().length() == 0) {
            return null;
        }
        int lim = limit > 0 ? Math.min(limit, 25) : 15;
        Response resp = null;
        try {
            JSONObject clientObj = new JSONObject();
            clientObj.put("clientName", "WEB");
            clientObj.put("clientVersion", "2.20250301.00.00");
            clientObj.put("hl", "vi");
            clientObj.put("gl", "VN");

            JSONObject ctxObj = new JSONObject();
            ctxObj.put("client", clientObj);

            JSONObject payload = new JSONObject();
            payload.put("context", ctxObj);
            payload.put("query", query.trim());
            payload.put("params", "EgIQAQ=="); // videos only

            Request req = new Request.Builder()
                    .url(SEARCH_URL)
                    .header("User-Agent", WEB_UA)
                    .header("Accept-Language", "vi-VN,vi;q=0.9,en;q=0.8")
                    .post(RequestBody.create(JSON_TYPE, payload.toString()))
                    .build();
            resp = client().newCall(req).execute();
            if (!resp.isSuccessful() || resp.body() == null) {
                SessionLog.i("yt search http=" + resp.code() + " q=" + query);
                return null;
            }
            String raw = resp.body().string();
            JSONObject root = new JSONObject(raw);

            List<JSONObject> found = new ArrayList<JSONObject>();
            List<String> seen = new ArrayList<String>();
            collectVideoRenderers(root, found, seen, lim * 2);

            if (found.isEmpty()) {
                return null;
            }

            // If user did not ask for karaoke/beat, gently push karaoke results down.
            String ql = query.toLowerCase();
            boolean wantKaraoke = ql.contains("karaoke") || ql.contains("beat") || ql.contains("instrumental");
            if (!wantKaraoke && found.size() > 1) {
                List<JSONObject> normal = new ArrayList<JSONObject>();
                List<JSONObject> karaoke = new ArrayList<JSONObject>();
                for (int i = 0; i < found.size(); i++) {
                    JSONObject it = found.get(i);
                    String tl = it.optString("title", "").toLowerCase();
                    if (tl.contains("karaoke") || tl.contains("beat chuẩn") || tl.contains("instrumental")) {
                        karaoke.add(it);
                    } else {
                        normal.add(it);
                    }
                }
                if (!normal.isEmpty()) {
                    found = normal;
                    found.addAll(karaoke);
                }
            }

            JSONArray out = new JSONArray();
            for (int i = 0; i < found.size() && out.length() < lim; i++) {
                out.put(found.get(i));
            }
            JSONObject result = new JSONObject();
            result.put("total", out.length());
            result.put("list", out);
            SessionLog.i("yt search 「" + query.trim() + "」 -> " + out.length() + " videos");
            return result;
        } catch (Throwable t) {
            Log.w(TAG, "searchJson failed: " + t);
            SessionLog.i("yt search 「" + query + "」 failed: " + t);
            return null;
        } finally {
            closeQuietly(resp);
        }
    }

    /** Convert YouTube search results into PlayerBridge {@code String[8]} tracks with lazy local URLs. */
    public static List<String[]> searchTracks(String query, int limit) {
        JSONObject sj = searchJson(query, limit);
        if (sj == null) {
            return null;
        }
        JSONArray list = sj.optJSONArray("list");
        if (list == null || list.length() == 0) {
            return null;
        }
        List<String[]> out = new ArrayList<String[]>(list.length());
        for (int i = 0; i < list.length(); i++) {
            JSONObject it = list.optJSONObject(i);
            if (it == null) {
                continue;
            }
            String vid = it.optString("rid", "");
            if (!isValidVideoId(vid)) {
                continue;
            }
            out.add(new String[]{
                    it.optString("title", ""),
                    it.optString("artist", ""),
                    "",
                    localStreamUrl(vid),
                    "YouTube",
                    it.optString("duration", ""),
                    it.optString("pic", thumbUrl(vid)),
                    "online"
            });
        }
        return out.isEmpty() ? null : out;
    }

    /** Random batch of Vietnamese / chill music from YouTube for voice "mở nhạc" / random_online. */
    public static List<String[]> randomTracks(int limit) {
        String q = RANDOM_QUERIES[new Random().nextInt(RANDOM_QUERIES.length)];
        List<String[]> list = searchTracks(q, Math.min(limit > 0 ? limit : 15, 20));
        if (list != null && list.size() > 1) {
            Collections.shuffle(list);
        }
        return list;
    }

    /** Fetch and cache YouTube visitorData (required by ANDROID Innertube player client). */
    private static String getVisitorData(boolean forceRefresh) {
        long now = System.currentTimeMillis();
        if (!forceRefresh && cachedVisitorData.length() > 0 && (now - cachedVisitorAt) < VISITOR_TTL_MS) {
            return cachedVisitorData;
        }
        Response resp = null;
        try {
            Request req = new Request.Builder()
                    .url(SW_DATA_URL)
                    .header("User-Agent", WEB_UA)
                    .get()
                    .build();
            resp = client().newCall(req).execute();
            if (resp.isSuccessful() && resp.body() != null) {
                String txt = resp.body().string();
                Matcher m = VISITOR_PAT.matcher(txt);
                if (m.find()) {
                    cachedVisitorData = m.group(1);
                    cachedVisitorAt = now;
                    return cachedVisitorData;
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "getVisitorData: " + t);
        } finally {
            closeQuietly(resp);
        }
        return cachedVisitorData;
    }

    /** Resolve direct YouTube playable URL (cached for 45 min). */
    public static StreamInfo resolveStream(String videoId, boolean forceRefresh) {
        if (!isValidVideoId(videoId)) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (!forceRefresh) {
            synchronized (streamCache) {
                StreamInfo cached = streamCache.get(videoId);
                if (cached != null && (now - cached.fetchedAt) < CACHE_TTL_MS) {
                    return cached;
                }
            }
        }
        Response resp = null;
        try {
            String vdata = getVisitorData(forceRefresh);

            JSONObject clientObj = new JSONObject();
            clientObj.put("clientName", "ANDROID");
            clientObj.put("clientVersion", ANDROID_VER);
            clientObj.put("androidSdkVersion", 30);
            clientObj.put("hl", "vi");
            clientObj.put("gl", "VN");
            if (vdata != null && vdata.length() > 0) {
                clientObj.put("visitorData", vdata);
            }

            JSONObject ctxObj = new JSONObject();
            ctxObj.put("client", clientObj);

            JSONObject payload = new JSONObject();
            payload.put("context", ctxObj);
            payload.put("videoId", videoId);
            payload.put("contentCheckOk", true);
            payload.put("racyCheckOk", true);

            Request.Builder rb = new Request.Builder()
                    .url(PLAYER_URL)
                    .header("User-Agent", ANDROID_UA)
                    .header("X-YouTube-Client-Name", "3")
                    .header("X-YouTube-Client-Version", ANDROID_VER)
                    .post(RequestBody.create(JSON_TYPE, payload.toString()));
            if (vdata != null && vdata.length() > 0) {
                rb.header("X-Goog-Visitor-Id", vdata);
            }
            resp = client().newCall(rb.build()).execute();
            if (!resp.isSuccessful() || resp.body() == null) {
                SessionLog.i("yt resolve " + videoId + " http=" + resp.code());
                return null;
            }
            JSONObject pr = new JSONObject(resp.body().string());
            JSONObject sd = pr.optJSONObject("streamingData");
            if (sd == null) {
                JSONObject st = pr.optJSONObject("playabilityStatus");
                SessionLog.i("yt resolve " + videoId + " no streamingData: "
                        + (st == null ? "?" : st.optString("status") + " " + st.optString("reason")));
                return null;
            }

            // Prefer progressive formats (itag 18 = MP4 with AAC stereo) which YouTube serves
            // full-stream without PO Token 403 restrictions.
            JSONObject picked = null;
            JSONArray fmts = sd.optJSONArray("formats");
            if (fmts != null) {
                for (int i = 0; i < fmts.length(); i++) {
                    JSONObject f = fmts.optJSONObject(i);
                    if (f == null || f.optString("url", "").length() == 0) continue;
                    if (f.optInt("itag", 0) == 18) {
                        picked = f;
                        break;
                    }
                    if (picked == null) {
                        picked = f;
                    }
                }
            }
            if (picked == null) {
                JSONArray afmts = sd.optJSONArray("adaptiveFormats");
                if (afmts != null) {
                    for (int i = 0; i < afmts.length(); i++) {
                        JSONObject f = afmts.optJSONObject(i);
                        if (f == null) continue;
                        String u = f.optString("url", "");
                        String mt = f.optString("mimeType", "");
                        if (u.length() > 0 && mt.startsWith("audio/mp4")) {
                            picked = f;
                            if (f.optInt("itag", 0) == 140) break;
                        }
                    }
                }
            }
            if (picked == null) {
                SessionLog.i("yt resolve " + videoId + " no direct format");
                return null;
            }
            String u = picked.optString("url", "");
            String mt = cleanMime(picked.optString("mimeType", "video/mp4"));
            long cl = -1L;
            try {
                String cls = picked.optString("contentLength", "");
                if (cls.length() > 0) {
                    cl = Long.parseLong(cls);
                }
            } catch (Throwable ignored) {
            }
            StreamInfo info = new StreamInfo(u, mt, cl, now);
            synchronized (streamCache) {
                streamCache.put(videoId, info);
            }
            SessionLog.i("yt resolve " + videoId + " ok itag=" + picked.optInt("itag", 0)
                    + " mime=" + mt + " len=" + cl);
            return info;
        } catch (Throwable t) {
            Log.w(TAG, "resolveStream " + videoId + ": " + t);
            SessionLog.i("yt resolve " + videoId + " failed: " + t);
            return null;
        } finally {
            closeQuietly(resp);
        }
    }

    /**
     * Serve {@code GET/HEAD /yt/stream?v=<videoId>} to R1's ExoPlayer over loopback HTTP.
     * Supports HTTP Range (206 Partial Content) and streams via a 16KB buffer (minimal RAM).
     */
    public static void handleStream(String method, String videoId, String rangeHeader,
                                    OutputStream out) {
        try {
            if (!isValidVideoId(videoId)) {
                writeErr(out, "400 Bad Request", "invalid video id");
                return;
            }
            StreamInfo info = resolveStream(videoId, false);
            if (info == null) {
                writeErr(out, "502 Bad Gateway", "yt resolve failed");
                return;
            }
            if ("HEAD".equalsIgnoreCase(method)) {
                StringBuilder h = new StringBuilder();
                h.append("HTTP/1.1 200 OK\r\n");
                h.append("Content-Type: ").append(info.mimeType).append("\r\n");
                h.append("Accept-Ranges: bytes\r\n");
                if (info.contentLength > 0) {
                    h.append("Content-Length: ").append(info.contentLength).append("\r\n");
                }
                h.append("Connection: close\r\n\r\n");
                out.write(h.toString().getBytes("UTF-8"));
                out.flush();
                return;
            }

            Response up = openUpstream(info.url, rangeHeader);
            if (up != null && (up.code() == 403 || up.code() == 410)) {
                closeQuietly(up);
                info = resolveStream(videoId, true);
                if (info == null) {
                    writeErr(out, "502 Bad Gateway", "yt refresh failed");
                    return;
                }
                up = openUpstream(info.url, rangeHeader);
            }
            if (up == null) {
                writeErr(out, "502 Bad Gateway", "upstream connect failed");
                return;
            }
            try {
                int code = up.code();
                if (code != 200 && code != 206) {
                    writeErr(out, "502 Bad Gateway", "upstream http " + code);
                    return;
                }
                ResponseBody body = up.body();
                if (body == null) {
                    writeErr(out, "502 Bad Gateway", "empty body");
                    return;
                }
                String ct = up.header("Content-Type");
                if (ct == null || ct.length() == 0) {
                    ct = info.mimeType;
                }
                String cl = up.header("Content-Length");
                String cr = up.header("Content-Range");

                StringBuilder h = new StringBuilder();
                h.append("HTTP/1.1 ").append(code == 206 ? "206 Partial Content" : "200 OK").append("\r\n");
                h.append("Content-Type: ").append(ct).append("\r\n");
                h.append("Accept-Ranges: bytes\r\n");
                if (cl != null && cl.length() > 0) {
                    h.append("Content-Length: ").append(cl).append("\r\n");
                }
                if (cr != null && cr.length() > 0) {
                    h.append("Content-Range: ").append(cr).append("\r\n");
                }
                h.append("Connection: close\r\n\r\n");
                out.write(h.toString().getBytes("UTF-8"));
                out.flush();

                InputStream in = body.byteStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                out.flush();
            } catch (IOException ignored) {
                // ExoPlayer closed socket early (next/stop/seek) — normal.
            } finally {
                closeQuietly(up);
            }
        } catch (Throwable t) {
            Log.w(TAG, "handleStream " + videoId + ": " + t);
        }
    }

    private static Response openUpstream(String url, String rangeHeader) {
        try {
            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .header("User-Agent", ANDROID_UA);
            if (rangeHeader != null && rangeHeader.length() > 0) {
                rb.header("Range", rangeHeader);
            }
            return client().newCall(rb.build()).execute();
        } catch (Throwable t) {
            Log.w(TAG, "openUpstream: " + t);
            return null;
        }
    }

    private static void writeErr(OutputStream out, String status, String msg) {
        try {
            byte[] b = msg.getBytes("UTF-8");
            String h = "HTTP/1.1 " + status + "\r\n"
                    + "Content-Type: text/plain; charset=utf-8\r\n"
                    + "Content-Length: " + b.length + "\r\n"
                    + "Connection: close\r\n\r\n";
            out.write(h.getBytes("UTF-8"));
            out.write(b);
            out.flush();
        } catch (Throwable ignored) {
        }
    }

    private static String cleanMime(String raw) {
        if (raw == null || raw.length() == 0) {
            return "audio/mp4";
        }
        int sc = raw.indexOf(';');
        return (sc > 0 ? raw.substring(0, sc) : raw).trim();
    }

    private static void closeQuietly(Response r) {
        if (r != null) {
            try {
                r.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void collectVideoRenderers(Object node, List<JSONObject> out,
                                              List<String> seen, int cap) {
        if (node == null || out.size() >= cap) {
            return;
        }
        if (node instanceof JSONObject) {
            JSONObject obj = (JSONObject) node;
            JSONObject vr = obj.optJSONObject("videoRenderer");
            if (vr != null) {
                String vid = vr.optString("videoId", "");
                String dur = extractText(vr.optJSONObject("lengthText"));
                // Skip livestreams (no duration) and duplicates.
                if (isValidVideoId(vid) && dur.length() > 0 && !seen.contains(vid)) {
                    String title = extractText(vr.optJSONObject("title"));
                    String artist = extractText(vr.optJSONObject("ownerText"));
                    if (artist.length() == 0) {
                        artist = extractText(vr.optJSONObject("longBylineText"));
                    }
                    if (title.length() > 0) {
                        try {
                            JSONObject it = new JSONObject();
                            it.put("title", title);
                            it.put("artist", artist);
                            it.put("source", "yt");
                            it.put("rid", vid);
                            it.put("album", "YouTube");
                            it.put("duration", dur);
                            it.put("pic", thumbUrl(vid));
                            out.add(it);
                            seen.add(vid);
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return;
            }
            Iterator<String> keys = obj.keys();
            while (keys.hasNext() && out.size() < cap) {
                String k = keys.next();
                Object v = obj.opt(k);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    collectVideoRenderers(v, out, seen, cap);
                }
            }
        } else if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length() && out.size() < cap; i++) {
                Object v = arr.opt(i);
                if (v instanceof JSONObject || v instanceof JSONArray) {
                    collectVideoRenderers(v, out, seen, cap);
                }
            }
        }
    }

    private static String extractText(JSONObject o) {
        if (o == null) {
            return "";
        }
        String s = o.optString("simpleText", "");
        if (s.length() > 0) {
            return s.trim();
        }
        JSONArray runs = o.optJSONArray("runs");
        if (runs == null || runs.length() == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject r = runs.optJSONObject(i);
            if (r != null) {
                sb.append(r.optString("text", ""));
            }
        }
        return sb.toString().trim();
    }
}
