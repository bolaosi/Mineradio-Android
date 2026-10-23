package com.mineradio.android.music;

import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.UUID;
import static org.junit.Assert.*;

public class QqLoginPolicyTest {
    private static final String COOKIE = "uin=12345; qm_keyst=TEST_ONLY_NOT_REAL";
    private static JSONObject account() throws Exception { return new JSONObject().put("userId", "12345").put("loggedIn", true).put("verified", true); }
    private static File directory() throws Exception { return Files.createTempDirectory("mineradio-qq-test-").toFile(); }
    @Test public void actualOfficialLoginPageAndOAuthFramesAreAllowed() {
        for (String url : new String[]{"https://y.qq.com/n/ryqq/profile", "https://y.qq.com/n/ryqq_v2/profile", "https://graph.qq.com/oauth2.0/authorize", "https://graph.qq.com/oauth2.0/show", "https://xui.ptlogin2.qq.com/cgi-bin/xlogin", "https://open.weixin.qq.com/connect/qrconnect"}) assertTrue(url, QqLoginPolicy.allowPage(url));
        assertTrue(QqLoginPolicy.allowResource("https://imgcache.qq.com/open/connect/widget/pc/login/pt_adapt.js"));
        assertTrue(QqLoginPolicy.allowResource("https://y.qq.com/ryqq/js/profile.chunk.js"));
    }
    @Test public void arbitraryNavigationCannotReachPrivateOrUntrustedOrigins() {
        for (String url : new String[]{"http://y.qq.com/", "https://evil.qq.com/", "https://y.qq.com.evil.invalid/", "https://y.qq.com@evil.invalid/", "https://evil.invalid@y.qq.com/", "https://127.0.0.1/", "https://y.qq.com:8443/", "javascript:alert(1)", "data:text/html,hello", "file:///data/", "content://private", "intent://oauth/#Intent;package=evil;end", "https://y.qq.com\\@evil.invalid/"}) assertFalse(url, QqLoginPolicy.allowPage(url));
        assertFalse(QqLoginPolicy.allowResource("https://qq.com.evil.invalid/script.js"));
    }
    @Test public void externalQqSchemesAreExplicitAndArbitraryIntentExtrasAreRejected() {
        assertTrue(QqLoginPolicy.allowQqApp("mqqopensdkapi://example/login?opaque=TEST_ONLY"));
        assertTrue(QqLoginPolicy.allowQqApp("mqqapi://example/login"));
        assertFalse(QqLoginPolicy.allowQqApp("intent://x#Intent;package=evil;end"));
        assertFalse(QqLoginPolicy.allowQqApp("https://evil.invalid/"));
        assertFalse(QqLoginPolicy.allowQqApp("mqqapi://user:password@example/"));
    }
    @Test public void candidateHeadersAreMusicScopedDeduplicatedAndNeverEquivalentToVerified() {
        String merged = QqLoginPolicy.mergeMusicCookies(Arrays.asList("uin=12345; qm_keyst=TEST_ONLY_FIRST; eas_sid=TEST_SESSION", "uin=99999; qm_keyst=TEST_ONLY_SECOND; p_skey=TEST_OAUTH"));
        assertEquals("uin=12345; qm_keyst=TEST_ONLY_FIRST; eas_sid=TEST_SESSION; p_skey=TEST_OAUTH", merged);
        assertTrue(QqLoginPolicy.hasCandidate(merged));
        assertTrue(QqLoginPolicy.hasCandidate("uin=o0012345; p_skey=TEST_ONLY"));
        assertFalse(QqLoginPolicy.hasCandidate("uin=0; qm_keyst=TEST_ONLY"));
        assertFalse(QqLoginPolicy.hasCandidate("uin=12345; nickname=NotAKey"));
        for (String url : QqLoginPolicy.COOKIE_URLS) assertTrue(url.startsWith("https://y.qq.com/") || url.startsWith("https://u.y.qq.com/") || url.startsWith("https://c.y.qq.com/"));
    }
    @Test public void controlCharactersAndOversizedCookiesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> QqLoginPolicy.mergeMusicCookies(Arrays.asList("uin=123\r\nHeader:bad")));
        assertThrows(IllegalArgumentException.class, () -> QqLoginPolicy.mergeMusicCookies(Arrays.asList("x=" + "A".repeat(32768))));
    }
    @Test public void handoffRequiresRandomUuidAndVerifiedAccount() throws Exception {
        File directory = directory(); String nonce = UUID.randomUUID().toString();
        assertTrue(QqLoginPolicy.validRequestId(nonce)); assertFalse(QqLoginPolicy.validRequestId("../../other"));
        assertFalse(QqLoginPolicy.validRequestId("00000000-0000-0000-0000-000000000000"));
        assertThrows(Exception.class, () -> QqLoginHandoff.write(directory, "../../other", COOKIE, account(), System.currentTimeMillis()));
        JSONObject unverified = new JSONObject().put("loggedIn", true).put("verified", false).put("userId", "12345");
        assertThrows(Exception.class, () -> QqLoginHandoff.write(directory, nonce, COOKIE, unverified, System.currentTimeMillis()));
        assertEquals(0, directory.listFiles().length);
    }
    @Test public void handoffIsSingleUseBoundedAndRemovedAfterConsumption() throws Exception {
        File directory = directory(); String nonce = UUID.randomUUID().toString(); long now = System.currentTimeMillis();
        QqLoginHandoff.write(directory, nonce, COOKIE, account(), now);
        assertEquals(COOKIE, QqLoginHandoff.take(directory, nonce, now));
        assertEquals("", QqLoginHandoff.take(directory, nonce, now)); assertEquals(0, directory.listFiles().length);
    }
    @Test public void expiredFutureAndOversizedHandoffCannotSupplyCredentials() throws Exception {
        File directory = directory(); long now = System.currentTimeMillis();
        String expired = UUID.randomUUID().toString(); QqLoginHandoff.write(directory, expired, COOKIE, account(), now - QqLoginHandoff.TTL_MS - 1);
        assertEquals("", QqLoginHandoff.take(directory, expired, now));
        String future = UUID.randomUUID().toString(); QqLoginHandoff.write(directory, future, COOKIE, account(), now + 60000);
        assertEquals("", QqLoginHandoff.take(directory, future, now));
        String large = UUID.randomUUID().toString(); Files.write(new File(directory, large + ".json").toPath(), new byte[65537]);
        assertEquals("", QqLoginHandoff.take(directory, large, now)); assertFalse(new File(directory, large + ".json").exists());
    }
    @Test public void cancellationAndExpiredCleanupOnlyRemoveOwnedRequestFiles() throws Exception {
        File directory = directory(); long now = System.currentTimeMillis(); String nonce = UUID.randomUUID().toString();
        QqLoginHandoff.write(directory, nonce, COOKIE, account(), now); QqLoginHandoff.discard(directory, nonce);
        assertEquals("", QqLoginHandoff.take(directory, nonce, now));
        File unrelated = new File(directory, "notes.txt"); Files.write(unrelated.toPath(), "keep".getBytes(StandardCharsets.UTF_8));
        File stale = new File(directory, UUID.randomUUID() + ".tmp"); Files.write(stale.toPath(), new byte[]{1}); assertTrue(stale.setLastModified(now - QqLoginHandoff.TTL_MS - 1));
        QqLoginHandoff.cleanExpired(directory, now); assertFalse(stale.exists()); assertTrue(unrelated.exists());
    }
}
