package com.moa.server.photos;

import com.moa.server.api.ApiException;
import com.moa.server.auth.FamilyMember;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class AlbumService {
    public record Photo(String id, int width, int height, String mimeType, long byteSize,
                        String thumbnailUrl, String previewUrl, String originalUrl) { }
    public record Post(long id, FamilyMember author, String caption, Instant createdAt, List<Photo> photos, int likeCount, boolean likedByMe) { }
    public record Feed(List<Post> posts, Long nextCursor) { }
    public record FileInfo(String id, String mimeType) { }
    private final JdbcTemplate db;
    private final PhotoFiles files;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private final Semaphore uploadSlots = new Semaphore(2);
    public AlbumService(JdbcTemplate db, PhotoFiles files, TransactionTemplate transactions, Clock clock) {
        this.db = db; this.files = files; this.transactions = transactions; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Feed feed(String viewer, Long before, int limit) {
        if (limit < 1 || limit > 50 || (before != null && before < 1)) throw new ApiException(HttpStatus.BAD_REQUEST, "잘못된 앨범 요청이에요.");
        List<Long> ids = db.query("SELECT id FROM posts WHERE id < ? ORDER BY id DESC LIMIT ?",
                (row, index) -> row.getLong(1), before == null ? Long.MAX_VALUE : before, limit + 1);
        boolean more = ids.size() > limit;
        List<Long> page = more ? ids.subList(0, limit) : ids;
        return new Feed(page.stream().map(id -> post(id, viewer)).toList(), more ? page.getLast() : null);
    }

    public Post post(long id, String viewer) {
        var rows = db.query("""
                SELECT p.id, p.member_id, m.display_name, p.caption, p.created_at,
                (SELECT count(*) FROM likes l WHERE l.post_id = p.id) AS like_count,
                EXISTS(SELECT 1 FROM likes l WHERE l.post_id = p.id AND l.member_id = ?) AS liked
                FROM posts p JOIN members m ON m.id = p.member_id WHERE p.id = ?
                """, (row, index) -> new Post(row.getLong("id"), new FamilyMember(row.getString("member_id"), row.getString("display_name")),
                row.getString("caption"), Instant.ofEpochMilli(row.getLong("created_at")), List.of(), row.getInt("like_count"), row.getBoolean("liked")), viewer, id);
        if (rows.isEmpty()) throw notFound();
        Post post = rows.getFirst();
        List<Photo> photos = db.query("SELECT * FROM photos WHERE post_id = ? ORDER BY position", (row, index) -> {
            String photoId = row.getString("id");
            String base = "/api/v1/photos/" + photoId;
            return new Photo(photoId, row.getInt("width"), row.getInt("height"), row.getString("mime_type"), row.getLong("byte_size"),
                    base + "/thumbnail", base + "/preview", base + "/original");
        }, id);
        return new Post(post.id(), post.author(), post.caption(), post.createdAt(), photos, post.likeCount(), post.likedByMe());
    }

    public Post upload(FamilyMember member, String caption, String requestId, List<MultipartFile> uploads) {
        try { UUID.fromString(requestId); }
        catch (IllegalArgumentException error) { throw new ApiException(HttpStatus.BAD_REQUEST, "업로드 요청 번호가 올바르지 않아요."); }
        if (caption.length() > 500 || uploads.isEmpty() || uploads.size() > 5) throw new ApiException(HttpStatus.BAD_REQUEST, "사진은 1~5장, 설명은 500자까지 입력해 주세요.");
        var existing = existing(requestId, member.id());
        if (existing != null) return existing;
        if (!uploadSlots.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "다른 사진을 저장 중이에요. 잠시 후 다시 공유해 주세요.");
        List<PhotoFiles.Stored> stored = new ArrayList<>();
        try {
            for (var upload : uploads) stored.add(files.store(upload));
            return transactions.execute(transaction -> {
                // Recheck inside the serialized write transaction for a simultaneous retry.
                Post repeated = existing(requestId, member.id());
                if (repeated != null) {
                    stored.forEach(photo -> files.remove(photo.id()));
                    return repeated;
                }
                db.update("INSERT INTO posts(request_id, member_id, caption, created_at) VALUES (?, ?, ?, ?)",
                        requestId, member.id(), caption.strip(), clock.millis());
                long postId = db.queryForObject("SELECT last_insert_rowid()", Long.class);
                for (int position = 0; position < stored.size(); position++) {
                    var photo = stored.get(position);
                    db.update("INSERT INTO photos VALUES (?, ?, ?, ?, ?, ?, ?)", photo.id(), postId, position,
                            photo.mimeType(), photo.byteSize(), photo.width(), photo.height());
                }
                return post(postId, member.id());
            });
        } catch (RuntimeException error) {
            stored.forEach(photo -> files.remove(photo.id()));
            throw error;
        } finally {
            uploadSlots.release();
        }
    }

    private Post existing(String requestId, String memberId) {
        var ids = db.query("SELECT id, member_id FROM posts WHERE request_id = ?", (row, index) -> {
            if (!row.getString("member_id").equals(memberId)) throw new ApiException(HttpStatus.CONFLICT, "다른 업로드 요청 번호를 사용해 주세요.");
            return row.getLong("id");
        }, requestId);
        return ids.isEmpty() ? null : post(ids.getFirst(), memberId);
    }

    public Post like(long id, String member, boolean liked) {
        return transactions.execute(transaction -> {
            post(id, member);
            if (liked) db.update("INSERT OR IGNORE INTO likes VALUES (?, ?)", id, member);
            else db.update("DELETE FROM likes WHERE post_id = ? AND member_id = ?", id, member);
            return post(id, member);
        });
    }

    public FileInfo file(String id) {
        return db.query("SELECT id, mime_type FROM photos WHERE id = ?", (row, index) -> new FileInfo(row.getString(1), row.getString(2)), id)
                .stream().findFirst().orElseThrow(this::notFound);
    }

    public void delete(long id, String memberId) {
        List<String> ids = transactions.execute(transaction -> {
            Post post = post(id, memberId);
            if (!post.author().id().equals(memberId)) throw new ApiException(HttpStatus.FORBIDDEN, "내가 올린 사진만 삭제할 수 있어요.");
            List<String> photoIds = post.photos().stream().map(Photo::id).toList();
            db.update("DELETE FROM posts WHERE id = ?", id);
            return photoIds;
        });
        // Files are removed only after the database transaction has committed.
        ids.forEach(files::remove);
    }

    private ApiException notFound() { return new ApiException(HttpStatus.NOT_FOUND, "게시물을 찾을 수 없어요."); }
}
