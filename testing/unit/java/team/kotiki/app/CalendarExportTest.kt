package team.kotiki.app

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CalendarExportTest {
    private val day = LocalDate.of(2026, 11, 20)
    private val zone = ZoneId.of("Europe/Moscow")
    private fun event() = Entry("test-event", "Тестовое событие", day, mondayOf(day), start = "19:00", end = "20:00", kind = Kind.EVENT)
    private class Memory : CalendarLinkStore {
        val values = mutableMapOf<String, CalendarLink>()
        override fun link(key: String) = values[key]
        override fun putLink(key: String, link: CalendarLink) { values[key] = link }
    }
    private data class Call(val method: String, val url: String, val body: String?, val headers: Map<String, String>)
    private class FakeHttp : CalendarHttp {
        val calls = mutableListOf<Call>()
        val answers = ArrayDeque<CalendarHttpResponse>()
        override fun request(method: String, url: String, authorization: String, body: String?, headers: Map<String, String>): CalendarHttpResponse {
            calls += Call(method, url, body, headers)
            return answers.removeFirst()
        }
    }
    @Test fun allDayInclusiveLastDateBecomesExclusiveWithoutTimezoneShift() {
        val source = event().copy(start = "", end = "", lastDate = day.plusDays(3))
        val body = googleEventBody(source, ZoneId.of("Pacific/Auckland"))
        assertEquals("2026-11-20", body.getJSONObject("start").getString("date"))
        assertEquals("2026-11-24", body.getJSONObject("end").getString("date"))
        assertFalse(body.getJSONObject("start").has("dateTime"))
        val ics = calendarIcs(source, zone, "stable@test", Instant.EPOCH)
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261120\r\n"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20261124\r\n"))
    }
    @Test fun timedMultiDayPreservesZoneAndInstant() {
        val source = event().copy(lastDate = day.plusDays(1), end = "07:30")
        val body = googleEventBody(source, zone)
        assertEquals("2026-11-20T19:00:00+03:00", body.getJSONObject("start").getString("dateTime"))
        assertEquals("2026-11-21T07:30:00+03:00", body.getJSONObject("end").getString("dateTime"))
        assertEquals("Europe/Moscow", body.getJSONObject("start").getString("timeZone"))
        val ics = calendarIcs(source, zone, "stable@test", Instant.EPOCH)
        assertTrue(ics.contains("DTSTART:20261120T160000Z")); assertTrue(ics.contains("DTEND:20261121T043000Z"))
    }
    @Test fun invalidOrAmbiguousDatesNeverBecomeExports() {
        listOf(event().copy(date = null), event().copy(end = ""), event().copy(start = "26:00"), event().copy(end = "18:00"), event().copy(lastDate = day.minusDays(1)))
            .forEach { assertThrows(CalendarExportException::class.java) { calendarTimes(it, zone) } }
        val gap = event().copy(date = LocalDate.of(2026, 3, 29), lastDate = LocalDate.of(2026, 3, 29), start = "02:30", end = "04:00")
        assertThrows(CalendarExportException::class.java) { calendarTimes(gap, ZoneId.of("Europe/Berlin")) }
    }
    @Test fun icsTextCannotInjectPropertiesAndUtf8LinesFit75Octets() {
        val source = event().copy(title = "Котики 🐾".repeat(30), note = "Note\r\nATTENDEE:other;test,\\")
        val ics = calendarIcs(source, zone, "stable@test", Instant.EPOCH)
        assertFalse(ics.contains("\r\nATTENDEE:"))
        assertTrue(normalizeIcs(ics).contains("DESCRIPTION:Note\\nATTENDEE:other\\;test\\,\\\\"))
        ics.split("\r\n").forEach { assertTrue(it.toByteArray(Charsets.UTF_8).size <= 75) }
        assertTrue(normalizeIcs(ics).contains("SUMMARY:${source.title}"))
    }
    @Test fun externalIdIsStablePrivateAndAccountScoped() {
        val id = calendarEventId("account-a", "entry-a")
        assertEquals(id, calendarEventId("account-a", "entry-a"))
        assertNotEquals(id, calendarEventId("account-b", "entry-a"))
        assertNotEquals(id, calendarEventId("account-a", "entry-b"))
        assertTrue(id.matches(Regex("[a-v0-9]{5,1024}")))
        assertFalse(id.contains("account"))
    }
    @Test fun googleSecondExportUpdatesSameIdWithLastExportEtag() {
        val http = FakeHttp(); val store = Memory(); val exporter = CalendarExporter(http, store)
        http.answers += CalendarHttpResponse(200, "{\"etag\":\"v1\"}")
        http.answers += CalendarHttpResponse(200, "{\"etag\":\"v2\"}")
        exporter.google(event(), "account-a", "test-token", zone)
        exporter.google(event().copy(title = "Обновлено"), "account-a", "test-token", zone)
        assertEquals(listOf("POST", "PATCH"), http.calls.map { it.method })
        val id = JSONObject(http.calls.first().body!!).getString("id")
        assertTrue(http.calls.last().url.contains("/$id?"))
        assertEquals("v1", http.calls.last().headers["If-Match"])
        assertFalse(JSONObject(http.calls.last().body!!).has("attendees"))
        assertEquals("v2", store.values.values.single().etag)
    }
    @Test fun googleExternalConflictDoesNotRefreshEtagOrRetryOverwrite() {
        val http = FakeHttp(); val store = Memory(); val exporter = CalendarExporter(http, store)
        http.answers += CalendarHttpResponse(200, "{\"etag\":\"v1\"}")
        http.answers += CalendarHttpResponse(412)
        exporter.google(event(), "account-a", "test-token", zone)
        assertThrows(CalendarExportException::class.java) { exporter.google(event().copy(title = "Новое"), "account-a", "test-token", zone) }
        assertEquals(2, http.calls.size); assertEquals("v1", store.values.values.single().etag)
    }
    @Test fun googleLostInsertResponseRecoversSameCopyWithoutDuplicatingOrOverwriting() {
        val http = FakeHttp(); val store = Memory(); val exporter = CalendarExporter(http, store)
        val id = calendarEventId("account-a", event().id)
        val key = calendarLinkKey(CalendarProvider.GOOGLE, "account-a", event().id)
        val body = googleEventBody(event(), zone)
        store.putLink(key, CalendarLink(id, "", body.toString()))
        http.answers += CalendarHttpResponse(409)
        http.answers += CalendarHttpResponse(200, JSONObject(body.toString()).put("etag", "v1").toString())
        exporter.google(event(), "account-a", "test-token", zone)
        assertEquals(listOf("POST", "GET"), http.calls.map { it.method })
        assertEquals("v1", store.link(key)!!.etag)
        assertEquals(id, JSONObject(http.calls.first().body!!).getString("id"))
    }
    @Test fun yandexCreateUsesIfNoneMatchThenUpdateUsesEtagAndStableUid() {
        val http = FakeHttp(); val store = Memory(); val exporter = CalendarExporter(http, store)
        val connection = YandexCalendarConnection("account-a", "test-app-password", "https://caldav.yandex.ru/calendars/test/", "Test")
        http.answers += CalendarHttpResponse(201, etag = "\"v1\"")
        http.answers += CalendarHttpResponse(204, etag = "\"v2\"")
        exporter.yandex(event(), connection, zone); exporter.yandex(event().copy(title = "Изменение"), connection, zone)
        assertEquals("*", http.calls[0].headers["If-None-Match"])
        assertEquals("\"v1\"", http.calls[1].headers["If-Match"])
        assertEquals(http.calls[0].url, http.calls[1].url)
        assertEquals(http.calls[0].body!!.lineSequence().first { it.startsWith("UID:") }, http.calls[1].body!!.lineSequence().first { it.startsWith("UID:") })
    }
    @Test fun yandexConflictNeverRetriesPut() {
        val http = FakeHttp(); val store = Memory(); val exporter = CalendarExporter(http, store)
        val connection = YandexCalendarConnection("account-a", "test-app-password", "https://caldav.yandex.ru/calendars/test/", "Test")
        http.answers += CalendarHttpResponse(201, etag = "\"v1\"")
        http.answers += CalendarHttpResponse(412)
        exporter.yandex(event(), connection, zone)
        assertThrows(CalendarExportException::class.java) { exporter.yandex(event().copy(title = "Изменение"), connection, zone) }
        assertEquals(2, http.calls.size); assertEquals("\"v1\"", store.values.values.single().etag)
    }
    @Test fun discoveryRejectsCredentialExfiltrationAndXmlEntities() {
        listOf("http://caldav.yandex.ru/", "https://caldav.yandex.ru.attacker.test/", "https://attacker.test/", "https://user:secret@caldav.yandex.ru/", "https://caldav.yandex.ru:444/", "//attacker.test/", "https://caldav.yandex.ru/?secret=1")
            .forEach { assertThrows(CalendarExportException::class.java) { trustedDavUrl(YANDEX_DAV_ROOT, it) } }
        assertEquals("https://caldav.yandex.ru/calendars/a/", trustedDavUrl(YANDEX_DAV_ROOT, "/calendars/a/"))
        assertThrows(CalendarExportException::class.java) { davDocument("<!DOCTYPE x [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><x>&x;</x>") }
    }
    @Test fun discoveryOnlyUsesSuccessfulPropertiesAndWritableEventCollections() {
        fun response(href: String, props: String) = "<d:response><d:href>$href</d:href><d:propstat><d:prop>$props</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
        fun envelope(rows: String) = "<d:multistatus xmlns:d='DAV:' xmlns:c='urn:ietf:params:xml:ns:caldav'>$rows</d:multistatus>"
        val http = FakeHttp()
        http.answers += CalendarHttpResponse(207, envelope(response("/", "<d:current-user-principal><d:href>/principal/test/</d:href></d:current-user-principal>")))
        http.answers += CalendarHttpResponse(207, envelope(response("/principal/test/", "<c:calendar-home-set><d:href>/calendars/test/</d:href></c:calendar-home-set>")))
        http.answers += CalendarHttpResponse(207, envelope(response("/calendars/test/writable/", "<d:displayname>My calendar</d:displayname><d:resourcetype><c:calendar/></d:resourcetype><d:current-user-privilege-set><d:privilege><d:write/></d:privilege></d:current-user-privilege-set>") + response("/calendars/test/readonly/", "<d:resourcetype><c:calendar/></d:resourcetype><d:current-user-privilege-set><d:privilege><d:read/></d:privilege></d:current-user-privilege-set>")))
        val found = discoverYandexCalendars(http, "account-a", "test-app-password")
        assertEquals(listOf(DavCalendar("https://caldav.yandex.ru/calendars/test/writable/", "My calendar")), found)
        assertEquals(listOf("0", "0", "1"), http.calls.map { it.headers["Depth"] })
    }
}
