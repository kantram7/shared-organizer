package team.kotiki.app

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

internal data class EntryReminderPreference(val enabled: Boolean = true, val minutesBefore: Int = 30, val dayTime: String = "09:00")
internal fun reminderEligible(entry: Entry, viewer: Person): Boolean = entry.date != null && !entry.done &&
    entry.invite in listOf(InviteState.NONE, InviteState.ACCEPTED) && entry.visibleTo(viewer) &&
    (entry.who == viewer || entry.who == Person.BOTH) && !(entry.visibility == Visibility.BUSY && entry.owner != viewer)
internal fun reminderInstant(entry: Entry, viewer: Person, preference: EntryReminderPreference, zone: ZoneId): Instant? {
    if (!preference.enabled || !reminderEligible(entry, viewer)) return null
    if (preference.minutesBefore !in 0..10080) return null
    return runCatching {
        val time = LocalTime.parse(if (entry.start.isBlank()) preference.dayTime else entry.start)
        val local = entry.date!!.atTime(time)
        if (zone.rules.getValidOffsets(local).size != 1) return null
        local.atZone(zone).toInstant().minusSeconds(if(entry.start.isBlank()) 0 else preference.minutesBefore * 60L)
    }.getOrNull()
}
internal fun reminderIdentity(authUid: String, entryId: String): String = calendarHash("reminder-v1\u0000$authUid\u0000$entryId")

internal class ReminderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("entry_reminders_v1", Context.MODE_PRIVATE)
    private fun profile(uid: String) = "profile_" + calendarHash(uid)
    fun preference(uid: String, id: String): EntryReminderPreference {
        val raw = prefs.getString("setting_" + reminderIdentity(uid,id), null) ?: return EntryReminderPreference()
        return runCatching { JSONObject(raw).let { EntryReminderPreference(it.optBoolean("enabled",true),it.optInt("minutes",30),it.optString("time","09:00")) } }.getOrDefault(EntryReminderPreference())
    }
    fun setPreference(uid: String, id: String, value: EntryReminderPreference) {
        require(value.minutesBefore in 0..10080); LocalTime.parse(value.dayTime)
        prefs.edit().putString("setting_" + reminderIdentity(uid,id), JSONObject().put("enabled",value.enabled).put("minutes",value.minutesBefore).put("time",value.dayTime).toString()).apply()
    }
    fun snapshot(uid: String): JSONObject? = prefs.getString(profile(uid),null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    fun profiles(): Set<String> = prefs.getStringSet("active_profiles",emptySet()).orEmpty().toSet()
    fun save(uid: String, snapshot: JSONObject) {
        check(prefs.edit().putString(profile(uid),snapshot.toString()).putStringSet("active_profiles",profiles()+uid).commit()) { "Reminder state could not be saved" }
    }
    fun clear(uid: String) {
        val editor = prefs.edit().remove(profile(uid)).putStringSet("active_profiles",profiles()-uid)
        snapshot(uid)?.optJSONArray("entries")?.let { entries -> (0 until entries.length()).forEach { index ->
            val id = entries.getJSONObject(index).getString("id")
            editor.remove("fired_"+reminderIdentity(uid,id))
        } }
        editor.commit()
    }
    fun wasFired(uid: String, id: String, due: Long): Boolean = prefs.getLong("fired_"+reminderIdentity(uid,id),Long.MIN_VALUE)==due
    fun markFired(uid: String, id: String, due: Long) { prefs.edit().putLong("fired_"+reminderIdentity(uid,id),due).commit() }
}

