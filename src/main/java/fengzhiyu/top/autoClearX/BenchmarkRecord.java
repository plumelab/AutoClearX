package fengzhiyu.top.autoClearX;

import java.util.List;

public class BenchmarkRecord {
    public String timestamp;
    public double baselineSingle;
    public double slopeSingle;
    public double thresholdSingleChunk;
    public int stopEntitiesSingle;
    public double baselineMulti;
    public double slopeMulti;
    public double thresholdArea;
    public int stopEntitiesMulti;
    public boolean capReached;
    public List<BenchmarkSample> samplesSingle;
    public List<BenchmarkSample> samplesMulti;

    public static class BenchmarkSample {
        public int entitiesTotal;
        public double msptMedian;

        public BenchmarkSample(int entitiesTotal, double msptMedian) {
            this.entitiesTotal = entitiesTotal;
            this.msptMedian = msptMedian;
        }
    }
}
