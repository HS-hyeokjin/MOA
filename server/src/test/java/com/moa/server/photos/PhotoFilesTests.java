package com.moa.server.photos;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PhotoFilesTests {
    @Test void portraitExifOrientationRotatesPixelsClockwise() {
        var source = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        source.setRGB(0, 0, Color.RED.getRGB());
        source.setRGB(1, 0, Color.BLUE.getRGB());
        var rotated = PhotoFiles.orient(source, 6);
        assertEquals(1, rotated.getWidth());
        assertEquals(2, rotated.getHeight());
        assertEquals(Color.RED.getRGB(), rotated.getRGB(0, 0));
        assertEquals(Color.BLUE.getRGB(), rotated.getRGB(0, 1));
    }
}
