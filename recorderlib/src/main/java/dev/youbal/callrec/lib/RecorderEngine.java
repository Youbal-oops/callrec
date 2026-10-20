package dev.youbal.callrec.lib;

interface RecorderEngine {

    boolean isReady();

    void start(String outputDir, String number, String direction) throws Exception;

    RecordResult stop() throws Exception;

    void release();
}
