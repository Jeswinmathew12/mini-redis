package com.miniredis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MainTest {

    @Test
    void noArgumentsMeansDefaultPortAndNoLimit() {
        assertEquals(new Main.Options(6380, 0), Main.parseArgs(new String[] {}));
    }

    @Test
    void firstArgumentIsThePort() {
        assertEquals(new Main.Options(7000, 0), Main.parseArgs(new String[] {"7000"}));
    }

    @Test
    void secondArgumentIsTheKeyLimit() {
        assertEquals(new Main.Options(7000, 500), Main.parseArgs(new String[] {"7000", "500"}));
    }

    @Test
    void zeroKeyLimitMeansUnlimitedAndPortZeroPicksAFreePort() {
        assertEquals(new Main.Options(0, 0), Main.parseArgs(new String[] {"0", "0"}));
    }

    @Test
    void portMustBeInRange() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseArgs(new String[] {"-1"}));
        assertThrows(IllegalArgumentException.class, () -> Main.parseArgs(new String[] {"65536"}));
        assertEquals(65535, Main.parseArgs(new String[] {"65535"}).port());
    }

    @Test
    void nonNumericArgumentsAreRejectedWithAClearMessage() {
        IllegalArgumentException port = assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(new String[] {"abc"}));
        assertTrue(port.getMessage().contains("port"), port.getMessage());

        IllegalArgumentException keys = assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(new String[] {"7000", "many"}));
        assertTrue(keys.getMessage().contains("maxKeys"), keys.getMessage());
    }

    @Test
    void negativeKeyLimitIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Main.parseArgs(new String[] {"7000", "-5"}));
    }

    @Test
    void tooManyArgumentsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> Main.parseArgs(new String[] {"7000", "5", "extra"}));
    }
}