object LocalReminders {
    const val CHANNEL = "personal_reminders"
    const val EXTRA_AUTH_UID = "reminder_auth_uid"
    const val EXTRA_ENTRY_ID = "reminder_entry_id"
    private const val EXTRA_DUE = "reminder_due_at"
    private val lock = Any()
    data class Target(val authUid: String, val entryId: String)
    fun target(intent: Intent?): Target? {
        val uid=intent?.getStringExtra(EXTRA_AUTH_UID)?.takeIf { it.isNotBlank() } ?: return null
        val id=intent.getStringExtra(EXTRA_ENTRY_ID)?.takeIf { it.isNotBlank() } ?: return null
        return Target(uid,id)
    }
    fun canScheduleExact(context: Context): Boolean = Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Напоминания о делах и событиях",NotificationManager.IMPORTANCE_DEFAULT))
    }
    /** Call after every cloud/local snapshot and preference change. Proposals must pass the still-accepted event until confirmation. */
    fun sync(context: Context, authUid: String, entries: List<Entry>, viewer: Person) = synchronized(lock) {
        if(authUid.isBlank()) return@synchronized
        val preferences=ReminderPreferences(context)
        val old=preferences.snapshot(authUid)
        val rows=JSONArray()
        entries.filter { reminderEligible(it,viewer) }.forEach { rows.put(encode(it)) }
        val next=JSONObject().put("viewer",viewer.name).put("entries",rows)
        // Persist before scheduling so a stale PendingIntent can always validate the current snapshot.
        preferences.save(authUid,next)
        val current=decodeRows(next).associateBy { it.id }
        decodeRows(old).forEach { oldEntry ->
            val changed=current[oldEntry.id]!=oldEntry || !preferences.preference(authUid,oldEntry.id).enabled
            cancel(context,authUid,oldEntry.id,clearNotification=changed)
        }
        scheduleSnapshot(context,authUid,next)
    }
    fun clearProfile(context: Context, authUid: String) = synchronized(lock) {
        val preferences=ReminderPreferences(context)
        decodeRows(preferences.snapshot(authUid)).forEach { cancel(context,authUid,it.id) }
        preferences.clear(authUid)
    }
    fun reschedulePersisted(context: Context) = synchronized(lock) {
        val preferences=ReminderPreferences(context)
        preferences.profiles().forEach { uid -> preferences.snapshot(uid)?.let { snapshot ->
            decodeRows(snapshot).forEach { cancel(context,uid,it.id,clearNotification=false) }; scheduleSnapshot(context,uid,snapshot)
        } }
    }
    private fun scheduleSnapshot(context: Context, uid: String, snapshot: JSONObject) {
        val viewer=runCatching { Person.valueOf(snapshot.getString("viewer")) }.getOrNull() ?: return
        val preferences=ReminderPreferences(context); val now=Instant.now()
        val manager=context.getSystemService(AlarmManager::class.java)
        decodeRows(snapshot).forEach { entry ->
            val due=reminderInstant(entry,viewer,preferences.preference(uid,entry.id),ZoneId.systemDefault()) ?: return@forEach
            if(!due.isAfter(now) || preferences.wasFired(uid,entry.id,due.toEpochMilli())) return@forEach
            val operation=PendingIntent.getBroadcast(context,0,alarmIntent(context,uid,entry.id).putExtra(EXTRA_DUE,due.toEpochMilli()),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            try {
                if(canScheduleExact(context))manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,due.toEpochMilli(),operation)
                else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,due.toEpochMilli(),operation)
            } catch(_:SecurityException) { manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,due.toEpochMilli(),operation) }
        }
    }
    private fun alarmIntent(context: Context, uid: String, id: String) = Intent(context,ReminderReceiver::class.java).apply {
        action="${context.packageName}.ENTRY_REMINDER"
        data=Uri.parse("kotiki-reminder://alarm/${reminderIdentity(uid,id)}")
        putExtra(EXTRA_AUTH_UID,uid);putExtra(EXTRA_ENTRY_ID,id)
    }
    private fun cancel(context: Context, uid: String, id: String, clearNotification: Boolean = true) {
        PendingIntent.getBroadcast(context,0,alarmIntent(context,uid,id),PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)?.let {
            context.getSystemService(AlarmManager::class.java).cancel(it);it.cancel()
        }
        if(clearNotification)context.getSystemService(NotificationManager::class.java).cancel(reminderIdentity(uid,id),0)
    }
    internal fun receive(context: Context, intent: Intent) = synchronized(lock) {
        val target=target(intent) ?: return@synchronized
        val preferences=ReminderPreferences(context)
        if(target.authUid !in preferences.profiles()) return@synchronized
        val snapshot=preferences.snapshot(target.authUid) ?: return@synchronized
        val viewer=runCatching { Person.valueOf(snapshot.getString("viewer")) }.getOrNull() ?: return@synchronized
        val entry=decodeRows(snapshot).find { it.id==target.entryId } ?: return@synchronized
        val due=reminderInstant(entry,viewer,preferences.preference(target.authUid,entry.id),ZoneId.systemDefault())?.toEpochMilli() ?: return@synchronized
        if(due!=intent.getLongExtra(EXTRA_DUE,Long.MIN_VALUE) || due>System.currentTimeMillis()+1000 || preferences.wasFired(target.authUid,entry.id,due)) return@synchronized
        if(Build.VERSION.SDK_INT>=33 && ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return@synchronized
        createChannel(context)
        val manager=context.getSystemService(NotificationManager::class.java)
        if(!manager.areNotificationsEnabled()) return@synchronized
        try {
            manager.notify(reminderIdentity(target.authUid,entry.id),0,buildNotification(context,target.authUid,entry))
            preferences.markFired(target.authUid,entry.id,due)
        } catch(_:SecurityException) { /* Permission may have been revoked after the check. */ }
    }
    internal fun buildNotification(context: Context, uid: String, entry: Entry): Notification {
        val launch=Intent(context,MainActivity::class.java).apply {
            flags=Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            data=Uri.parse("kotiki-reminder://open/${reminderIdentity(uid,entry.id)}")
            putExtra(EXTRA_AUTH_UID,uid);putExtra(EXTRA_ENTRY_ID,entry.id)
        }
        val pending=PendingIntent.getActivity(context,0,launch,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public=NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_calendar).setContentTitle("Напоминание")
            .setContentText("Открой приложение, чтобы посмотреть подробности").build()
        return NotificationCompat.Builder(context,CHANNEL).setSmallIcon(R.drawable.ic_calendar).setContentTitle(entry.title)
            .setContentText(entry.date?.let(::fullDayLabel).orEmpty() + if(entry.start.isNotBlank()) " · ${entry.start}" else "")
            .setCategory(NotificationCompat.CATEGORY_REMINDER).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public).setContentIntent(pending).setAutoCancel(true).build()
    }
    private fun encode(entry: Entry) = JSONObject().put("id",entry.id).put("title",entry.title).put("date",entry.date.toString())
        .put("start",entry.start).put("end",entry.end).put("who",entry.who.name).put("owner",entry.owner.name)
        .put("visibility",entry.visibility.name).put("kind",entry.kind.name).put("invite",entry.invite.name).put("done",entry.done)
    private fun decodeRows(snapshot: JSONObject?): List<Entry> {
        val rows=snapshot?.optJSONArray("entries") ?: return emptyList()
        return (0 until rows.length()).mapNotNull { index -> runCatching {
            val item=rows.getJSONObject(index);val date=LocalDate.parse(item.getString("date"))
            Entry(item.getString("id"),item.getString("title"),date,mondayOf(date),start=item.optString("start"),end=item.optString("end"),
                who=Person.valueOf(item.getString("who")),owner=Person.valueOf(item.getString("owner")),visibility=Visibility.valueOf(item.getString("visibility")),
                kind=Kind.valueOf(item.getString("kind")),invite=InviteState.valueOf(item.getString("invite")),done=item.optBoolean("done"))
        }.getOrNull() }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { LocalReminders.receive(context,intent) }
}
class ReminderRescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if(intent.action in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_TIME_CHANGED,AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED))
            LocalReminders.reschedulePersisted(context)
    }
}
