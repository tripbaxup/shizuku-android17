package lat.gsports.player;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.JsResult;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoView;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MainActivity extends Activity {
    private static final String API_URL = "https://gsports.lat/?api";
    private static final String HOME_URL = "https://gsports.lat/";

    private static final int BG = Color.rgb(4, 8, 10);
    private static final int SURFACE = Color.rgb(12, 18, 23);
    private static final int SURFACE2 = Color.rgb(17, 25, 31);
    private static final int TEXT = Color.rgb(243, 247, 245);
    private static final int MUTED = Color.rgb(145, 158, 154);
    private static final int EMERALD = Color.rgb(64, 240, 140);
    private static final int BORDER = Color.rgb(30, 43, 48);

    private final Handler handler = new Handler(Looper.getMainLooper());

    private FrameLayout root;
    private LinearLayout page;
    private LinearLayout listBox;
    private TextView status;
    private TextView countText;
    private WebView apiView;
    private WebView homeView;
    private WebView playerWeb;
    private GeckoView geckoView;
    private GeckoSession geckoSession;
    private static GeckoRuntime geckoRuntime;
    private PlayerView nativePlayerView;
    private ExoPlayer nativePlayer;
    private FrameLayout playerShell;
    private LinearLayout loading;
    private TextView playerBack;

    private JSONArray apiEvents;
    private final Map<String, Meta> metaByUrl = new HashMap<>();
    private Uri eventUri;
    private String currentEventUrl;
    private String currentUserAgent;
    private volatile boolean nativePreparing;
    private final Set<String> attemptedStreams =
            Collections.synchronizedSet(new HashSet<>());
    private OnBackInvokedCallback backCallback;
    private boolean backRegistered;

    private static final class Meta {
        String time = "";
        String channel = "";
    }

    private final class LegacyBridge {
        @JavascriptInterface
        public void onStream(String url) {
            Log.i("RenegadeSports", "Legacy bridge stream: " + url);
            considerLegacyHlsSource(url);
        }

        @JavascriptInterface
        public void onLog(String message) {
            Log.i("RenegadeSports", "Legacy bridge: " + message);
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().getDecorView().setSystemUiVisibility(0);

        buildUi();
        applyInsets();
        configureApi();
        configureHome();

        apiView.loadUrl(API_URL);
        homeView.loadUrl(HOME_URL);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView tv(String value, float sp, int color) {
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        return v;
    }

    private GradientDrawable bg(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private GradientDrawable border(int color, int radius, int strokeColor) {
        GradientDrawable d = bg(color, radius);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private GradientDrawable gradient(int start, int end, int radius) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{start, end});
        d.setCornerRadius(dp(radius));
        return d;
    }

    private int accentFor(String league) {
        String l = league == null ? "" : league.toUpperCase(Locale.US);
        if ("NFL".equals(l)) return Color.rgb(121, 82, 255);
        if ("NBA".equals(l)) return Color.rgb(41, 161, 255);
        if ("MLB".equals(l)) return Color.rgb(255, 72, 96);
        if ("NHL".equals(l)) return Color.rgb(192, 203, 213);
        if ("UFC".equals(l) || "MMA".equals(l)) return Color.rgb(255, 84, 54);
        return EMERALD;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        setContentView(root);

        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        root.addView(page, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setOrientation(LinearLayout.HORIZONTAL);
        page.addView(header, new LinearLayout.LayoutParams(-1, -2));

        TextView logo = tv("R", 22, Color.rgb(3, 11, 8));
        logo.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(gradient(Color.rgb(86, 255, 157), Color.rgb(49, 203, 255), 16));
        header.addView(logo, new LinearLayout.LayoutParams(dp(52), dp(52)));

        LinearLayout brand = new LinearLayout(this);
        brand.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams brandLp = new LinearLayout.LayoutParams(0, -2, 1f);
        brandLp.leftMargin = dp(14);
        header.addView(brand, brandLp);

        TextView title = tv("Renegade Sports", 28, TEXT);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        brand.addView(title);

        TextView subtitle = tv("LIVE EVENT HUB", 10, EMERALD);
        subtitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        subtitle.setLetterSpacing(0.16f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-2, -2);
        subLp.topMargin = dp(5);
        brand.addView(subtitle, subLp);

        TextView refresh = tv("↻", 25, EMERALD);
        refresh.setGravity(Gravity.CENTER);
        refresh.setBackground(border(Color.rgb(9, 22, 17), 18, Color.rgb(31, 72, 52)));
        header.addView(refresh, new LinearLayout.LayoutParams(dp(48), dp(48)));
        refresh.setOnClickListener(v -> {
            status.setText("Refreshing live schedule…");
            apiView.reload();
            homeView.reload();
        });

        LinearLayout summary = new LinearLayout(this);
        summary.setOrientation(LinearLayout.HORIZONTAL);
        summary.setGravity(Gravity.CENTER_VERTICAL);
        summary.setPadding(dp(14), dp(12), dp(14), dp(12));
        summary.setBackground(border(Color.rgb(8, 18, 15), 16, Color.rgb(24, 53, 41)));
        LinearLayout.LayoutParams summaryLp = new LinearLayout.LayoutParams(-1, -2);
        summaryLp.topMargin = dp(18);
        page.addView(summary, summaryLp);

        summary.addView(tv("●", 12, EMERALD));
        countText = tv("Loading events", 14, TEXT);
        countText.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(0, -2, 1f);
        countLp.leftMargin = dp(8);
        summary.addView(countText, countLp);

        TextView live = tv("LIVE", 10, Color.rgb(3, 13, 8));
        live.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        live.setGravity(Gravity.CENTER);
        live.setPadding(dp(11), dp(6), dp(11), dp(6));
        live.setBackground(bg(EMERALD, 20));
        summary.addView(live);

        status = tv("Syncing schedule and stream metadata…", 13, MUTED);
        LinearLayout.LayoutParams statusLp = new LinearLayout.LayoutParams(-1, -2);
        statusLp.topMargin = dp(10);
        page.addView(status, statusLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        listBox = new LinearLayout(this);
        listBox.setOrientation(LinearLayout.VERTICAL);
        listBox.setPadding(0, dp(14), 0, dp(24));
        scroll.addView(listBox, new ScrollView.LayoutParams(-1, -2));
        page.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));

        apiView = hiddenWebView();
        homeView = hiddenWebView();

        playerShell = new FrameLayout(this);
        playerShell.setBackgroundColor(Color.BLACK);
        playerShell.setVisibility(View.GONE);
        root.addView(playerShell, new FrameLayout.LayoutParams(-1, -1));

        playerWeb = new WebView(this);
        playerWeb.setBackgroundColor(Color.BLACK);
        if (Build.VERSION.SDK_INT <= 27) {
            playerWeb.addJavascriptInterface(new LegacyBridge(), "RenegadeBridge");
        }
        playerShell.addView(playerWeb, new FrameLayout.LayoutParams(-1, -1));

        geckoView = new GeckoView(this);
        geckoView.setBackgroundColor(Color.BLACK);
        geckoView.setVisibility(View.GONE);
        playerShell.addView(geckoView, new FrameLayout.LayoutParams(-1, -1));

        nativePlayerView = new PlayerView(this);
        nativePlayerView.setBackgroundColor(Color.BLACK);
        nativePlayerView.setVisibility(View.GONE);
        playerShell.addView(nativePlayerView, new FrameLayout.LayoutParams(-1, -1));

        loading = new LinearLayout(this);
        loading.setOrientation(LinearLayout.VERTICAL);
        loading.setGravity(Gravity.CENTER);
        loading.setBackgroundColor(0xF0000000);

        ProgressBar spinner = new ProgressBar(this);
        loading.addView(spinner, new LinearLayout.LayoutParams(dp(52), dp(52)));

        TextView loadingText = tv("Opening stream…", 16, Color.WHITE);
        loadingText.setId(android.R.id.message);
        LinearLayout.LayoutParams lt = new LinearLayout.LayoutParams(-2, -2);
        lt.topMargin = dp(14);
        loading.addView(loadingText, lt);
        playerShell.addView(loading, new FrameLayout.LayoutParams(-1, -1));

        playerBack = tv("‹  Home", 15, Color.WHITE);
        playerBack.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        playerBack.setGravity(Gravity.CENTER);
        playerBack.setPadding(dp(14), 0, dp(14), 0);
        playerBack.setBackground(border(0xCC09100D, 18, Color.rgb(44, 65, 56)));
        FrameLayout.LayoutParams backLp =
                new FrameLayout.LayoutParams(-2, dp(44), Gravity.TOP | Gravity.START);
        backLp.setMargins(dp(10), dp(10), 0, 0);
        playerShell.addView(playerBack, backLp);
        playerBack.setOnClickListener(v -> closePlayer());
    }

    private WebView hiddenWebView() {
        WebView w = new WebView(this);
        w.setVisibility(View.GONE);
        root.addView(w, new FrameLayout.LayoutParams(1, 1));
        return w;
    }

    private void applyInsets() {
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            int top;
            int bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                top = insets.getInsets(WindowInsets.Type.statusBars()).top;
                bottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
            } else {
                top = insets.getSystemWindowInsetTop();
                bottom = insets.getSystemWindowInsetBottom();
            }

            page.setPadding(dp(18), top + dp(14), dp(18), bottom + dp(8));

            ViewGroup.LayoutParams params = playerBack.getLayoutParams();
            if (params instanceof FrameLayout.LayoutParams) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) params;
                lp.topMargin = top + dp(8);
                lp.leftMargin = dp(10);
                playerBack.setLayoutParams(lp);
            }
            return insets;
        });
        root.requestApplyInsets();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureApi() {
        WebSettings s = apiView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        apiView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(
                    WebView view, boolean dialog, boolean gesture,
                    android.os.Message resultMsg) {
                return false;
            }
        });

        apiView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                handler.postDelayed(MainActivity.this::readApi, 150);
            }
        });
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureHome() {
        WebSettings s = homeView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setLoadsImagesAutomatically(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);

        homeView.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(
                    WebView view, boolean dialog, boolean gesture,
                    android.os.Message resultMsg) {
                return false;
            }
        });

        homeView.setWebViewClient(new WebViewClient() {
            @Override public void onPageFinished(WebView view, String url) {
                handler.postDelayed(MainActivity.this::scrapeScheduleMetadata, 450);
            }
        });
    }

    private void readApi() {
        apiView.evaluateJavascript(
                "(function(){return document.body?document.body.innerText:'';})()",
                raw -> {
                    try {
                        String json = new JSONArray("[" + raw + "]").getString(0);
                        apiEvents = new JSONObject(json).getJSONArray("events");
                        renderEvents();
                    } catch (Exception e) {
                        status.setText("Could not load live events. Tap refresh to retry.");
                    }
                });
    }

    private void scrapeScheduleMetadata() {
        String js =
                "(function(){" +
                "function norm(s){return (s||'').replace(/\\s+/g,' ').trim();}" +
                "var networks=['NBA League Pass','NBA TV','NFL Network','MLB Network','ESPN2','ESPN','TNT','TBS','truTV','CBS','FOX','NBC','ABC','Prime Video'];" +
                "var seen={};var out=[];" +
                "document.querySelectorAll('a[href*=\"/event/\"]').forEach(function(a){" +
                "var u=a.href||'';if(!u||seen[u])return;seen[u]=1;" +
                "var c=a.closest('article,section,li,[class*=event],[class*=game],[class*=card]')||a.parentElement||a;" +
                "var text=norm(c.innerText||a.innerText);" +
                "var tm=text.match(/\\b(\\d{1,2}:\\d{2}\\s?(?:AM|PM))\\b/i);" +
                "var channel='';for(var i=0;i<networks.length;i++){if(text.indexOf(networks[i])>=0){channel=networks[i];break;}}" +
                "out.push({url:u,time:tm?tm[1].toUpperCase().replace(/\\s+/g,' '):'',channel:channel});" +
                "});return JSON.stringify(out);})();";

        homeView.evaluateJavascript(js, raw -> {
            try {
                String payload = new JSONArray("[" + raw + "]").getString(0);
                JSONArray arr = new JSONArray(payload);
                metaByUrl.clear();

                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    Meta m = new Meta();
                    m.time = o.optString("time", "");
                    m.channel = o.optString("channel", "");
                    String url = o.optString("url", "");
                    if (!url.isEmpty()) metaByUrl.put(url, m);
                }

                if (apiEvents != null) renderEvents();
            } catch (Exception ignored) {
            }
        });
    }

    private void renderEvents() throws Exception {
        if (apiEvents == null) return;

        listBox.removeAllViews();
        countText.setText(apiEvents.length() +
                (apiEvents.length() == 1 ? " live event" : " live events"));

        String lastLeague = "";

        for (int i = 0; i < apiEvents.length(); i++) {
            JSONObject item = apiEvents.getJSONObject(i);
            String title = item.optString("title");
            String league = item.optString("category", "Other").toUpperCase(Locale.US);
            String url = item.optString("url");

            if (title.isEmpty() || !url.startsWith("http")) continue;

            Meta meta = metaByUrl.get(url);
            String time = meta == null ? "" : meta.time;
            String channel = meta == null ? "" : meta.channel;
            int accent = accentFor(league);

            if (!league.equals(lastLeague)) {
                LinearLayout section = new LinearLayout(this);
                section.setGravity(Gravity.CENTER_VERTICAL);
                section.setPadding(dp(2), dp(14), dp(2), dp(10));

                section.addView(tv("●", 12, accent));

                TextView heading = tv(league, 13, TEXT);
                heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                heading.setLetterSpacing(0.08f);
                LinearLayout.LayoutParams h =
                        new LinearLayout.LayoutParams(-2, -2);
                h.leftMargin = dp(8);
                section.addView(heading, h);

                listBox.addView(section);
                lastLeague = league;
            }

            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setBackground(border(SURFACE, 20, BORDER));
            card.setElevation(dp(2));

            View accentBar = new View(this);
            GradientDrawable barBg = new GradientDrawable();
            barBg.setColor(accent);
            float r = dp(20);
            barBg.setCornerRadii(new float[]{r, r, 0, 0, 0, 0, r, r});
            accentBar.setBackground(barBg);
            card.addView(accentBar, new LinearLayout.LayoutParams(dp(5), -1));

            LinearLayout timeBox = new LinearLayout(this);
            timeBox.setOrientation(LinearLayout.VERTICAL);
            timeBox.setGravity(Gravity.CENTER_HORIZONTAL);
            timeBox.setPadding(dp(12), dp(18), dp(8), dp(18));
            card.addView(timeBox, new LinearLayout.LayoutParams(dp(104), -1));

            TextView timeView = tv(time.isEmpty() ? "LIVE" : time,
                    time.isEmpty() ? 13 : 17,
                    time.isEmpty() ? accent : Color.rgb(185, 196, 201));
            timeView.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            timeView.setGravity(Gravity.CENTER);
            timeBox.addView(timeView);

            TextView leagueChip = tv(league, 11, Color.WHITE);
            leagueChip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            leagueChip.setGravity(Gravity.CENTER);
            leagueChip.setPadding(dp(12), dp(7), dp(12), dp(7));
            leagueChip.setBackground(bg(accent, 12));
            LinearLayout.LayoutParams chip =
                    new LinearLayout.LayoutParams(-2, -2);
            chip.topMargin = dp(11);
            timeBox.addView(leagueChip, chip);

            LinearLayout body = new LinearLayout(this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setGravity(Gravity.CENTER_VERTICAL);
            body.setPadding(dp(8), dp(18), dp(6), dp(18));
            card.addView(body, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView game = tv(title, 18, TEXT);
            game.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            game.setLineSpacing(0, 1.04f);
            body.addView(game);

            if (!channel.isEmpty()) {
                TextView channelPill = tv(channel, 12, Color.rgb(180, 193, 199));
                channelPill.setPadding(dp(10), dp(6), dp(10), dp(6));
                channelPill.setBackground(border(SURFACE2, 10, Color.rgb(40, 52, 59)));
                LinearLayout.LayoutParams ch =
                        new LinearLayout.LayoutParams(-2, -2);
                ch.topMargin = dp(12);
                body.addView(channelPill, ch);
            }

            TextView arrow = tv("›", 26, Color.rgb(92, 111, 116));
            arrow.setGravity(Gravity.CENTER);
            arrow.setPadding(dp(4), 0, dp(12), 0);
            card.addView(arrow, new LinearLayout.LayoutParams(dp(40), -1));

            LinearLayout.LayoutParams cardLp =
                    new LinearLayout.LayoutParams(-1, dp(128));
            cardLp.setMargins(0, 0, 0, dp(12));
            listBox.addView(card, cardLp);

            card.setOnClickListener(v -> openPlayer(title, url));
        }

        status.setText("Schedule synced • tap any event to watch");
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void openPlayer(String title, String url) {
        eventUri = Uri.parse(url);
        currentEventUrl = url;
        attemptedStreams.clear();
        nativePreparing = false;
        releaseNativePlayer();
        registerBackHandler();

        if (Build.VERSION.SDK_INT <= 27) {
            openGeckoPlayer(title, url);
            return;
        }

        playerShell.setVisibility(View.VISIBLE);
        nativePlayerView.setVisibility(View.GONE);
        playerWeb.setVisibility(View.VISIBLE);
        playerWeb.setAlpha(1f);
        playerWeb.onResume();
        playerWeb.resumeTimers();
        loading.setVisibility(View.VISIBLE);
        ((TextView) loading.findViewById(android.R.id.message))
                .setText("Opening “" + title + "”…");

        WebSettings s = playerWeb.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setBuiltInZoomControls(false);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        currentUserAgent = s.getUserAgentString();

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(playerWeb, true);

        playerWeb.setDownloadListener((u, ua, cd, mt, len) -> {});

        playerWeb.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null) {
                    Log.i("RenegadeSports",
                            "WEB " + message.messageLevel() + " " +
                                    message.sourceId() + ":" + message.lineNumber() +
                                    " :: " + message.message());
                }
                return super.onConsoleMessage(message);
            }

            @Override public boolean onCreateWindow(
                    WebView view, boolean dialog, boolean gesture,
                    android.os.Message resultMsg) {
                return false;
            }

            @Override public boolean onJsAlert(
                    WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }

            @Override public boolean onJsConfirm(
                    WebView view, String url, String message, JsResult result) {
                result.cancel();
                return true;
            }
        });

        playerWeb.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(
                    WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (uri == null) return true;
                if (!request.isForMainFrame()) return false;
                return !allowedMainFrame(uri);
            }

            @Override public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                if (Build.VERSION.SDK_INT <= 27 && request != null &&
                        request.getUrl() != null) {
                    String requestUrl = request.getUrl().toString();
                    String lower = requestUrl.toLowerCase(Locale.US);

                    if (lower.contains("hls.js")) {
                        handler.post(() ->
                                ((TextView) loading.findViewById(android.R.id.message))
                                        .setText("Loading Android 8.1 compatibility player…"));
                        Log.i("RenegadeSports", "Serving bundled legacy hls.js for " + requestUrl);
                        return legacyHlsShimResponse();
                    }

                    considerLegacyMedia(
                            requestUrl,
                            request.getRequestHeaders());

                    if (shouldProbeLegacyResource(requestUrl)) {
                        probeLegacyResource(
                                requestUrl,
                                request.getRequestHeaders());
                    }
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override public void onPageFinished(WebView view, String loadedUrl) {
                if (loadedUrl == null || !loadedUrl.startsWith("http")) return;

                if (Build.VERSION.SDK_INT <= 27) {
                    scanLegacyPage();
                    handler.postDelayed(MainActivity.this::scanLegacyPage, 1200);
                    handler.postDelayed(() -> {
                        if (!nativePreparing &&
                                playerShell.getVisibility() == View.VISIBLE) {
                            ((TextView) loading.findViewById(android.R.id.message))
                                    .setText("Locating Android 8.1 stream…");
                        }
                    }, 4500);
                    return;
                }

                lockToPlayer();

                handler.postDelayed(() -> {
                    if (playerShell.getVisibility() == View.VISIBLE) {
                        loading.setVisibility(View.GONE);
                        startSitePlayer();
                    }
                }, 350);
            }
        });

        playerWeb.stopLoading();
        playerWeb.clearHistory();
        playerWeb.loadUrl(url);

        if (Build.VERSION.SDK_INT <= 27) {
            resolveLegacyHtml(url);
        }
    }

    private void openGeckoPlayer(String title, String url) {
        Log.i("RenegadeSports",
                "Android 8.1: using embedded GeckoView instead of system WebView");

        playerShell.setVisibility(View.VISIBLE);
        playerWeb.setVisibility(View.GONE);
        nativePlayerView.setVisibility(View.GONE);
        geckoView.setVisibility(View.VISIBLE);
        loading.setVisibility(View.VISIBLE);

        ((TextView) loading.findViewById(android.R.id.message))
                .setText("Opening Android 8.1 modern player…");

        try {
            if (geckoSession != null) {
                geckoSession.close();
                geckoSession = null;
            }

            if (geckoRuntime == null) {
                geckoRuntime = GeckoRuntime.create(getApplicationContext());
            }

            GeckoSession session = new GeckoSession();
            geckoSession = session;

            session.setContentDelegate(new GeckoSession.ContentDelegate() {});

            session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
                @Override
                public GeckoResult<Integer> onContentPermissionRequest(
                        GeckoSession requestedSession,
                        GeckoSession.PermissionDelegate.ContentPermission perm) {
                    if (perm.permission ==
                            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                            perm.permission ==
                            GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE) {
                        return GeckoResult.fromValue(
                                GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
                    }

                    return GeckoResult.fromValue(
                            GeckoSession.PermissionDelegate.ContentPermission.VALUE_PROMPT);
                }
            });

            session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
                @Override
                public GeckoResult<GeckoSession> onNewSession(
                        GeckoSession sourceSession,
                        String popupUri) {
                    Log.i("RenegadeSports",
                            "Blocked Gecko popup: " + popupUri);
                    return null;
                }
            });

            session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
                @Override
                public void onPageStart(
                        GeckoSession loadedSession,
                        String loadedUrl) {
                    Log.i("RenegadeSports",
                            "Gecko page start: " + loadedUrl);
                }

                @Override
                public void onPageStop(
                        GeckoSession loadedSession,
                        boolean success) {
                    Log.i("RenegadeSports",
                            "Gecko page stop success=" + success);
                    if (playerShell.getVisibility() == View.VISIBLE &&
                            geckoSession == loadedSession) {
                        loading.setVisibility(View.GONE);
                    }
                }
            });

            session.open(geckoRuntime);
            geckoView.setSession(session);
            session.loadUri(url);
        } catch (Throwable error) {
            Log.e("RenegadeSports",
                    "GeckoView startup failed", error);
            ((TextView) loading.findViewById(android.R.id.message))
                    .setText("Android 8.1 browser engine failed to start");
        }
    }

    private void considerLegacyHlsSource(String url) {
        if (Build.VERSION.SDK_INT > 27 || url == null) return;

        String clean = url.replace("\\/", "/").trim();
        if (clean.isEmpty()) return;

        try {
            if (clean.startsWith("//")) {
                clean = "https:" + clean;
            } else if (!clean.startsWith("http://") &&
                    !clean.startsWith("https://")) {
                clean = new URL(
                        new URL(currentEventUrl == null ? HOME_URL : currentEventUrl),
                        clean).toString();
            }
        } catch (Exception ignored) {
            return;
        }

        final String resolved = clean;
        synchronized (attemptedStreams) {
            if (attemptedStreams.contains(resolved)) return;
            attemptedStreams.add(resolved);
        }

        runOnUiThread(() ->
                startNativeStream(resolved, Collections.emptyMap()));
    }

    private WebResourceResponse legacyHlsShimResponse() {
        StringBuilder js = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(
                            getAssets().open("hls-0.14.17.min.js")));
            String line;
            while ((line = reader.readLine()) != null) {
                js.append(line).append('\n');
            }
            reader.close();

            js.append(
                    ";(function(){" +
                    "try{" +
                    "if(!window.Hls||!Hls.prototype)return;" +
                    "if(Hls.prototype.__renegadeWrapped)return;" +
                    "var original=Hls.prototype.loadSource;" +
                    "Hls.prototype.loadSource=function(u){" +
                    "try{if(window.RenegadeBridge){" +
                    "RenegadeBridge.onLog('Hls.loadSource '+String(u));" +
                    "RenegadeBridge.onStream(String(u));" +
                    "}}catch(e){}" +
                    "return original.apply(this,arguments);" +
                    "};" +
                    "Hls.prototype.__renegadeWrapped=true;" +
                    "try{if(window.RenegadeBridge)" +
                    "RenegadeBridge.onLog('legacy hls.js 0.14.17 loaded');}catch(e){}" +
                    "}catch(e){" +
                    "try{if(window.RenegadeBridge)" +
                    "RenegadeBridge.onLog('legacy hls patch failed: '+e);}catch(x){}" +
                    "}" +
                    "})();");
        } catch (Exception e) {
            Log.e("RenegadeSports", "Could not load bundled legacy hls.js", e);

            js.setLength(0);
            js.append(
                    "(function(){" +
                    "function Hls(){};" +
                    "Hls.isSupported=function(){return true;};" +
                    "Hls.Events={MEDIA_ATTACHED:'hlsMediaAttached'," +
                    "MANIFEST_PARSED:'hlsManifestParsed',ERROR:'hlsError'};" +
                    "Hls.prototype.on=function(){return this;};" +
                    "Hls.prototype.attachMedia=function(){};" +
                    "Hls.prototype.loadSource=function(u){" +
                    "try{if(window.RenegadeBridge)" +
                    "RenegadeBridge.onStream(String(u));}catch(e){}" +
                    "};" +
                    "window.Hls=Hls;" +
                    "})();");
        }

        Map<String, String> headers = new HashMap<>();
        headers.put("Access-Control-Allow-Origin", "*");
        headers.put("Cache-Control", "no-store");
        headers.put("Content-Type", "application/javascript; charset=UTF-8");

        return new WebResourceResponse(
                "application/javascript",
                "UTF-8",
                200,
                "OK",
                headers,
                new ByteArrayInputStream(js.toString().getBytes()));
    }

    private boolean shouldProbeLegacyResource(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        if (lower.startsWith("data:") || lower.startsWith("blob:")) return false;

        return lower.contains("stream") ||
                lower.contains("player") ||
                lower.contains("source") ||
                lower.contains("manifest") ||
                lower.contains("playlist") ||
                lower.contains("event") ||
                lower.contains("api") ||
                lower.endsWith(".js") ||
                lower.contains(".js?");
    }

    private void probeLegacyResource(
            String resourceUrl,
            Map<String, String> requestHeaders) {
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                URL target = new URL(resourceUrl);
                connection = (HttpURLConnection) target.openConnection();
                connection.setInstanceFollowRedirects(true);
                connection.setConnectTimeout(7000);
                connection.setReadTimeout(7000);

                if (currentUserAgent != null && !currentUserAgent.isEmpty()) {
                    connection.setRequestProperty("User-Agent", currentUserAgent);
                }
                if (currentEventUrl != null) {
                    connection.setRequestProperty("Referer", currentEventUrl);
                }

                String cookie = CookieManager.getInstance().getCookie(resourceUrl);
                if (cookie != null && !cookie.isEmpty()) {
                    connection.setRequestProperty("Cookie", cookie);
                }

                if (requestHeaders != null) {
                    for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                        String key = entry.getKey();
                        String value = entry.getValue();
                        if (key == null || value == null) continue;
                        if ("Host".equalsIgnoreCase(key) ||
                                "Connection".equalsIgnoreCase(key) ||
                                "Content-Length".equalsIgnoreCase(key)) continue;
                        try {
                            connection.setRequestProperty(key, value);
                        } catch (Exception ignored) {
                        }
                    }
                }

                String contentType = connection.getContentType();
                if (contentType != null) {
                    String ct = contentType.toLowerCase(Locale.US);
                    if (!(ct.contains("javascript") ||
                            ct.contains("json") ||
                            ct.contains("text") ||
                            ct.contains("xml") ||
                            ct.contains("mpegurl"))) {
                        return;
                    }
                }

                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(connection.getInputStream()));
                StringBuilder body = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null &&
                        body.length() < 1_500_000) {
                    body.append(line).append('\n');
                }
                reader.close();

                String candidate =
                        findPlayerSourceInText(body.toString(), resourceUrl);
                if (candidate != null) {
                    Log.i("RenegadeSports",
                            "Legacy resource source: " + candidate);
                    considerLegacyHlsSource(candidate);
                }
            } catch (Exception ignored) {
            } finally {
                if (connection != null) connection.disconnect();
            }
        }, "renegade-resource-probe").start();
    }

    private boolean isMediaManifest(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);
        return lower.contains(".m3u8") || lower.contains(".mpd");
    }

    private void considerLegacyMedia(String url, Map<String, String> requestHeaders) {
        if (Build.VERSION.SDK_INT > 27 || !isMediaManifest(url)) return;

        String clean = url.replace("\\/", "/");
        synchronized (attemptedStreams) {
            if (attemptedStreams.contains(clean)) return;
            attemptedStreams.add(clean);
        }

        Map<String, String> copy = new HashMap<>();
        if (requestHeaders != null) copy.putAll(requestHeaders);

        runOnUiThread(() -> startNativeStream(clean, copy));
    }

    private void resolveLegacyHtml(String eventUrl) {
        new Thread(() -> {
            Set<String> visited = new LinkedHashSet<>();
            boolean found = crawlLegacyResource(eventUrl, 0, visited);

            if (!found) {
                Log.w("RenegadeSports",
                        "Native legacy resolver exhausted " +
                                visited.size() + " resource(s) without a stream");
                handler.post(() -> {
                    if (Build.VERSION.SDK_INT <= 27 &&
                            playerShell.getVisibility() == View.VISIBLE &&
                            !nativePreparing) {
                        ((TextView) loading.findViewById(android.R.id.message))
                                .setText("Legacy player config not found yet…");
                    }
                });
            }
        }, "renegade-native-crawler").start();
    }

    private boolean crawlLegacyResource(
            String resourceUrl,
            int depth,
            Set<String> visited) {
        if (Build.VERSION.SDK_INT > 27 ||
                resourceUrl == null ||
                depth > 3 ||
                visited.size() >= 28) {
            return false;
        }

        String resolved = resolveLegacyUrl(
                resourceUrl,
                currentEventUrl == null ? HOME_URL : currentEventUrl);
        if (resolved == null || visited.contains(resolved)) return false;
        if (!shouldFollowLegacyUrl(resolved, depth)) return false;

        visited.add(resolved);

        String body = fetchLegacyText(resolved);
        if (body == null || body.isEmpty()) return false;

        Log.i("RenegadeSports",
                "Native resolver scanned depth=" + depth +
                        " bytes=" + body.length() +
                        " url=" + resolved);

        String media = findPlayerSourceInText(body, resolved);
        if (media != null) {
            Log.i("RenegadeSports",
                    "Native resolver found stream: " + media);
            considerLegacyHlsSource(media);
            return true;
        }

        List<String> links = extractLegacyFollowLinks(body, resolved);
        int followed = 0;

        for (String link : links) {
            if (followed >= 12) break;
            if (crawlLegacyResource(link, depth + 1, visited)) {
                return true;
            }
            followed++;
        }

        return false;
    }

    private String fetchLegacyText(String resourceUrl) {
        HttpURLConnection connection = null;
        try {
            URL target = new URL(resourceUrl);
            connection = (HttpURLConnection) target.openConnection();
            connection.setInstanceFollowRedirects(true);
            connection.setConnectTimeout(9000);
            connection.setReadTimeout(9000);
            connection.setRequestProperty(
                    "User-Agent",
                    currentUserAgent == null ?
                            "Mozilla/5.0 (Linux; Android 8.1) AppleWebKit/537.36 Chrome/79 Mobile Safari/537.36" :
                            currentUserAgent);
            connection.setRequestProperty(
                    "Accept",
                    "text/html,application/json,text/javascript,application/javascript,application/xml,text/plain,*/*;q=0.8");

            if (currentEventUrl != null) {
                connection.setRequestProperty("Referer", currentEventUrl);
            }

            String cookie =
                    CookieManager.getInstance().getCookie(resourceUrl);
            if (cookie != null && !cookie.isEmpty()) {
                connection.setRequestProperty("Cookie", cookie);
            }

            int response = connection.getResponseCode();
            if (response < 200 || response >= 400) {
                Log.w("RenegadeSports",
                        "Native resolver HTTP " + response +
                                " for " + resourceUrl);
                return null;
            }

            String contentType = connection.getContentType();
            if (contentType != null) {
                String ct = contentType.toLowerCase(Locale.US);
                if (ct.startsWith("image/") ||
                        ct.startsWith("video/") ||
                        ct.startsWith("audio/") ||
                        ct.contains("font") ||
                        ct.contains("octet-stream")) {
                    return null;
                }
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream()));
            StringBuilder text = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null &&
                    text.length() < 2_500_000) {
                text.append(line).append('\n');
            }
            reader.close();
            return text.toString();
        } catch (Exception e) {
            Log.w("RenegadeSports",
                    "Native resolver fetch failed for " + resourceUrl +
                            ": " + e.getClass().getSimpleName() +
                            " " + e.getMessage());
            return null;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private String findPlayerSourceInText(String raw, String baseUrl) {
        return findPlayerSourceInText(raw, baseUrl, 0);
    }

    private String findPlayerSourceInText(
            String raw,
            String baseUrl,
            int decodeDepth) {
        if (raw == null) return null;

        String text = raw
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u002F", "/")
                .replace("\\u003A", ":")
                .replace("&amp;", "&");

        Pattern absoluteManifest = Pattern.compile(
                "https?://[^\\\"'<>\\s]+?\\.(?:m3u8|mpd)(?:\\?[^\\\"'<>\\s]*)?",
                Pattern.CASE_INSENSITIVE);
        Matcher absoluteMatcher = absoluteManifest.matcher(text);
        if (absoluteMatcher.find()) return absoluteMatcher.group();

        Pattern relativeManifest = Pattern.compile(
                "[\\\"']([^\\\"']+\\.(?:m3u8|mpd)(?:\\?[^\\\"']*)?)[\\\"']",
                Pattern.CASE_INSENSITIVE);
        Matcher relativeMatcher = relativeManifest.matcher(text);
        if (relativeMatcher.find()) {
            String resolved = resolveLegacyUrl(
                    relativeMatcher.group(1),
                    baseUrl);
            if (resolved != null) return resolved;
        }

        String[] playerPatterns = new String[]{
                "(?is)loadSource\\s*\\(\\s*[\\\"']([^\\\"']+)[\\\"']",
                "(?is)(?:streamUrl|stream_url|hlsUrl|hls_url|playlistUrl|playlist_url|manifestUrl|manifest_url)\\s*[:=]\\s*[\\\"']([^\\\"']+)[\\\"']",
                "(?is)(?:stream|playlist|source|file)\\s*[:=]\\s*[\\\"']([^\\\"']+)[\\\"']"
        };

        for (String expression : playerPatterns) {
            Matcher matcher = Pattern.compile(expression).matcher(text);
            while (matcher.find()) {
                String resolved =
                        resolveLegacyUrl(matcher.group(1), baseUrl);
                if (looksLikeLegacyMediaSource(resolved)) {
                    return resolved;
                }
            }
        }

        if (decodeDepth < 2) {
            Pattern base64Pattern = Pattern.compile(
                    "(?:atob\\s*\\(\\s*[\\\"']|base64,)([A-Za-z0-9+/=]{24,})",
                    Pattern.CASE_INSENSITIVE);
            Matcher base64Matcher = base64Pattern.matcher(text);

            int decoded = 0;
            while (base64Matcher.find() && decoded < 8) {
                try {
                    byte[] bytes = Base64.decode(
                            base64Matcher.group(1),
                            Base64.DEFAULT);
                    String nested = new String(bytes);
                    String candidate =
                            findPlayerSourceInText(
                                    nested,
                                    baseUrl,
                                    decodeDepth + 1);
                    if (candidate != null) return candidate;
                } catch (Exception ignored) {
                }
                decoded++;
            }
        }

        return null;
    }

    private boolean looksLikeLegacyMediaSource(String url) {
        if (url == null || url.isEmpty()) return false;

        String lower = url.toLowerCase(Locale.US);

        if (lower.endsWith(".js") ||
                lower.contains(".js?") ||
                lower.endsWith(".css") ||
                lower.contains(".css?") ||
                lower.matches(".*\\.(png|jpg|jpeg|gif|svg|webp|woff2?|ttf)(\\?.*)?$")) {
            return false;
        }

        return lower.contains(".m3u8") ||
                lower.contains(".mpd") ||
                lower.contains("/stream") ||
                lower.contains("stream=") ||
                lower.contains("/live") ||
                lower.contains("playlist") ||
                lower.contains("manifest") ||
                lower.contains("/hls") ||
                lower.contains("master.");
    }

    private List<String> extractLegacyFollowLinks(
            String raw,
            String baseUrl) {
        LinkedHashSet<String> links = new LinkedHashSet<>();
        if (raw == null) return new ArrayList<>(links);

        String text = raw
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("&amp;", "&");

        String[] expressions = new String[]{
                "(?is)<script[^>]+src\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']",
                "(?is)(?:fetch|axios\\.get)\\s*\\(\\s*[\\\"']([^\\\"']+)[\\\"']",
                "(?is)\\.open\\s*\\(\\s*[\\\"']GET[\\\"']\\s*,\\s*[\\\"']([^\\\"']+)[\\\"']",
                "(?is)(?:configUrl|config_url|apiUrl|api_url|playerUrl|player_url)\\s*[:=]\\s*[\\\"']([^\\\"']+)[\\\"']"
        };

        for (String expression : expressions) {
            Matcher matcher = Pattern.compile(expression).matcher(text);
            while (matcher.find() && links.size() < 24) {
                String resolved =
                        resolveLegacyUrl(matcher.group(1), baseUrl);
                if (resolved != null) links.add(resolved);
            }
        }

        Pattern absoluteKeywordUrl = Pattern.compile(
                "https?://[^\\\"'<>\\s]+",
                Pattern.CASE_INSENSITIVE);
        Matcher absoluteMatcher = absoluteKeywordUrl.matcher(text);
        while (absoluteMatcher.find() && links.size() < 24) {
            String candidate = absoluteMatcher.group();
            String lower = candidate.toLowerCase(Locale.US);
            if (lower.contains("api") ||
                    lower.contains("player") ||
                    lower.contains("stream") ||
                    lower.contains("config") ||
                    lower.contains("source") ||
                    lower.contains("playlist")) {
                links.add(candidate);
            }
        }

        return new ArrayList<>(links);
    }

    private String resolveLegacyUrl(String value, String baseUrl) {
        if (value == null) return null;

        String clean = value.trim()
                .replace("\\/", "/")
                .replace("&amp;", "&");

        if (clean.isEmpty() ||
                clean.startsWith("javascript:") ||
                clean.startsWith("data:") ||
                clean.startsWith("blob:") ||
                clean.startsWith("#")) {
            return null;
        }

        try {
            if (clean.startsWith("//")) {
                return "https:" + clean;
            }
            if (clean.startsWith("http://") ||
                    clean.startsWith("https://")) {
                return clean;
            }
            return new URL(
                    new URL(baseUrl == null ? HOME_URL : baseUrl),
                    clean).toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private boolean shouldFollowLegacyUrl(
            String url,
            int depth) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.US);

        if (lower.contains("sharethis") ||
                lower.contains("adsco.re") ||
                lower.contains("adscore") ||
                lower.contains("simpli.fi") ||
                lower.contains("crwdcntrl") ||
                lower.contains("lijit") ||
                lower.contains("intentiq") ||
                lower.contains("doubleclick") ||
                lower.contains("googlesyndication") ||
                lower.contains("google-analytics") ||
                lower.contains("facebook") ||
                lower.contains("hls.js")) {
            return false;
        }

        if (looksLikeLegacyMediaSource(url)) return true;

        try {
            String host = new URL(url).getHost();
            if (host != null &&
                    (host.equalsIgnoreCase("gsports.lat") ||
                            host.endsWith(".gsports.lat"))) {
                return true;
            }
        } catch (Exception ignored) {
        }

        if (depth == 0) return true;

        return lower.contains("api") ||
                lower.contains("player") ||
                lower.contains("config") ||
                lower.contains("stream") ||
                lower.contains("source");
    }

    private void scanLegacyPage() {
        if (Build.VERSION.SDK_INT > 27 ||
                playerShell.getVisibility() != View.VISIBLE) return;

        String js =
                "(function(){" +
                "var a=[];" +
                "try{var e=(performance&&performance.getEntriesByType)?performance.getEntriesByType('resource'):[];" +
                "for(var i=0;i<e.length;i++){var n=e[i].name||'';if(/\\.(m3u8|mpd)(\\?|$)/i.test(n))a.push(n);}}catch(x){}" +
                "try{var h=document.documentElement?document.documentElement.innerHTML:'';" +
                "var m=h.match(/https?:[^\\\"'<>\\s]+\\.(?:m3u8|mpd)(?:\\?[^\\\"'<>\\s]*)?/ig);" +
                "if(m){for(var j=0;j<m.length;j++)a.push(m[j]);}}catch(x){}" +
                "return JSON.stringify(a);" +
                "})()";

        playerWeb.evaluateJavascript(js, raw -> {
            try {
                String payload = new JSONArray("[" + raw + "]").getString(0);
                JSONArray arr = new JSONArray(payload);
                for (int i = 0; i < arr.length(); i++) {
                    considerLegacyMedia(arr.optString(i), Collections.emptyMap());
                }
            } catch (Exception ignored) {
            }
        });
    }

    private void startNativeStream(String streamUrl, Map<String, String> captured) {
        if (Build.VERSION.SDK_INT > 27 ||
                playerShell.getVisibility() != View.VISIBLE) return;

        nativePreparing = true;
        releaseNativePlayer();

        Map<String, String> headers = new HashMap<>();
        if (currentUserAgent != null && !currentUserAgent.isEmpty()) {
            headers.put("User-Agent", currentUserAgent);
        }
        if (currentEventUrl != null) headers.put("Referer", currentEventUrl);
        headers.put("Accept", "*/*");

        String cookie = CookieManager.getInstance().getCookie(streamUrl);
        if (cookie != null && !cookie.isEmpty()) headers.put("Cookie", cookie);

        if (captured != null) {
            copyHeader(captured, headers, "Origin");
            copyHeader(captured, headers, "Referer");
            copyHeader(captured, headers, "User-Agent");
            copyHeader(captured, headers, "Cookie");
        }

        DefaultHttpDataSource.Factory http =
                new DefaultHttpDataSource.Factory()
                        .setAllowCrossProtocolRedirects(true)
                        .setDefaultRequestProperties(headers);
        DefaultDataSource.Factory data =
                new DefaultDataSource.Factory(this, http);

        nativePlayer = new ExoPlayer.Builder(this)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(data))
                .build();

        nativePlayerView.setPlayer(nativePlayer);
        nativePlayerView.setVisibility(View.VISIBLE);
        playerWeb.setVisibility(View.INVISIBLE);
        loading.setVisibility(View.VISIBLE);
        ((TextView) loading.findViewById(android.R.id.message))
                .setText("Starting Android 8.1 native player…");

        String lower = streamUrl.toLowerCase(Locale.US);
        MediaItem item = new MediaItem.Builder()
                .setUri(streamUrl)
                .setMimeType(lower.contains(".mpd") ?
                        MimeTypes.APPLICATION_MPD :
                        MimeTypes.APPLICATION_M3U8)
                .build();

        nativePlayer.setMediaItem(item);
        nativePlayer.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_READY) {
                    nativePreparing = false;
                    loading.setVisibility(View.GONE);
                    playerWeb.stopLoading();
                    nativePlayer.play();
                }
            }

            @Override public void onPlayerError(PlaybackException error) {
                Log.e("RenegadeSports",
                        "Native player error: " +
                                (error == null ? "unknown" :
                                        error.getErrorCodeName() + " " + error.getMessage()),
                        error);
                handler.post(() -> {
                    nativePreparing = false;
                    releaseNativePlayer();
                    nativePlayerView.setVisibility(View.GONE);
                    loading.setVisibility(View.VISIBLE);
                    ((TextView) loading.findViewById(android.R.id.message))
                            .setText("Trying another Android 8.1 stream source…");
                    scanLegacyPage();
                });
            }
        });

        nativePlayer.prepare();
        nativePlayer.play();
    }

    private void copyHeader(
            Map<String, String> from,
            Map<String, String> to,
            String wanted) {
        for (Map.Entry<String, String> entry : from.entrySet()) {
            if (wanted.equalsIgnoreCase(entry.getKey()) &&
                    entry.getValue() != null) {
                to.put(wanted, entry.getValue());
                return;
            }
        }
    }

    private void releaseNativePlayer() {
        if (nativePlayer != null) {
            nativePlayer.release();
            nativePlayer = null;
        }
        if (nativePlayerView != null) nativePlayerView.setPlayer(null);
    }

    private boolean allowedMainFrame(Uri uri) {
        if (eventUri == null || uri == null) return false;

        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) &&
                !"http".equalsIgnoreCase(scheme)) return false;

        String host = uri.getHost();
        if (host == null || !host.equalsIgnoreCase(eventUri.getHost()))
            return false;

        String path = uri.getPath();
        String eventPath = eventUri.getPath();
        return path != null && eventPath != null && path.equals(eventPath);
    }

    private void lockToPlayer() {
        String js =
                "(function(){" +
                "var p=document.querySelector('#player');if(!p)return;" +
                "var st=document.getElementById('__rs_lock');" +
                "if(!st){st=document.createElement('style');st.id='__rs_lock';" +
                "st.textContent='html,body{margin:0!important;padding:0!important;width:100%!important;height:100%!important;background:#000!important;overflow:hidden!important}body>*:not(#player){display:none!important}#player{display:block!important;position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;z-index:2147483646!important;background:#000!important}';document.head.appendChild(st);}" +
                "})();";
        playerWeb.evaluateJavascript(js, null);
    }

    private void startSitePlayer() {
        String js =
                "(function(){" +
                "var v=document.querySelector('video');" +
                "if(v&&v.paused){try{var q=v.play();if(q&&q.catch)q.catch(function(){});}catch(e){}}" +
                "var b=document.querySelector('[data-playstop].stopped,[data-play].paused,.play-wrapper');" +
                "if((!v||v.paused)&&b){try{b.click();}catch(e){}}" +
                "})();";
        playerWeb.evaluateJavascript(js, null);
    }

    private void registerBackHandler() {
        if (Build.VERSION.SDK_INT >= 33 && !backRegistered) {
            backCallback = this::closePlayer;
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback);
            backRegistered = true;
        }
    }

    private void unregisterBackHandler() {
        if (Build.VERSION.SDK_INT >= 33 &&
                backRegistered && backCallback != null) {
            getOnBackInvokedDispatcher()
                    .unregisterOnBackInvokedCallback(backCallback);
            backRegistered = false;
            backCallback = null;
        }
    }

    private void closePlayer() {
        unregisterBackHandler();
        nativePreparing = false;
        releaseNativePlayer();
        nativePlayerView.setVisibility(View.GONE);

        if (geckoSession != null) {
            try {
                geckoSession.close();
            } catch (Throwable ignored) {
            }
            geckoSession = null;
        }
        if (geckoView != null) {
            geckoView.setVisibility(View.GONE);
        }

        playerWeb.stopLoading();
        playerWeb.loadUrl("about:blank");
        playerWeb.pauseTimers();
        playerWeb.onPause();
        playerShell.setVisibility(View.GONE);
        loading.setVisibility(View.GONE);
        currentEventUrl = null;
        eventUri = null;
    }

    @Override public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK &&
                playerShell.getVisibility() == View.VISIBLE) {
            closePlayer();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override public void onBackPressed() {
        if (playerShell.getVisibility() == View.VISIBLE) closePlayer();
        else super.onBackPressed();
    }

    @Override protected void onStop() {
        super.onStop();
        if (nativePlayer != null) nativePlayer.pause();
    }

    @Override protected void onDestroy() {
        unregisterBackHandler();
        releaseNativePlayer();
        if (geckoSession != null) {
            try {
                geckoSession.close();
            } catch (Throwable ignored) {
            }
            geckoSession = null;
        }
        if (apiView != null) apiView.destroy();
        if (homeView != null) homeView.destroy();
        if (playerWeb != null) playerWeb.destroy();
        super.onDestroy();
    }
}
