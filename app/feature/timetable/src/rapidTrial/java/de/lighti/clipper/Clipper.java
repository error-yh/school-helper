/* Third-party trial source: hzkitty/RapidOCR4j-Android, commit 8e11959786c0fe609c68f4b133ee3210940a0df8.
 * Copyright: original RapidOCR4j and embedded Clipper authors. License: see bundled notices.
 * Modified for school-helper: provenance notice; source otherwise unchanged for comparison.
 */
package de.lighti.clipper;

import de.lighti.clipper.Point.LongPoint;

public interface Clipper {
    enum ClipType {
        INTERSECTION, UNION, DIFFERENCE, XOR
    }

    enum Direction {
        RIGHT_TO_LEFT, LEFT_TO_RIGHT
    }

    enum EndType {
        CLOSED_POLYGON, CLOSED_LINE, OPEN_BUTT, OPEN_SQUARE, OPEN_ROUND
    }

    enum JoinType {
        SQUARE, ROUND, MITER
    }

    enum PolyFillType {
        EVEN_ODD, NON_ZERO, POSITIVE, NEGATIVE
    }

    enum PolyType {
        SUBJECT, CLIP
    }

    interface ZFillCallback {
        void zFill(LongPoint bot1, LongPoint top1, LongPoint bot2, LongPoint top2, LongPoint pt);
    }

    //InitOptions that can be passed to the constructor ...
    int REVERSE_SOLUTION = 1;

    int STRICTLY_SIMPLE = 2;

    int PRESERVE_COLINEAR = 4;

    boolean addPath(Path pg, PolyType polyType, boolean Closed);

    boolean addPaths(Paths ppg, PolyType polyType, boolean closed);

    void clear();

    boolean execute(ClipType clipType, Paths solution);

    boolean execute(ClipType clipType, Paths solution, PolyFillType subjFillType, PolyFillType clipFillType);

    boolean execute(ClipType clipType, PolyTree polytree);

    boolean execute(ClipType clipType, PolyTree polytree, PolyFillType subjFillType, PolyFillType clipFillType);
}
