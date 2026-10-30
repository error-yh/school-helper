/* SPDX-License-Identifier: GPL-3.0-only; invented observations, no private fixture. */
package com.lingion.sleepy.importing.ocr

import com.lingion.sleepy.importing.*
import com.lingion.sleepy.importing.formats.FormatParseContext
import org.junit.Assert.*
import org.junit.Test

class MatrixHeaderEvidenceTest {
    private fun word(t:String,x:Float,y:Float=45f,w:Float=9f)=OcrSpan(t,OcrLevel.WORD,95f,SourcePosition(1,TextBox(x,y,x+w,y+10)))
    private fun plan():MatrixPlan {
        val words=listOf(word("教学周",10f,20f,60f),word("星期一",200f,20f,80f),word("星期二",600f,20f,80f),
            word("1",200f),word("2",220f),word("5",280f),word("6",300f),
            word("1",600f),word("2",620f),word("3",640f),word("4",660f),word("5",680f),word("6",700f),word("第1周",10f,100f,40f))
        val p=PositionedPage(1,1000f,300f,emptyList())
        val v=OcrVariantEvidence(OcrVariantId.ORIGINAL,1000,300,PageAffineTransform.IDENTITY,p,words,emptySet(),null,OcrWordQuality.EMPTY,true)
        val page=OcrLayoutPage(1,1000f,300f,listOf(v),OcrVariantId.ORIGINAL)
        return checkNotNull(WeekMatrixAdapter().detect(OcrGeometryIndex(page,v,LayoutWork(OcrLayoutContext(FormatParseContext("synthetic-header",20))))))
    }
    @Test fun onlyActuallyObservedGapDigitsAreSupplementedWithTheirExactPositions() {
        val seen=listOf(word("3",240f),word("4",260f))
        assertEquals(seen,MatrixHeaderEvidence.additions(plan(),seen))
    }
    @Test fun aGapNeverGeneratesDigitsWithoutNativeEvidence() {
        assertTrue(MatrixHeaderEvidence.additions(plan(),emptyList()).isEmpty())
    }
    @Test fun lettersAndContradictoryOutOfOrderNumbersAreNotCorrections() {
        assertTrue(MatrixHeaderEvidence.additions(plan(),listOf(word("A",240f),word("8",260f))).isEmpty())
    }
    @Test fun anObservedDigitInTheCourseBodyIsNotAHeaderLabel() {
        assertTrue(MatrixHeaderEvidence.additions(plan(),listOf(word("3",240f,100f))).isEmpty())
    }
    @Test fun onlyOneObservedMissingLabelDoesNotFillTheOtherOne() {
        val seen=word("3",240f)
        assertEquals(listOf(seen),MatrixHeaderEvidence.additions(plan(),listOf(seen)))
    }
    @Test fun duplicateOrConflictingObservationsNeverOverwriteExistingAnchors() {
        assertTrue(MatrixHeaderEvidence.additions(plan(),listOf(word("2",220f),word("3",220f))).isEmpty())
    }
}
