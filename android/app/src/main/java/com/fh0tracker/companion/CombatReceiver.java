package com.fh0tracker.companion;

import android.app.Notification;
import android.widget.RemoteViews;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONObject;
import org.json.JSONArray;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;

/** Notification action receiver; serializes cloud writes to avoid revision collisions. */
public class CombatReceiver extends BroadcastReceiver {
 private static final Object MUTEX=new Object();
 private static final String CHANNEL="combat_silent_v4";
 static void display(Context context,String name,int hp,int xp) {
  NotificationManager manager=(NotificationManager)context.getSystemService(Context.NOTIFICATION_SERVICE);
  if(Build.VERSION.SDK_INT>=26) {
   NotificationChannel channel=new NotificationChannel(CHANNEL,"FH0Tracker combat controls",NotificationManager.IMPORTANCE_LOW);
   channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
   channel.enableVibration(false);
   channel.setSound(null,null);
   manager.createNotificationChannel(channel);
  }
  Intent launch=new Intent(context,NativeActivity.class);
  PendingIntent open=PendingIntent.getActivity(context,0,launch,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
  Notification.Builder n=new Notification.Builder(context,CHANNEL)
   .setSmallIcon(android.R.drawable.ic_menu_edit)
   .setContentTitle("FH0Tracker · "+name)
   .setContentText("HP "+hp+"   |   XP "+xp)
   .setStyle(new Notification.BigTextStyle().bigText("Health: "+hp+"     Scenario XP: "+xp))
   .setContentIntent(open)
   .setVisibility(Notification.VISIBILITY_PUBLIC)
   .setCategory(Notification.CATEGORY_STATUS)
   .setOngoing(true)
   .setShowWhen(false)
   .setOnlyAlertOnce(true)
   .setSilent(true);
  RemoteViews expanded=new RemoteViews(context.getPackageName(),R.layout.notification_combat);
  expanded.setTextViewText(R.id.combat_title,"FH0Tracker · "+name);
  expanded.setTextViewText(R.id.hp_value,String.valueOf(hp));
  expanded.setTextViewText(R.id.xp_value,String.valueOf(xp));
  int[] controls={R.id.hp_down,R.id.hp_up,R.id.xp_down,R.id.xp_up};
  String[] labels={"HP −","HP +","XP −","XP +"};
  for(int i=0;i<4;i++) {
   Intent action=new Intent(context,CombatReceiver.class).setAction("com.fh0tracker.companion.ADJUST").putExtra("index",i);
   PendingIntent pending=PendingIntent.getBroadcast(context,100+i,action,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
   expanded.setOnClickPendingIntent(controls[i],pending);
   n.addAction(android.R.drawable.ic_menu_edit,labels[i],pending);
  }
  n.setCustomBigContentView(expanded);
  n.setStyle(new Notification.DecoratedCustomViewStyle());
  manager.notify(77,n.build());
 }
 @Override public void onReceive(Context context,Intent intent) {
  if(!"com.fh0tracker.companion.ADJUST".equals(intent.getAction()))return;
  int index=intent.getIntExtra("index",-1);
  if(index<0||index>3)return;
  PendingResult result=goAsync();
  new Thread(()->{
   synchronized(MUTEX){
    try{adjust(context.getApplicationContext(),index);}
    catch(Exception e){new Handler(Looper.getMainLooper()).post(()->android.widget.Toast.makeText(context,"Combat update failed; open FH0Tracker to refresh.",android.widget.Toast.LENGTH_LONG).show());}
    finally{result.finish();}
   }
  }).start();
 }
 private static JSONObject rpc(String base,String key,String name,JSONObject body)throws Exception {
  HttpURLConnection c=(HttpURLConnection)new URL(base+"/rest/v1/rpc/"+name).openConnection();
  try{
   c.setRequestMethod("POST");c.setConnectTimeout(12000);c.setReadTimeout(12000);
   c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("apikey",key);c.setRequestProperty("Authorization","Bearer "+key);
   c.setDoOutput(true);
   try(OutputStream out=c.getOutputStream()){out.write(body.toString().getBytes("UTF-8"));}
   ByteArrayOutputStream bytes=new ByteArrayOutputStream();
   try(InputStream in=c.getResponseCode()<400?c.getInputStream():c.getErrorStream()){
    byte[] buffer=new byte[4096];int n;while((n=in.read(buffer))!=-1)bytes.write(buffer,0,n);
   }
   String response=bytes.toString("UTF-8");
   if(c.getResponseCode()>=400)throw new Exception(response);
   return new JSONObject(response);
  }finally{c.disconnect();}
 }
 private static void adjust(Context context,int index)throws Exception {
  android.content.SharedPreferences prefs=context.getSharedPreferences("combat_private",Context.MODE_PRIVATE);
  String token=prefs.getString("token",""),base=prefs.getString("base",""),key=prefs.getString("key",""),id=prefs.getString("id","");
  long expires=prefs.getLong("expires",0);
  if(token.isEmpty()||base.isEmpty()||key.isEmpty()||id.isEmpty()||System.currentTimeMillis()>expires)throw new Exception("Session expired");
  for(int attempt=0;attempt<3;attempt++) {
   JSONObject dashboard=rpc(base,key,"fh_dashboard",new JSONObject().put("p_token",token));
   JSONArray chars=dashboard.getJSONArray("characters");
   JSONObject character=null;
   for(int i=0;i<chars.length();i++)if(id.equals(chars.getJSONObject(i).optString("id"))) {character=chars.getJSONObject(i);break;}
   if(character==null)throw new Exception("Character unavailable");
   JSONObject data=character.getJSONObject("data");
   String field=index<2?"hp":"scenarioXp";
   int old=data.optInt(field,0),delta=index%2==0?-1:1;
   int max=field.equals("hp")?data.optInt("maxHpOverride",99):99999;
   if(max<=0)max=99;
   int next=Math.max(0,Math.min(max,old+delta));
   if(next==old){display(context,data.optString("name","Character"),data.optInt("hp"),data.optInt("scenarioXp"));return;}
   data.put(field,next);
   try{
    rpc(base,key,"fh_save_character",new JSONObject().put("p_token",token).put("p_character_id",id).put("p_revision",character.getInt("revision")).put("p_data",data));
    display(context,data.optString("name","Character"),data.optInt("hp"),data.optInt("scenarioXp"));
    context.sendBroadcast(new Intent("com.fh0tracker.companion.STATS_CHANGED").setPackage(context.getPackageName()));
    return;
   }catch(Exception conflict){if(attempt==2)throw conflict;}
  }
 }
}
