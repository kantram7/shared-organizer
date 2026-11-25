package team.kotiki.app

import java.net.URI
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import org.w3c.dom.Element
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource

internal const val GOOGLE_CALENDAR_SCOPE = "https://www.googleapis.com/auth/calendar.events"
internal const val YANDEX_DAV_ROOT = "https://caldav.yandex.ru/"
internal const val GOOGLE_EVENTS = "https://www.googleapis.com/calendar/v3/calendars/primary/events"
internal enum class CalendarProvider { GOOGLE, YANDEX }
internal class CalendarExportException(message: String, val status: Int = 0) : Exception(message)
internal data class CalendarHttpResponse(val code: Int, val body: String = "", val etag: String = "")
internal fun interface CalendarHttp {
    fun request(method: String, url: String, authorization: String, body: String?, headers: Map<String, String>): CalendarHttpResponse
}

/** No redirects: credentials must never follow a server-selected host. Call only on Dispatchers.IO. */
internal class CalendarNetwork : CalendarHttp {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(java.time.Duration.ofSeconds(15)).readTimeout(java.time.Duration.ofSeconds(25))
        .callTimeout(java.time.Duration.ofSeconds(40)).build()
    override fun request(method: String, url: String, authorization: String, body: String?, headers: Map<String, String>): CalendarHttpResponse {
        val uri = URI(url)
        require(uri.scheme == "https" && uri.userInfo == null && uri.fragment == null && (uri.port == -1 || uri.port == 443))
        require(uri.host == "www.googleapis.com" || uri.host == "caldav.yandex.ru")
        val type = headers["Content-Type"] ?: "application/json; charset=utf-8"
        val builder = Request.Builder().url(url).header("Authorization", authorization)
        headers.forEach { (key, value) -> builder.header(key, value) }
        builder.method(method, body?.toRequestBody(type.toMediaType()))
        return client.newCall(builder.build()).execute().use { response ->
            // Calendar responses are small. Never echo provider response bodies into errors/logs.
            val data = response.body?.source()?.let { source ->
                if (source.request(2_000_001)) throw CalendarExportException("Ответ календаря слишком большой")
                source.readUtf8()
            } ?: ""
            CalendarHttpResponse(response.code, data, response.header("ETag").orEmpty())
        }
    }
}

internal fun calendarFailure(code: Int): Nothing = throw CalendarExportException(when (code) {
    401 -> "Доступ к календарю истёк. Подключи аккаунт снова в настройках."
    403 -> "Нет разрешения на запись в этот календарь. Проверь доступ и настройку API."
    404, 410 -> "Копия или календарь удалены снаружи. Новая копия автоматически не создаётся."
    409, 412 -> "Копия уже существует или изменена в календаре. Внешние изменения не перезаписаны."
    429 -> "Календарь временно ограничил запросы. Попробуй позже."
    in 300..399 -> "Сервер предложил перенаправление. Проверь подключение календаря."
    else -> "Календарь недоступен (HTTP $code). Повтори позже."
}, code)

internal fun calendarHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
internal fun calendarEventId(account: String, entryId: String): String = "k" + calendarHash("shared-organizer-v1\u0000$account\u0000$entryId")
internal fun calendarLinkKey(provider: CalendarProvider, account: String, entryId: String): String =
    calendarHash("${provider.name}\u0000$account\u0000$entryId")

internal data class CalendarTimes(val first: LocalDate, val lastExclusive: LocalDate, val from: String?, val until: String?, val zone: ZoneId)
internal fun calendarTimes(entry: Entry, zone: ZoneId): CalendarTimes {
    val first = entry.date ?: throw CalendarExportException("Сначала выбери день события.")
    val last = entry.lastDate ?: first
    if (last < first) throw CalendarExportException("Дата окончания раньше начала.")
    if (richPlainText(entry.title,entry.titleFormat).isBlank()) throw CalendarExportException("Укажи название события.")
    if (entry.start.isBlank() && entry.end.isBlank()) return CalendarTimes(first, last.plusDays(1), null, null, zone)
    fun time(text: String): LocalTime = try { LocalTime.parse(text) } catch (_: Exception) {
        throw CalendarExportException("Укажи корректные время начала и окончания.")
    }
    fun zoned(date: LocalDate, time: LocalTime) = LocalDateTime.of(date, time).let { local ->
        // Do not silently shift a nonexistent clock time during a DST transition.
        if (zone.rules.getValidOffsets(local).size != 1) throw CalendarExportException("Это время неоднозначно из-за смены часового пояса. Выбери другое время.")
        local.atZone(zone)
    }
    val start = zoned(first, time(entry.start)); val end = zoned(last, time(entry.end))
    if (!end.isAfter(start)) throw CalendarExportException("Окончание должно быть позже начала.")
    return CalendarTimes(first, last.plusDays(1), start.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), end.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), zone)
}

