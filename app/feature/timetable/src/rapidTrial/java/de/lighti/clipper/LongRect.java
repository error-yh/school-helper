/* Third-party trial source: hzkitty/RapidOCR4j-Android, commit 8e11959786c0fe609c68f4b133ee3210940a0df8.
 * Copyright: original RapidOCR4j and embedded Clipper authors. License: see bundled notices.
 * Modified for school-helper: provenance notice; source otherwise unchanged for comparison.
 */
package de.lighti.clipper;

public class LongRect {
    public long left;
    public long top;
    public long right;
    public long bottom;

    public LongRect() {

    }

    public LongRect( long l, long t, long r, long b ) {
        left = l;
        top = t;
        right = r;
        bottom = b;
    }

    public LongRect( LongRect ir ) {
        left = ir.left;
        top = ir.top;
        right = ir.right;
        bottom = ir.bottom;
    }
}
