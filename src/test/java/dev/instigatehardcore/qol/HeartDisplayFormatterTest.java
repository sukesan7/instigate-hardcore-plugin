package dev.instigatehardcore.qol;

import dev.instigatehardcore.ui.HeartDisplayFormatter;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class HeartDisplayFormatterTest {
    @Test void formatsHeartCount() {
        assertEquals("10", HeartDisplayFormatter.hearts(20));
        assertEquals("9.5", HeartDisplayFormatter.hearts(19));
        assertEquals("4.5", HeartDisplayFormatter.hearts(9));
        assertEquals("1.5", HeartDisplayFormatter.hearts(3));
        assertEquals("4.9", HeartDisplayFormatter.hearts(9.99));
        assertEquals("1.9", HeartDisplayFormatter.hearts(3.99));
        assertEquals("0", HeartDisplayFormatter.hearts(0));
        assertEquals("?", HeartDisplayFormatter.hearts(Double.NaN));
    }
}
