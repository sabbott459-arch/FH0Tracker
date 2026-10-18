package com.fh0tracker.companion

import android.content.Intent
import android.net.Uri
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import kotlin.math.atan2
import kotlin.math.PI

private val Night=Color(0xFF101820)
private val Panel=Color(0xFF1C2B35)
private val Gold=Color(0xFFE7BD7D)
private val Red=Color(0xFFF06A71)
private val Blue=Color(0xFF64BAFF)
private const val BASE="https://aowmwqnrzxxgqubhxopc.supabase.co"
private const val KEY="sb_publishable_j0-QYkBrBYecHjm01Dqtvw_4E424d8q"
private val Resources=listOf("Gold","Lumber","Metal","Hide","Arrowvine","Axenut","Corpsecap","Flamefruit","Rockroot","Snowthistle")

private fun rpc(name:String,body:JSONObject):JSONObject {
    val conn=URL("$BASE/rest/v1/rpc/$name").openConnection() as HttpURLConnection
    try {
        conn.requestMethod="POST";conn.connectTimeout=12000;conn.readTimeout=12000;conn.doOutput=true
        conn.setRequestProperty("Content-Type","application/json")
        conn.setRequestProperty("apikey",KEY)
        conn.setRequestProperty("Authorization","Bearer $KEY")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val stream=if(conn.responseCode<400)conn.inputStream else conn.errorStream
        val response=stream.bufferedReader().use { it.readText() }
        if(conn.responseCode>=400)throw IllegalStateException(JSONObject(response).optString("message","Cloud error ${conn.responseCode}"))
        return JSONObject(response)
    }finally{conn.disconnect()}
}
private fun maxHp(data:JSONObject):Int {
    val override=data.optInt("maxHpOverride",0)
    if(override>0)return override
    val high=setOf("banner-spear","drifter","fist")
    val low=setOf("boneshaper","deathwalker")
    val profile=when(data.optString("classId")){in high->intArrayOf(10,12,14,16,18,20,22,24,26);in low->intArrayOf(6,7,8,9,10,11,12,13,14);else->intArrayOf(8,9,11,12,14,15,17,18,20)}
    return profile[(data.optInt("level",1)-1).coerceIn(0,8)]
}