internal fun googleEventBody(entry: Entry, zone: ZoneId): JSONObject {
    val times = calendarTimes(entry, zone)
    fun point(first: Boolean) = JSONObject().apply {
        if (times.from == null) put("date", (if (first) times.first else times.lastExclusive).toString())
        else { put("dateTime", if (first) times.from else times.until); put("timeZone", zone.id) }
    }
    return JSONObject().put("summary", richPlainText(entry.title,entry.titleFormat)).put("description", richPlainText(entry.note,entry.descriptionFormat))
        .put("start", point(true)).put("end", point(false))
        .put("status", if (entry.invite == InviteState.PENDING) "tentative" else "confirmed")
}

internal fun icsEscape(value: String): String = value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n")
    .replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
internal fun icsFold(line: String): String {
    val result = StringBuilder(); var bytes = 0
    line.codePoints().toArray().forEach { point ->
        val char = String(Character.toChars(point)); val size = char.toByteArray(Charsets.UTF_8).size
        if (bytes + size > 75) { result.append("\r\n "); bytes = 1 }
        result.append(char); bytes += size
    }
    return result.toString()
}
internal fun calendarIcs(entry: Entry, zone: ZoneId, uid: String, stamp: Instant): String {
    val times = calendarTimes(entry, zone)
    val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)
    val dates = if (times.from == null) listOf("DTSTART;VALUE=DATE:${times.first.format(DateTimeFormatter.BASIC_ISO_DATE)}",
        "DTEND;VALUE=DATE:${times.lastExclusive.format(DateTimeFormatter.BASIC_ISO_DATE)}")
    else listOf("DTSTART:${utc.format(java.time.OffsetDateTime.parse(times.from))}", "DTEND:${utc.format(java.time.OffsetDateTime.parse(times.until))}")
    return (listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Shared Organizer//Calendar copy//EN", "BEGIN:VEVENT",
        "UID:$uid", "DTSTAMP:${utc.format(stamp)}") + dates + listOf("SUMMARY:${icsEscape(richPlainText(entry.title,entry.titleFormat))}", "DESCRIPTION:${icsEscape(richPlainText(entry.note,entry.descriptionFormat))}",
        "STATUS:${if(entry.invite == InviteState.PENDING) "TENTATIVE" else "CONFIRMED"}", "END:VEVENT", "END:VCALENDAR"))
        .joinToString("\r\n", postfix = "\r\n", transform = ::icsFold)
}

internal data class CalendarLink(val externalId: String, val etag: String, val pendingBody: String = "", val stamp: String = Instant.now().toString())
internal interface CalendarLinkStore {
    fun link(key: String): CalendarLink?
    fun putLink(key: String, link: CalendarLink)
}

