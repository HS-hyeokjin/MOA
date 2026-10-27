package com.moa.server.photos;

import com.moa.server.api.ApiException;
import com.moa.server.auth.FamilyMember;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1")
public class AlbumController {
    private final AlbumService album;
    private final PhotoFiles files;
    public AlbumController(AlbumService album, PhotoFiles files) { this.album = album; this.files = files; }

    @GetMapping("/posts")
    AlbumService.Feed feed(@AuthenticationPrincipal FamilyMember member, @RequestParam(required = false) Long before,
                           @RequestParam(defaultValue = "20") int limit) { return album.feed(member.id(), before, limit); }

    @PostMapping(value = "/posts", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    AlbumService.Post upload(@AuthenticationPrincipal FamilyMember member, @RequestParam(defaultValue = "") String caption,
                             @RequestHeader("Idempotency-Key") String requestId, @RequestParam("photos") List<MultipartFile> uploads) {
        return album.upload(member, caption, requestId, uploads);
    }
    @PutMapping("/posts/{id}/like")
    AlbumService.Post like(@AuthenticationPrincipal FamilyMember member, @PathVariable long id) { return album.like(id, member.id(), true); }
    @DeleteMapping("/posts/{id}/like")
    AlbumService.Post unlike(@AuthenticationPrincipal FamilyMember member, @PathVariable long id) { return album.like(id, member.id(), false); }
    @DeleteMapping("/posts/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@AuthenticationPrincipal FamilyMember member, @PathVariable long id) { album.delete(id, member.id()); }

    @GetMapping("/photos/{id}/{variant}")
    ResponseEntity<Resource> photo(@PathVariable String id, @PathVariable String variant) throws IOException {
        var info = album.file(id);
        var path = files.path(info.id(), variant);
        if (!Files.isRegularFile(path)) throw new ApiException(HttpStatus.NOT_FOUND, "사진 파일을 찾을 수 없어요.");
        boolean original = variant.equals("original");
        String mime = original ? info.mimeType() : "image/jpeg";
        String filename = "MOA-" + id + (mime.equals("image/png") ? ".png" : ".jpg");
        var disposition = original ? ContentDisposition.attachment() : ContentDisposition.inline();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(mime)).contentLength(Files.size(path))
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.filename(filename, StandardCharsets.UTF_8).build().toString())
                .body(new FileSystemResource(path));
    }
}
