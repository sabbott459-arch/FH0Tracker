package com.fh0tracker.companion;
import android.app.Activity;
import android.os.Bundle;
import android.webkit.WebView;
public class MainActivity extends Activity {
 @Override public void onCreate(Bundle b) {
  super.onCreate(b);
  WebView w = new WebView(this);
  w.getSettings().setJavaScriptEnabled(true);
  w.getSettings().setDomStorageEnabled(true);
  w.setWebViewClient(new android.webkit.WebViewClient());
  setContentView(w);
  w.loadUrl("https://sabbott459-arch.github.io/FH0Tracker/cloud.html");
 }
}