/** One-way, user-triggered export. ETags are compared with the LAST export, never silently refreshed before a write. */
internal class CalendarExporter(private val http: CalendarHttp, private val store: CalendarLinkStore) {
    fun google(entry: Entry, account: String, token: String, zone: ZoneId): String {
        val key = calendarLinkKey(CalendarProvider.GOOGLE, account, entry.id)
        val previous = store.link(key)
        val id = previous?.externalId ?: calendarEventId(account, entry.id)
        require(id.matches(Regex("[a-v0-9]{5,1024}")))
        if(previous!=null && previous.etag.isNotEmpty() && previous.pendingBody.isNotEmpty()) {
            val remote=http.request("GET","$GOOGLE_EVENTS/$id","Bearer $token",null,emptyMap())
            if(remote.code!=200)calendarFailure(remote.code)
            val actual=JSONObject(remote.body);val version=actual.optString("etag")
            if(version.isBlank())throw CalendarExportException("Календарь не вернул версию события.")
            if(version!=previous.etag) {
                if(!googleAttemptEqual(actual,JSONObject(previous.pendingBody)))calendarFailure(412)
                store.putLink(key,previous.copy(etag=version,pendingBody=""))
                return "Предыдущее обновление подтверждено. Для новых изменений нажми кнопку ещё раз."
            }
        }
        val payload = googleEventBody(entry, zone)
        if(previous?.etag?.isNotEmpty()==true)payload.put("extendedProperties",JSONObject().put("private",JSONObject().put("kotikiExportAttempt",newId())))
        val pending = if(previous?.etag?.isNotEmpty()==true)previous.copy(pendingBody=payload.toString()) else previous ?: CalendarLink(id, "", payload.toString())
        // This durable receipt must exist before PATCH: a lost response must not lose the attempted version.
        if (previous == null || pending.etag.isNotEmpty()) store.putLink(key, pending)
        val create = pending.etag.isEmpty()
        val response = http.request(if (create) "POST" else "PATCH", if (create) "$GOOGLE_EVENTS?sendUpdates=none" else "$GOOGLE_EVENTS/$id?sendUpdates=none",
            "Bearer $token", (if(create) JSONObject(payload.toString()).put("id", id) else payload).toString(),
            if (create) emptyMap() else mapOf("If-Match" to pending.etag))
        if (response.code == 409 && create) {
            // A timed-out insert can already have committed. Recover only the exact payload we attempted.
            val remote = http.request("GET", "$GOOGLE_EVENTS/$id", "Bearer $token", null, emptyMap())
            if (remote.code != 200) calendarFailure(remote.code)
            val actual = JSONObject(remote.body)
            if (!googleManagedEqual(actual, JSONObject(pending.pendingBody))) calendarFailure(409)
            val etag = actual.optString("etag"); if (etag.isBlank()) throw CalendarExportException("Календарь не вернул версию события.")
            store.putLink(key, pending.copy(etag = etag, pendingBody = ""))
            return "Связь с существующей копией восстановлена. Для новых изменений нажми кнопку ещё раз."
        }
        if (response.code !in 200..299) calendarFailure(response.code)
        val saved = JSONObject(response.body)
        val etag = saved.optString("etag"); if (etag.isBlank()) throw CalendarExportException("Копия отправлена, но сервер не вернул её версию. Повтори проверку кнопкой.")
        store.putLink(key, pending.copy(etag = etag, pendingBody = ""))
        return if (create) "Копия создана в Google Календаре." else "Копия обновлена в Google Календаре."
    }

