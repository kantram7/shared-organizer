package team.kotiki.app

import org.json.JSONArray
import org.json.JSONObject

data class CloudSession(val uid:String,val spaceId:String,val viewer:Person,val uids:List<String>,
    val roles:Map<String,Person>,val names:Map<Person,String>,val authUid:String=uid,val actorRoles:Map<String,Person> = roles) {
    val identity get()=DemoIdentity(viewer,names,false)
    fun uidFor(person:Person)=roles.entries.first{it.value==person}.key
}

data class StoreContent(val entries:List<Entry>,val plans:List<DemoCollection>,val lists:List<DemoCollection>)
data class CloudRecord(val id:String,val type:String,val owner:String,val shared:Boolean,val data:Map<String,Any>,val deleted:Boolean=false) {
    fun projection(revision:String):Map<String,Any> = if(!deleted && data["visibility"]=="BUSY")
        mapOf("owner" to owner,"revision" to revision,"active" to true) +
            listOf("date","lastDate","week","start","end","who","kind").associateWith{data.getValue(it)}
        else mapOf("owner" to owner,"revision" to revision,"active" to false)
    fun envelope(session:CloudSession,revision:String,time:Any):Map<String,Any> = mapOf(
        "type" to type,"owner" to owner,"readers" to if(shared)session.uids else listOf(owner),
        "shared" to shared,"data" to data,"deleted" to deleted,"revision" to revision,"updatedAt" to time,"actor" to session.authUid)
}
internal fun JSONObject.toCloudMap():Map<String,Any> = keys().asSequence().associateWith { key ->
    fun convert(v:Any):Any = when(v){is JSONObject->v.toCloudMap();is JSONArray->(0 until v.length()).map{convert(v.get(it))};else->v}
    convert(get(key))
}
internal fun StoreContent.records(session:CloudSession):List<CloudRecord> =
    entries.map{CloudRecord("entry_${it.id}","entry",session.uidFor(it.owner),it.visibility==Visibility.SHARED,writeEntry(it).toCloudMap())} +
    plans.map{CloudRecord("plan_${it.id}","plan",session.uidFor(it.owner),true,writeCollection(it).toCloudMap())} +
    lists.map{CloudRecord("list_${it.id}","list",session.uidFor(it.owner),it.shared,writeCollection(it).toCloudMap())}

@Suppress("UNCHECKED_CAST")
internal fun cloudRecord(id:String,map:Map<String,Any>):CloudRecord = CloudRecord(id,map["type"] as String,map["owner"] as String,
    map["shared"] as Boolean,map["data"] as Map<String,Any>,map["deleted"] as Boolean)
