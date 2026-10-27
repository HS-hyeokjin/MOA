package com.moa.server.photos;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.exif.ExifIFD0Directory;
import com.moa.server.api.ApiException;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class PhotoFiles {
    public static final long MAX_BYTES = 10L * 1024 * 1024;
    private final Path originals;
    private final Path previews;
    private final Path thumbnails;
    public record Stored(String id, String mimeType, long byteSize, int width, int height) { }

    public PhotoFiles(@Value("${moa.data-directory}") String directory) throws IOException {
        Path root = Path.of(directory).toAbsolutePath().normalize();
        originals = Files.createDirectories(root.resolve("photos/originals"));
        previews = Files.createDirectories(root.resolve("photos/previews"));
        thumbnails = Files.createDirectories(root.resolve("photos/thumbnails"));
    }

    public Stored store(MultipartFile upload) {
        if (upload.isEmpty() || upload.getSize() > MAX_BYTES) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 한 장에 10MB까지 올릴 수 있어요.");
        }
        String id = UUID.randomUUID().toString();
        Path original = originals.resolve(id);
        try {
            try (var input = upload.getInputStream(); var output = Files.newOutputStream(original)) {
                byte[] buffer = new byte[16_384];
                long total = 0;
                for (int count; (count = input.read(buffer)) != -1;) {
                    total += count;
                    if (total > MAX_BYTES) throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "사진은 한 장에 10MB까지 올릴 수 있어요.");
                    output.write(buffer, 0, count);
                }
            }
            BufferedImage decoded;
            int width;
            int height;
            String mime;
            try (var input = ImageIO.createImageInputStream(original.toFile())) {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw unsupported();
                var reader = readers.next();
                try {
                    String format = reader.getFormatName().toLowerCase(Locale.ROOT);
                    mime = switch (format) { case "jpeg", "jpg" -> "image/jpeg"; case "png" -> "image/png"; default -> throw unsupported(); };
                    reader.setInput(input, true, true);
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                    if (width < 1 || height < 1 || width > 30_000 || height > 30_000 || (long) width * height > 100_000_000L) {
                        throw new ApiException(HttpStatus.BAD_REQUEST, "사진 해상도가 너무 커요. 작은 크기로 저장한 뒤 올려 주세요.");
                    }
                    var parameters = reader.getDefaultReadParam();
                    int sample = Math.max(1, Math.max(width, height) / 1_600);
                    parameters.setSourceSubsampling(sample, sample, 0, 0);
                    decoded = reader.read(0, parameters);
                } finally { reader.dispose(); }
            }
            int orientation = orientation(original);
            BufferedImage oriented = orient(decoded, orientation);
            writeScaled(oriented, previews.resolve(id + ".jpg"), 1_600);
            writeScaled(oriented, thumbnails.resolve(id + ".jpg"), 480);
            return new Stored(id, mime, Files.size(original), orientation >= 5 ? height : width, orientation >= 5 ? width : height);
        } catch (ApiException error) {
            remove(id);
            throw error;
        } catch (IOException | RuntimeException error) {
            remove(id);
            throw new ApiException(HttpStatus.BAD_REQUEST, "읽을 수 없는 사진이에요. 다른 사진을 선택해 주세요.");
        }
    }

    private ApiException unsupported() { return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "현재 JPG와 PNG 사진을 올릴 수 있어요."); }

    private int orientation(Path path) {
        try {
            var directory = ImageMetadataReader.readMetadata(path.toFile()).getFirstDirectoryOfType(ExifIFD0Directory.class);
            Integer value = directory == null ? null : directory.getInteger(ExifIFD0Directory.TAG_ORIENTATION);
            return value != null && value >= 1 && value <= 8 ? value : 1;
        } catch (Exception ignored) { return 1; }
    }

    static BufferedImage orient(BufferedImage source, int orientation) {
        if (orientation == 1) return source;
        int width = source.getWidth(), height = source.getHeight();
        BufferedImage target = new BufferedImage(orientation >= 5 ? height : width, orientation >= 5 ? width : height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            int dx, dy;
            switch (orientation) {
                case 2 -> { dx = width - 1 - x; dy = y; }
                case 3 -> { dx = width - 1 - x; dy = height - 1 - y; }
                case 4 -> { dx = x; dy = height - 1 - y; }
                case 5 -> { dx = y; dy = x; }
                case 6 -> { dx = height - 1 - y; dy = x; }
                case 7 -> { dx = height - 1 - y; dy = width - 1 - x; }
                case 8 -> { dx = y; dy = width - 1 - x; }
                default -> { dx = x; dy = y; }
            }
            target.setRGB(dx, dy, source.getRGB(x, y));
        }
        return target;
    }

    private void writeScaled(BufferedImage source, Path output, int maximum) throws IOException {
        double scale = Math.min(1.0, (double) maximum / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage resized = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = resized.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally { graphics.dispose(); }
        if (!ImageIO.write(resized, "jpeg", output.toFile())) throw new IOException("JPEG encoder unavailable");
    }

    public Path path(String id, String variant) {
        // Database identifiers, never client filenames, determine disk paths.
        if (!id.matches("[a-f0-9-]{36}")) throw new ApiException(HttpStatus.NOT_FOUND, "사진을 찾을 수 없어요.");
        return switch (variant) {
            case "original" -> originals.resolve(id);
            case "preview" -> previews.resolve(id + ".jpg");
            case "thumbnail" -> thumbnails.resolve(id + ".jpg");
            default -> throw new ApiException(HttpStatus.NOT_FOUND, "사진을 찾을 수 없어요.");
        };
    }

    public void remove(String id) {
        for (String variant : new String[]{"original", "preview", "thumbnail"}) {
            try { Files.deleteIfExists(path(id, variant)); }
            catch (IOException ignored) { /* A leftover file has no public route without its DB row. */ }
        }
    }
}