    fun yandex(entry: Entry, connection: YandexCalendarConnection, zone: ZoneId): String {
        val collection = trustedDavUrl(YANDEX_DAV_ROOT, connection.calendarUrl).trimEnd('/') + "/"
        val account = connection.username.lowercase(Locale.ROOT) + "|" + collection
        val key = calendarLinkKey(CalendarProvider.YANDEX, account, entry.id)
        val previous = store.link(key)
        val id = previous?.externalId ?: (collection + calendarEventId(account, entry.id) + ".ics")
        trustedDavUrl(collection, id)
        if(previous!=null && previous.etag.isNotEmpty() && previous.pendingBody.isNotEmpty()) {
            val remote=http.request("GET",id,connection.authorization(),null,emptyMap())
            if(remote.code!=200)calendarFailure(remote.code)
            if(remote.etag.isBlank())throw CalendarExportException("Календарь не вернул версию события.")
            if(remote.etag!=previous.etag) {
                if(!previous.pendingBody.contains("X-KOTIKI-EXPORT-ATTEMPT:") || normalizeIcs(remote.body)!=normalizeIcs(previous.pendingBody))calendarFailure(412)
                store.putLink(key,previous.copy(etag=remote.etag,pendingBody=""))
                return "Предыдущее обновление подтверждено. Для новых изменений нажми кнопку ещё раз."
            }
        }
        val stamp = previous?.stamp ?: Instant.now().toString()
        val body = calendarIcs(entry, zone, calendarEventId(account, entry.id) + "@shared-organizer", Instant.parse(stamp)).let{
            if(previous?.etag?.isNotEmpty()==true)it.replace("END:VEVENT\r\n","X-KOTIKI-EXPORT-ATTEMPT:${newId()}\r\nEND:VEVENT\r\n")else it
        }
        val pending = if(previous?.etag?.isNotEmpty()==true)previous.copy(pendingBody=body) else previous ?: CalendarLink(id, "", body, stamp)
        if (previous == null || pending.etag.isNotEmpty()) store.putLink(key, pending)
        val create = pending.etag.isEmpty()
        val response = http.request("PUT", id, connection.authorization(), body, mapOf("Content-Type" to "text/calendar; charset=utf-8") +
            if (create) mapOf("If-None-Match" to "*") else mapOf("If-Match" to pending.etag))
        if (response.code == 412 && create) {
            val remote = http.request("GET", id, connection.authorization(), null, emptyMap())
            if (remote.code != 200) calendarFailure(remote.code)
            if (normalizeIcs(remote.body) != normalizeIcs(pending.pendingBody) || remote.etag.isBlank()) calendarFailure(412)
            store.putLink(key, pending.copy(etag = remote.etag, pendingBody = ""))
            return "Связь с существующей копией восстановлена. Для новых изменений нажми кнопку ещё раз."
        }
        if (response.code !in 200..299) calendarFailure(response.code)
        // CalDAV may omit ETag after transforming a PUT. GET the committed representation, but only adopt an exact match.
        val etag = response.etag.ifBlank {
            val remote = http.request("GET", id, connection.authorization(), null, emptyMap())
            if (remote.code != 200 || normalizeIcs(remote.body) != normalizeIcs(body) || remote.etag.isBlank())
                throw CalendarExportException("Копия отправлена, но её версия не подтверждена. Повтори проверку кнопкой.")
            remote.etag
        }
        store.putLink(key, pending.copy(etag = etag, pendingBody = ""))
        return if (create) "Копия создана в Яндекс Календаре." else "Копия обновлена в Яндекс Календаре."
    }
}
internal fun normalizeIcs(body: String): String = body.replace("\r\n", "\n").replace(Regex("\n[ \t]"), "").trim()
internal fun googleManagedEqual(a: JSONObject, b: JSONObject): Boolean = listOf("summary", "description", "status").all { a.optString(it) == b.optString(it) } &&
    listOf("start", "end").all { field ->
        val x = a.optJSONObject(field) ?: return@all false; val y = b.optJSONObject(field) ?: return@all false
        if (y.has("date")) x.optString("date") == y.optString("date")
        else runCatching { java.time.OffsetDateTime.parse(x.getString("dateTime")).toInstant() == java.time.OffsetDateTime.parse(y.getString("dateTime")).toInstant() }.getOrDefault(false)
    }
internal fun googleAttemptEqual(actual:JSONObject,attempted:JSONObject):Boolean {
    val token=attempted.optJSONObject("extendedProperties")?.optJSONObject("private")?.optString("kotikiExportAttempt").orEmpty()
    return token.isNotEmpty() && actual.optJSONObject("extendedProperties")?.optJSONObject("private")?.optString("kotikiExportAttempt")==token && googleManagedEqual(actual,attempted)
}

