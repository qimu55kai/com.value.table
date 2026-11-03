package com.value.table.util;

import org.eclipse.swt.SWT;
import org.eclipse.swt.events.KeyEvent;

/**
 * Recognises the copy gesture from a key event.
 * <p>
 * The obvious test, {@code event.character == 'c'}, never matches on Windows.
 * A printable key arrives twice: first as {@code SWT.KeyDown} with the virtual
 * key ({@code keyCode == 'c'}), then as {@code WM_CHAR} with the character the
 * key produces - and that character is <em>not</em> 'c'. With Ctrl held, Windows
 * sends the control character for that letter, so Ctrl+C delivers
 * {@code character == 0x03} (ETX), Ctrl+V {@code 0x16}. Measured on win32 with
 * SWT 3.130:
 * </p>
 * <pre>
 * Ctrl+C -&gt; stateMask=0x40000 (SWT.MOD1)  keyCode=0x63 ('c')  character=0x03
 * </pre>
 * <p>
 * The virtual key is the one piece that is always the letter the user pressed,
 * so it is what this class goes by. The character is still accepted for
 * platforms that report Ctrl+letter that way, and so is the control character
 * for a keyboard layout that only sends {@code WM_CHAR}.
 * </p>
 */
public final class Keys {

    /** The character Windows puts in {@code WM_CHAR} for Ctrl+C. */
    private static final char ETX = 0x03;

    private Keys() {
    }

    /** True when the event is Ctrl+C (Cmd+C on macOS, where MOD1 is the command key). */
    public static boolean isCopy(KeyEvent event) {
        return event != null && isCopy(event.stateMask, event.keyCode, event.character);
    }

    /**
     * The same test on a raw {@code SWT.KeyDown} event, which the display filter
     * delivers as an {@code Event} rather than as a {@code KeyEvent}.
     */
    public static boolean isCopy(int stateMask, int keyCode, char character) {
        if ((stateMask & SWT.MOD1) == 0) {
            return false;
        }
        return keyCode == 'c' || keyCode == 'C'
                || character == 'c' || character == 'C'
                || character == ETX;
    }
}
