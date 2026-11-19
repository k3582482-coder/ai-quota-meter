package com.aiquota.meter;

import android.app.Activity;
import android.os.Bundle;
import android.view.Window;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

public class MainActivity extends Activity {

    private WebView webView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Window window = getWindow();
        window.setStatusBarColor(0xFF0B0C10);
        window.setNavigationBarColor(0xFF0B0C10);

        webView = new WebView(this);
        webView.setBackgroundColor(0xFF0B0C10);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);

        webView.setWebViewClient(new WebViewClient());
        webView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        setContentView(webView);

        webView.loadUrl("file:///android_asset/index.html");
    }

    @Override
    protected void onResume() {
        super.onResume();
        QuotaWidgetProvider.updateAllWidgets(this);
    }

    private class AndroidBridge {
        @JavascriptInterface
        public String getState() {
            return QuotaStore.getNormalizedState(MainActivity.this).toString();
        }

        @JavascriptInterface
        public String getSyncUrl() {
            return QuotaStore.getSyncUrl(MainActivity.this);
        }

        @JavascriptInterface
        public void saveState(String json, String syncUrl) {
            QuotaStore.saveSyncUrl(MainActivity.this, syncUrl);
            QuotaStore.saveStateJson(MainActivity.this, json);
            QuotaWidgetProvider.updateAllWidgets(MainActivity.this);
            QuotaStore.pushToCloudAsync(json, syncUrl);
        }

        @JavascriptInterface
        public void syncNow() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final boolean ok = QuotaStore.syncFromCloudBlocking(MainActivity.this);
                    QuotaWidgetProvider.updateAllWidgets(MainActivity.this);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (webView != null) {
                                webView.evaluateJavascript("window.onNativeSynced && window.onNativeSynced(" + ok + ")", null);
                            }
                        }
                    });
                }
            }).start();
        }
    }
}