internal data class YandexCalendarConnection(val username: String, val password: String, val calendarUrl: String, val label: String) {
    fun authorization(): String = "Basic " + java.util.Base64.getEncoder().encodeToString("$username:$password".toByteArray(Charsets.UTF_8))
}
internal data class DavCalendar(val url: String, val label: String)
internal fun trustedDavUrl(base: String, href: String): String {
    val uri = try { URI(base).resolve(href) } catch (_: Exception) { throw CalendarExportException("Некорректный адрес календаря.") }
    if (uri.scheme != "https" || uri.host != "caldav.yandex.ru" || (uri.port != -1 && uri.port != 443) || uri.userInfo != null || uri.fragment != null || uri.query != null)
        throw CalendarExportException("Адрес календаря не принадлежит доверенному серверу Яндекса.")
    return uri.toASCIIString()
}
internal fun davDocument(xml: String): org.w3c.dom.Document {
    if (xml.contains("<!DOCTYPE", true) || xml.contains("<!ENTITY", true)) throw CalendarExportException("Небезопасный ответ календаря.")
    val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
    return factory.newDocumentBuilder().apply { setEntityResolver { _, _ -> InputSource(StringReader("")) } }.parse(InputSource(StringReader(xml)))
}
internal fun Element.childText(namespace: String, name: String): String? = getElementsByTagNameNS(namespace, name).item(0)?.textContent?.trim()?.takeIf { it.isNotBlank() }
internal fun davProperties(xml: String): List<Pair<String, Element>> {
    val responses = davDocument(xml).getElementsByTagNameNS("DAV:", "response")
    return (0 until responses.length).flatMap { index ->
        val response = responses.item(index) as Element
        val href = response.childText("DAV:", "href") ?: return@flatMap emptyList()
        val statuses = response.getElementsByTagNameNS("DAV:", "propstat")
        (0 until statuses.length).mapNotNull { position ->
            val status = statuses.item(position) as Element
            if (!status.childText("DAV:", "status").orEmpty().contains(" 200 ")) null
            else (status.getElementsByTagNameNS("DAV:", "prop").item(0) as? Element)?.let { href to it }
        }
    }
}
internal fun discoverYandexCalendars(http: CalendarHttp, username: String, password: String): List<DavCalendar> {
    if (username.isBlank() || ':' in username || username.any { it.isISOControl() } || password.isBlank()) throw CalendarExportException("Укажи логин и пароль приложения Яндекса.")
    val authorization = YandexCalendarConnection(username.trim(), password, "", "").authorization()
    fun propfind(url: String, depth: String, properties: String): List<Pair<String, Element>> {
        val body = "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind xmlns:d=\"DAV:\" xmlns:c=\"urn:ietf:params:xml:ns:caldav\"><d:prop>$properties</d:prop></d:propfind>"
        val response = http.request("PROPFIND", trustedDavUrl(YANDEX_DAV_ROOT, url), authorization, body, mapOf("Depth" to depth, "Content-Type" to "application/xml; charset=utf-8"))
        if (response.code != 207) calendarFailure(response.code)
        return davProperties(response.body)
    }
    val principalProps = propfind(YANDEX_DAV_ROOT, "0", "<d:current-user-principal/>")
    val principalHref = principalProps.firstNotNullOfOrNull { (_, p) -> (p.getElementsByTagNameNS("DAV:", "current-user-principal").item(0) as? Element)?.childText("DAV:", "href") }
        ?: throw CalendarExportException("Яндекс не вернул адрес аккаунта CalDAV.")
    val principal = trustedDavUrl(YANDEX_DAV_ROOT, principalHref)
    val homeHref = propfind(principal, "0", "<c:calendar-home-set/>").firstNotNullOfOrNull { (_, p) ->
        (p.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar-home-set").item(0) as? Element)?.childText("DAV:", "href")
    } ?: throw CalendarExportException("Яндекс не вернул список календарей.")
    val home = trustedDavUrl(principal, homeHref)
    return propfind(home, "1", "<d:displayname/><d:resourcetype/><d:current-user-privilege-set/><c:supported-calendar-component-set/>").mapNotNull { (href, props) ->
        if (props.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "calendar").length == 0) return@mapNotNull null
        val components = props.getElementsByTagNameNS("urn:ietf:params:xml:ns:caldav", "comp")
        if (components.length > 0 && (0 until components.length).none { (components.item(it) as Element).getAttribute("name") == "VEVENT" }) return@mapNotNull null
        val privileges = props.getElementsByTagNameNS("DAV:", "current-user-privilege-set")
        if (privileges.length > 0 && listOf("all", "write", "write-content").none { props.getElementsByTagNameNS("DAV:", it).length > 0 }) return@mapNotNull null
        DavCalendar(trustedDavUrl(home, href), props.childText("DAV:", "displayname") ?: "Календарь")
    }.distinctBy { it.url }.ifEmpty { throw CalendarExportException("Нет доступного календаря для записи событий.") }
}
