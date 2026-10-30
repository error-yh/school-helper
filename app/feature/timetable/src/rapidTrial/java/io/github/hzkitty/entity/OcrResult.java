/* Third-party trial source: hzkitty/RapidOCR4j-Android, commit 8e11959786c0fe609c68f4b133ee3210940a0df8.
 * Copyright: original RapidOCR4j and embedded Clipper authors. License: see bundled notices.
 * Modified for school-helper: provenance notice; source otherwise unchanged for comparison.
 */
package io.github.hzkitty.entity;

import java.util.List;

/**
 * OCR 识别结果
 */
public class OcrResult {
    private String strRes;
    private List<RecResult> recRes;
    private double elapseTime;  // 总耗时
    private double detTime;  // 检测耗时
    private double clsTime;  // 分类耗时
    private double recTime;  // 识别耗时

    public OcrResult(String strRes, List<RecResult> recRes, double elapseTime, double detTime, double clsTime, double recTime) {
        this.strRes = strRes;
        this.recRes = recRes;
        this.elapseTime = elapseTime;
        this.detTime = detTime;
        this.clsTime = clsTime;
        this.recTime = recTime;
    }

    public String getStrRes() {
        return strRes;
    }

    public void setStrRes(String strRes) {
        this.strRes = strRes;
    }

    public List<RecResult> getRecRes() {
        return recRes;
    }

    public void setRecRes(List<RecResult> recRes) {
        this.recRes = recRes;
    }

    public double getElapseTime() {
        return elapseTime;
    }

    public void setElapseTime(double elapseTime) {
        this.elapseTime = elapseTime;
    }

    public double getDetTime() {
        return detTime;
    }

    public void setDetTime(double detTime) {
        this.detTime = detTime;
    }

    public double getClsTime() {
        return clsTime;
    }

    public void setClsTime(double clsTime) {
        this.clsTime = clsTime;
    }

    public double getRecTime() {
        return recTime;
    }

    public void setRecTime(double recTime) {
        this.recTime = recTime;
    }

    @Override
    public String toString() {
        return "OcrResult{" +
                "strRes='" + strRes + '\'' +
                ", recRes=" + recRes +
                ", elapseTime=" + elapseTime +
                ", detTime=" + detTime +
                ", clsTime=" + clsTime +
                ", recTime=" + recTime +
                '}';
    }
}