class NativeActivity:ComponentActivity(){
    private val store by lazy {getSharedPreferences("native_session",MODE_PRIVATE)}
    private var token by mutableStateOf("")
    private var characterId by mutableStateOf("")
    private var revision by mutableIntStateOf(0)
    private var data by mutableStateOf(JSONObject())
    private var campaign by mutableStateOf("")
    private var status by mutableStateOf("Enter your campaign code and PIN")
    private var loading by mutableStateOf(false)
    private var tab by mutableStateOf("Combat")
    private var suppliesPage by mutableIntStateOf(0)
    private var perkPage by mutableIntStateOf(0)
    private var perkSection by mutableStateOf("masteries")
    private val classReference by lazy {JSONObject(assets.open("fh_class_reference.json").bufferedReader().use {it.readText()})}
    private var pendingWrites=0
    private val network=Executors.newSingleThreadExecutor()
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        window.statusBarColor=android.graphics.Color.rgb(16,24,32)
        window.navigationBarColor=android.graphics.Color.rgb(16,24,32)
        if(android.os.Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),1)
        token=store.getString("token","")?:""
        if(token.isNotEmpty())refresh()
        setContent { MaterialTheme(colorScheme=darkColorScheme(primary=Gold,background=Night,surface=Panel,onSurface=Color.White)){
            Surface(Modifier.fillMaxSize(),color=Night){Screen()}
        }}
    }
    override fun onResume(){super.onResume();if(token.isNotEmpty())refresh()}
    override fun onDestroy(){network.shutdown();super.onDestroy()}
    private fun work(block:()->Unit){network.execute {try{block()}catch(e:Exception){runOnUiThread{status=e.message?:"Network error";loading=false;pendingWrites=0;refresh()}}}}
    private fun login(code:String,pin:String){
        if(!Regex("[A-Z0-9]{4}").matches(code)||!Regex("[0-9]{4}").matches(pin)){status="Use a 4-character code and 4-digit PIN";return}
        loading=true
        work {
            val response=rpc("fh_join",JSONObject().put("p_suffix",code).put("p_pin",pin))
            if(response.optString("status")=="new_player")throw IllegalStateException("Create your character using the web tracker first; then sign in here.")
            val t=response.getString("token")
            runOnUiThread{token=t;store.edit().putString("token",t).putString("code",code).apply()}
            fetchDashboard(t)
        }
    }
    private fun fetchDashboard(t:String){
        val result=rpc("fh_dashboard",JSONObject().put("p_token",t))
        if(result.optString("role")=="manager")throw IllegalStateException("Manager accounts are not yet supported in the native app.")
        val ch=result.getJSONArray("characters").getJSONObject(0)
        runOnUiThread {
            characterId=ch.getString("id");revision=ch.getInt("revision");data=ch.getJSONObject("data")
            campaign=result.optString("name","Campaign")
            loading=false;status="Cloud synced"
            store.edit().putString("characterId",characterId).apply()
            getSharedPreferences("combat_private",MODE_PRIVATE).edit().putString("token",t).putString("id",characterId).putString("base",BASE).putString("key",KEY).putInt("maxHp",maxHp(data)).putLong("expires",System.currentTimeMillis()+4*60*60*1000L).apply()
            notifyCombat()
        }
    }
    private fun refresh(){
        if(token.isEmpty()||pendingWrites>0)return
        work {fetchDashboard(token)}
    }
    private fun notifyCombat(){
        if(android.os.Build.VERSION.SDK_INT<33||checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED)
            CombatReceiver.display(this,data.optString("name","Character"),data.optInt("hp"),data.optInt("scenarioXp"))
    }
    private fun change(field:String,delta:Int){
        if(characterId.isEmpty())return
        val before=data.optInt(field,0)
        val upper=when(field){"hp"->maxHp(data);"checks"->18;else->99999}
        val after=(before+delta).coerceIn(0,upper)
        if(after==before)return
        val copy=JSONObject(data.toString()).put(field,after)
        data=copy;status="Saving…"
        pendingWrites++
        val id=characterId
        work {
            // Always read the newest revision to avoid overwriting notification edits.
            val dashboard=rpc("fh_dashboard",JSONObject().put("p_token",token))
            val characters=dashboard.getJSONArray("characters")
            var remote:JSONObject?=null
            for(i in 0 until characters.length())if(characters.getJSONObject(i).optString("id")==id){remote=characters.getJSONObject(i);break}
            val ch=remote?:throw IllegalStateException("Character missing")
            val payload=ch.getJSONObject("data")
            val latest=payload.optInt(field,0)
            payload.put(field,(latest+delta).coerceIn(0,upper))
            rpc("fh_save_character",JSONObject().put("p_token",token).put("p_character_id",id).put("p_revision",ch.getInt("revision")).put("p_data",payload))
            runOnUiThread{pendingWrites--;if(pendingWrites==0)refresh();status="Saved to cloud"}
        }
        notifyCombat()
    }
    private fun changeResource(name:String,delta:Int){
        val resources=data.optJSONObject("resources")?:JSONObject()
        val newValue=(resources.optInt(name)+delta).coerceAtLeast(0)
        val copy=JSONObject(data.toString());copy.getJSONObject("resources").put(name,newValue);data=copy
        pendingWrites++
        work {
            val dashboard=rpc("fh_dashboard",JSONObject().put("p_token",token))
            val ch=dashboard.getJSONArray("characters").getJSONObject(0)
            val payload=ch.getJSONObject("data");val remoteResources=payload.getJSONObject("resources")
            remoteResources.put(name,(remoteResources.optInt(name)+delta).coerceAtLeast(0))
            rpc("fh_save_character",JSONObject().put("p_token",token).put("p_character_id",characterId).put("p_revision",ch.getInt("revision")).put("p_data",payload))
            runOnUiThread{pendingWrites--;if(pendingWrites==0)refresh();status="Saved to cloud"}
        }
    }
    private fun classSheet():JSONObject?=classReference.optJSONObject(data.optString("classId"))
    private fun resolveText(raw:String,sheet:JSONObject):String{
        val labels=sheet.optJSONObject("labels")?:JSONObject()
        var result=raw
        repeat(3){result=Regex("%data\\.custom\\.fh\\.[^.]+\\.(\\d+)%").replace(result){m->labels.optString(m.groupValues[1],"Class effect")}}
        result=result.replace(Regex("<br\\s*/?>",RegexOption.IGNORE_CASE)," ").replace(Regex("<[^>]+>")," ")
        result=Regex("%game\\.([\\w.-]+)(?::([^%]*))?%").replace(result){m->
            val key=m.groupValues[1];val arg=m.groupValues[2]
            when {
                key.contains("ignoreScenario")->"Ignore negative scenario effects"
                key.contains("ignoreNegativeItem")->"Ignore negative item effects"
                key.startsWith("element.consume.")->"Consume "+key.substringAfterLast('.').replaceFirstChar{it.uppercase()}
                key.startsWith("condition.")->key.substringAfterLast('.').replaceFirstChar{it.uppercase()}
                key.startsWith("action.range")->"Range "+arg
                key.contains("valueSign")->if(arg.startsWith("-"))arg else "+$arg"
                else->key.substringAfterLast('.').replaceFirstChar{it.uppercase()}+(if(arg.isNotEmpty())" $arg" else "")
            }
        }
        return result.replace(Regex("%[^%]+%"),"").replace(Regex("\\s+")," ").trim()
    }
    private fun effectDescription(effect:JSONObject,sheet:JSONObject):String{
        val type=effect.optString("type");val value=effect.optString("value")
        return when(type){
            "custom"->resolveText(value,sheet)
            "elementHalf"->"Infuse "+value.replace("|"," or ")
            "condition"->value.replaceFirstChar{it.uppercase()}
            "specialTarget"->if(value=="allyShort")"one ally" else value
            "element"->"Infuse "+value.replaceFirstChar{it.uppercase()}
            else->type.replaceFirstChar{it.uppercase()}+(if(value.isNotBlank())" $value" else "")
        }
    }
    private fun cardDescription(card:JSONObject,sheet:JSONObject):String{
        val mod=card.optJSONObject("attackModifier")?:JSONObject()
        val value=mod.optString("type").replace("plus","+").replace("minus","−")
        val effects=mod.optJSONArray("effects")?:JSONArray()
        val extra=(0 until effects.length()).map{effectDescription(effects.getJSONObject(it),sheet)}.filter{it.isNotBlank()}
        return "${card.optInt("count",1)}× "+(if(mod.optBoolean("rolling"))"Rolling " else "")+value+(if(extra.isNotEmpty())" · "+extra.joinToString(", ") else "")
    }
    private fun perkDescription(perk:JSONObject,sheet:JSONObject):String{
        val type=perk.optString("type")
        val cards=perk.optJSONArray("cards")?:JSONArray()
        val items=(0 until cards.length()).map{cardDescription(cards.getJSONObject(it),sheet)}
        val base=when(type){
            "replace"->"Remove "+(items.firstOrNull()?:"modifier")+"; add "+items.drop(1).joinToString(" and ")
            "remove"->if(items.isEmpty())"" else "Remove "+items.joinToString(" and ")
            "add"->if(items.isEmpty())"" else "Add "+items.joinToString(" and ")
            else->""
        }
        val special=resolveText(perk.optString("custom"),sheet)
        return listOf(base,special).filter{it.isNotBlank()}.joinToString(". ").ifBlank{"Perk details unavailable"}
    }
    private fun toggleChecklist(kind:String,index:Int,label:String){
        val copy=JSONObject(data.toString())
        val entries=copy.optJSONArray(kind)?:JSONArray().also{copy.put(kind,it)}
        while(entries.length()<=index)entries.put(JSONObject().put("text","").put("done",false))
        val item=entries.getJSONObject(index)
        val checked=!item.optBoolean("done")
        item.put("done",checked);item.put("text",label)
        data=copy;pendingWrites++
        work {
            val dashboard=rpc("fh_dashboard",JSONObject().put("p_token",token))
            val ch=dashboard.getJSONArray("characters").getJSONObject(0)
            val payload=ch.getJSONObject("data")
            val remote=payload.optJSONArray(kind)?:JSONArray().also{payload.put(kind,it)}
            while(remote.length()<=index)remote.put(JSONObject().put("text","").put("done",false))
            val remoteItem=remote.getJSONObject(index)
            remoteItem.put("done",!remoteItem.optBoolean("done"));remoteItem.put("text",label)
            rpc("fh_save_character",JSONObject().put("p_token",token).put("p_character_id",characterId).put("p_revision",ch.getInt("revision")).put("p_data",payload))
            runOnUiThread{pendingWrites--;if(pendingWrites==0)refresh();status="Saved to cloud"}
        }
    }
    private fun signOut(){
        token="";characterId="";data=JSONObject();store.edit().clear().apply();getSharedPreferences("combat_private",MODE_PRIVATE).edit().clear().apply()
        (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(77)
        status="Signed out"
    }
    @Composable private fun Screen(){
        var code by remember {mutableStateOf(store.getString("code","")?:"")}
        var pin by remember {mutableStateOf("")}
        Column(Modifier.fillMaxSize().padding(WindowInsets.systemBars.asPaddingValues()).padding(horizontal=12.dp,vertical=6.dp)){
            if(characterId.isEmpty()){
                Spacer(Modifier.weight(1f))
                Text("FH0TRACKER",fontSize=30.sp,fontWeight=FontWeight.Black,color=Gold)
                Text("Native Frosthaven companion",color=Color.LightGray)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(code,{code=it.uppercase().take(4)},label={Text("Campaign code (last 4)")},singleLine=true,modifier=Modifier.fillMaxWidth())
                OutlinedTextField(pin,{pin=it.filter(Char::isDigit).take(4)},label={Text("4-digit PIN")},visualTransformation=PasswordVisualTransformation(),keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.NumberPassword),singleLine=true,modifier=Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button(onClick={login(code,pin)},enabled=!loading,modifier=Modifier.fillMaxWidth()){Text(if(loading)"Connecting…" else "Enter campaign")}
                Text(status,color=Color.LightGray,fontSize=12.sp)
                Spacer(Modifier.weight(1f))
            }else{
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                    Column{Text(data.optString("name","Character"),fontWeight=FontWeight.Bold,fontSize=21.sp,color=Gold)
                        Text("$campaign · Level ${data.optInt("level",1)}",fontSize=12.sp,color=Color.LightGray)}
                    Text(status,fontSize=10.sp,color=Color.LightGray)
                }
                Spacer(Modifier.height(10.dp))
                Box(Modifier.weight(1f).fillMaxWidth()){
                    when(tab){
                        "Combat"->Combat()
                        "Supplies"->Supplies()
                        "Perks"->Perks()
                        else->Settings(::signOut)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(3.dp)){
                    listOf("Combat","Supplies","Perks","Settings").forEach { name->
                        Button(onClick={tab=name},modifier=Modifier.weight(1f),contentPadding=PaddingValues(horizontal=1.dp,vertical=8.dp),colors=ButtonDefaults.buttonColors(containerColor=if(tab==name)Gold else Panel,contentColor=if(tab==name)Night else Color.White)){
                            Text(name,fontSize=11.sp,maxLines=1)
                        }
                    }
                }
            }
        }
    }
    @Composable private fun Combat(){
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.SpaceEvenly){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)){
                Dial("HEALTH",data.optInt("hp"),maxHp(data),Red,Modifier.weight(1f)){change("hp",it)}
                Dial("SCENARIO XP",data.optInt("scenarioXp"),99999,Blue,Modifier.weight(1f)){change("scenarioXp",it)}
            }
            StatCard("Campaign XP",data.optInt("campaignXp")){change("campaignXp",it)}
            StatCard("Perk checks",data.optInt("checks")){change("checks",it)}
            Text("Turn a wheel clockwise to add, counterclockwise to subtract.",fontSize=11.sp,color=Color.LightGray,textAlign=TextAlign.Center,modifier=Modifier.fillMaxWidth())
        }
    }
    @Composable private fun Dial(label:String,value:Int,max:Int,color:Color,modifier:Modifier=Modifier,onStep:(Int)->Unit){
        val vibrator=remember{getSystemService(Context.VIBRATOR_SERVICE) as Vibrator}
        var degrees by remember{mutableFloatStateOf(0f)}
        var lastAngle by remember{mutableFloatStateOf(Float.NaN)}
        Column(modifier, horizontalAlignment=Alignment.CenterHorizontally){
            Text(label,fontSize=11.sp,fontWeight=FontWeight.Bold,color=color)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().aspectRatio(1f).pointerInput(Unit){
                detectDragGestures(onDragStart={lastAngle=Float.NaN;degrees=0f},onDragEnd={lastAngle=Float.NaN}){
                    change,_-> val center=Offset(size.width/2f,size.height/2f)
                    val pos=change.position
                    val angle=(atan2(pos.y-center.y,pos.x-center.x)*180/PI).toFloat()
                    if(!lastAngle.isNaN()){
                        var difference=angle-lastAngle
                        if(difference>180)difference-=360f
                        if(difference< -180)difference+=360f
                        degrees+=difference
                        var ticks=0
                        while(kotlin.math.abs(degrees)>=18f&&ticks<20){
                            val step=if(degrees>0)1 else -1
                            onStep(step);degrees-=step*18f;ticks++
                            vibrator.vibrate(VibrationEffect.createOneShot(8,VibrationEffect.DEFAULT_AMPLITUDE))
                        }
                    }
                    lastAngle=angle;change.consume()
                }
            },contentAlignment=Alignment.Center){
                Canvas(Modifier.fillMaxSize()){
                    drawCircle(color.copy(alpha=.12f))
                    drawCircle(color,style=Stroke(9.dp.toPx(),cap=StrokeCap.Round))
                    for(i in 0 until 36){
                        val rad=(i*10.0-90.0)*PI/180
                        val r=size.minDimension*.44f
                        val x=center.x+kotlin.math.cos(rad).toFloat()*r
                        val y=center.y+kotlin.math.sin(rad).toFloat()*r
                        drawCircle(Color.White.copy(alpha=.36f),radius=1.5.dp.toPx(),center=Offset(x,y))
                    }
                }
                Column(horizontalAlignment=Alignment.CenterHorizontally){
                    Text("$value",fontSize=39.sp,fontWeight=FontWeight.Black,color=Color.White)
                    Text(if(max==99999)"XP" else "/ $max",fontSize=13.sp,color=color)
                }
            }
        }
    }
    @Composable private fun StatCard(label:String,value:Int,change:(Int)->Unit){
        Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).padding(10.dp),verticalAlignment=Alignment.CenterVertically){
            Text(label,Modifier.weight(1f),fontSize=15.sp,color=Color.White)
            OutlinedButton(onClick={change(-1)},contentPadding=PaddingValues(0.dp)){Text("−")}
            Text("$value",Modifier.width(56.dp),textAlign=TextAlign.Center,fontWeight=FontWeight.Bold)
            OutlinedButton(onClick={change(1)},contentPadding=PaddingValues(0.dp)){Text("+")}
        }
    }
    @Composable private fun Supplies(){
        val start=suppliesPage*5
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.SpaceBetween){
            Text("CRAFTING RESOURCES",fontWeight=FontWeight.Bold,color=Gold)
            Resources.drop(start).take(5).forEach { name->
                val amount=data.optJSONObject("resources")?.optInt(name)?:0
                Row(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(10.dp)).padding(horizontal=10.dp,vertical=2.dp),verticalAlignment=Alignment.CenterVertically){
                    Text(name,Modifier.weight(1f),fontSize=15.sp)
                    IconButton(onClick={changeResource(name,-1)}){Text("−",fontSize=23.sp)}
                    Text("$amount",Modifier.width(44.dp),textAlign=TextAlign.Center)
                    IconButton(onClick={changeResource(name,1)}){Text("+",fontSize=23.sp)}
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically){
                OutlinedButton(onClick={suppliesPage=0},enabled=suppliesPage>0){Text("‹ Previous")}
                Text("${suppliesPage+1} / 2")
                OutlinedButton(onClick={suppliesPage=1},enabled=suppliesPage<1){Text("Next ›")}
            }
        }
    }
    @Composable private fun Perks(){
        val sheet=classSheet()
        val perks=sheet?.optJSONArray("perks")?:JSONArray()
        val masteries=sheet?.optJSONArray("masteries")?:JSONArray()
        val isPerks=perkSection=="perks"
        val rows=mutableListOf<Triple<Int,String,Int>>()
        if(isPerks){
            var index=0
            for(i in 0 until perks.length()){
                val item=perks.getJSONObject(i)
                val count=item.optInt("count",1).coerceAtLeast(1)
                rows.add(Triple(index,perkDescription(item,sheet!!),count))
                index+=count
            }
        }else{
            for(i in 0 until masteries.length())rows.add(Triple(i,resolveText(masteries.optString(i),sheet!!),1))
        }
        val saved=data.optJSONArray(perkSection)?:JSONArray()
        val completed=(0 until saved.length()).count{saved.optJSONObject(it)?.optBoolean("done")==true}
        val total=rows.sumOf{it.third}
        Column(Modifier.fillMaxSize()){
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){
                listOf("perks","masteries").forEach{section->
                    Button(onClick={perkSection=section;perkPage=0},modifier=Modifier.weight(1f),
                        colors=ButtonDefaults.buttonColors(containerColor=if(perkSection==section)Gold else Panel,contentColor=if(perkSection==section)Night else Color.White)){
                        Text(section.uppercase(),fontSize=12.sp)
                    }
                }
            }
            Text("$completed / $total completed",fontSize=12.sp,color=Gold,modifier=Modifier.padding(vertical=6.dp))
            if(sheet==null)Text("Class reference not available for this character",color=Color.LightGray)
            else Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)){
                rows.forEachIndexed{rowNumber,(startIndex,description,count)->
                    Column(Modifier.fillMaxWidth().background(Panel,RoundedCornerShape(12.dp)).padding(12.dp)){
                        Text(if(isPerks)"PERK ${rowNumber+1}" else "MASTERY ${rowNumber+1}",
                            color=Gold,fontSize=11.sp,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(5.dp))
                        Text(description,fontSize=14.sp,lineHeight=20.sp,color=Color.White)
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically){
                            repeat(count){occurrence->
                                val index=startIndex+occurrence
                                val checked=saved.optJSONObject(index)?.optBoolean("done")==true
                                Row(verticalAlignment=Alignment.CenterVertically){
                                    Checkbox(checked=checked,onCheckedChange={toggleChecklist(perkSection,index,description)})
                                    if(count>1)Text("${occurrence+1}",fontSize=12.sp,color=Color.LightGray)
                                    else Text(if(checked)"Earned" else "Not earned",fontSize=12.sp,color=Color.LightGray)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }
    @Composable private fun Settings(signOut:()->Unit){
        Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.SpaceEvenly){
            Text("CHARACTER",color=Gold,fontWeight=FontWeight.Bold)
            Text(data.optString("name","Character"))
            Text("Class: ${data.optString("classId")} · Level ${data.optInt("level",1)}")
            Text("Campaign: $campaign")
            HorizontalDivider(color=Color.DarkGray)
            Text("Cloud sync: $status")
            Button(onClick={::refresh},modifier=Modifier.fillMaxWidth()){Text("Refresh character from cloud")}
            Button(onClick={startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("https://github.com/sabbott459-arch/FH0Tracker/releases")))},modifier=Modifier.fillMaxWidth()){Text("Get latest Android update")}
            Text("Native Android · No WebView",color=Color.LightGray,fontSize=12.sp)
            OutlinedButton(onClick=signOut,modifier=Modifier.fillMaxWidth()){Text("Sign out")}
        }
    }
}
