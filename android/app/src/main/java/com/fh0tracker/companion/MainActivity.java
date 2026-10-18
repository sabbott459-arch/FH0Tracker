package com.fh0tracker.companion;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.Manifest;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.app.AlertDialog;
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
import android.webkit.WebResourceRequest;
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
   android.database.Cursor cursor=manager.query(new DownloadManager.Query().setFilterById(id));
   try {
    if(!cursor.moveToFirst() || cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))!=DownloadManager.STATUS_SUCCESSFUL){toast("Update download failed. Please retry.");return;}
   }finally{cursor.close();}
   Uri uri=manager.getUriForDownloadedFile(id);
   if(uri==null){toast("Downloaded APK unavailable. Open Downloads.");return;}
   promptInstall(uri);
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
  web.setWebViewClient(new WebViewClient(){
   @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest request){return !"sabbott459-arch.github.io".equals(request.getUrl().getHost());}
  });
  web.addJavascriptInterface(new UpdateBridge(), "FHAppUpdate");
  root.addView(web,new LinearLayout.LayoutParams(-1,0,1));
  setContentView(root);
  if (Build.VERSION.SDK_INT >= 33) registerReceiver(downloaded,new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),Context.RECEIVER_EXPORTED);
  else registerReceiver(downloaded,new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE));
  if(Build.VERSION.SDK_INT>=26){NotificationManager nm=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);NotificationChannel channel=new NotificationChannel("combat_v2","Frosthaven combat tracker",NotificationManager.IMPORTANCE_DEFAULT);channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);nm.createNotificationChannel(channel);}
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},42);
  web.loadUrl("https://sabbott459-arch.github.io/FH0Tracker/cloud.html");
 }
 public class UpdateBridge {
  @JavascriptInterface public void check() { runOnUiThread(() -> checkUpdate()); }
  @JavascriptInterface public void showStats(String name,int hp,int xp) { runOnUiThread(() -> publishStats(name,hp,xp)); }
  @JavascriptInterface public void configureCombat(String token,String id,String base,String key,int maxHp) {
   if(!base.equals("https://aowmwqnrzxxgqubhxopc.supabase.co")||token.length()>512||id.length()>100)return;
   getSharedPreferences("combat_private",MODE_PRIVATE).edit().putString("token",token).putString("id",id).putString("base",base).putString("key",key).putInt("maxHp",maxHp).putLong("expires",System.currentTimeMillis()+4*60*60*1000L).apply();
  }
  @JavascriptInterface public void hideStats() { getSharedPreferences("combat_private",MODE_PRIVATE).edit().clear().apply();runOnUiThread(() -> ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).cancel(77)); }
 }
 private void publishStats(String name,int hp,int xp) {
  if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
  CombatReceiver.display(this,name,hp,xp);
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
    int latest = installed;
    String latestUrl = null;
    for(int i=0;i<releases.length();i++) {
     JSONObject release=releases.getJSONObject(i);
     String tag=release.optString("tag_name","");
     if(!tag.startsWith("android-build-"))continue;
     int remote;
     try {remote=Integer.parseInt(tag.substring("android-build-".length()));}catch(Exception ignored){continue;}
     if(remote<=latest)continue;
     JSONArray assets=release.optJSONArray("assets");
     if(assets==null)continue;
     for(int j=0;j<assets.length();j++) {
      JSONObject asset=assets.getJSONObject(j);
      if(asset.optString("name","").endsWith(".apk")) {
       latest=remote;
       latestUrl=asset.getString("browser_download_url");
       break;
      }
     }
    }
    if(latestUrl==null) {
     toast("You're up to date (build "+installed+").");
    } else {
     final String url=latestUrl;
     final int newest=latest;
     runOnUiThread(() -> {
      new android.app.AlertDialog.Builder(this)
       .setTitle("FH0Tracker update available")
       .setMessage("Installed build: "+installed+"\nNew build: "+newest+"\n\nDownload and install now?")
       .setNegativeButton("Not now",null)
       .setPositiveButton("Update", (dialog,which) -> downloadApk(url))
       .show();
     });
    }
   }catch(Exception e){toast("Update check failed: "+e.getMessage());}
   finally{if(connection!=null)connection.disconnect();}
  }).start();
 }
 private void promptInstall(Uri uri) {
  if(Build.VERSION.SDK_INT>=26&&!getPackageManager().canRequestPackageInstalls()){
   new AlertDialog.Builder(this).setTitle("Allow FH0Tracker updates")
    .setMessage("Android requires permission for FH0Tracker to install downloaded APK updates. Enable Allow from this source, then return to the app.")
    .setNegativeButton("Cancel",null)
    .setPositiveButton("Open settings",(d,w)->startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName()))))
    .show();
   return;
  }
  try{
   Intent install=new Intent(Intent.ACTION_VIEW);
   install.setDataAndType(uri,"application/vnd.android.package-archive");
   install.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_ACTIVITY_NEW_TASK);
   startActivity(install);
  }catch(Exception e){toast("Installer couldn't open. Tap the completed APK in Downloads.");}
 }
 private void downloadApk(String url) {
  try {
   DownloadManager.Request request=new DownloadManager.Request(Uri.parse(url));
   request.setTitle("FH0Tracker Android update");
   request.setDescription("Download and install the latest Android APK");
   request.setMimeType("application/vnd.android.package-archive");
   request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
   request.setDestinationInExternalFilesDir(this,Environment.DIRECTORY_DOWNLOADS,"FH0Tracker-update-"+System.currentTimeMillis()+".apk");
   pendingDownload=((DownloadManager)getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
   toast("Downloading update. Android will ask permission before installation.");
  }catch(Exception e){toast("Cannot download update: "+e.getMessage());}
 }
 @Override protected void onDestroy(){unregisterReceiver(downloaded);if(web!=null)web.destroy();super.onDestroy();}
 @Override public void onBackPressed(){if(web!=null&&web.canGoBack())web.goBack();else super.onBackPressed();}
}
