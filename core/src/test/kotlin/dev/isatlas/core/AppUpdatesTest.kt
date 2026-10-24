package dev.isatlas.core

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    private fun version(value: String) = requireNotNull(AppVersion.parse(value))
    private fun release(tag: String = "v0.4.0", changes: JsonObjectBuilder.() -> Unit = {}): String = buildJsonObject {
        put("tag_name", tag)
        put("draft", false)
        put("prerelease", false)
        put("body", "新增更新检测\n- 后台下载")
        putJsonArray("assets") { add(buildJsonObject {
            put("name", "ISAtlas-${tag.removePrefix("v")}.apk")
            put("state", "uploaded")
            put("browser_download_url", "${AppUpdates.REPOSITORY}/releases/download/$tag/ISAtlas-${tag.removePrefix("v")}.apk")
            put("size", 7_000_000)
            put("digest", "sha256:${"a".repeat(64)}")
        }) }
        changes()
    }.toString()

    @Test fun `versions compare numerically rather than lexicographically`() {
        assertTrue(version("0.10.0") > version("0.9.9"))
        assertTrue(version("1.0.0") > version("0.99.99"))
        assertEquals(0, version("v1.2.3+build.7").compareTo(version("1.2.3")))
    }
    @Test fun `prereleases follow semver ordering including numeric identifiers`() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        ordered.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", version(a) < version(b)) }
    }
    @Test fun `invalid version tags cannot become file paths`() {
        listOf("../0.4.0", "0.4", "01.2.3", "1.2.3-", "1.2.3-a..b", "1.2.3-01", "1.2.3+a..b", "1.2.3/evil", "999999999999999999999999.0.0").forEach {
            assertNull(it, AppVersion.parse(it))
        }
    }
    @Test fun `new release includes complete notes and digest and survives persistence`() {
        val result = AppUpdates.decodeResponse(release(), "0.3.0").single()
        assertEquals("0.4.0", result.version)
        assertTrue(result.notes.contains("后台下载"))
        assertEquals("a".repeat(64), result.sha256)
        assertEquals(result, AppUpdates.restore(result.encode()))
    }
    @Test fun `stable users never receive drafts prereleases same versions or downgrades`() {
        assertTrue(AppUpdates.decodeResponse(release("v0.3.0"), "0.3.0").isEmpty())
        assertTrue(AppUpdates.decodeResponse(release("v0.2.0"), "0.3.0").isEmpty())
        assertTrue(AppUpdates.decodeResponse(release { put("draft", true) }, "0.3.0").isEmpty())
        assertTrue(AppUpdates.decodeResponse(release("v0.4.0-rc.1") { put("prerelease", true) }, "0.3.0").isEmpty())
        assertTrue(AppUpdates.decodeResponse(release("v0.4.0-rc.1"), "0.3.0").isEmpty())
    }
    @Test fun `prerelease users can advance to newer prereleases and stable`() {
        assertEquals(1, AppUpdates.decodeResponse(release("v0.4.0-rc.2") { put("prerelease", true) }, "0.4.0-rc.1").size)
        assertEquals(1, AppUpdates.decodeResponse(release(), "0.4.0-rc.1").size)
    }
    @Test fun `a release without exactly one expected uploaded universal APK is ignored`() {
        assertTrue(AppUpdates.decodeResponse(release { putJsonArray("assets") {} }, "0.3.0").isEmpty())
        val bad = release().replace("\"uploaded\"", "\"new\"")
        assertTrue(AppUpdates.decodeResponse(bad, "0.3.0").isEmpty())
    }
    @Test fun `mirror responses cannot substitute an external repository or malformed digest`() {
        for (modified in listOf(
            release().replace("https://github.com/", "http://github.com/"),
            release().replace("/noionion/ISAtlas/releases/", "/attacker/ISAtlas/releases/"),
            release().replace("https://github.com/", "https://github.com.evil.example/"),
            release().replace("sha256:${"a".repeat(64)}", "sha256:1234"),
        )) assertTrue(AppUpdates.decodeResponse(modified, "0.3.0").isEmpty())
    }
    @Test fun `channel consensus chooses the newest semantic version despite stale mirrors`() {
        val official = AppUpdates.decodeResponse(release("v0.4.0"), "0.3.0")
        val mirror = AppUpdates.decodeResponse(release("v0.10.0"), "0.3.0")
        assertEquals("0.10.0", AppUpdates.newest(official + mirror)?.version)
        assertEquals("0.10.0", AppUpdates.newest(mirror + official)?.version)
    }
    @Test fun `response arrays need not be sorted and empty releases are valid`() {
        val response = "[${release("v0.4.0")},${release("v0.12.0")},${release("v0.5.0")}]"
        assertEquals("0.12.0", AppUpdates.newest(AppUpdates.decodeResponse(response, "0.3.0"))?.version)
        assertTrue(AppUpdates.decodeResponse("[]", "0.3.0").isEmpty())
        assertNull(AppUpdates.restore("{invalid}"))
    }
    @Test fun `API error objects are failures not evidence that app is latest`() {
        for (body in listOf("{\"message\":\"rate limit\"}", "null", "<html>blocked</html>")) {
            assertTrue(runCatching { AppUpdates.decodeResponse(body, "0.3.0") }.isFailure)
        }
    }
    @Test fun `all configured channels use HTTPS and official URL is preserved`() {
        assertEquals(2, UpdateChannels.all.size)
        val url = "${AppUpdates.API}/latest"
        assertEquals(url, UpdateChannels.all.first().url(url))
        assertTrue(UpdateChannels.all.all { it.url(url).startsWith("https://") })
        assertEquals("https://gh-proxy.com/$url", UpdateChannels.all.last().url(url))
    }
    @Test fun `explicit track overrides installed version type`() {
        val preview = release("v0.5.0-beta.1") { put("prerelease", true) }
        assertEquals("0.5.0-beta.1", AppUpdates.decodeResponse(preview, "0.4.1", UpdateTrack.PREVIEW).single().version)
        assertTrue(AppUpdates.decodeResponse(preview, "0.5.0-alpha.1", UpdateTrack.STABLE).isEmpty())
    }
    @Test fun `preview track includes stable versions and selects the newest semantic version`() {
        val response = "[${release("v0.6.0-beta.1") { put("prerelease", true) }},${release("v0.5.0")},${release("v0.6.0")}]"
        assertEquals("0.6.0", AppUpdates.newest(AppUpdates.decodeResponse(response, "0.4.1", UpdateTrack.PREVIEW))!!.version)
        assertEquals("0.6.0", AppUpdates.newest(AppUpdates.decodeResponse(response, "0.4.1", UpdateTrack.STABLE))!!.version)
    }
    @Test fun `prerelease flag persists even when tag has no prerelease suffix`() {
        val marked = release("v0.5.0") { put("prerelease", true) }
        assertTrue(AppUpdates.decodeResponse(marked, "0.4.1", UpdateTrack.STABLE).isEmpty())
        val result = AppUpdates.decodeResponse(marked, "0.4.1", UpdateTrack.PREVIEW).single()
        assertTrue(result.prerelease)
        assertEquals(result, AppUpdates.restore(result.encode()))
        assertFalse(UpdateTrack.STABLE.accepts(AppUpdates.restore(result.encode())!!))
        assertTrue(AppUpdates.decodeResponse(release("v0.5.0-beta.1"), "0.4.1", UpdateTrack.STABLE).isEmpty())
    }
    @Test fun `switching to stable cannot offer downgrades from an installed preview`() {
        assertTrue(AppUpdates.decodeResponse(release("v0.4.1"), "0.5.0-beta.1", UpdateTrack.STABLE).isEmpty())
        assertTrue(AppUpdates.decodeResponse(release("v0.4.1"), "0.5.0-beta.1", UpdateTrack.PREVIEW).isEmpty())
        assertEquals("0.5.0", AppUpdates.decodeResponse(release("v0.5.0"), "0.5.0-beta.1", UpdateTrack.STABLE).single().version)
    }
    @Test fun `track migration preserves existing defaults but explicit preferences survive upgrades`() {
        assertEquals(UpdateTrack.STABLE, UpdateTrack.restore(null, "0.4.1"))
        assertEquals(UpdateTrack.PREVIEW, UpdateTrack.restore(null, "0.5.0-beta.1"))
        assertEquals(UpdateTrack.STABLE, UpdateTrack.restore("STABLE", "0.5.0-beta.1"))
        assertEquals(UpdateTrack.PREVIEW, UpdateTrack.restore("PREVIEW", "0.5.0"))
        assertEquals(UpdateTrack.STABLE, UpdateTrack.restore("invalid", "0.5.0"))
    }
    @Test fun `legacy cached previews cannot enter the stable track`() {
        val old = AppRelease("v0.5.0-beta.1", "0.5.0-beta.1", "", "${AppUpdates.REPOSITORY}/releases/download/v0.5.0-beta.1/ISAtlas-0.5.0-beta.1.apk", 123)
        val restored = AppUpdates.restore(old.encode().replace(",\"prerelease\":true", ""))!!
        assertFalse(UpdateTrack.STABLE.accepts(restored))
        assertTrue(UpdateTrack.PREVIEW.accepts(restored))
    }
}
