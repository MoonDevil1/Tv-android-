package com.moondevil1.livetgtv
import org.json.JSONObject
object PlaylistParser {
 fun parse(source:String):List<Channel>{
  val result=mutableListOf<Channel>();var name:String?=null;var logo="";var group="Other"
  var headers=linkedMapOf<String,String>();var licenseUrl:String?=null;var licenseType:String?=null;var manifestType:String?=null
  fun decode(s:String)=s.replace("&amp;","&").replace("&quot;","\"").replace("&#39;","'")
  for(raw in source.removePrefix("\uFEFF").lines()){
   val line=raw.trim();if(line.isEmpty())continue
   if(line.startsWith("#EXTINF:")){
    val attrs=Regex("""([\w-]+)="([^"]*)"""").findAll(line).associate{it.groupValues[1] to decode(it.groupValues[2])}
    val comma=line.indexOf(',');name=if(comma>=0)line.substring(comma+1).trim() else attrs["tvg-name"]?:"Unknown channel"
    logo=attrs["tvg-logo"].orEmpty();group=attrs["group-title"]?:"Other";headers=linkedMapOf()
    licenseUrl=null;licenseType=null;manifestType=null
   }else if(line.startsWith("#EXTVLCOPT:http-")){
    val pair=line.removePrefix("#EXTVLCOPT:http-").split("=",limit=2)
    if(pair.size==2){val key=when(pair[0].lowercase()){"user-agent"->"User-Agent";"referer","referrer"->"Referer";"origin"->"Origin";"cookie"->"Cookie";"authorization"->"Authorization";else->pair[0]};headers[key]=decode(pair[1])}
   }else if(line.startsWith("#EXTHTTP:")){
    runCatching{val json=JSONObject(line.removePrefix("#EXTHTTP:"));json.keys().forEach{key->headers[key]=json.optString(key)}}
   }else if(line.startsWith("#KODIPROP:")){
    val pair=line.removePrefix("#KODIPROP:").split("=",limit=2)
    if(pair.size==2)when(pair[0]){"inputstream.adaptive.license_key"->licenseUrl=decode(pair[1]);"inputstream.adaptive.license_type"->licenseType=decode(pair[1]);"inputstream.adaptive.manifest_type"->manifestType=decode(pair[1])}
   }else if(!line.startsWith("#")&&name!=null){
    result+=Channel(name!!,logo,group,decode(line),headers.toMap(),licenseUrl,licenseType,manifestType);name=null
   }
  };return result
 }
}
