package com.moa.server;

import com.jayway.jsonpath.JsonPath;
import com.moa.server.auth.AuthService;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AlbumIntegrationTests {
    static final Path ROOT = Path.of("build/test-data", UUID.randomUUID().toString()).toAbsolutePath();
    @DynamicPropertySource static void configure(DynamicPropertyRegistry registry) { registry.add("moa.data-directory", ROOT::toString); }
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired JdbcTemplate db;

    @BeforeEach void reset() {
        db.update("DELETE FROM posts");
        db.update("DELETE FROM sessions");
        db.update("DELETE FROM invites");
    }

    String login(String member) throws Exception {
        String code = auth.invite(member).code();
        var response = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + code + "\"}")).andExpect(status().isOk()).andReturn();
        return JsonPath.read(response.getResponse().getContentAsString(), "$.token");
    }
    byte[] image() throws Exception {
        BufferedImage image = new BufferedImage(120, 80, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, Color.RED.getRGB());
        var output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
    String upload(String token, String key) throws Exception {
        return mvc.perform(multipart("/api/v1/posts")
                .file(new MockMultipartFile("photos", "../../private.png", "image/png", image()))
                .param("caption", "오늘의 사진").header("Idempotency-Key", key).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
    }

    @Test void invitationIsSingleUseAndTokensAreStoredOnlyAsHashes() throws Exception {
        var invite = auth.invite("mom");
        String body = "{\"code\":\"" + invite.code() + "\"}";
        var result = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.member.id").value("mom")).andReturn();
        String token = JsonPath.read(result.getResponse().getContentAsString(), "$.token");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isUnauthorized());
        assertNotEquals(token, db.queryForObject("SELECT token_hash FROM sessions", String.class));
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.id").value("mom"));
        mvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token)).andExpect(status().isNoContent());
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void expiredInvitesAndExpiredSessionsCannotLogin() throws Exception {
        var invite = auth.invite("dad");
        db.update("UPDATE invites SET expires_at = 0");
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"code\":\"" + invite.code() + "\"}")).andExpect(status().isUnauthorized());
        String token = login("dad");
        db.update("UPDATE sessions SET expires_at = 0");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
    }

    @Test void displayNameChangesApplyToExistingSessionsAndRejectControlCharacters() throws Exception {
        String token = login("me");
        auth.rename("me", "가족 이름");
        mvc.perform(get("/api/v1/me").header("Authorization", "Bearer " + token)).andExpect(jsonPath("$.displayName").value("가족 이름"));
        assertThrows(IllegalArgumentException.class, () -> auth.rename("me", "bad\n\rname"));
    }

    @Test void simultaneousRetriesCommitOnePostAndDoNotLeaveExtraOriginals() throws Exception {
        String token = login("me"), key = UUID.randomUUID().toString();
        long before;
        try (var paths = Files.list(ROOT.resolve("photos/originals"))) { before = paths.count(); }
        var start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> { start.await(); return upload(token, key); });
            var second = executor.submit(() -> { start.await(); return upload(token, key); });
            start.countDown();
            Number firstId = JsonPath.read(first.get(15, TimeUnit.SECONDS), "$.id");
            Number secondId = JsonPath.read(second.get(15, TimeUnit.SECONDS), "$.id");
            assertEquals(firstId, secondId);
        }
        assertEquals(1, db.queryForObject("SELECT count(*) FROM posts", Integer.class));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM photos", Integer.class));
        try (var paths = Files.list(ROOT.resolve("photos/originals"))) { assertEquals(before + 1, paths.count()); }
    }

    @Test void fiveMembersAreAvailableAndRevocationClosesAllMemberSessions() throws Exception {
        String first = login("sister"), second = login("sister");
        mvc.perform(get("/api/v1/members").header("Authorization", "Bearer " + first)).andExpect(jsonPath("$.length()").value(5));
        auth.revoke("sister");
        assertTrue(auth.authenticate(first).isEmpty());
        assertTrue(auth.authenticate(second).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> auth.invite("outsider"));
    }

    @Test void uploadPreservesOriginalAndProtectsEveryImageVariant() throws Exception {
        String token = login("sister");
        String body = upload(token, UUID.randomUUID().toString());
        String id = JsonPath.read(body, "$.photos[0].id");
        assertArrayEquals(image(), Files.readAllBytes(ROOT.resolve("photos/originals").resolve(id)));
        assertFalse(Files.exists(ROOT.resolve("private.png")));
        for (String variant : new String[]{"thumbnail", "preview", "original"}) {
            mvc.perform(get("/api/v1/photos/" + id + "/" + variant)).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/v1/photos/" + id + "/" + variant).header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        }
        mvc.perform(get("/api/v1/photos/" + id + "/original").header("Authorization", "Bearer " + token))
                .andExpect(content().bytes(image())).andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test void phoneJpegExifRotationAffectsPreviewButPreservesOriginal() throws Exception {
        var portrait = new BufferedImage(80, 120, BufferedImage.TYPE_INT_RGB);
        var encoded = new ByteArrayOutputStream();
        ImageIO.write(portrait, "jpeg", encoded);
        byte[] jpeg = encoded.toByteArray();
        var exif = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN);
        exif.put(new byte[]{'E', 'x', 'i', 'f', 0, 0, 'I', 'I'}).putShort((short) 42).putInt(8);
        exif.putShort((short) 1).putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) 6).putShort((short) 0).putInt(0);
        var original = new ByteArrayOutputStream();
        original.write(jpeg, 0, 2);
        original.write(new byte[]{(byte) 0xff, (byte) 0xe1, 0, 34});
        original.write(exif.array());
        original.write(jpeg, 2, jpeg.length - 2);
        String token = login("sister");
        var response = mvc.perform(multipart("/api/v1/posts")
                .file(new MockMultipartFile("photos", "phone.jpg", "image/jpeg", original.toByteArray()))
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("Authorization", "Bearer " + token))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.photos[0].width").value(120))
                .andExpect(jsonPath("$.photos[0].height").value(80)).andReturn();
        String id = JsonPath.read(response.getResponse().getContentAsString(), "$.photos[0].id");
        var preview = ImageIO.read(ROOT.resolve("photos/previews").resolve(id + ".jpg").toFile());
        assertEquals(120, preview.getWidth());
        assertEquals(80, preview.getHeight());
        assertArrayEquals(original.toByteArray(), Files.readAllBytes(ROOT.resolve("photos/originals").resolve(id)));
    }

    @Test void repeatedUploadAndLikeDoNotCreateDuplicates() throws Exception {
        String owner = login("me"), mom = login("mom");
        String key = UUID.randomUUID().toString();
        Number id = JsonPath.read(upload(owner, key), "$.id");
        assertEquals(id, JsonPath.<Number>read(upload(owner, key), "$.id"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM posts", Integer.class));
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(put("/api/v1/posts/" + id + "/like").header("Authorization", "Bearer " + mom))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.likeCount").value(1)).andExpect(jsonPath("$.likedByMe").value(true));
        }
        mvc.perform(delete("/api/v1/posts/" + id + "/like").header("Authorization", "Bearer " + mom))
                .andExpect(jsonPath("$.likeCount").value(0));
        mvc.perform(multipart("/api/v1/posts").file(new MockMultipartFile("photos", "photo.png", "image/png", image()))
                .header("Idempotency-Key", key).header("Authorization", "Bearer " + mom)).andExpect(status().isConflict());
    }

    @Test void paginationDoesNotRepeatPostsAndOnlyOwnerCanDelete() throws Exception {
        String owner = login("me"), dad = login("dad");
        String oldest = upload(owner, UUID.randomUUID().toString());
        Number oldestId = JsonPath.read(oldest, "$.id");
        String photoId = JsonPath.read(oldest, "$.photos[0].id");
        upload(owner, UUID.randomUUID().toString());
        var first = mvc.perform(get("/api/v1/posts?limit=1").header("Authorization", "Bearer " + dad))
                .andExpect(status().isOk()).andReturn();
        Number cursor = JsonPath.read(first.getResponse().getContentAsString(), "$.nextCursor");
        mvc.perform(get("/api/v1/posts?limit=1&before=" + cursor).header("Authorization", "Bearer " + dad))
                .andExpect(jsonPath("$.posts[0].id").value(oldestId.intValue())).andExpect(jsonPath("$.nextCursor").isEmpty());
        mvc.perform(delete("/api/v1/posts/" + oldestId).header("Authorization", "Bearer " + dad)).andExpect(status().isForbidden());
        mvc.perform(delete("/api/v1/posts/" + oldestId).header("Authorization", "Bearer " + owner)).andExpect(status().isNoContent());
        assertFalse(Files.exists(ROOT.resolve("photos/originals").resolve(photoId)));
        mvc.perform(get("/api/v1/photos/" + photoId + "/original").header("Authorization", "Bearer " + dad)).andExpect(status().isNotFound());
    }

    @Test void fakeImageAndMixedInvalidUploadLeaveNoPostOrFiles() throws Exception {
        String token = login("me");
        long before;
        try (var listing = Files.list(ROOT.resolve("photos/originals"))) { before = listing.count(); }
        mvc.perform(multipart("/api/v1/posts")
                .file(new MockMultipartFile("photos", "photo.png", "image/png", image()))
                .file(new MockMultipartFile("photos", "bad.png", "image/png", "<script>bad</script>".getBytes()))
                .header("Idempotency-Key", UUID.randomUUID().toString()).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnsupportedMediaType());
        assertEquals(0, db.queryForObject("SELECT count(*) FROM posts", Integer.class));
        try (var listing = Files.list(ROOT.resolve("photos/originals"))) { assertEquals(before, listing.count()); }
    }
}
