/* Third-party trial source: hzkitty/RapidOCR4j-Android, commit 8e11959786c0fe609c68f4b133ee3210940a0df8.
 * Copyright: original RapidOCR4j and embedded Clipper authors. License: see bundled notices.
 * Modified for school-helper: provenance notice; source otherwise unchanged for comparison.
 */
package io.github.hzkitty.entity;

import org.opencv.core.Point;

import java.util.Arrays;
import java.util.List;

public class WordBoxResult {
    private List<String> wordBoxContentList;
    private List<Point[]> sortedWordBoxList;
    private List<Float> confList;

    public WordBoxResult(List<String> wordBoxContentList, List<Point[]> sortedWordBoxList, List<Float> confList) {
        this.wordBoxContentList = wordBoxContentList;
        this.sortedWordBoxList = sortedWordBoxList;
        this.confList = confList;
    }

    public List<String> getWordBoxContentList() {
        return wordBoxContentList;
    }

    public void setWordBoxContentList(List<String> wordBoxContentList) {
        this.wordBoxContentList = wordBoxContentList;
    }

    public List<Point[]> getSortedWordBoxList() {
        return sortedWordBoxList;
    }

    public void setSortedWordBoxList(List<Point[]> sortedWordBoxList) {
        this.sortedWordBoxList = sortedWordBoxList;
    }

    public List<Float> getConfList() {
        return confList;
    }

    public void setConfList(List<Float> confList) {
        this.confList = confList;
    }

    @Override
    public String toString() {
        return "WordBoxResult{" +
                "wordBoxContentList=" + wordBoxContentList +
                ", sortedWordBoxList=" + Arrays.deepToString(sortedWordBoxList.toArray()) +
                ", confList=" + confList +
                '}';
    }
}