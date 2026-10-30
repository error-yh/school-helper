/* Third-party trial source: hzkitty/RapidOCR4j-Android, commit 8e11959786c0fe609c68f4b133ee3210940a0df8.
 * Copyright: original RapidOCR4j and embedded Clipper authors. License: see bundled notices.
 * Modified for school-helper: provenance notice; source otherwise unchanged for comparison.
 */
package io.github.hzkitty.entity;

import java.util.List;

public class WordBoxInfo {
    private Double textIndexLen;
    private List<List<String>> wordList;
    private List<List<Integer>> wordColList;
    private List<String> stateList;
    private List<Float> confList;

    public WordBoxInfo(Double textIndexLen, List<List<String>> wordList, List<List<Integer>> wordColList, List<String> stateList, List<Float> confList) {
        this.textIndexLen = textIndexLen;
        this.wordList = wordList;
        this.wordColList = wordColList;
        this.stateList = stateList;
        this.confList = confList;
    }

    public Double getTextIndexLen() {
        return textIndexLen;
    }

    public void setTextIndexLen(Double textIndexLen) {
        this.textIndexLen = textIndexLen;
    }

    public List<List<String>> getWordList() {
        return wordList;
    }

    public void setWordList(List<List<String>> wordList) {
        this.wordList = wordList;
    }

    public List<List<Integer>> getWordColList() {
        return wordColList;
    }

    public void setWordColList(List<List<Integer>> wordColList) {
        this.wordColList = wordColList;
    }

    public List<String> getStateList() {
        return stateList;
    }

    public void setStateList(List<String> stateList) {
        this.stateList = stateList;
    }

    public List<Float> getConfList() {
        return confList;
    }

    public void setConfList(List<Float> confList) {
        this.confList = confList;
    }

    @Override
    public String toString() {
        return "WordBoxInfo{" +
                "textIndexLen=" + textIndexLen +
                ", wordList=" + wordList +
                ", wordColList=" + wordColList +
                ", stateList=" + stateList +
                ", confList=" + confList +
                '}';
    }
}