package com.fh0tracker.companion;

import android.app.Activity;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.JavascriptInterface;
import android.widget.LinearLayout;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;

public class MainActivity extends Activity {
 private WebView web;
 private long pendingDownload = -1;
 private final BroadcastReceiver downloaded = new BroadcastReceiver() {
  @Override public void onReceive(Context context, Intent intent) {
   if (!DownloadManager.ACTION_DOWNLOAD_COMPLETE.equals(intent.getAction())) return;
   long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
   if (id != pendingDownload) return;
   DownloadManager manager = (DownloadManager)getSystemService(DOWNLOAD_SERVICE);
   Uri uri = manager.getUriForDownloadedFile(id);
   if (uri == null) { toast("Download failed. Try again."); return; }
   try {
    Intent install = new Intent(Intent.ACTION_VIEW);
    install.setDataAndType(uri, "application/vnd.android.package-archive");
    install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    startActivity(install);
   } catch (Exception e) { toast("Open Downloads to install the update."); }
  }
 };
 private void toast(String message) { runOnUiThread(() -> Toast.makeText(this,message,Toast.LENGTH_LONG).show()); }
 @Override public void onCreate(Bundle state) {
  super.onCreate(state);
  LinearLayout root = new LinearLayout(this);
  root.setOrientation(LinearLayout.VERTICAL);
  root.setBackgroundColor(0xff101820);
  web = new WebView(this);
  web.setBackgroundColor(0xff101820);
  web.getSettings().setJavaScriptEnabled(true);
  web.getSettings().setDomStorageEnabled(true);
  web.setWebViewClient(new WebViewClient());
  web.addJavascriptInterface(new UpdateBridge(), "FHAppUpdate");
  root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
  setContentView(root);
  if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloaded,new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),Context.RECEIVER_NOT_EXPORTED);
  else registerReceiver(downloaded,new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
  web.loadUrl("https://sabbott459-arch.github.io/FH0Tracker/cloud.html");
 }
 public class UpdateBridge {
  @JavascriptInterface public void check() { runOnUiThread(() -> checkUpdate()); }
 }
 private void checkUpdate() {
  toast("Checking GitHub releases...");
  new Thread(() -> {
   HttpURLConnection connection = null;
   try {
    connection=(HttpURLConnection)new URL("https://api.github.com/repos/sabbott459-arch/FH0Tracker/releases?per_page=20").openConnection();
    connection.setRequestProperty("Accept","application/vnd.github+json");
    connection.setRequestProperty("User-Agent","FH0Tracker-Android");
    connection.setConnectTimeout(12000);
    connection.setReadTimeout(12000);
    if(connection.getResponseCode()!=200) throw new Exception("GitHub returned "+connection.getResponseCode());
    ByteArrayOutputStream out=new ByteArrayOutputStream();
    try(InputStream in=connection.getInputStream()) { byte[] buf=new byte[8192];int n;while((n=in.read(buf))!=-1)out.write(buf,0,n); }
    JSONArray releases=new JSONArray(out.toString("UTF-8"));
    int installed=getPackageManager().getPackageInfo(getPackageName(),0).versionCode;
    for(int i=0;i<releases.length();i++) {
     JSONObject release=releases.getJSONObject(i);
     String tag=release.optString("tag_name","");
     if(!tag.startsWith("android-build-"))continue;
     int remote;
     try {remote=Integer.parseInt(tag.substring("android-build-".length()));}catch(Exception ignored){continue;}
     if(remote<=installed) {toast("You're already on the latest Android build.");return;}
     JSONArray assets=release.optJSONArray("assets");
     if(assets==null)continue;
     for(int j=0;j<assets.length();j++){
      JSONObject asset=assets.getJSONObject(j);
      if(asset.optString("name","").endsWith(".apk")){
       String url=asset.getString("browser_download_url");
       runOnUiThread(() -> downloadApk(url));
       return;
      }
     }
    }
    toast("No newer APK found.");
   }catch(Exception e){toast("Update check failed: "+e.getMessage());}
   finally{if(connection!=null)connection.disconnect();}
  }).start();
 }
 private void downloadApk(String url) {
  try {
   DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
   request.setTitle("FH0Tracker Android update");
   request.setDescription("Download and install the latest Android APK");
   request.setMimeType("application/vnd.android.package-archive");
   request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
   request.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,"FH0Tracker-update.apk");
   pendingDownload=((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
   toast("Downloading update. Android will ask permission before installation.");
  }catch(Exception e){toast("Cannot download update: "+e.getMessage());}
 }
 @Override protected void onDestroy(){unregisterReceiver(downloaded);if(web!=null)web.destroy();super.onDestroy();}
 @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
}
